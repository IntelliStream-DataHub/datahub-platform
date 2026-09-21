// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.init.pulsar;

import ai.intellistream.datahub.api.messaging.PartitionedTopicProvisioner;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;


import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionTopicProvisionerTest {

    private static final String TENANT_ID = "org-1";
    private static final String NAMESPACE = "cust-1/subscriptions";
    private static final String FANOUT_TOPIC = "persistent://cust-1/subscriptions/fanout";

    /**
     * The fan-out topic is a live tail, so a cursor past the quota must lose its own oldest entries
     * rather than have the broker refuse producers on its partition — which took the whole tenant's
     * live feed down with it.
     */
    @Test
    void backlogQuotaEvictsTheLaggingConsumerRatherThanRefusingProducers() throws Exception {
        Fixture f = new Fixture(false);

        f.provisioner.provisionAll();

        ArgumentCaptor<BacklogQuota> quota = ArgumentCaptor.forClass(BacklogQuota.class);
        verify(f.namespaces).setBacklogQuota(eq(NAMESPACE), quota.capture());
        assertEquals(BacklogQuota.RetentionPolicy.consumer_backlog_eviction,
                quota.getValue().getPolicy());
    }

    /**
     * Policies used to be applied only in the branch that created the namespace, so a namespace
     * provisioned by an earlier build kept that build's policies forever and no policy fix could
     * ever reach it.
     */
    @Test
    void policiesAreReappliedToAnAlreadyExistingNamespace() throws Exception {
        Fixture f = new Fixture(true);

        f.provisioner.provisionAll();

        verify(f.namespaces, never()).createNamespace(anyString());
        verify(f.namespaces).setBacklogQuota(eq(NAMESPACE), any());
        verify(f.namespaces).setRetention(eq(NAMESPACE), any());
    }

    private static final class Fixture {
        final Namespaces namespaces = mock(Namespaces.class);
        final PartitionedTopicProvisioner topicProvisioner = mock(PartitionedTopicProvisioner.class);
        final SubscriptionTopicProvisioner provisioner;

        Fixture(boolean namespaceAlreadyExists) throws Exception {
            PulsarAdmin admin = mock(PulsarAdmin.class);
            when(admin.namespaces()).thenReturn(namespaces);
            when(namespaces.getNamespaces("cust-1"))
                    .thenReturn(namespaceAlreadyExists ? List.of(NAMESPACE) : List.of());

            TopicNames topicNames = mock(TopicNames.class);
            when(topicNames.getSubscriptionFanoutTopicName(TENANT_ID)).thenReturn(FANOUT_TOPIC);

            Tenant tenant = mock(Tenant.class);
            when(tenant.getOrganizationId()).thenReturn(TENANT_ID);
            // A mock's field initialisers never run, so cachedTenants has to be supplied.
            TenantConfigService tenantConfigService = mock(TenantConfigService.class);
            tenantConfigService.cachedTenants = new ConcurrentHashMap<>();
            tenantConfigService.cachedTenants.put(TENANT_ID, tenant);

            provisioner = new SubscriptionTopicProvisioner(
                    admin, topicNames, tenantConfigService, topicProvisioner);
        }
    }
}
