// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.tenant.PulsarTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantFeatures;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * The records topic against a real broker: what the provisioner leaves behind, that a second pass
 * changes nothing, and that the topic outlives Pulsar's deletion of idle topics because it has a
 * subscription. The broker deletes inactive topics every second here, against a minute by
 * default, so the last point is shown rather than assumed.
 */
@Tag("integration")
class RecordsTopicProvisionerIT {

    private static final String FLAGGED = "it-flow-on";
    private static final String UNFLAGGED = "it-flow-off";
    private static final String TOPIC = "persistent://" + FLAGGED + "/flow/records";

    private static PulsarContainer pulsar;
    private static PulsarAdmin admin;

    @BeforeAll
    static void start() throws Exception {
        String version = System.getProperty("datahub.pulsar.image.version", "5.0.0");
        pulsar = new PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:" + version))
                .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsEnabled", "true")
                .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsFrequencySeconds", "1")
                .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsMaxInactiveDurationSeconds", "1")
                .withStartupTimeout(Duration.ofMinutes(3));
        pulsar.start();
        admin = PulsarAdmin.builder().serviceHttpUrl(pulsar.getHttpServiceUrl()).build();
        for (String tenant : new String[]{FLAGGED, UNFLAGGED}) {
            admin.tenants().createTenant(tenant,
                    TenantInfo.builder().allowedClusters(Set.of("standalone")).build());
        }
    }

    @AfterAll
    static void stop() {
        if (admin != null) admin.close();
        if (pulsar != null) pulsar.stop();
    }

    @Test
    void provisionsTheTopicItsSubscriptionAndPoliciesOnlyForFlaggedTenants() throws Exception {
        RecordsTopicProvisioner provisioner = provisioner();

        provisioner.provisionAll();
        provisioner.provisionAll();   // what every later boot does: adopts, reapplies, never fails

        assertThat(admin.namespaces().getNamespaces(UNFLAGGED)).isEmpty();
        assertThat(admin.namespaces().getNamespaces(FLAGGED)).containsExactly(FLAGGED + "/flow");

        assertThat(admin.topics().getList(FLAGGED + "/flow")).containsExactly(TOPIC);
        assertThat(admin.topics().getPartitionedTopicList(FLAGGED + "/flow")).isEmpty();
        assertThat(admin.topics().getSubscriptions(TOPIC))
                .containsExactly(RecordsTopicProvisioner.SUBSCRIPTION);

        Map<BacklogQuota.BacklogQuotaType, BacklogQuota> quotas =
                admin.namespaces().getBacklogQuotaMap(FLAGGED + "/flow");
        BacklogQuota size = quotas.get(BacklogQuota.BacklogQuotaType.destination_storage);
        BacklogQuota age = quotas.get(BacklogQuota.BacklogQuotaType.message_age);
        assertThat(size.getLimitSize()).isEqualTo(DataSize.ofMegabytes(64).toBytes());
        assertThat(size.getPolicy()).isEqualTo(BacklogQuota.RetentionPolicy.producer_exception);
        assertThat(age.getLimitTime()).isEqualTo((int) Duration.ofDays(2).toSeconds());
        assertThat(age.getPolicy()).isEqualTo(BacklogQuota.RetentionPolicy.producer_exception);

        assertThat(admin.namespaces().getRetention(FLAGGED + "/flow"))
                .isEqualTo(new RetentionPolicies((int) Duration.ofDays(3).toMinutes(), 128));

        Map<String, Set<AuthAction>> permissions = admin.namespaces().getPermissions(FLAGGED + "/flow");
        assertThat(permissions).containsOnlyKeys("istream", "admin");
    }

    @Test
    void aChangedPolicyReachesAnExistingNamespace() throws Exception {
        provisioner().provisionAll();

        // A later build with a smaller quota: the broker refuses an age quota next to an existing
        // retention, so this only passes if the provisioner reapplies in the order it accepts.
        TenantConfigService registry = mock(TenantConfigService.class);
        registry.cachedTenants = Map.of("org-on", tenant("org-on", FLAGGED, true));
        new RecordsTopicProvisioner(admin, registry, DataSize.ofMegabytes(32), Duration.ofDays(1),
                DataSize.ofMegabytes(128), Duration.ofDays(3)).provisionAll();

        Map<BacklogQuota.BacklogQuotaType, BacklogQuota> quotas =
                admin.namespaces().getBacklogQuotaMap(FLAGGED + "/flow");
        assertThat(quotas.get(BacklogQuota.BacklogQuotaType.destination_storage).getLimitSize())
                .isEqualTo(DataSize.ofMegabytes(32).toBytes());
        assertThat(quotas.get(BacklogQuota.BacklogQuotaType.message_age).getLimitTime())
                .isEqualTo((int) Duration.ofDays(1).toSeconds());
        assertThat(admin.namespaces().getRetention(FLAGGED + "/flow"))
                .isEqualTo(new RetentionPolicies((int) Duration.ofDays(3).toMinutes(), 128));

        provisioner().provisionAll();   // and back, for the other tests
    }

    @Test
    void theTopicOutlivesIdleTopicDeletionBecauseItIsSubscribed() throws Exception {
        provisioner().provisionAll();

        // A sibling with no subscription goes, which shows deletion is running at all...
        String unsubscribed = "persistent://" + FLAGGED + "/flow/unsubscribed";
        admin.topics().createNonPartitionedTopic(unsubscribed);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> !admin.topics().getList(FLAGGED + "/flow").contains(unsubscribed));

        // ...and the records topic, idle the whole time, is still there.
        assertThat(admin.topics().getList(FLAGGED + "/flow")).contains(TOPIC);
        assertThat(admin.topics().getSubscriptions(TOPIC))
                .containsExactly(RecordsTopicProvisioner.SUBSCRIPTION);
    }

    private static RecordsTopicProvisioner provisioner() {
        TenantConfigService registry = mock(TenantConfigService.class);
        registry.cachedTenants = Map.of(
                "org-on", tenant("org-on", FLAGGED, true),
                "org-off", tenant("org-off", UNFLAGGED, false));
        return new RecordsTopicProvisioner(admin, registry, DataSize.ofMegabytes(64),
                Duration.ofDays(2), DataSize.ofMegabytes(128), Duration.ofDays(3));
    }

    private static Tenant tenant(String orgId, String pulsarTenant, boolean flows) {
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(orgId);
        PulsarTenant pulsar = new PulsarTenant();
        pulsar.setTenant(pulsarTenant);
        tenant.setPulsarTenant(pulsar);
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(flows);
        tenant.setFeatures(features);
        return tenant;
    }
}
