// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class ClickHouseTenant extends AbstractTenant{
    // Jackson handles field mapping automatically based on parent fields

    /**
     * The tenant's {@code SELECT}-only ClickHouse user, provisioned beside the owner by the tenant
     * manager and granted nothing but {@code SELECT} on this one database.
     *
     * <p>It exists for the events filter language: {@code POST /events/filter} takes an expression
     * the <em>caller</em> writes. The renderer compiles that to a parameterised query and emits
     * every character of the SQL itself, so no caller text reaches the query string — but reads
     * run as this user anyway, so that a mistake in the renderer still cannot become an
     * {@code INSERT}, an {@code ALTER} or a {@code DROP}, and cannot reach another tenant.
     *
     * <p>Null for a tenant provisioned before the tenant manager created one. Such a tenant keeps
     * working — {@link #hasReadOnlyUser()} is false and reads fall back to the owner — but it does
     * not get the separation until it is re-provisioned.
     */
    @JsonProperty("readonly-user")
    private String readOnlyUsername;

    @JsonProperty("readonly-password")
    private String readOnlyPassword;

    /** Whether this tenant's block actually carries the read-only pair. */
    public boolean hasReadOnlyUser() {
        return readOnlyUsername != null && !readOnlyUsername.isBlank();
    }
}
