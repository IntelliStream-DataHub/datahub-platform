// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * What the binary ingest path needs to know about a series, per instance and briefly: its external
 * id, value type and dataset. Misses are fetched in one query per request. Entries live for a few
 * seconds because another instance may rename or re-parent a series; within that window a renamed
 * series is still caught by the frame's directory check.
 */
@Service
public class TimeseriesMetaCache {

    public record SeriesMeta(long id, String externalId, int valueTypeId, Long dataSetId) {
    }

    private record Entry(SeriesMeta meta, long expiresAtNanos) {
    }

    static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    static final int MAX_ENTRIES_PER_TENANT = 200_000;
    static final int QUERY_CHUNK = 5_000;

    private final TimeseriesRepository timeseriesRepository;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Entry>> byTenant = new ConcurrentHashMap<>();

    public TimeseriesMetaCache(TimeseriesRepository timeseriesRepository, TransactionTemplate transactionTemplate) {
        this.timeseriesRepository = timeseriesRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /** The series behind {@code ids} that exist in the tenant; an id that does not is simply absent. */
    public Map<Long, SeriesMeta> resolve(String tenantId, Collection<Long> ids) {
        ConcurrentHashMap<Long, Entry> cache = byTenant.computeIfAbsent(tenantId, t -> new ConcurrentHashMap<>());
        long now = System.nanoTime();
        Map<Long, SeriesMeta> found = new HashMap<>();
        List<Long> misses = new ArrayList<>();
        for (Long id : ids) {
            Entry e = cache.get(id);
            if (e != null && e.expiresAtNanos() > now) {
                found.put(id, e.meta());
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            if (cache.size() > MAX_ENTRIES_PER_TENANT) {
                cache.clear();
            }
            long expiresAt = now + TTL_NANOS;
            for (int from = 0; from < misses.size(); from += QUERY_CHUNK) {
                List<Long> chunk = misses.subList(from, Math.min(misses.size(), from + QUERY_CHUNK));
                List<TimeseriesEntity> loaded = transactionTemplate.execute(
                        status -> timeseriesRepository.findAllWithDataSetByIdIn(chunk));
                if (loaded == null) {
                    continue;
                }
                for (TimeseriesEntity t : loaded) {
                    SeriesMeta meta = new SeriesMeta(t.getId(), t.getExternalId(), t.getValueType().getId(),
                            t.getDataSet() == null ? null : t.getDataSet().getId());
                    cache.put(t.getId(), new Entry(meta, expiresAt));
                    found.put(t.getId(), meta);
                }
            }
        }
        return found;
    }

    public void invalidate(String tenantId, Collection<Long> ids) {
        ConcurrentHashMap<Long, Entry> cache = byTenant.get(tenantId);
        if (cache != null) {
            ids.forEach(cache::remove);
        }
    }

    public void invalidateTenant(String tenantId) {
        byTenant.remove(tenantId);
    }
}
