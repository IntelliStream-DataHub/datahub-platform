// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * A tool server's registration, for {@code PUT /tenant/settings/flow/mcp-servers/{name}}.
 *
 * <p>{@code auth} picks how its calls are authenticated, and which of the other fields apply:
 * {@value #AUTH_HEADER} (the default) sends one fixed header, {@value #AUTH_CLIENT_CREDENTIALS}
 * takes an OAuth 2.0 access token with the client-credentials grant and sends it as
 * {@code Authorization: Bearer}. Saving one kind removes what was stored for the other.
 *
 * <p>The credential is the exception to "replace": an absent or blank {@code headerValue} or
 * {@code clientSecret} leaves the stored one alone, so a server can be re-pointed without retyping
 * it; it is required when nothing of that kind is stored, and a changed {@code clientId} needs its
 * secret. The credential is never returned.
 *
 * @param url          the server's Streamable HTTP endpoint, as it is to be called; https
 * @param auth         {@value #AUTH_HEADER} or {@value #AUTH_CLIENT_CREDENTIALS}; {@value #AUTH_HEADER} when left out
 * @param headerName   header: the header the credential travels in; {@code Authorization} when left out
 * @param headerValue  header: the credential, e.g. {@code Bearer …}; blank keeps the stored one
 * @param tokenUrl     client-credentials: the token endpoint; https
 * @param clientId     client-credentials: the client id
 * @param clientSecret client-credentials: the client secret; blank keeps the stored one
 * @param scope        client-credentials: the scope to ask for, space-separated; left out when blank
 * @param resource     client-credentials: the resource indicator (RFC 8707) to ask for, an absolute
 *                     URI, often the server's own URL; left out when blank
 */
public record TenantFlowMcpServerForm(String url, String auth, String headerName, String headerValue,
                                      String tokenUrl, String clientId, String clientSecret, String scope,
                                      String resource) {

    public static final String AUTH_HEADER = "header";
    public static final String AUTH_CLIENT_CREDENTIALS = "client-credentials";
}
