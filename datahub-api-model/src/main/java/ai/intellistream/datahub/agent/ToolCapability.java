// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.agent;

/**
 * What a tool does to the data, as classified by the server that serves it.
 *
 * <p>MCP itself gives a client nothing to go on: a {@code tools/list} entry carries a name, a
 * description and an input schema, and Spring AI's {@code @Tool} adds only {@code returnDirect}.
 * So the classification has to be published deliberately. It is published by datahub-api rather
 * than inferred by each client, because datahub-api owns the tools and is the only place that
 * cannot forget to update it when one is added.
 *
 * <p>The MCP spec does have the right home for this — {@code ToolAnnotations.readOnlyHint} — and
 * this moves there once Spring AI can populate it.
 */
public enum ToolCapability {

    /** Reads data. Safe to offer to any caller who can read something. */
    READ,

    /** Creates, updates or deletes. Never offered by an agent whose allowlist is read-only. */
    WRITE
}
