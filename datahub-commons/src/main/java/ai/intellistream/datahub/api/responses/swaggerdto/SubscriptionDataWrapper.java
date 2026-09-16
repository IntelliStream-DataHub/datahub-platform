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

    /**
     * Mirrors {@code DataWrapper.nextCursor}. These documentation envelopes exist because
     * {@code DataWrapper} carries a fixed {@code @Schema(name)}, so every generic instantiation
     * would otherwise document as one untyped schema — and each copy was written when {@code items}
     * was the whole envelope. The real one grew a cursor; the copies did not, so the published spec
     * described a response with no way to page and a generated client had no field to read, while
     * the endpoint descriptions told callers to loop on exactly this value.
     *
     * <p>Read-only: the live envelope does accept it on a request body, but that is a defect to fix
     * there, not a shape to publish here.
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Opaque cursor for the next page. Send it back as `cursor`. "
                    + "Absent when there are no further pages.")
    private String nextCursor;

    public String getNextCursor() {
        return nextCursor;
    }
}
