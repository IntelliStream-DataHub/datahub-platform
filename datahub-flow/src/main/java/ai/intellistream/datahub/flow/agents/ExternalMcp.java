// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.agents;

import ai.intellistream.datahub.llm.mcp.ClientCredentialsTokens;
import ai.intellistream.datahub.llm.mcp.StreamableMcpClient;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * How this service reaches the tool servers a tenant registered: the one HTTP client (no
 * redirects), the timeout an exchange gets, whether a server may sit on a private network, and the
 * tokens of the servers registered with a client-credentials grant, shared by every run. A client
 * is opened per run or per request and closed after, since a Streamable HTTP session is the
 * client's.
 */
public record ExternalMcp(HttpClient http, JsonMapper json, Duration timeout, boolean allowPrivateNetworks,
                          ClientCredentialsTokens tokens) {

    /** A session with {@code server}, as the tenant registered it. Nothing is sent until the first call. */
    public StreamableMcpClient open(TenantFlowSection.McpServer server) {
        StreamableMcpClient.Credential credential = switch (server.auth()) {
            case TenantFlowSection.McpHeader header -> StreamableMcpClient.Credential.header(header.name(), header.value());
            case TenantFlowSection.McpClientCredentials grant -> tokens.credential(server.name(), grant);
        };
        return new StreamableMcpClient(server.name(), server.url(), credential, http, json, timeout, allowPrivateNetworks);
    }
}
