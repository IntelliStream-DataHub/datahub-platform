// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.repositories.node.SeriesMeta;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the series a binary ingest request names, in one query per request.
 *
 * <p>Deliberately not cached. The JSON path resolves one series per <em>collection</em> with no
 * cache at all, so a single projection query for the whole request is already well inside what
 * that path costs. A cache here would buy a query that is not hot and charge for it in staleness:
 * the dataset id is an input to the write ACL, so serving a stale one keeps authorising against
 * the dataset a series has been moved out of. If this ever does show up in a profile, the answer
 * is to narrow the query, not to hold authorisation data in memory.
 */
@Service
public class TimeseriesMetaLookup {

    /** Ids per query, so one request naming very many series does not build one enormous IN list. */
    static final int QUERY_CHUNK = 5_000;

    private final TimeseriesRepository timeseriesRepository;
    private final TransactionTemplate transactionTemplate;

    public TimeseriesMetaLookup(TimeseriesRepository timeseriesRepository, TransactionTemplate transactionTemplate) {
        this.timeseriesRepository = timeseriesRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The series behind {@code ids} that exist in the tenant; an id that does not is simply absent,
     * and the caller turns that into the 404. All chunks read in one transaction, so the request
     * sees a single snapshot however many queries it takes.
     */
    public Map<Long, SeriesMeta> resolve(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Long> wanted = List.copyOf(ids);
        return transactionTemplate.execute(status -> {
            Map<Long, SeriesMeta> found = new HashMap<>();
            for (int from = 0; from < wanted.size(); from += QUERY_CHUNK) {
                List<Long> chunk = wanted.subList(from, Math.min(wanted.size(), from + QUERY_CHUNK));
                for (SeriesMeta meta : timeseriesRepository.findSeriesMetaByIdIn(chunk)) {
                    found.put(meta.id(), meta);
                }
            }
            return found;
        });
    }
}
