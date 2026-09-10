// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * What the caller may do with one settings scope, from {@code GET /tenant/settings/permissions}.
 *
 * <p>Read and write are independent: write does not imply read. A client should use this to choose
 * between an editable form, a read-only one, and nothing at all — but it is not the security
 * boundary, and the endpoints enforce the same grants whatever a client does with this.
 */
public record SettingsPermission(boolean read, boolean write) {

    public static final SettingsPermission NONE = new SettingsPermission(false, false);
}
