// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    public static void setTenantId(String tenantId) { CURRENT_TENANT.set(tenantId); }
    public static String getTenantId() { return CURRENT_TENANT.get(); }
    public static void clear() { CURRENT_TENANT.remove(); }

    /**
     * Run {@code task} with {@code tenantId} set as the current tenant, then restore the previous
     * tenant (or clear it) afterward. Use this to give a block of work a specific tenant on a thread
     * that doesn't otherwise carry one — e.g. a multi-tenant worker pool processing a per-tenant
     * batch. (For propagating an existing request-thread tenant onto a pool, prefer
     * {@code TenantContextExecutorService}.)
     */
    public static void runWith(String tenantId, Runnable task) {
        callWith(tenantId, () -> {
            task.run();
            return null;
        });
    }

    /**
     * {@link #runWith} for work that produces a value — a per-tenant batch that reports whether it
     * finished, say. Same save-set-restore contract.
     */
    public static <T> T callWith(String tenantId, java.util.function.Supplier<T> task) {
        String previous = CURRENT_TENANT.get();
        try {
            if (tenantId == null) CURRENT_TENANT.remove();
            else CURRENT_TENANT.set(tenantId);
            return task.get();
        } finally {
            if (previous == null) CURRENT_TENANT.remove();
            else CURRENT_TENANT.set(previous);
        }
    }
}
