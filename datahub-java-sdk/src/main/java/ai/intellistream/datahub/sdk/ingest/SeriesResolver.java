// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.validation.FieldLimits;
import ai.intellistream.datahub.timeseries.Timeseries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a binary frame needs to know about a series and the JSON path does not: its internal id
 * and its value type. Resolved once per unseen external id through {@code POST /timeseries/byids},
 * in bulk, and cached for the life of the client. A server answer that a series is unknown or
 * renamed evicts it, so the next request resolves it afresh.
 */
public final class SeriesResolver {

    /** A series as the frame names it. */
    public record Resolved(long id, String externalId, DatapointValueType type) {
    }

    private final Function<List<IdCollection>, DataWrapper<Timeseries>> byIds;
    private final Map<String, Resolved> byExternalId = new ConcurrentHashMap<>();
    private final Map<Long, Resolved> byInternalId = new ConcurrentHashMap<>();

    public SeriesResolver(Function<List<IdCollection>, DataWrapper<Timeseries>> byIds) {
        this.byIds = byIds;
    }

    /** Resolves every external id; one that does not exist (or is not readable) is absent. */
    public Map<String, Resolved> resolveExternalIds(Collection<String> externalIds) {
        Map<String, Resolved> found = new HashMap<>();
        Set<String> misses = new LinkedHashSet<>();
        for (String externalId : externalIds) {
            Resolved r = byExternalId.get(externalId);
            if (r != null) found.put(externalId, r); else misses.add(externalId);
        }
        if (!misses.isEmpty()) {
            List<IdCollection> query = new ArrayList<>(misses.size());
            for (String externalId : misses) {
                IdCollection ic = new IdCollection();
                ic.setExternalId(externalId);
                query.add(ic);
            }
            for (Resolved r : fetch(query)) {
                found.put(r.externalId(), r);
            }
        }
        return found;
    }

    /** Resolves every internal id; one that does not exist (or is not readable) is absent. */
    public Map<Long, Resolved> resolveIds(Collection<Long> ids) {
        Map<Long, Resolved> found = new HashMap<>();
        List<IdCollection> query = new ArrayList<>();
        for (Long id : ids) {
            Resolved r = byInternalId.get(id);
            if (r != null) {
                found.put(id, r);
            } else {
                IdCollection ic = new IdCollection();
                ic.setId(id);
                query.add(ic);
            }
        }
        if (!query.isEmpty()) {
            for (Resolved r : fetch(query)) {
                found.put(r.id(), r);
            }
        }
        return found;
    }

    private List<Resolved> fetch(List<IdCollection> query) {
        List<Resolved> resolved = new ArrayList<>();
        for (int from = 0; from < query.size(); from += FieldLimits.BATCH_ITEMS_MAX) {
            List<IdCollection> chunk = query.subList(from, Math.min(query.size(), from + FieldLimits.BATCH_ITEMS_MAX));
            DataWrapper<Timeseries> answer = byIds.apply(chunk);
            if (answer == null || answer.getItems() == null) continue;
            for (Timeseries t : answer.getItems()) {
                if (t.getId() == null || t.getExternalId() == null || t.getValueType() == null) continue;
                Resolved r = new Resolved(t.getId(), t.getExternalId(), DatapointValueType.fromName(t.getValueType()));
                byExternalId.put(r.externalId(), r);
                byInternalId.put(r.id(), r);
                resolved.add(r);
            }
        }
        return resolved;
    }

    /** Forget series the server said were unknown or renamed. */
    public void evict(Collection<Long> ids) {
        for (Long id : ids) {
            Resolved r = byInternalId.remove(id);
            if (r != null) byExternalId.remove(r.externalId());
        }
    }

    public void evictAll() {
        byExternalId.clear();
        byInternalId.clear();
    }

    public int size() {
        return byInternalId.size();
    }
}
