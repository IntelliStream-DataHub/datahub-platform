// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.resource;

import ai.intellistream.datahub.models.Resource;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The console's browser-facing resource form: a {@link Resource} plus the two fields the "create
 * with relations" UI needs.
 *
 * <p>It used to extend {@code ResourceForm}/{@code NodeForm}, a hierarchy parallel to
 * {@link ai.intellistream.datahub.models.NodeModel} that re-declared the same fields with the same
 * validation — and handed {@code isRoot} to every node type, which is how a FUNCTION create body
 * came to carry a field a function cannot have. One hierarchy now: what the browser posts is the
 * shape the api models.
 *
 * <p>The two extra fields are {@code WRITE_ONLY} so they bind from the browser but never serialize
 * outward. The console maps this form into a plain {@code Resource}/{@code Asset} before calling
 * the api anyway (the api reads request bodies strictly and would reject fields it has no place
 * for), so this is a second line of defence rather than the mechanism.
 */
@Getter
@Setter
public class ResourceWebForm extends Resource {

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Long relationFrom;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<String> relationTypes;

    /**
     * The location the form offers, which only an asset can store.
     *
     * <p>Declared here rather than inherited: this one form creates both assets and plain
     * resources, so the browser can post a location, but {@code Resource} has nowhere to keep one
     * — {@code geoLocation} lives on {@code Asset} alone now that the flat shape no longer has to
     * double as a Pulsar payload. {@code toNode} reads it on the ASSET branch and drops it
     * otherwise, which is the behaviour it already had; the field simply belongs to the form.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @jakarta.validation.Valid
    private ai.intellistream.datahub.models.GeoLocation geoLocation;

}
