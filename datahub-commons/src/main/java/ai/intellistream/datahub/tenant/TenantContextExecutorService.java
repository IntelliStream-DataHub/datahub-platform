// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link ExecutorService} decorator that propagates the submitting thread's {@link TenantContext}
 * onto the worker thread that runs each task, then restores the worker's previous context.
 *
 * <p>{@code TenantContext} is a ThreadLocal, so any request work offloaded to a pool would otherwise
 * run with no tenant and fail every per-tenant lookup (e.g. {@code ValkeyService} /
 * {@code KVRocksService} resolving a connection). Wrap a pool with this once and every
 * {@code execute}/{@code submit}/{@code invoke*} inherits the caller's tenant automatically, so call
 * sites don't have to remember to thread the tenant through by hand.
 *
 * <p>Save/restore (rather than just clear) keeps a {@code CallerRunsPolicy} safe: when the pool runs
 * a task on the submitting thread under saturation, the finally block restores that thread's own
 * context instead of wiping it mid-request.
 */
public class TenantContextExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    public TenantContextExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    /** Decorate {@code delegate} so submitted tasks inherit the submitting thread's TenantContext. */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new TenantContextExecutorService(delegate);
    }

    /** Capture the current tenant now and run {@code task} under it, restoring the worker's prior context. */
    public static Runnable wrap(Runnable task) {
        final String captured = TenantContext.getTenantId();
        return () -> {
            final String previous = TenantContext.getTenantId();
            try {
                applyTenant(captured);
                task.run();
            } finally {
                applyTenant(previous);
            }
        };
    }

    /** Callable variant of {@link #wrap(Runnable)}. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        final String captured = TenantContext.getTenantId();
        return () -> {
            final String previous = TenantContext.getTenantId();
            try {
                applyTenant(captured);
                return task.call();
            } finally {
                applyTenant(previous);
            }
        };
    }

    private static void applyTenant(String tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.setTenantId(tenantId);
        }
    }

    private static <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrap(task));
        }
        return wrapped;
    }

    // --- task-accepting methods: capture + propagate TenantContext ---

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    // --- lifecycle methods: delegate untouched ---

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
