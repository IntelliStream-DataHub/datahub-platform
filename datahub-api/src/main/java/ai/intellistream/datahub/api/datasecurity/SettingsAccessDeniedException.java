// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when a caller may not read or change one of their tenant's settings. Extends
 * {@link AccessDeniedException} so it still maps to 403, and names the exact organization group
 * that would grant it — the caller cannot fix this themselves, so the message is written for
 * whoever they will forward it to.
 */
public class SettingsAccessDeniedException extends AccessDeniedException {

    private final transient String scope;
    private final transient String permission;

    public SettingsAccessDeniedException(String scope, String permission) {
        super("No permission to " + permission + " the '" + scope + "' settings of this "
                + "organization. This needs the /settings/" + scope + "/" + permission
                + " group in your Keycloak organization.");
        this.scope = scope;
        this.permission = permission;
    }

    public String getScope() {
        return scope;
    }

    public String getPermission() {
        return permission;
    }
}
