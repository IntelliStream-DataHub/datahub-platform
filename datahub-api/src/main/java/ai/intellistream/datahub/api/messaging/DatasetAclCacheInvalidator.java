// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging;

import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.messaging.events.DatasetAclInvalidationEvent;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the tenant's cached dataset-ACL closures once the transaction that changed the dataset
 * graph has committed.
 *
 * <p>The listener runs on the committing thread, which normally still has {@code TenantContext}
 * set, but the tenant is taken from the event and restored afterwards rather than assumed: the
 * ordering against {@code RequestStateCleanupFilter} is not something this should depend on.
 */
@Component
@Slf4j
public class DatasetAclCacheInvalidator {

    private final DatasetClosureService closureService;

    public DatasetAclCacheInvalidator(DatasetClosureService closureService) {
        this.closureService = closureService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDatasetAclChange(DatasetAclInvalidationEvent event) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(event.tenantId());
            closureService.invalidate();
        } catch (RuntimeException e) {
            // Never fail the request over a cache bump — the write itself already committed. The
            // cost is stale grants until the closure entries expire, which invalidate() logs.
            log.error("Dataset ACL cache invalidation failed for tenant {}", event.tenantId(), e);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }
}
