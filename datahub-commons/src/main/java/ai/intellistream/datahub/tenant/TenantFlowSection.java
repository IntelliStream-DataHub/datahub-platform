// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The {@code flow.*} section of a tenant's {@code tenant-config/<org-name>} secret: the credentials
 * flows use, one flat key (or key pair) per entry, any number of each.
 *
 * <pre>
 * flow.secret.&lt;name&gt;                     a value a flow refers to by name (a password, a token)
 * flow.client.&lt;name&gt;.client-id           a registered client a flow's runs may act as
 * flow.client.&lt;name&gt;.client-secret
 * flow.token.&lt;deploymentId&gt;              a deployer's offline token, written by the flow service
 * flow.mcp.&lt;name&gt;.url                    an MCP server agents may call: its Streamable HTTP endpoint,
 *                                       and either one fixed header
 * flow.mcp.&lt;name&gt;.header-name            the header its credential travels in,
 * flow.mcp.&lt;name&gt;.header-value           and the credential;
 *                                       or an OAuth 2.0 client-credentials grant
 * flow.mcp.&lt;name&gt;.token-url              the token endpoint,
 * flow.mcp.&lt;name&gt;.client-id              the client,
 * flow.mcp.&lt;name&gt;.client-secret
 * flow.mcp.&lt;name&gt;.scope                  and, when the endpoint wants them, the scope
 * flow.mcp.&lt;name&gt;.resource               and the resource (RFC 8707) to ask for
 * </pre>
 *
 * <p>Flat keys in the one secret rather than a secret per entry, so that every section of the
 * tenant's settings stays in one place under the one policy line. The price is that every write
 * rewrites the whole secret under compare-and-set, that the secret grows with the number of
 * deployments, and that a removed value stays in the secret's version history until Vault rolls it
 * out — so removing a credential is no substitute for rotating it at its source.
 *
 * <p>The changes here are functions for {@link TenantConfigWriter#update}, so they apply to the
 * secret as it stands inside compare-and-set rather than to a copy read earlier. Each touches only
 * the keys of its own entry: the {@code llm.*} section and the tokens, which the settings API
 * neither lists nor changes, pass through as they were.
 */
public final class TenantFlowSection {

    /** A secret's or a client's name: lowercase, digits and dashes, starting with a letter or digit. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    /**
     * A tool server's name: as {@link #NAME} but at most 24 characters, so that
     * {@code <name>__<tool>} fits the 64 characters a model provider allows a tool name.
     */
    public static final Pattern MCP_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,23}");

    private static final String SECRET = "flow.secret.";
    private static final String CLIENT = "flow.client.";
    private static final String CLIENT_ID = ".client-id";
    private static final String CLIENT_SECRET = ".client-secret";
    private static final String TOKEN = "flow.token.";
    private static final String MCP = "flow.mcp.";
    private static final String MCP_URL = ".url";
    private static final String MCP_HEADER_NAME = ".header-name";
    private static final String MCP_HEADER_VALUE = ".header-value";
    private static final String MCP_TOKEN_URL = ".token-url";
    private static final String MCP_CLIENT_ID = ".client-id";
    private static final String MCP_CLIENT_SECRET = ".client-secret";
    private static final String MCP_SCOPE = ".scope";
    private static final String MCP_RESOURCE = ".resource";
    private static final List<String> MCP_KEYS = List.of(MCP_URL, MCP_HEADER_NAME, MCP_HEADER_VALUE,
            MCP_TOKEN_URL, MCP_CLIENT_ID, MCP_CLIENT_SECRET, MCP_SCOPE, MCP_RESOURCE);

    private TenantFlowSection() {
    }

    /** Stores {@code value} as the secret {@code name}, replacing any value it had. */
    public static UnaryOperator<Map<String, String>> putSecret(String name, String value) {
        String key = SECRET + checked(name);
        Objects.requireNonNull(value, "value");
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            changed.put(key, value);
            return changed;
        };
    }

    /** Removes the secret {@code name}; nothing changes when there is none. */
    public static UnaryOperator<Map<String, String>> removeSecret(String name) {
        String key = SECRET + checked(name);
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            changed.remove(key);
            return changed;
        };
    }

    /**
     * Registers the client {@code name}, replacing both its credentials if it was registered
     * already. Both are written together so a client is never left with one half of a pair.
     */
    public static UnaryOperator<Map<String, String>> putClient(String name, String clientId,
                                                               String clientSecret) {
        String prefix = CLIENT + checked(name);
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            changed.put(prefix + CLIENT_ID, clientId);
            changed.put(prefix + CLIENT_SECRET, clientSecret);
            return changed;
        };
    }

    /** Removes the client {@code name}; nothing changes when there is none. */
    public static UnaryOperator<Map<String, String>> removeClient(String name) {
        String prefix = CLIENT + checked(name);
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            changed.remove(prefix + CLIENT_ID);
            changed.remove(prefix + CLIENT_SECRET);
            return changed;
        };
    }

    /** The names of the stored secrets, sorted. Never their values. */
    public static TreeSet<String> secretNames(Map<String, String> secret) {
        TreeSet<String> names = new TreeSet<>();
        secret.keySet().forEach(key -> {
            if (key.startsWith(SECRET)) {
                names.add(key.substring(SECRET.length()));
            }
        });
        return names;
    }

    /**
     * The value of the secret {@code name}, or null when there is none. For the flow service, which
     * hands it to the run that refers to it; never for anything a caller reads back.
     */
    public static String secretValue(Map<String, String> secret, String name) {
        return secret.get(SECRET + name);
    }

    /** Each registered client's name and client id, sorted by name. Never the client secret. */
    public static TreeMap<String, String> clientIds(Map<String, String> secret) {
        TreeMap<String, String> clients = new TreeMap<>();
        secret.forEach((key, value) -> {
            if (key.startsWith(CLIENT) && key.endsWith(CLIENT_ID)) {
                clients.put(key.substring(CLIENT.length(), key.length() - CLIENT_ID.length()), value);
            }
        });
        return clients;
    }

    /** A registered client's credentials. */
    public record ClientCredentials(String clientId, String clientSecret) {
    }

    /**
     * The credentials of the client registered as {@code name}, or null when there is none. For the
     * flow service, which takes tokens with them; never for anything a caller reads back.
     */
    public static ClientCredentials clientCredentials(Map<String, String> secret, String name) {
        String id = secret.get(CLIENT + name + CLIENT_ID);
        String clientSecret = secret.get(CLIENT + name + CLIENT_SECRET);
        return id == null || clientSecret == null ? null : new ClientCredentials(id, clientSecret);
    }

    /**
     * Stores the offline token a flow's runs act with, as {@code flow.token.<flowId>}, replacing the
     * one it had. Written by the flow service when someone deploys the flow; the settings API never
     * lists or changes it.
     */
    public static UnaryOperator<Map<String, String>> putToken(long flowId, String token) {
        String key = TOKEN + flowId;
        Objects.requireNonNull(token, "token");
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            changed.put(key, token);
            return changed;
        };
    }

    /** Removes a flow's offline token; nothing changes when there is none. */
    public static UnaryOperator<Map<String, String>> removeToken(long flowId) {
        String key = TOKEN + flowId;
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            changed.remove(key);
            return changed;
        };
    }

    /** The offline token stored for a flow, or null when there is none. */
    public static String token(Map<String, String> secret, long flowId) {
        return secret.get(TOKEN + flowId);
    }

    /** Every flow's offline token in the secret. */
    public static List<String> tokens(Map<String, String> secret) {
        return secret.entrySet().stream()
                .filter(e -> e.getKey().startsWith(TOKEN))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** A registered tool server, credential included: for the flow service, never for anything a caller reads back. */
    public record McpServer(String name, String url, McpAuth auth) {
    }

    /** How a tool server's calls are authenticated. */
    public sealed interface McpAuth permits McpHeader, McpClientCredentials {
    }

    /** One fixed header on every call. */
    public record McpHeader(String name, String value) implements McpAuth {
        @Override
        public String toString() {
            return "McpHeader[name=" + name + "]";
        }
    }

    /**
     * An OAuth 2.0 client-credentials grant: the flow service takes a token at {@code tokenUrl} and
     * sends it as {@code Authorization: Bearer}. {@code scope} and {@code resource} are null when
     * the token endpoint is not to be sent them.
     */
    public record McpClientCredentials(String tokenUrl, String clientId, String clientSecret, String scope,
                                       String resource) implements McpAuth {
        @Override
        public String toString() {
            return "McpClientCredentials[tokenUrl=" + tokenUrl + ", clientId=" + clientId + "]";
        }
    }

    /**
     * Registers the tool server {@code name}, replacing what it had: the other kind of
     * authentication's keys go, so a server is never left with both. A null header value or client
     * secret keeps the stored one, so a server can be re-pointed without retyping it; the caller
     * has checked one of that kind is stored. Applied inside compare-and-set, so a credential
     * rotated meanwhile is the one kept.
     */
    public static UnaryOperator<Map<String, String>> putMcpServer(String name, String url, McpAuth auth) {
        String prefix = MCP + checkedMcp(name);
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(auth, "auth");
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            String keptValue = existing.get(prefix + MCP_HEADER_VALUE);
            String keptSecret = existing.get(prefix + MCP_CLIENT_SECRET);
            MCP_KEYS.forEach(key -> changed.remove(prefix + key));
            changed.put(prefix + MCP_URL, url);
            switch (auth) {
                case McpHeader header -> {
                    changed.put(prefix + MCP_HEADER_NAME, Objects.requireNonNull(header.name(), "header name"));
                    putIfNotNull(changed, prefix + MCP_HEADER_VALUE, header.value() != null ? header.value() : keptValue);
                }
                case McpClientCredentials grant -> {
                    changed.put(prefix + MCP_TOKEN_URL, Objects.requireNonNull(grant.tokenUrl(), "tokenUrl"));
                    changed.put(prefix + MCP_CLIENT_ID, Objects.requireNonNull(grant.clientId(), "clientId"));
                    putIfNotNull(changed, prefix + MCP_CLIENT_SECRET,
                            grant.clientSecret() != null ? grant.clientSecret() : keptSecret);
                    putIfNotNull(changed, prefix + MCP_SCOPE, grant.scope());
                    putIfNotNull(changed, prefix + MCP_RESOURCE, grant.resource());
                }
            }
            return changed;
        };
    }

    /** Removes the tool server {@code name}, credential included; nothing changes when there is none. */
    public static UnaryOperator<Map<String, String>> removeMcpServer(String name) {
        String prefix = MCP + checkedMcp(name);
        return existing -> {
            Map<String, String> changed = new HashMap<>(existing);
            MCP_KEYS.forEach(key -> changed.remove(prefix + key));
            return changed;
        };
    }

    /**
     * The registered tool servers by name, sorted, credentials included — for the flow service,
     * which calls them, and for the settings API's mapping to what a caller may see, which drops
     * the credential. An entry missing its URL or its credential is not a server and is left out.
     */
    public static TreeMap<String, McpServer> mcpServers(Map<String, String> secret) {
        TreeMap<String, McpServer> servers = new TreeMap<>();
        secret.forEach((key, url) -> {
            if (key.startsWith(MCP) && key.endsWith(MCP_URL)) {
                String name = key.substring(MCP.length(), key.length() - MCP_URL.length());
                String prefix = MCP + name;
                McpAuth auth = null;
                String tokenUrl = secret.get(prefix + MCP_TOKEN_URL);
                if (tokenUrl != null) {
                    String clientId = secret.get(prefix + MCP_CLIENT_ID);
                    String clientSecret = secret.get(prefix + MCP_CLIENT_SECRET);
                    if (clientId != null && clientSecret != null) {
                        auth = new McpClientCredentials(tokenUrl, clientId, clientSecret,
                                secret.get(prefix + MCP_SCOPE), secret.get(prefix + MCP_RESOURCE));
                    }
                } else if (secret.get(prefix + MCP_HEADER_VALUE) != null) {
                    auth = new McpHeader(secret.getOrDefault(prefix + MCP_HEADER_NAME, "Authorization"),
                            secret.get(prefix + MCP_HEADER_VALUE));
                }
                if (auth != null) {
                    servers.put(name, new McpServer(name, url, auth));
                }
            }
        });
        return servers;
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String checked(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid name: " + name);
        }
        return name;
    }

    private static String checkedMcp(String name) {
        if (name == null || !MCP_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid tool server name: " + name);
        }
        return name;
    }
}
