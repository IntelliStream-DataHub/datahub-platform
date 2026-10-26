// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import java.time.Duration;

/**
 * A failure talking to an MCP server — the request never produced a usable JSON-RPC result:
 * unreachable, a non-200 status, or a JSON-RPC {@code error} envelope.
 *
 * <p>Distinct from a failed tool call, which comes back as {@link McpToolResult#isError()}.
 * Whether a transport failure is shown to the model as a tool result or ends the turn is the
 * caller's decision, not this client's.
 *
 * <p>{@link #isUnauthorized()} singles out a 401/403 so a caller can tell the user to sign in
 * again instead of reporting a generic failure. A token captured once per request can expire
 * mid-loop, and this is where that surfaces. {@link #retryAfter()} carries a 429's
 * {@code Retry-After}, so a caller that may wait knows how long; whether it waits is its call.
 */
public class McpException extends RuntimeException {

    private final boolean unauthorized;
    private final Duration retryAfter;

    public McpException(String message, boolean unauthorized) {
        super(message);
        this.unauthorized = unauthorized;
        this.retryAfter = null;
    }

    /** A 429: the server asked for a pause of {@code retryAfter}, or null when it named none. */
    public McpException(String message, Duration retryAfter) {
        super(message);
        this.unauthorized = false;
        this.retryAfter = retryAfter;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.unauthorized = false;
        this.retryAfter = null;
    }

    public boolean isUnauthorized() {
        return unauthorized;
    }

    /** How long a rate-limited call was asked to wait, or null for any other failure. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
