// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.access.AccessDeniedException;

/**
 * The caller lacks {@code /settings/read} or {@code /settings/write} in their organization.
 *
 * <p>Extends {@link AccessDeniedException} so the existing {@code AccessDeniedExceptionHandler}
 * renders it as a 403 without a new advice — and, more usefully, so it is not folded into a 404 the
 * way a dataset denial is. Hiding a dataset's existence is worth something; hiding whether this
 * tenant has settings is not, and telling someone plainly which group they need is the difference
 * between a fixable problem and a support ticket.
 */
public class SettingsAccessDeniedException extends AccessDeniedException {

    public static SettingsAccessDeniedException read() {
        return new SettingsAccessDeniedException(SettingsGrants.READ_PATH);
    }

    public static SettingsAccessDeniedException write() {
        return new SettingsAccessDeniedException(SettingsGrants.WRITE_PATH);
    }

    private SettingsAccessDeniedException(String groupPath) {
        super("This action needs the " + groupPath + " group in your organization.");
    }
}
