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
        String previous = CURRENT_TENANT.get();
        try {
            if (tenantId == null) CURRENT_TENANT.remove();
            else CURRENT_TENANT.set(tenantId);
            task.run();
        } finally {
            if (previous == null) CURRENT_TENANT.remove();
            else CURRENT_TENANT.set(previous);
        }
    }
}
