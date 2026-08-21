// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A stable per-instance identifier used to make Pulsar producer/consumer names unique across
 * instances of the same service on a shared topic. A fixed name collides on the broker (only one
 * producer per name per topic, one consumer per Exclusive subscription), which silently breaks
 * horizontal scale-out; suffixing names with this id fixes it.
 *
 * <p>The id is {@code <hostname>-numa<node>} — deployments pin at most one instance of a service per
 * {@code (host, NUMA node)} slot, so it is unique across instances yet stable across restarts of the
 * same slot. The NUMA node is read from {@code Mems_allowed_list} in {@code /proc/self/status}
 * (a single node id when pinned via {@code numactl --membind}/cpuset). When the node can't be
 * determined (unpinned, or a non-Linux host such as a dev box) the id is just the hostname.
 */
public final class InstanceIdentity {

    private static final String ID = compute(hostname(), readMemsAllowedList());

    private InstanceIdentity() {
    }

    /** The per-instance id, e.g. {@code "api-host-01-numa1"}; safe to embed in Pulsar names. */
    public static String get() {
        return ID;
    }

    // ---- testable pieces ----------------------------------------------------------------------

    static String compute(String host, String memsAllowedList) {
        String base = sanitize(host);
        String numa = parseNumaNode(memsAllowedList);
        return numa == null ? base : base + "-numa" + numa;
    }

    /**
     * The bound NUMA node from a {@code Mems_allowed_list} value: a bare integer when the process is
     * pinned to a single node ({@code "1"}), or {@code null} for a range/list ({@code "0-1"},
     * {@code "0,2"}) which means unpinned.
     */
    static String parseNumaNode(String memsAllowedList) {
        if (memsAllowedList == null) return null;
        String value = memsAllowedList.trim();
        return value.matches("\\d+") ? value : null;
    }

    /** Reduce to characters that are safe in a Pulsar producer/subscription name. */
    static String sanitize(String s) {
        String cleaned = (s == null ? "" : s).replaceAll("[^A-Za-z0-9-]", "-");
        return cleaned.isBlank() ? "unknown-host" : cleaned;
    }

    // ---- environment probes -------------------------------------------------------------------

    private static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            String etc = Files.readString(Path.of("/etc/hostname")).trim();
            if (!etc.isBlank()) return etc;
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }

    private static String readMemsAllowedList() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/self/status"));
            for (String line : lines) {
                if (line.startsWith("Mems_allowed_list:")) {
                    return line.substring("Mems_allowed_list:".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // /proc unavailable (non-Linux) — treat as no NUMA info
        }
        return null;
    }
}
