// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.models.policy.PolicyWarning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Carries the warnings raised during a write from the service that raised them to the envelope that
 * reports them.
 *
 * <p>A {@link ThreadLocal} rather than a return value, because the alternative is threading a
 * warnings list back through every create/update signature in the api — services, MCP tools and all
 * their callers — to carry something that is empty on almost every request. The repo already
 * handles request-scoped state this way; {@code RequestStateCleanupFilter} clears this alongside
 * {@code TenantContext} and the memoised dataset permissions.
 *
 * <p><strong>Clearing is not optional.</strong> Tomcat reuses worker threads, so a list left behind
 * is reported to whichever request lands on that thread next — one caller would see another
 * caller's external ids. That is why the clear sits in the same filter, in a {@code finally}, at
 * {@code HIGHEST_PRECEDENCE}.
 */
public final class PolicyWarningContext {

    private static final ThreadLocal<List<PolicyWarning>> CURRENT = new ThreadLocal<>();

    private PolicyWarningContext() {
    }

    public static void add(Collection<PolicyWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        List<PolicyWarning> current = CURRENT.get();
        if (current == null) {
            current = new ArrayList<>();
            CURRENT.set(current);
        }
        current.addAll(warnings);
    }

    /** The warnings raised so far on this request, or an empty list. Never null. */
    public static List<PolicyWarning> current() {
        List<PolicyWarning> current = CURRENT.get();
        return current == null ? List.of() : List.copyOf(current);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
