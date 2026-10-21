// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import java.util.HashMap;
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

    private static final String SECRET = "flow.secret.";
    private static final String CLIENT = "flow.client.";
    private static final String CLIENT_ID = ".client-id";
    private static final String CLIENT_SECRET = ".client-secret";

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

    private static String checked(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid name: " + name);
        }
        return name;
    }
}
