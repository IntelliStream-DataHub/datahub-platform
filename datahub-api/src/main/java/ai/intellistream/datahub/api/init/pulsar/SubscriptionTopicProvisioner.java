// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.init.pulsar;

import ai.intellistream.datahub.api.messaging.PartitionedTopicProvisioner;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantAddedEvent;
import ai.intellistream.datahub.tenant.TenantConfigService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

import static org.apache.pulsar.common.policies.data.BacklogQuota.RetentionPolicy.producer_exception;

/**
 * Provisions each customer's subscription fan-out topic in that customer's OWN Pulsar tenant,
 * replacing the single shared {@code public-tenant/subscriptions/fanout} that {@code InitNamespaces}
 * used to create globally. For every known tenant (and every tenant added at runtime) it ensures
 * the {@code <pulsar-tenant>/subscriptions} namespace and the partitioned fan-out topic exist.
 *
 * <p>Unlike {@code InitNamespaces} (dev-only), this runs in <b>all profiles</b> so prod gets the
 * per-tenant fan-out topic provisioned automatically. It assumes the customer's Pulsar <i>tenant</i>
 * already exists (created out-of-band at customer onboarding, like the global public/internal
 * tenants); it only creates the namespace and topic within it.
 *
 * <p>Best-effort: any Pulsar-admin failure is logged and never thrown, so a broker hiccup or a
 * not-yet-created Pulsar tenant can't block API startup. The fan-out producer (stateless consumer)
 * and the WebSocket consumers self-heal once the topic exists.
 */
@Component
@Slf4j
@DependsOn("tenantConfigService")
public class SubscriptionTopicProvisioner {

    private static final int SUBSCRIPTION_FANOUT_PARTITIONS = 8;

    // Namespace policies mirror the subscription namespace set up by InitNamespaces.
    private static final long BACKLOG_LIMIT_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int BACKLOG_LIMIT_MINUTES = 7 * 60 * 24;   // 7 days
    private static final int RETENTION_TIME_MINUTES = 7 * 60 * 24;  // 7 days
    private static final int RETENTION_SIZE_MB = 12000;             // 12 GB

    private final PulsarAdmin admin;
    private final TopicNames topicNames;
    private final TenantConfigService tenantConfigService;
    private final PartitionedTopicProvisioner topicProvisioner;

    public SubscriptionTopicProvisioner(@Qualifier("pulsarAdmin") PulsarAdmin admin,
                                        TopicNames topicNames,
                                        TenantConfigService tenantConfigService,
                                        PartitionedTopicProvisioner topicProvisioner) {
        this.admin = admin;
        this.topicNames = topicNames;
        this.tenantConfigService = tenantConfigService;
        this.topicProvisioner = topicProvisioner;
    }

    @PostConstruct
    public void provisionAll() {
        var tenants = tenantConfigService.cachedTenants.values();
        log.info("Provisioning per-tenant subscription fan-out topics for {} tenant(s)...", tenants.size());
        for (Tenant tenant : tenants) {
            provisionForTenant(tenant);
        }
    }

    @EventListener
    public void onTenantAdded(TenantAddedEvent event) {
        log.info("Provisioning subscription fan-out topic for newly cached tenant {}.",
                event.tenant().getOrganizationId());
        provisionForTenant(event.tenant());
    }

    private void provisionForTenant(Tenant tenant) {
        String tenantId = tenant.getOrganizationId();
        // persistent://<pulsar-tenant>/subscriptions/fanout — resolved per tenant by TopicNames,
        // which throws if the tenant has no pulsar.tenant configured (no silent shared fallback).
        // Skip that one tenant rather than aborting provisioning for everyone else.
        String fanoutTopic;
        try {
            fanoutTopic = topicNames.getSubscriptionFanoutTopicName(tenantId);
        } catch (IllegalStateException e) {
            log.error("Skipping subscription fan-out provisioning for tenant {}: {}", tenantId, e.getMessage());
            return;
        }
        String namespace = namespaceOf(fanoutTopic);
        ensureNamespace(namespace, tenantId);
        topicProvisioner.ensurePartitioned(fanoutTopic, SUBSCRIPTION_FANOUT_PARTITIONS);
    }

    /**
     * Idempotently create the {@code <pulsar-tenant>/subscriptions} namespace with the same
     * backlog/retention/permission policies InitNamespaces applies. Never throws.
     */
    private void ensureNamespace(String namespace, String tenantId) {
        String pulsarTenant = namespace.substring(0, namespace.indexOf('/'));
        try {
            if (admin.namespaces().getNamespaces(pulsarTenant).contains(namespace)) {
                return;
            }
            admin.namespaces().createNamespace(namespace);
            admin.namespaces().setBacklogQuota(namespace, BacklogQuota.builder()
                    .limitSize(BACKLOG_LIMIT_BYTES)
                    .limitTime(BACKLOG_LIMIT_MINUTES)
                    .retentionPolicy(producer_exception)
                    .build());
            admin.namespaces().setRetention(namespace, new RetentionPolicies(RETENTION_TIME_MINUTES, RETENTION_SIZE_MB));
            admin.namespaces().grantPermissionOnNamespace(namespace, "admin,istream",
                    Set.of(AuthAction.produce, AuthAction.consume));
            log.info("Created subscription namespace {} for tenant {}.", namespace, tenantId);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // Created concurrently or out-of-band — fine.
        } catch (PulsarAdminException.NotFoundException tenantMissing) {
            log.error("Pulsar tenant '{}' does not exist; cannot provision subscription namespace {} for tenant {}. "
                    + "Create the Pulsar tenant at customer onboarding.", pulsarTenant, namespace, tenantId);
        } catch (PulsarAdminException e) {
            log.error("Failed to ensure subscription namespace {} for tenant {}: {}",
                    namespace, tenantId, e.getMessage(), e);
        }
    }

    /** persistent://&lt;tenant&gt;/&lt;namespace&gt;/&lt;topic&gt; → &lt;tenant&gt;/&lt;namespace&gt; */
    private static String namespaceOf(String topicPath) {
        String afterScheme = topicPath.substring(topicPath.indexOf("://") + 3);
        return afterScheme.substring(0, afterScheme.lastIndexOf('/'));
    }
}
