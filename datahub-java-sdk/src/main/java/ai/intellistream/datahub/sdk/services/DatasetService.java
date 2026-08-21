// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.DataSetRetreiver;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.forms.DataSetForm;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.models.SearchBody;
import tools.jackson.databind.JavaType;

import java.util.List;

/** Datasets — logical groupings of resources and time-series. Mirrors the {@code /datasets} endpoints. */
public final class DatasetService {

    private final ApiHttp http;
    private final JavaType datasets; // DataWrapper<DataSetModel>

    public DatasetService(ApiHttp http) {
        this.http = http;
        this.datasets = http.typeFactory().constructParametricType(DataWrapper.class, DataSetModel.class);
    }

    /**
     * GET /datasets/{id} — fetch one data set by its numeric id.
     *
     * <p>A missing id is a 404 ({@code DatahubApiException}). To look one up by {@code externalId},
     * or several at once, use {@link #byIds(List)}.
     */
    public DataWrapper<DataSetModel> getById(long id) {
        return http.get("/datasets/" + id, datasets);
    }

    /** POST /datasets/list */
    public DataWrapper<DataSetModel> list(DataSetRetreiver retriever) {
        return http.post("/datasets/list", retriever, datasets);
    }

    /** POST /datasets/search */
    public DataWrapper<DataSetModel> search(SearchBody<DataSetFilter> search) {
        return http.post("/datasets/search", search, datasets);
    }

    /**
     * POST /datasets/filter — the typed data set query. Criteria AND together; within a list field
     * the entries OR: {@code id}, {@code externalId}, {@code name}, {@code source},
     * {@code labels}, {@code metadata}, and the {@code createdTime} / {@code lastUpdatedTime}
     * ranges.
     *
     * <p>The OR'd fields are named in the singular but still take lists — each accepts a bare value
     * or an array, so {@code name: "Plant A"} and {@code name: ["Plant A", "Plant B"]} are both
     * valid. {@code labels} stays plural because its entries AND.
     *
     * <p>{@code externalId}, {@code name} and {@code source} take literals or patterns in the
     * same list — {@code *} and {@code %} are wildcards, {@code _} is literal, matching is
     * case-insensitive — so {@code ["sap_assets", "plant_*"]} is one call. {@code labels} and
     * {@code metadata} are the two that AND rather than OR: every label named must be carried, and
     * every metadata entry present, where a null value asks for the key alone whatever it holds.
     * An empty list (or null) places no restriction.
     *
     * <p>Results come newest created first unless the retriever carries a {@code sort}, capped by
     * its {@code limit} (default 1000, max 10000). Past that cap, page with the retriever's
     * {@code cursor} and the response's {@code nextCursor}.
     */
    public DataWrapper<DataSetModel> filter(DataSetRetreiver request) {
        return http.post("/datasets/filter", request, datasets);
    }

    /** {@link #filter(DataSetRetreiver)} with just the criteria and the default limit. */
    public DataWrapper<DataSetModel> filter(DataSetFilter criteria) {
        DataSetRetreiver request = new DataSetRetreiver();
        request.setFilter(criteria);
        return filter(request);
    }

    /** POST /datasets/byids */
    public DataWrapper<DataSetModel> byIds(List<IdCollection> ids) {
        return http.post("/datasets/byids", new DataWrapper<IdCollection>().setItems(ids), datasets);
    }

    /** POST /datasets/create */
    public DataWrapper<DataSetModel> create(List<DataSetModel> models) {
        return http.post("/datasets/create", new DataWrapper<DataSetModel>().setItems(models), datasets);
    }

    /** POST /datasets/update */
    public DataWrapper<DataSetModel> update(List<DataSetForm> forms) {
        return http.post("/datasets/update", new DataWrapper<DataSetForm>().setItems(forms), datasets);
    }

    /** POST /datasets/delete */
    public DataWrapper<DataSetModel> delete(List<IdCollection> ids) {
        return http.post("/datasets/delete", new DataWrapper<IdCollection>().setItems(ids), datasets);
    }
}
