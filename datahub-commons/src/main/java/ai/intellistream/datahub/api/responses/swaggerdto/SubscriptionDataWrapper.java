// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.subscription.Subscription;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Subscription Collection", description = "Data Response with Subscriptions as collection.")
public class SubscriptionDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<Subscription> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<Subscription> getItems() {
        return items;
    }
}
