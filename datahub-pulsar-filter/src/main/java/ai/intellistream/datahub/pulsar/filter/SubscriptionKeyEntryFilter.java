// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar.filter;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.pulsar.broker.service.plugin.EntryFilter;
import org.apache.pulsar.broker.service.plugin.FilterContext;
import org.apache.pulsar.common.api.proto.MessageMetadata;

import java.util.Map;

/**
 * Broker-side entry filter that lets one partitioned Pulsar topic serve many logical
 * subscribers. Each Pulsar subscription declares the partition key it cares about via the
 * {@code filter.key} subscription property (set by {@code SubscriptionService} at create
 * time). Messages published to the fanout topic are keyed by the target subscription's
 * externalId. On dispatch this filter accepts entries whose key equals the subscription's
 * {@code filter.key} and rejects everything else, so a WebSocket client only sees its own
 * messages even though every subscription reads the same topic.
 * <p>
 * Subscriptions without a {@code filter.key} property (e.g. ad-hoc admin tooling) pass
 * all entries through — the filter is opt-in per subscription.
 */
public class SubscriptionKeyEntryFilter implements EntryFilter {

    public static final String FILTER_KEY_PROP = "filter.key";

    @Override
    public FilterResult filterEntry(Entry entry, FilterContext context) {
        Map<String, String> props = context.getSubscription().getSubscriptionProperties();
        if (props == null) return FilterResult.ACCEPT;

        String expected = props.get(FILTER_KEY_PROP);
        if (expected == null) return FilterResult.ACCEPT;

        MessageMetadata md = context.getMsgMetadata();
        if (md == null) return FilterResult.REJECT;

        String actual = md.hasPartitionKey() ? md.getPartitionKey() : null;
        return expected.equals(actual) ? FilterResult.ACCEPT : FilterResult.REJECT;
    }

    @Override
    public void close() {
        // No resources to release.
    }
}
