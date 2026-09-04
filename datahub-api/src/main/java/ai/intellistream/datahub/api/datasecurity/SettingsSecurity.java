// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The settings equivalent of {@link DataSecurity}: the one place asking whether this caller may
 * see or change their tenant's own settings.
 *
 * <p>Resolved per call rather than memoised. The dataset path caches because a single request can
 * ask about hundreds of datasets; a settings request asks once, so a cache would add a lifecycle
 * to clear and buy nothing. {@link OrgGroupResolver} does its own caching underneath either way.
 */
@Slf4j
@Service
public class SettingsSecurity {

    private final OrgGroupResolver orgGroupResolver;

    public SettingsSecurity(OrgGroupResolver orgGroupResolver) {
        this.orgGroupResolver = orgGroupResolver;
    }

    public SettingsGrants grants() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Answered from the token alone, so an operator keeps access through an identity-provider
        // outage — the same reason DatasetPermissions short-circuits on it.
        if (authentication != null && DatasetPermissions.isAdmin(authentication.getAuthorities())) {
            return SettingsGrants.all();
        }
        return SettingsGrants.from(orgGroupResolver.groupsForCurrentCaller());
    }

    public void assertCanRead() {
        if (!grants().canRead()) {
            throw new SettingsAccessDeniedException("read");
        }
    }

    public void assertCanWrite() {
        if (!grants().canWrite()) {
            throw new SettingsAccessDeniedException("write");
        }
    }
}
