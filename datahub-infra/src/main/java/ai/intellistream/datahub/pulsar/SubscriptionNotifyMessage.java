// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SubscriptionNotifyMessage {

    private EventAction eventAction;
    private String tenantId;
    private String subscriptionExternalId;

    /**
     * Timeseries ids the subscription is (or was) bound to. Ordered to match
     * {@link #timeseriesExternalIds} so index {@code i} is the same timeseries in both lists.
     */
    private List<Long> timeseriesIds = new ArrayList<>();
    private List<String> timeseriesExternalIds = new ArrayList<>();

    public SubscriptionNotifyMessage(EventAction eventAction,
                                     String tenantId,
                                     String subscriptionExternalId,
                                     List<Long> timeseriesIds,
                                     List<String> timeseriesExternalIds) {
        this.eventAction = eventAction;
        this.tenantId = tenantId;
        this.subscriptionExternalId = subscriptionExternalId;
        this.timeseriesIds = timeseriesIds;
        this.timeseriesExternalIds = timeseriesExternalIds;
    }
}
