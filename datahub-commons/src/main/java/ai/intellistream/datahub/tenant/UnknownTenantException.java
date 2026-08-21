// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

/**
 * The request carries a valid token for an organization this deployment has no tenant record for —
 * nothing in Vault's {@code tenant-resources} answers to that id, so there is no database to route
 * to.
 *
 * <p>This is a denial, not a fault: the caller authenticated fine, the org simply is not (or is no
 * longer) onboarded here. Left as a bare {@code IllegalStateException} it reached REST callers as a
 * bodyless 500, which reads as "the api is broken" when the truth is "this org was never
 * provisioned" — see {@code UnknownTenantExceptionHandler} and {@code TenantProvisioningFilter} in
 * datahub-api for the 403 the request path turns it into.
 *
 * <p>Extends {@link IllegalStateException} so existing handling of the old exception type is
 * unchanged; the value is the type itself, which callers outside the request path (the consumers
 * route by tenant too, with no filter in front of them) can catch specifically.
 */
public class UnknownTenantException extends IllegalStateException {

    private final String tenantId;

    public UnknownTenantException(String tenantId) {
        super("Unknown Tenant: " + tenantId);
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
