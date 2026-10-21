// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

/**
 * One tool the model may call, in provider-neutral form.
 *
 * <p>{@code inputSchemaJson} is the raw JSON Schema string exactly as the MCP server advertised
 * it. Both the Anthropic API and every OpenAI-compatible server accept JSON Schema verbatim, so
 * this needs no conversion in either adapter — which is what keeps the provider seam cheap.
 *
 * <p>{@code readOnly} and {@code destructive} are the server's own word, from the MCP
 * {@code annotations} ({@code readOnlyHint}, {@code destructiveHint}) on {@code tools/list}.
 * {@code null} means the server said nothing, which a caller must treat as the worst case: the
 * tool may write. Nothing here classifies a tool by its name.
 */
public record LlmToolDef(String name, String description, String inputSchemaJson,
                         Boolean readOnly, Boolean destructive) {

    /** A tool that advertised no hints. */
    public LlmToolDef(String name, String description, String inputSchemaJson) {
        this(name, description, inputSchemaJson, null, null);
    }
}
