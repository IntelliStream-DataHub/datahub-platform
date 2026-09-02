// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Whether the caller may read or change this tenant's configuration.
 *
 * <p>The settings counterpart to {@link DataSecurity}, and deliberately a separate service: the two
 * answer different questions from the same group tree, and folding settings into the dataset
 * permission set would make every caller who wanted one pay for the dataset closure expansion that
 * produces the other.
 *
 * <p>Resolution reuses {@link OrgGroupResolver}, which already caches a caller's groups for tens of
 * seconds, so asking it a second question in a request costs nothing. {@code DATAHUB_ADMIN} is
 * answered from the token alone and never reaches it — the operator escape hatch has to keep
 * working when UserInfo does not.
 */
@Service
public class SettingsSecurity {

    private final OrgGroupResolver orgGroupResolver;

    public SettingsSecurity(OrgGroupResolver orgGroupResolver) {
        this.orgGroupResolver = orgGroupResolver;
    }

    public boolean canReadSettings() {
        return grants().read();
    }

    public boolean canWriteSettings() {
        return grants().write();
    }

    /**
     * @throws SettingsAccessDeniedException naming the group to grant, because the person who hits
     *                                       this cannot fix it and the person who can needs to be
     *                                       told exactly what to add
     */
    public void assertCanReadSettings() {
        if (!canReadSettings()) {
            throw SettingsAccessDeniedException.read();
        }
    }

    /** @see #assertCanReadSettings() */
    public void assertCanWriteSettings() {
        if (!canWriteSettings()) {
            throw SettingsAccessDeniedException.write();
        }
    }

    private SettingsGrants grants() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return SettingsGrants.none();
        }
        if (DatasetPermissions.isAdmin(authentication.getAuthorities())) {
            return SettingsGrants.all();
        }
        return SettingsGrants.from(orgGroupResolver.groupsForCurrentCaller());
    }
}
