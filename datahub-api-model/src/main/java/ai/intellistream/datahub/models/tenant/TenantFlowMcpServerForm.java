// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * A tool server's registration, for {@code PUT /tenant/settings/flow/mcp-servers/{name}}.
 *
 * <p>{@code headerValue} is the exception to "replace": absent or blank leaves the stored
 * credential alone, so a server can be re-pointed without retyping it; it is required when the
 * server is new. The credential is never returned.
 *
 * @param url         the server's Streamable HTTP endpoint, as it is to be called; https
 * @param headerName  the header the credential travels in; {@code Authorization} when left out
 * @param headerValue the credential, e.g. {@code Bearer …}; blank keeps the stored one
 */
public record TenantFlowMcpServerForm(String url, String headerName, String headerValue) {
}
