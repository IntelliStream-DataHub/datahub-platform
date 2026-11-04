// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantAddedEvent;
import ai.intellistream.datahub.tenant.TenantConfigService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.pulsar.common.policies.data.BacklogQuota.BacklogQuotaType.destination_storage;
import static org.apache.pulsar.common.policies.data.BacklogQuota.BacklogQuotaType.message_age;
import static org.apache.pulsar.common.policies.data.BacklogQuota.RetentionPolicy.producer_exception;

/**
 * Provisions each flow tenant's records topic: the namespace {@code <pulsar-tenant>/flow}, the
 * non-partitioned topic {@code persistent://<pulsar-tenant>/flow/records} that a run's records
 * travel through on their way to ClickHouse, and its subscription {@value #SUBSCRIPTION}.
 *
 * <p>One topic per customer tenant, so one tenant's ClickHouse outage fills that tenant's quota
 * and no one else's. Non-partitioned because the records consumer reads it Key_Shared by run id,
 * which orders by key within one topic. The subscription is created here, at the earliest
 * position, rather than by the first consumer: Pulsar deletes a topic nobody subscribes to after a
 * minute idle, and nothing published before a consumer first attaches may be lost.
 *
 * <h2>When</h2>
 * Only for tenants with the {@code flow} feature on. At startup for every known tenant, with the
 * namespace policies reapplied on every boot so a policy change reaches existing tenants; when the
 * registry announces a new tenant; and on a timer for flagged tenants this instance has not
 * provisioned yet. The timer is what picks up a flag switched on for an existing tenant: the
 * registry announces new tenants, not changed ones. Once a tenant is provisioned the timer skips
 * it, so steady state costs nothing.
 *
 * <h2>Quota</h2>
 * {@code producer_exception}, not eviction: a record evicted is a record lost, so when the backlog
 * fills (that tenant's ClickHouse has been down too long) publishing fails and that tenant's runs
 * fail at the end instead. Size and age are two separate quota types in Pulsar, each its own call;
 * a single call with both registers only the size. Pulsar also refuses a retention that does not
 * exceed the quota, in size and in time, so the constructor checks that.
 *
 * <p>Like the api's subscription-topic provisioner it assumes the customer's Pulsar tenant exists
 * (created at onboarding) and never throws: a broker hiccup is logged and retried by the timer.
 */
@Component
@Slf4j
public class RecordsTopicProvisioner {

    public static final String NAMESPACE = "flow";
    public static final String TOPIC = "records";
    public static final String SUBSCRIPTION = "intellistream-flow-records";

    // Roles that produce to and consume from the topic: the platform's client role and the admin.
    private static final List<String> ROLES = List.of("istream", "admin");

    private final PulsarAdmin admin;
    private final TenantConfigService tenantConfigService;
    private final DataSize backlogSize;
    private final Duration backlogAge;
    private final DataSize retentionSize;
    private final Duration retentionTime;

    /** Organization ids whose topic this instance has provisioned since it started. */
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    /** A tenant's records topic, by its Pulsar tenant. */
    public static String topic(String pulsarTenant) {
        return "persistent://" + pulsarTenant + "/" + NAMESPACE + "/" + TOPIC;
    }

    /**
     * The organization ids whose records topic this instance has provisioned. The records consumers
     * start a consumer for each, so they never subscribe to a topic that is not ready.
     */
    public Set<String> provisioned() {
        return Set.copyOf(provisioned);
    }

    public RecordsTopicProvisioner(
            PulsarAdmin admin,
            TenantConfigService tenantConfigService,
            @Value("${datahub.flow.records.backlog-size:1GB}") DataSize backlogSize,
            @Value("${datahub.flow.records.backlog-age:7d}") Duration backlogAge,
            @Value("${datahub.flow.records.retention-size:2GB}") DataSize retentionSize,
            @Value("${datahub.flow.records.retention-time:8d}") Duration retentionTime) {
        if (retentionSize.compareTo(backlogSize) <= 0 || retentionTime.compareTo(backlogAge) <= 0) {
            throw new IllegalStateException("datahub.flow.records.retention-size and -time must "
                    + "exceed backlog-size and -age: Pulsar refuses a retention below the backlog quota.");
        }
        this.admin = admin;
        this.tenantConfigService = tenantConfigService;
        this.backlogSize = backlogSize;
        this.backlogAge = backlogAge;
        this.retentionSize = retentionSize;
        this.retentionTime = retentionTime;
    }

    @PostConstruct
    public void provisionAll() {
        for (Tenant tenant : tenantConfigService.cachedTenants.values()) {
            provisionIfFlagged(tenant);
        }
    }

    @EventListener
    public void onTenantAdded(TenantAddedEvent event) {
        provisionIfFlagged(event.tenant());
    }

    @Scheduled(initialDelayString = "${datahub.flow.records.provision-interval:PT5M}",
            fixedDelayString = "${datahub.flow.records.provision-interval:PT5M}")
    public void provisionNewlyFlagged() {
        for (Tenant tenant : tenantConfigService.cachedTenants.values()) {
            if (!provisioned.contains(tenant.getOrganizationId())) {
                provisionIfFlagged(tenant);
            }
        }
    }

    private void provisionIfFlagged(Tenant tenant) {
        if (tenant.getFeatures() == null || !tenant.getFeatures().isFlowFeatureEnabled()) {
            return;
        }
        String tenantId = tenant.getOrganizationId();
        if (tenant.getPulsarTenant() == null || tenant.getPulsarTenant().getTenant() == null
                || tenant.getPulsarTenant().getTenant().isBlank()) {
            log.error("Tenant {} has flows on but no pulsar.tenant configured; cannot provision its "
                    + "records topic.", tenantId);
            return;
        }
        String namespace = tenant.getPulsarTenant().getTenant() + "/" + NAMESPACE;
        String topic = topic(tenant.getPulsarTenant().getTenant());
        try {
            ensureNamespace(namespace);
            applyNamespacePolicies(namespace);
            ensureTopic(topic);
            ensureSubscription(topic);
            provisioned.add(tenantId);
            log.info("Records topic {} ready for tenant {}.", topic, tenantId);
        } catch (PulsarAdminException.NotFoundException e) {
            log.error("Cannot provision {} for tenant {}: Pulsar tenant '{}' does not exist. Create it "
                    + "at customer onboarding.", topic, tenantId, tenant.getPulsarTenant().getTenant());
        } catch (PulsarAdminException | RuntimeException e) {
            log.error("Failed to provision {} for tenant {}: {}", topic, tenantId, e.getMessage(), e);
        }
    }

    private void ensureNamespace(String namespace) throws PulsarAdminException {
        try {
            admin.namespaces().createNamespace(namespace);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // Adopt it; its policies are applied next either way.
        }
    }

    /**
     * Checked on every provisioning and reapplied when anything differs, so a changed policy reaches
     * namespaces made by older builds.
     *
     * <p>The order is forced by the broker. Pulsar (5.0.0) refuses an age quota on a namespace that
     * already has retention, whatever the values, but accepts both quotas first and the retention
     * after. So a change removes the retention, sets both quotas and puts the retention back; an
     * unchanged namespace is left alone, so the brief window without retention, which only affects
     * records already acknowledged, happens only when the policy actually changes.
     */
    private void applyNamespacePolicies(String namespace) throws PulsarAdminException {
        BacklogQuota size = BacklogQuota.builder()
                .limitSize(backlogSize.toBytes())
                .retentionPolicy(producer_exception)
                .build();
        BacklogQuota age = BacklogQuota.builder()
                .limitTime((int) backlogAge.toSeconds())
                .retentionPolicy(producer_exception)
                .build();
        RetentionPolicies retention = new RetentionPolicies(
                (int) retentionTime.toMinutes(), retentionSize.toMegabytes());

        Map<BacklogQuota.BacklogQuotaType, BacklogQuota> quotas =
                admin.namespaces().getBacklogQuotaMap(namespace);
        if (!sameQuota(quotas.get(destination_storage), size) || !sameQuota(quotas.get(message_age), age)
                || !retention.equals(admin.namespaces().getRetention(namespace))) {
            admin.namespaces().removeRetention(namespace);
            admin.namespaces().setBacklogQuota(namespace, size, destination_storage);
            admin.namespaces().setBacklogQuota(namespace, age, message_age);
            admin.namespaces().setRetention(namespace, retention);
            log.info("Applied records policies to {}.", namespace);
        }
        for (String role : ROLES) {
            admin.namespaces().grantPermissionOnNamespace(namespace, role,
                    Set.of(AuthAction.produce, AuthAction.consume));
        }
    }

    private static boolean sameQuota(BacklogQuota current, BacklogQuota wanted) {
        return current != null && current.getLimitSize() == wanted.getLimitSize()
                && current.getLimitTime() == wanted.getLimitTime()
                && current.getPolicy() == wanted.getPolicy();
    }

    private void ensureTopic(String topic) throws PulsarAdminException {
        try {
            admin.topics().createNonPartitionedTopic(topic);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // Adopt it.
        }
    }

    private void ensureSubscription(String topic) throws PulsarAdminException {
        try {
            admin.topics().createSubscription(topic, SUBSCRIPTION, MessageId.earliest);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // Adopt it; an existing subscription keeps its position.
        }
    }
}
