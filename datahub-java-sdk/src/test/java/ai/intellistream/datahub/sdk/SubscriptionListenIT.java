// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.subscriptions.SubscriptionListener;
import ai.intellistream.datahub.sdk.subscriptions.SubscriptionMessage;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end regression guard for the ingest -&gt; fan-out -&gt; subscription-delivery path (which the
 * per-instance producer-name change touches). Needs a running backend with the Pulsar fan-out
 * consumer; gated behind {@code RUN_LISTEN_TESTS=1} and driven by {@link DatahubClient#fromEnv()}
 * ({@code BASE_URL} + {@code TOKEN}). Mirrors the Rust/Python listen end-to-end tests, which the
 * Java SDK previously lacked.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LISTEN_TESTS", matches = "1")
class SubscriptionListenIT {

    @Test
    @DisplayName("Datapoints ingested over REST are fanned out to a subscription listener")
    void ingestedDatapointsAreDeliveredToTheSubscription() throws Exception {
        DatahubClient client = DatahubClient.fromEnv();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tsA = "sdk_listen_ts_a_" + suffix;
        String tsB = "sdk_listen_ts_b_" + suffix;
        String subExt = "sdk_listen_sub_" + suffix;

        // One subscription bound to two timeseries — exercises the per-subscription fan-out, not just
        // a single-datapoint smoke path.
        Timeseries seriesA = new Timeseries();
        seriesA.setExternalId(tsA);
        seriesA.setName("SDK Listen TS A");
        seriesA.setUnitExternalId("Celsius");
        Timeseries seriesB = new Timeseries();
        seriesB.setExternalId(tsB);
        seriesB.setName("SDK Listen TS B");
        seriesB.setUnitExternalId("Celsius");
        client.timeseries().create(seriesA, seriesB);

        Subscription sub = new Subscription();
        sub.setExternalId(subExt);
        sub.setName("SDK Listen Sub " + suffix);
        sub.setTimeseries(List.of(
                IdCollection.createFromExternalId(tsA),
                IdCollection.createFromExternalId(tsB)));
        client.subscriptions().create(List.of(sub));

        try (SubscriptionListener listener = client.subscriptions().listen(List.of(subExt))) {
            // Ingest AFTER connecting so the fan-out fires while we are listening.
            client.timeseries().ingest(Map.of(
                    tsA, List.of(Datapoint.of(Instant.now(), 42.0)),
                    tsB, List.of(Datapoint.of(Instant.now(), 7.0))));

            Set<String> deliveredTimeseries = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && !deliveredTimeseries.containsAll(Set.of(tsA, tsB))) {
                SubscriptionMessage msg = listener.poll(Duration.ofSeconds(5));
                if (msg == null) continue;
                assertEquals(subExt, msg.subscriptionExternalId());
                DataWrapperMessage payload = msg.payload();
                assertNotNull(payload, "delivered message must carry a payload");
                if (payload.getItems() != null) {
                    payload.getItems().stream()
                            .map(DataCollectionString::getExternalId)
                            .forEach(deliveredTimeseries::add);
                }
                listener.ack(msg.messageId());
            }

            assertFalse(deliveredTimeseries.isEmpty(), "no datapoints were delivered before the deadline");
            assertEquals(Set.of(tsA, tsB), deliveredTimeseries,
                    "both bound timeseries must fan out to the one subscription");
        } finally {
            try {
                // Remove the subscription first — a timeseries referenced by a subscription 409s.
                client.subscriptions().delete(List.of(IdCollection.createFromExternalId(subExt)));
                client.timeseries().delete(List.of(
                        IdCollection.createFromExternalId(tsA),
                        IdCollection.createFromExternalId(tsB)));
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }
}
