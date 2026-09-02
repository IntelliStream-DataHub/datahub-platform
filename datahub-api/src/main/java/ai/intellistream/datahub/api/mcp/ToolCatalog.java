// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp;

import ai.intellistream.datahub.agent.ToolCapability;
import ai.intellistream.datahub.agent.ToolCatalogEntry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every MCP tool on this platform does to the data.
 *
 * <p><strong>Why this exists here rather than in the client.</strong> Until now the console kept
 * its own hardcoded list of read-only tool names, with a comment explaining that MCP offers no
 * read-only hint to go on. That worked, but it put the classification in a different module from
 * the tools: adding a tool to datahub-api left it silently unusable, and renaming one silently
 * removed it from the assistant. Classification belongs next to the {@code @Tool} method it
 * describes, published once, so every client — the console, an agent definition's allowlist,
 * anything later — reads the same answer.
 *
 * <p><strong>Default-deny still applies at the point of use.</strong> This catalogue only says
 * what a tool <em>is</em>; it grants nothing. An agent may use a tool only if its own allowlist
 * names it (see {@code AgentService}), and only if the identity it runs as may do so.
 *
 * <p><strong>Adding a tool.</strong> Classify it here. {@code ToolCatalogTest} reflects over
 * every {@code @Tool} method registered in {@link McpConfig} and fails if one is missing or
 * unknown, so this cannot drift from the tools it describes.
 *
 * <p>The MCP spec has the right home for this — {@code ToolAnnotations.readOnlyHint} on the
 * {@code tools/list} entry — but Spring AI's {@code @Tool} carries only {@code name},
 * {@code description}, {@code returnDirect} and {@code resultConverter}, and its
 * {@code ToolDefinition} carries only name, description and input schema, so there is no way to
 * populate the annotation without bypassing {@code MethodToolCallbackProvider}. Move this there
 * when Spring AI supports it; the shape of the answer will not change.
 */
@Component
public class ToolCatalog {

    /** This service. */
    public static final String DATAHUB_API = "datahub-api";

    /**
     * The sibling MCP server. Its tools are catalogued here, not there, because agent definitions
     * are validated in one place and a tenant naming {@code analysis_related_series} in an
     * allowlist must not be rejected merely because another service owns it. Deployments without
     * datahub-analysis simply never see the tool advertised — {@code McpBridge} degrades to "its
     * tools are absent this turn" — and an allowlist entry for an absent tool is harmless.
     */
    public static final String DATAHUB_ANALYSIS = "datahub-analysis";

    private static final Map<String, ToolCatalogEntry> ENTRIES = build();

    private static Map<String, ToolCatalogEntry> build() {
        Map<String, ToolCatalogEntry> entries = new LinkedHashMap<>();

        // -- datahub-api ------------------------------------------------------------------
        read(entries, "dataset_list", "dataset_search");
        write(entries, "dataset_create", "dataset_update", "dataset_delete");

        read(entries, "resource_get", "resource_search", "resource_fetch_related",
                "resource_fetch_nearest");
        write(entries, "resource_create", "resource_update", "resource_delete");

        read(entries, "event_get", "event_search", "event_filter");
        write(entries, "event_create", "event_update", "event_delete");

        read(entries, "timeseries_get", "timeseries_search", "timeseries_get_latest",
                "timeseries_list", "timeseries_fetch_datapoints");
        write(entries, "timeseries_create", "timeseries_update", "timeseries_delete",
                "timeseries_send_datapoint");

        read(entries, "edge_get", "edge_list_types");
        write(entries, "edge_create", "edge_create_type", "edge_delete");

        read(entries, "label_list");
        write(entries, "label_create", "label_update");

        read(entries, "unit_list", "unit_get");

        // -- datahub-analysis -------------------------------------------------------------
        entries.put("analysis_related_series", new ToolCatalogEntry(
                "analysis_related_series", ToolCapability.READ, DATAHUB_ANALYSIS));

        return Map.copyOf(entries);
    }

    private static void read(Map<String, ToolCatalogEntry> into, String... names) {
        for (String name : names) {
            into.put(name, new ToolCatalogEntry(name, ToolCapability.READ, DATAHUB_API));
        }
    }

    private static void write(Map<String, ToolCatalogEntry> into, String... names) {
        for (String name : names) {
            into.put(name, new ToolCatalogEntry(name, ToolCapability.WRITE, DATAHUB_API));
        }
    }

    /** Every tool this platform serves, in a stable order so the catalogue reads the same twice. */
    public List<ToolCatalogEntry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    /** False for a name no MCP server on this platform serves — a typo, or a removed tool. */
    public boolean isKnown(String toolName) {
        return ENTRIES.containsKey(toolName);
    }

    /**
     * True only for a tool this catalogue knows <em>and</em> classifies as {@link
     * ToolCapability#READ}. An unknown name is not read-only: default-deny, so a tool added
     * without being classified is refused rather than assumed safe.
     */
    public boolean isReadOnly(String toolName) {
        ToolCatalogEntry entry = ENTRIES.get(toolName);
        return entry != null && entry.capability() == ToolCapability.READ;
    }

    /** The names of every read-only tool. */
    public List<String> readOnlyToolNames() {
        return ENTRIES.values().stream()
                .filter(e -> e.capability() == ToolCapability.READ)
                .map(ToolCatalogEntry::name)
                .toList();
    }
}
