// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.mcp;

/**
 * The outcome of one {@code tools/call}, flattened to the text the model will see.
 *
 * <p>datahub-api's {@code McpResultConverter} returns a single JSON string in one text content
 * block, so {@code text} is normally that JSON. It is passed to the model verbatim — do not
 * re-parse or re-shape it.
 *
 * <p>{@code isError} covers both failure surfaces (a JSON-RPC {@code error} object and a
 * {@code result.isError}). Tool failures are values, not exceptions: the model needs to see them
 * as a tool result so it can adapt, rather than having the turn aborted underneath it.
 */
public record McpToolResult(String text, boolean isError) {

    public static McpToolResult ok(String text) {
        return new McpToolResult(text, false);
    }

    public static McpToolResult error(String text) {
        return new McpToolResult(text, true);
    }
}
