// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.models.IdCollection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "SubscriptionFilter", description = "Filter criteria for listing subscriptions.")
@Data
public class SubscriptionFilter {

    @Schema(
            description = "Return only subscriptions bound to these timeseries. Each entry can specify " +
                    "a timeseries id, external id, or both. Empty means no timeseries filter.",
            example = "[{\"id\": 29}, {\"externalId\": \"heater_2012_temp\"}]"
    )
    private Collection<IdCollection> timeseries = new ArrayList<>();
}
