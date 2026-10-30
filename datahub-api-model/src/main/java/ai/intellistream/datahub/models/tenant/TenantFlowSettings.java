// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

import java.util.List;

/**
 * The credentials your tenant's flows may use, as returned by {@code GET /tenant/settings/flow}.
 *
 * <p>Names only. A secret's value, a client's secret and a tool server's credential are written
 * through this API and never read back through it; the flow service reads them from Vault when a
 * run starts. A client id is returned because it is not a credential, and it is what tells a
 * person which Keycloak client a name stands for; a tool server's URL, header name, token
 * endpoint and client id likewise.
 *
 * @param secrets    the stored secrets' names, sorted, which a flow refers to by name
 * @param clients    the registered clients a flow's runs may act as, sorted by name
 * @param mcpServers the registered MCP servers an agent's tools may come from, sorted by name
 */
public record TenantFlowSettings(List<String> secrets, List<Client> clients, List<McpServer> mcpServers) {

    /**
     * A registered client: a confidential Keycloak client the tenant created, whose service-account
     * user holds the organization groups the flows acting as it should have.
     *
     * @param name     what a deployment names it by ({@code runAs})
     * @param clientId its Keycloak client id
     */
    public record Client(String name, String clientId) {
    }

    /**
     * A registered MCP server: an agent names its tools as {@code <name>__<tool>}, and its runs
     * call the server with the stored credential, which is never returned here. The fields of the
     * kind of authentication it does not use are null.
     *
     * @param name       the registered name, and the prefix of its tools
     * @param url        its Streamable HTTP endpoint
     * @param auth       {@code header} or {@code client-credentials}, as in {@link TenantFlowMcpServerForm}
     * @param headerName header: the header the credential travels in
     * @param tokenUrl   client-credentials: the token endpoint
     * @param clientId   client-credentials: the client id
     * @param scope      client-credentials: the scope asked for, or null
     * @param resource   client-credentials: the resource asked for, or null
     */
    public record McpServer(String name, String url, String auth, String headerName, String tokenUrl,
                            String clientId, String scope, String resource) {
    }
}
