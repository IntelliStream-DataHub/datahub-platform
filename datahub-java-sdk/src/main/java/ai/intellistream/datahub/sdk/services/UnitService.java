// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Units of measure. Mirrors the {@code /units} endpoints. */
public final class UnitService {

    private final ApiHttp http;
    private final JavaType units; // DataWrapper<UnitModel>

    public UnitService(ApiHttp http) {
        this.http = http;
        this.units = http.typeFactory().constructParametricType(DataWrapper.class, UnitModel.class);
    }

    /** GET /units — list all units. */
    public DataWrapper<UnitModel> list() {
        return http.get("/units", units);
    }

    /**
     * GET /units/{externalId} — one unit by its external id, which is how a timeseries names the
     * unit it is measured in. A {@code 404} when there is none, where {@link #byIds(List)} would
     * simply omit it.
     */
    public DataWrapper<UnitModel> getByExternalId(String externalId) {
        return http.get("/units/" + URLEncoder.encode(externalId, StandardCharsets.UTF_8), units);
    }

    /**
     * POST /units/byids — look up units by {@code id} or {@code externalId}.
     *
     * <p>Takes {@link IdCollection}, which is what the endpoint binds. It used to take
     * {@code List<UnitModel>} and post the whole unit — name, symbol, quantity, conversion and the
     * rest — into a body with no such fields. The api rejects unknown request fields, so every call
     * was a 400; {@code aliasNames} initialises to a {@code TreeSet} and is emitted even on a
     * default instance, so there was no input that worked.
     */
    public DataWrapper<UnitModel> byIds(List<IdCollection> ids) {
        return http.post("/units/byids", new DataWrapper<IdCollection>().setItems(ids), units);
    }
}
