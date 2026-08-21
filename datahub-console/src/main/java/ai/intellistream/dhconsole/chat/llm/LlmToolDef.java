// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

/**
 * One tool the model may call, in provider-neutral form.
 *
 * <p>{@code inputSchemaJson} is the raw JSON Schema string exactly as datahub-api's MCP server
 * advertised it. Both the Anthropic API and every OpenAI-compatible server accept JSON Schema
 * verbatim, so this needs no conversion in either adapter — which is what keeps the provider
 * seam cheap.
 */
public record LlmToolDef(String name, String description, String inputSchemaJson) {
}
