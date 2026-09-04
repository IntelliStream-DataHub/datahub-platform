// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when a caller may not read or change their tenant's settings. Extends
 * {@link AccessDeniedException} so it still maps to 403, and names the organization group that
 * would grant it — the caller cannot fix this themselves, so the message is aimed at whoever they
 * will forward it to.
 */
public class SettingsAccessDeniedException extends AccessDeniedException {

    public SettingsAccessDeniedException(String permission) {
        super("No permission to " + permission + " this organization's settings. This needs the "
                + "/settings/" + permission + " group in your Keycloak organization.");
    }
}
