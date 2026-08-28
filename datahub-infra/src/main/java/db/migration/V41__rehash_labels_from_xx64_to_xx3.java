// SPDX-License-Identifier: AGPL-3.0-or-later
package db.migration;

import ai.intellistream.datahub.helpers.text.Labels;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recompute {@code label.hash} with the hash the application actually uses.
 *
 * <h2>What went wrong</h2>
 * Labels were hashed with XXH64 ({@code LongHashFunction.xx()}) until 2026-07-20, when
 * {@code 5c22b485} — a fix for 409s on duplicate label names — deleted the line that wrote it and
 * left {@code Label.setName}'s XXH3 as the only writer. The algorithm changed; the stored rows did
 * not. So every label created before that date carries a hash no current code can reproduce.
 *
 * <p>The damage is quiet, because only the derived column drifted. {@code label.name} is still
 * right, so reads report the label correctly — {@code node.labels} is a denormalised string and the
 * join row exists — while anything matching on {@code label.hash} misses. Filtering by such a label
 * returns an empty result, indistinguishable from "nothing is tagged that way". Label delete and the
 * label-in-use check look up by hash too, so they miss the same rows.
 *
 * <p>Java rather than SQL because the hash is XXH3 over the canonicalised name, which Postgres
 * cannot compute. It is written per tenant through the same Flyway path as every other migration.
 *
 * <h2>Collisions</h2>
 * Hashing goes through {@link Labels#hash}, which canonicalises first. Two rows whose names
 * canonicalise to the same string — {@code Sensor} and {@code SENSOR}, possible before names were
 * canonicalised on write — would therefore target one hash and collide on {@code label_hash_key}.
 * Those rows are left alone and logged individually, rather than failing the migration: a blocked
 * migration blocks tenant provisioning, and merging duplicate labels is a data decision this cannot
 * make on an operator's behalf. They stay unfilterable until someone merges them, which the log
 * says plainly.
 */
public class V41__rehash_labels_from_xx64_to_xx3 extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V41__rehash_labels_from_xx64_to_xx3.class);

    @Override
    public void migrate(Context context) throws Exception {
        Map<Long, String> names = new HashMap<>();
        Map<Long, Long> currentHashes = new HashMap<>();
        try (Statement read = context.getConnection().createStatement();
             ResultSet rs = read.executeQuery("SELECT id, name, hash FROM label")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                names.put(id, rs.getString("name"));
                currentHashes.put(id, rs.getLong("hash"));
            }
        }
        if (names.isEmpty()) {
            return;
        }

        // Every hash that will exist once this is done, so a rewrite cannot land on a row that is
        // already correct — or on another row's target.
        Map<Long, Long> targets = new HashMap<>();
        Map<Long, List<Long>> byTarget = new HashMap<>();
        names.forEach((id, name) -> {
            long target = Labels.hash(name);
            targets.put(id, target);
            byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(id);
        });

        Set<Long> collided = new HashSet<>();
        byTarget.forEach((target, ids) -> {
            if (ids.size() > 1) {
                collided.addAll(ids);
                log.error("Labels {} all canonicalise to the same name and cannot share hash {}. "
                                + "Leaving them as they are; they will not match label filters until "
                                + "they are merged by hand.",
                        ids.stream().map(id -> id + "=" + names.get(id)).toList(), target);
            }
        });

        List<Long> stale = targets.keySet().stream()
                .filter(id -> !collided.contains(id))
                .filter(id -> !targets.get(id).equals(currentHashes.get(id)))
                .toList();
        if (stale.isEmpty()) {
            log.info("Label hashes are already current; nothing to rewrite.");
            return;
        }

        try (PreparedStatement update = context.getConnection()
                .prepareStatement("UPDATE label SET hash = ? WHERE id = ?")) {
            for (Long id : stale) {
                update.setLong(1, targets.get(id));
                update.setLong(2, id);
                update.addBatch();
            }
            update.executeBatch();
        }
        log.info("Rewrote {} stale label hash(es) of {} label(s); {} left for manual merge.",
                stale.size(), names.size(), collided.size());
    }
}
