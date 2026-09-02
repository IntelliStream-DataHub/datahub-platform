// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.datahub.tenant.CallerPermissions;

/**
 * Who an agent is running as, for this turn.
 *
 * <p>This is the seam that keeps the tool rule from having to know what kind of agent it is
 * serving. Everything downstream — {@code ToolPolicy}, the loop, every MCP call — asks only two
 * things: what token do I present, and what may that token's owner do. Both are answered here.
 *
 * <p>Today there is exactly one kind of identity: the signed-in console user, whose token is
 * resolved on the request thread and whose grants come from {@code GET /tenant/permissions}. An
 * autonomous agent would be a second way of constructing this record — a Keycloak service account
 * that has joined the tenant's organization and holds its own {@code /datasets/<id>/read} groups —
 * and nothing that consumes it would change, because datahub-api's dataset ACLs are group-based
 * and do not care whether a principal is a person.
 *
 * <p>The bearer is carried as a value, never held on a bean. Holding one on an instance would turn
 * per-user access into a shared service account silently, which is the failure {@code McpBridge}'s
 * javadoc warns about at length.
 *
 * @param bearer      the access token every tool call is made with
 * @param permissions what that token's owner may read and write, or null when it could not be
 *                    established — treated as "nothing", never as "everything"
 */
public record ExecutionIdentity(String bearer, CallerPermissions permissions) {
}
