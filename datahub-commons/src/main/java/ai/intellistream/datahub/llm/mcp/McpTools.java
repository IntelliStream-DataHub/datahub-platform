// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;

import java.util.List;
import java.util.Map;

/**
 * An MCP server as a tool-use loop sees it: a name, the tools it advertises, and a call. Who the
 * calls are made as is settled when an instance is made — a platform server as the run's identity
 * ({@link StatelessMcpClient#as}), an external server as its own credential
 * ({@link StreamableMcpClient}) — so a caller holding several needs no idea which is which.
 */
public interface McpTools {

    /** For log lines, lineage and the prefix of an external server's tools. */
    String name();

    /** @throws McpException if the server did not answer with a tool list */
    List<LlmToolDef> listTools();

    /**
     * A tool that ran and failed is a result with {@link McpToolResult#isError()}; only a request
     * that produced no result at all throws.
     *
     * @throws McpException on a transport failure, a non-2xx status, or a JSON-RPC error
     */
    McpToolResult callTool(String tool, Map<String, Object> arguments);

    /** Ends whatever the server was told to keep for this caller. Never throws. */
    default void close() {
    }
}
