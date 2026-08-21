// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.mcp;

/**
 * A transport-level failure talking to datahub-api's MCP endpoint — the request never produced a
 * usable JSON-RPC response.
 *
 * <p>Distinct from a failed tool call, which comes back as {@link McpToolResult#isError()} and is
 * fed to the model rather than thrown.
 *
 * <p>{@link #isUnauthorized()} singles out a 401 so the controller can tell the user to sign in
 * again instead of reporting a generic failure. The user's access token is captured once per
 * request, so a token that expires mid-loop surfaces here.
 */
public class McpException extends RuntimeException {

    private final boolean unauthorized;

    public McpException(String message, boolean unauthorized) {
        super(message);
        this.unauthorized = unauthorized;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.unauthorized = false;
    }

    public boolean isUnauthorized() {
        return unauthorized;
    }
}
