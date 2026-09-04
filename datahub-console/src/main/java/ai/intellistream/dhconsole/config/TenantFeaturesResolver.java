// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * This tenant's feature flags, from datahub-api, once per request.
 *
 * <p>The flags used to be read out of the console's own copy of the Vault tenant registry, which is
 * a cache with a five-minute refresh. datahub-api reads the same Vault data and is authoritative
 * for it, so the console was maintaining a second, slower answer to a question the api already
 * answers — and the two disagreed for minutes after any change. Most of the console had already
 * moved: {@code DataSetController}, {@code FileController} and {@code CDCController} all call
 * {@code /tenant/features}. Only chat still went to Vault.
 *
 * <p>Request-scoped because {@code ChatAccess.available()} is called three times in one render of
 * {@code layout/main.html} and would otherwise make three identical round trips per page. Scoped to
 * the request rather than the session so a change is visible on the next page load, which is the
 * point of moving it.
 */
@Slf4j
@Component
@RequestScope
public class TenantFeaturesResolver {

    private final DatahubApi datahubApi;

    private TenantFeatures features;
    private boolean fetched;

    public TenantFeaturesResolver(DatahubApi datahubApi) {
        this.datahubApi = datahubApi;
    }

    /**
     * @return the flags, or an all-off {@link TenantFeatures} if the api cannot be reached. Failing
     *         closed is right for a gate: the features this hides are ones whose endpoints live in
     *         that same api, so offering them while it is down only moves the error later.
     */
    public TenantFeatures get() {
        if (fetched) {
            return features;
        }
        fetched = true;
        try {
            features = datahubApi.getTenantFeatures();
        } catch (RuntimeException e) {
            log.debug("Could not read tenant features from datahub-api: {}", e.getMessage());
        }
        if (features == null) {
            features = new TenantFeatures();
        }
        return features;
    }
}
