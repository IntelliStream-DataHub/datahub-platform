// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

import java.util.List;

/**
 * Labels — the tenant-wide vocabulary that categorises resources and timeseries. Every resource
 * carries at least one, and creating a resource with a new label name auto-creates the label, so
 * most callers never touch these endpoints; they exist to rename, describe, recolour and retire.
 *
 * <p>{@link LabelForm} is both the request and the response shape: it is annotated
 * {@code @Schema(name = "Label")} and carries exactly the five fields a label puts on the wire
 * ({@code id}, {@code name}, {@code description}, {@code i18nCode}, {@code color}).
 *
 * <p>{@code name} is normalised to SNAKE_UPPER_CASE, server-side on write and by
 * {@link LabelForm#setName} on read, so what you send back is what you were given.
 */
public final class LabelService {

    private final ApiHttp http;
    private final JavaType labels; // DataWrapper<LabelForm>

    public LabelService(ApiHttp http) {
        this.http = http;
        this.labels = http.typeFactory().constructParametricType(DataWrapper.class, LabelForm.class);
    }

    /** GET /labels/{id} — one label by its numeric id. */
    public DataWrapper<LabelForm> getById(long id) {
        return http.get("/labels/" + id, labels);
    }

    /** GET /labels — every label in the tenant. The vocabulary is small, so there is no paging. */
    public DataWrapper<LabelForm> list() {
        return http.get("/labels", labels);
    }

    /** POST /labels/create */
    public DataWrapper<LabelForm> create(List<LabelForm> items) {
        return http.post("/labels/create", new DataWrapper<LabelForm>().setItems(items), labels);
    }

    /** POST /labels/update — identify each label by {@code id}; the other fields replace what is stored. */
    public DataWrapper<LabelForm> update(List<LabelForm> items) {
        return http.post("/labels/update", new DataWrapper<LabelForm>().setItems(items), labels);
    }

    /**
     * DELETE /labels/delete — the endpoint answers {@code 204} with no body.
     *
     * <p>Two refusals, both {@code 400} rather than a silent no-op. A label still carried by
     * something comes back naming what blocks it, so clear it from those nodes first. A type-label
     * ({@code ASSET}, {@code TIMESERIES}, {@code FUNCTION}, {@code DATASET}, {@code POLICY}) is
     * reserved and cannot be deleted at all, attached or not: it is the wire discriminator for the
     * node family, so removing one would make the nodes carrying it unreadable.
     */
    public void delete(List<IdCollection> ids) {
        http.send("DELETE", "/labels/delete", new DataWrapper<IdCollection>().setItems(ids));
    }
}
