// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

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

    /** POST /units/byids — look up units by id (set the id on each {@link UnitModel}). */
    public DataWrapper<UnitModel> byIds(List<UnitModel> ids) {
        return http.post("/units/byids", new DataWrapper<UnitModel>().setItems(ids), units);
    }
}
