// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar.filter;

import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.plugin.EntryFilter.FilterResult;
import org.apache.pulsar.broker.service.plugin.FilterContext;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The README's behaviour matrix, one row per test. The integration tests prove the filter routes
 * inside a real broker; these pin the decision itself, including the opt-out and missing-key rows
 * the integration tests never produce.
 */
class SubscriptionKeyEntryFilterTest {

    private final SubscriptionKeyEntryFilter filter = new SubscriptionKeyEntryFilter();

    @Test
    void aSubscriptionWithoutPropertiesSeesEverything() {
        assertEquals(FilterResult.ACCEPT, filter.filterEntry(null, context(null, keyed("sub-b"))));
    }

    @Test
    void aSubscriptionWithoutFilterKeySeesEverything() {
        assertEquals(FilterResult.ACCEPT, filter.filterEntry(null, context(Map.of("other", "x"), keyed("sub-b"))));
    }

    @Test
    void aMatchingKeyIsDelivered() {
        assertEquals(FilterResult.ACCEPT, filter.filterEntry(null, context(filterKey("sub-a"), keyed("sub-a"))));
    }

    @Test
    void anotherSubscriptionsKeyIsWithheld() {
        assertEquals(FilterResult.REJECT, filter.filterEntry(null, context(filterKey("sub-a"), keyed("sub-b"))));
    }

    @Test
    void anUnkeyedMessageIsWithheldFromAFilteredSubscription() {
        assertEquals(FilterResult.REJECT, filter.filterEntry(null, context(filterKey("sub-a"), new MessageMetadata())));
    }

    @Test
    void missingMetadataIsWithheldFromAFilteredSubscription() {
        assertEquals(FilterResult.REJECT, filter.filterEntry(null, context(filterKey("sub-a"), null)));
    }

    private static Map<String, String> filterKey(String key) {
        return Map.of(SubscriptionKeyEntryFilter.FILTER_KEY_PROP, key);
    }

    private static MessageMetadata keyed(String key) {
        return new MessageMetadata().setPartitionKey(key);
    }

    private static FilterContext context(Map<String, String> subscriptionProperties, MessageMetadata metadata) {
        // The filter reads only getSubscriptionProperties(); a proxy stands in for the rest of the interface.
        Subscription subscription = (Subscription) Proxy.newProxyInstance(
                Subscription.class.getClassLoader(), new Class<?>[]{Subscription.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getSubscriptionProperties")) return subscriptionProperties;
                    throw new UnsupportedOperationException(method.getName());
                });
        FilterContext context = new FilterContext();
        context.setSubscription(subscription);
        context.setMsgMetadata(metadata);
        return context;
    }
}
