// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.models.DataSort;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import lombok.Data;

@Schema(name = "SubscriptionRetriever", description = "Configure how you want to fetch subscriptions.")
@Data
public class SubscriptionRetriever {

    private SubscriptionFilter filter = new SubscriptionFilter();

    @Max(10000)
    @Schema(description = "Maximum number of subscriptions to return.", example = "100", defaultValue = "100")
    private int limit = 100;

    private DataSort sort = new DataSort();

    @Schema(description = "Include subscriptions auto-provisioned by the function-binding lifecycle " +
            "(system_managed=true). Default false hides them from user-facing listings; set to true " +
            "from internal callers (e.g. function workers) that need to discover their bindings.",
            example = "false", defaultValue = "false")
    private boolean includeSystemManaged = false;
}
