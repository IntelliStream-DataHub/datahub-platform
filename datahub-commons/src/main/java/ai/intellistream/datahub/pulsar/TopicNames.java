// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TopicNames {

    @Getter
    @Value("${pulsar.internal-tenant:''}")
    private String internalTenant;

    // Resolves each customer's own Pulsar tenant for the subscription fan-out topic. Only the
    // fanout (formerly in the single shared public tenant) is per-customer; the internal-tenant
    // ingestion topics stay global.
    private final TenantConfigService tenantConfigService;

    public TopicNames(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    private static final String DP_TOPIC_NAME = "persistent://%s/datapoints/%s";
    private static final String RESOURCES_TOPIC_NAME = "persistent://%s/resources/cud-events";
    private static final String EVENTS_TOPIC_NAME = "persistent://%s/events/cud-events";
    private static final String HTTP_MSG_TOPIC_NAME = "persistent://%s/http/message";
    private static final String SUBSCRIPTION_NOTIFY_TOPIC_NAME = "persistent://%s/subscriptions/notify";
    private static final String SUBSCRIPTION_FANOUT_TOPIC_NAME = "persistent://%s/subscriptions/fanout";
    // Coordination topic (internal tenant): each instance holds a uniquely-named producer here as a
    // startup lock, so a duplicate instance id is rejected loudly instead of colliding on data topics.
    private static final String INSTANCE_LOCK_TOPIC_NAME = "persistent://%s/subscriptions/instance-lock";
    public static final String ALL_SUBSCRIPTIONS_NAME = "batched-datapoints-all-sub";
    public static final String SUBSCRIPTION_FILTER_KEY_PROP = "filter.key";

    public String getDPTopicName(String topic){
        return String.format(DP_TOPIC_NAME, internalTenant, topic);
    }

    public String getResourceTopicName(){
        return String.format(RESOURCES_TOPIC_NAME, internalTenant);

    }

    public String getEventsTopicName(){
        return String.format(EVENTS_TOPIC_NAME, internalTenant);
    }

    public String getHttpMessageTopicName(){
        return String.format(HTTP_MSG_TOPIC_NAME, internalTenant);
    }

    /**
     * Fan-out topic for a tenant's live WebSocket subscriptions, hosted in that customer's OWN
     * Pulsar tenant (see {@link #getPulsarTenant(String)}).
     */
    public String getSubscriptionFanoutTopicName(String tenantId){
        return String.format(SUBSCRIPTION_FANOUT_TOPIC_NAME, getPulsarTenant(tenantId));
    }

    /**
     * The Pulsar tenant that hosts a customer's OWN topics — its subscription fan-out topic and its
     * user-managed streams — read from the tenant's {@code pulsar.tenant} config so each customer is
     * isolated in its own Pulsar tenant.
     *
     * @throws IllegalStateException if the tenant has no {@code pulsar.tenant} configured. There is
     *         deliberately NO fallback to a shared tenant: a missing config is a hard error so a
     *         misconfigured tenant fails loudly instead of silently sharing another tenant's topics.
     */
    public String getPulsarTenant(String tenantId){
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("Cannot resolve a Pulsar tenant without a tenant id.");
        }
        Tenant tenant = tenantConfigService.getConfig(tenantId);
        if (tenant == null || tenant.getPulsarTenant() == null
                || tenant.getPulsarTenant().getTenant() == null
                || tenant.getPulsarTenant().getTenant().isBlank()) {
            throw new IllegalStateException("Tenant " + tenantId + " has no 'pulsar.tenant' configured in its "
                    + "tenant-resources entry. Add a \"pulsar\": { \"tenant\": \"...\" } block to the tenant.");
        }
        return tenant.getPulsarTenant().getTenant();
    }

    public String getSubscriptionNotifyTopicName(){
        return String.format(SUBSCRIPTION_NOTIFY_TOPIC_NAME, internalTenant);
    }

    public String getInstanceLockTopicName(){
        return String.format(INSTANCE_LOCK_TOPIC_NAME, internalTenant);
    }

    public String getAllDatapointsTopicName(){
        return String.format(DP_TOPIC_NAME, internalTenant, "all-datapoints");
    }
}
