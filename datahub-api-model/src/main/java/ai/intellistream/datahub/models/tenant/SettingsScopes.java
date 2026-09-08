// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

import java.util.List;

/**
 * The settings a tenant administers for itself, each granted separately.
 *
 * <p>A scope is the subject segment of the organization group that grants it —
 * {@code /settings/llm/read}, {@code /settings/llm/write} — with {@code /settings/*&#47;read} and
 * {@code /settings/*&#47;write} covering every scope, present and future. Granting per scope rather
 * than over settings as a whole matters because they are not comparable: whoever may change which
 * model your assistant runs on, and what it costs you, is not automatically whoever may change
 * anything else that ends up here.
 *
 * <p>Adding a scope means adding a constant, listing it in {@link #ALL}, and creating the group in
 * the organization. Nobody holds a new scope until it is granted, except holders of the wildcard.
 */
public final class SettingsScopes {

    /** Which model the tenant's AI assistant runs on, and what it may spend. */
    public static final String LLM = "llm";

    /** Every scope this platform knows, for a client rendering what it may do. */
    public static final List<String> ALL = List.of(LLM);

    private SettingsScopes() {
    }
}
