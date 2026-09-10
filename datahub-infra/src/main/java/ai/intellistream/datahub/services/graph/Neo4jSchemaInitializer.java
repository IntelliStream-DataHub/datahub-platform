// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services.graph;

import ai.intellistream.datahub.config.Neo4j;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the per-type-label uniqueness constraints the graph has never had, once per tenant per
 * JVM, on the drain path.
 *
 * <p>Two things depended on their absence going unnoticed: every {@code MERGE} matched on an
 * unindexed property and so scanned every node in the database, and nothing but the single-active
 * consumer stopped two writers from creating duplicate nodes for one id. The consumer is gone, so
 * the constraint is what makes concurrent appliers safe, and the index it brings is what makes
 * them fast.
 *
 * <p>Constraint creation is best-effort by design. If a tenant's graph already holds duplicates
 * from the years without a constraint, creation fails; the drain continues without it, which is
 * exactly today's behaviour and no worse. The failure is cached so it is logged once rather than
 * on every batch, and cleared only by a restart — after an operator has deduplicated.
 */
@Service
public class Neo4jSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

    private final Neo4j neo4j;
    private final Map<String, Boolean> initialised = new ConcurrentHashMap<>();

    public Neo4jSchemaInitializer(Neo4j neo4j) {
        this.neo4j = neo4j;
    }

    /** Idempotent and cheap after the first call for a tenant. */
    public void ensureConstraints(String tenantId) {
        if (initialised.containsKey(tenantId)) {
            return;
        }
        if (createConstraints(tenantId)) {
            initialised.put(tenantId, Boolean.TRUE);
        }
    }

    private boolean createConstraints(String tenantId) {
        try (Session session = neo4j.getSession(tenantId)) {
            for (String label : TypeLabels.ALL) {
                String name = "node_id_unique_" + label.toLowerCase(Locale.ROOT);
                try {
                    session.run("CREATE CONSTRAINT " + name + " IF NOT EXISTS "
                            + "FOR (n:" + label + ") REQUIRE n.id IS UNIQUE").consume();
                } catch (RuntimeException e) {
                    // IF NOT EXISTS still races: two instances creating the same constraint at the
                    // same moment leaves one of them holding an "equivalent rule already exists"
                    // error, which means the constraint is there — the outcome we wanted.
                    if (alreadyExists(e)) {
                        continue;
                    }
                    log.error("Could not create graph uniqueness constraint {} for tenant {} — the "
                                    + "mirror stays correct (writes are serialised per tenant) but "
                                    + "unindexed. Deduplicate the label's id values and restart to retry: {}",
                            name, tenantId, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // Neo4j being briefly unreachable is not a reason to give up on the constraints for the
            // rest of the JVM's life: report nothing done so the next drain tries again.
            log.error("Could not open a graph session to create constraints for tenant {}: {}",
                    tenantId, e.getMessage());
            return false;
        }
        return true;
    }

    private static boolean alreadyExists(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("equivalent");
    }
}
