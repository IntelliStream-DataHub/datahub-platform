// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What must always be true of the run store (RUN_STORE.md, <em>No schema constraints any more</em>),
 * read off the raw keys. Postgres used to hold these as constraints; now only the store's transactions
 * do, so the tests check them after random sequences of operations and under concurrent load. Called
 * once the operations have returned: a flow's presence in {@code flow:ready} is a hint, which is only
 * required to be there while there is something to start, and which may linger after.
 *
 * @return the violations, empty when the store is sound
 */
public final class StoreInvariants {

    private StoreInvariants() {
    }

    public static List<String> violations() {
        RedisCommands<String, String> r = SharedKvrocks.raw();
        List<String> bad = new ArrayList<>();
        Set<String> flows = new HashSet<>();
        for (String key : SharedKvrocks.keys("flow:meta:*")) {
            flows.add(key.substring("flow:meta:".length()));
        }
        for (String key : SharedKvrocks.keys("flow:q:*")) {
            flows.add(key.substring("flow:q:".length()));
        }
        for (String key : SharedKvrocks.keys("flow:retrying:*")) {
            flows.add(key.substring("flow:retrying:".length()));
        }
        Set<String> queued = new HashSet<>();
        Set<String> running = new HashSet<>();
        for (String flow : flows) {
            Map<String, String> meta = r.hgetall("flow:meta:" + flow);
            List<String> q = r.zrange("flow:q:" + flow, 0, -1);
            List<String> d = r.zrange("flow:d:" + flow, 0, -1);
            Set<String> rs = r.smembers("flow:running:" + flow);
            int max = Integer.parseInt(meta.getOrDefault("max_concurrent", "1"));
            boolean usable = !"0".equals(meta.get("enabled")) && !"0".equals(meta.get("deployed"));
            // More running than the limit is legal after the limit is lowered; starting one beyond it is
            // not, and the concurrency test watches for that.
            int room = usable ? max - rs.size() : 1;
            boolean ready = r.zscore("flow:ready", flow) != null;
            if (!ready && !q.isEmpty() && room > 0) {
                bad.add("flow " + flow + " has " + q.size() + " queued and room " + room + " but is not ready");
            }
            Set<String> retrying = r.smembers("flow:retrying:" + flow);
            for (String id : retrying) {
                Map<String, String> h = r.hgetall("flow:run:" + id);
                if (!"PENDING".equals(h.get("status")) || Integer.parseInt(h.get("attempt")) < 2) {
                    bad.add("retrying run " + id + " is " + h.get("status") + " on attempt " + h.get("attempt"));
                }
            }
            Set<String> delayedSet = new HashSet<>(d);
            for (String id : d) {
                if (r.zscore("flow:delayed", id) == null) {
                    bad.add("delayed run " + id + " is not in flow:delayed");
                }
            }
            for (String id : q) {
                if (!queued.add(id) || delayedSet.contains(id)) {
                    bad.add("run " + id + " is queued twice");
                }
                if (!"PENDING".equals(r.hget("flow:run:" + id, "status"))) {
                    bad.add("queued run " + id + " is " + r.hget("flow:run:" + id, "status"));
                }
            }
            for (String id : d) {
                if (!"PENDING".equals(r.hget("flow:run:" + id, "status"))) {
                    bad.add("delayed run " + id + " is " + r.hget("flow:run:" + id, "status"));
                }
            }
            for (String id : rs) {
                running.add(id);
                Map<String, String> h = r.hgetall("flow:run:" + id);
                if (!"RUNNING".equals(h.get("status")) || h.get("owner") == null) {
                    bad.add("running-set run " + id + " is " + h.get("status") + " owned by " + h.get("owner"));
                }
            }
        }
        for (String key : SharedKvrocks.keys("flow:run:*")) {
            String id = key.substring("flow:run:".length());
            Map<String, String> h = r.hgetall(key);
            String status = h.get("status");
            boolean preview = "1".equals(h.get("preview"));
            boolean leased = r.zscore("flow:leases", id) != null;
            boolean live = r.zscore("flow:live", id) != null;
            if (live != (status.equals("PENDING") || status.equals("RUNNING"))) {
                bad.add("run " + id + " is " + status + " but live=" + live);
            }
            switch (status) {
                case "RUNNING" -> {
                    if (!leased || h.get("owner") == null || h.get("lease_ms") == null) {
                        bad.add("running run " + id + " lacks a lease or an owner");
                    }
                    if (!preview && !running.contains(id)) {
                        bad.add("running run " + id + " is not in its flow's running set");
                    }
                }
                case "PENDING" -> {
                    if (!preview && Integer.parseInt(h.get("attempt")) > 1
                            && !r.sismember("flow:retrying:" + h.get("flow_id"), id)) {
                        bad.add("pending run " + id + " on attempt " + h.get("attempt") + " is not retrying");
                    }
                    if (!preview && !queued.contains(id) && !isDelayed(r, h.get("flow_id"), id)) {
                        bad.add("pending run " + id + " is in no queue");
                    }
                    if (leased) {
                        bad.add("pending run " + id + " has a lease");
                    }
                }
                default -> {
                    if (queued.contains(id) || running.contains(id) || leased || isDelayed(r, h.get("flow_id"), id)) {
                        bad.add("finished run " + id + " (" + status + ") is still in a live index");
                    }
                }
            }
        }
        for (String id : r.zrange("flow:live", 0, -1)) {
            if (r.hget("flow:run:" + id, "status") == null) {
                bad.add("live index holds " + id + ", which is gone");
            }
        }
        for (String id : r.zrange("flow:delayed", 0, -1)) {
            if (!"PENDING".equals(r.hget("flow:run:" + id, "status"))) {
                bad.add("delayed index holds " + id + " which is " + r.hget("flow:run:" + id, "status"));
            }
        }
        for (String id : r.zrange("flow:leases", 0, -1)) {
            if (!"RUNNING".equals(r.hget("flow:run:" + id, "status"))) {
                bad.add("lease of " + id + " which is " + r.hget("flow:run:" + id, "status"));
            }
        }
        for (String key : SharedKvrocks.keys("flow:sched:*")) {
            if (key.startsWith("flow:sched-lock:")) {
                continue;
            }
            String flow = key.substring("flow:sched:".length());
            Map<String, String> h = r.hgetall(key);
            Double due = r.zscore("flow:sched", flow);
            if (due == null) {
                bad.add("schedule of flow " + flow + " is not in flow:sched");
                continue;
            }
            String next = h.get("next_tick_ms");
            double expected = next == null || next.isEmpty() ? 9.0e15 : Long.parseLong(next) + Long.parseLong(h.get("delay_ms"));
            if (due != expected) {
                bad.add("schedule of flow " + flow + " is due at " + due + " but its next tick says " + expected);
            }
        }
        for (String flow : r.zrange("flow:sched", 0, -1)) {
            if (r.exists("flow:sched:" + flow) == 0) {
                bad.add("flow:sched holds flow " + flow + ", which has no schedule");
            }
        }
        return bad;
    }

    private static boolean isDelayed(RedisCommands<String, String> r, String flow, String id) {
        return flow != null && r.zscore("flow:d:" + flow, id) != null;
    }
}
