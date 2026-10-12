// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.client;

import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.models.me.MeGrants;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

/**
 * What the client's own token may do: its organization groups, and the datasets it may read or
 * write.
 *
 * <p>For a service acting on someone's behalf that has to respect the same dataset access the api
 * enforces. The api resolves the grants, so the answer is the one every other endpoint applies.
 */
public final class MeService {

    /** Which access {@link #datasets(Access)} answers for. */
    public enum Access {
        READ, WRITE
    }

    private final ApiHttp http;
    private final JavaType grants;   // MeGrants
    private final JavaType dataSets; // MeDataSets

    MeService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.grants = tf.constructType(MeGrants.class);
        this.dataSets = tf.constructType(MeDataSets.class);
    }

    /**
     * GET /me/grants — every organization group path you hold in this tenant, and whether you hold
     * the {@code DATAHUB_ADMIN} realm role, which is never a group.
     */
    public MeGrants grants() {
        return http.get("/me/grants", grants);
    }

    /**
     * GET /me/datasets?access= — the datasets you may read or write. Check {@code all} first: when
     * it is true, {@code ids} is empty rather than a list of everything.
     */
    public MeDataSets datasets(Access access) {
        return http.get("/me/datasets?access=" + access.name().toLowerCase(java.util.Locale.ROOT), dataSets);
    }
}
