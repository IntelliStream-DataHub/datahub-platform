// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Connection configuration: a base URL plus authentication — either a static bearer
 * {@code token} or OAuth2 client-credentials ({@code clientId}, {@code clientSecret},
 * {@code tokenUri}, optionally {@code scope} and {@code audience}).
 *
 * <p>{@code scope} and {@code audience} are omitted from the token request unless set.
 *
 * <p><strong>Against DataHub, whether {@code scope} is required depends on how the deployment
 * produces the {@code organization} claim.</strong> If it uses Keycloak's Organizations feature,
 * set {@code organization:*} (or {@code organization:<alias>} to pin one tenant): that claim comes
 * from a dynamic client scope which Keycloak only applies when the request names it, so without a
 * selector the token carries no tenant and every call fails {@code 401 invalid_token} — which looks
 * like a credentials problem but is not. If instead the claim comes from a protocol mapper on the
 * client, it is emitted unconditionally and no scope is needed. Ask whoever runs the realm which
 * it is.
 *
 * <p>Other providers want these for their own reasons: Microsoft Entra ID requires
 * {@code scope=api://<app-id-uri>/.default}, Auth0 requires {@code audience}.
 *
 * <p>Configuring an assertion source ({@code assertion}, or the {@code assertionCredentials}
 * triple) switches the token request to the RFC 7523 {@code jwt-bearer} grant: a JWT from one
 * issuer is exchanged at {@code tokenUri} for a token from another. That is how an Entra ID
 * service principal reaches an API that only trusts Keycloak.
 *
 * <p>Optionally, a durable ingest-buffer retention window. Buffering is off by default. When enabled
 * (via {@code enableBuffering()}, {@code bufferRetention(...)} or {@code bufferMaxBytes(...)}),
 * datapoint and event ingestion that can't reach the server or is rejected with an auth failure
 *  * (HTTP 401/403, e.g. an expired token) - spools to disk and is retried on the next
 * ingest call. The buffer is bounded on two axes, and any axis left unset when buffering is enabled
 * falls back to its default: {@link #DEFAULT_BUFFER_RETENTION} (72 hours) and
 * {@link #DEFAULT_BUFFER_MAX_BYTES} (5 GiB).
 */
public final class DatahubConfig {

    /** Default time window applied when buffering is enabled without an explicit retention. */
    public static final Duration DEFAULT_BUFFER_RETENTION = Duration.ofHours(72);

    /** Default size cap (5 GiB) applied when buffering is enabled without an explicit byte cap. */
    public static final long DEFAULT_BUFFER_MAX_BYTES = 5L * 1024 * 1024 * 1024;

    private final String baseUrl;
    private final String token;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUri;
    private final String scope;
    private final String audience;
    private final String assertion;
    private final String assertionTokenUri;
    private final String assertionClientId;
    private final String assertionClientSecret;
    private final String assertionScope;
    private final String assertionAudience;
    private final String projectName;
    private final Duration bufferRetention;
    private final Long bufferMaxBytes;
    private final Path bufferDirectory;

    private DatahubConfig(Builder b) {
        if (b.baseUrl == null || b.baseUrl.isBlank()) {
            throw new DatahubConfigException("baseUrl is required (set BASE_URL or builder().baseUrl(...))");
        }
        boolean hasToken = b.token != null && !b.token.isBlank();
        boolean hasClientCreds = notBlank(b.clientId) && notBlank(b.clientSecret) && notBlank(b.tokenUri);
        if (!hasToken && !hasClientCreds) {
            throw new DatahubConfigException(
                    "authentication required: provide a static TOKEN, or CLIENT_ID + CLIENT_SECRET + TOKEN_URI");
        }
        boolean anyAssertionField = notBlank(b.assertion) || notBlank(b.assertionTokenUri)
                || notBlank(b.assertionClientId) || notBlank(b.assertionClientSecret)
                || notBlank(b.assertionScope) || notBlank(b.assertionAudience);
        if (anyAssertionField) {
            boolean hasAssertionSource = notBlank(b.assertion)
                    || notBlank(b.assertionTokenUri) && notBlank(b.assertionClientId)
                            && notBlank(b.assertionClientSecret);
            if (!hasAssertionSource) {
                throw new DatahubConfigException("incomplete jwt-bearer assertion source: provide ASSERTION, "
                        + "or ASSERTION_TOKEN_URI + ASSERTION_CLIENT_ID + ASSERTION_CLIENT_SECRET");
            }
            if (!hasClientCreds) {
                throw new DatahubConfigException(
                        "jwt-bearer needs CLIENT_ID + CLIENT_SECRET + TOKEN_URI to authenticate the exchange");
            }
        }
        if (b.bufferMaxBytes != null && b.bufferMaxBytes <= 0) {
            throw new DatahubConfigException("bufferMaxBytes must be > 0 when set");
        }
        if (b.bufferRetention != null && (b.bufferRetention.isZero() || b.bufferRetention.isNegative())) {
            throw new DatahubConfigException("bufferRetention must be positive when set");
        }
        this.baseUrl = stripTrailingSlash(b.baseUrl);
        this.token = b.token;
        this.clientId = b.clientId;
        this.clientSecret = b.clientSecret;
        this.tokenUri = b.tokenUri;
        this.scope = b.scope;
        this.audience = b.audience;
        this.assertion = b.assertion;
        this.assertionTokenUri = b.assertionTokenUri;
        this.assertionClientId = b.assertionClientId;
        this.assertionClientSecret = b.assertionClientSecret;
        this.assertionScope = b.assertionScope;
        this.assertionAudience = b.assertionAudience;
        this.projectName = b.projectName;
        // Buffering is off unless the caller opts in (enableBuffering() or setting either dimension).
        // When on, each unset dimension falls back to its default (72h / 5 GiB), so "I want retention"
        // gives a sensible bounded buffer without spelling out both numbers.
        boolean buffering = b.bufferingRequested || b.bufferRetention != null || b.bufferMaxBytes != null;
        if (buffering) {
            this.bufferRetention = b.bufferRetention != null ? b.bufferRetention : DEFAULT_BUFFER_RETENTION;
            this.bufferMaxBytes = b.bufferMaxBytes != null ? b.bufferMaxBytes : DEFAULT_BUFFER_MAX_BYTES;
        } else {
            this.bufferRetention = null;
            this.bufferMaxBytes = null;
        }
        this.bufferDirectory = b.bufferDirectory;
    }

    public String baseUrl()      { return baseUrl; }
    public String token()        { return token; }
    public String clientId()     { return clientId; }
    public String clientSecret() { return clientSecret; }
    public String tokenUri()     { return tokenUri; }
    public String projectName()  { return projectName; }

    /** OAuth2 {@code scope} sent with the client-credentials request, or {@code null} to omit it. */
    public String scope()        { return scope; }

    /** OAuth2 {@code audience} sent with the client-credentials request, or {@code null} to omit it. */
    public String audience()     { return audience; }

    /** A ready-made JWT to present as the {@code jwt-bearer} assertion, or {@code null} to fetch one. */
    public String assertion()              { return assertion; }

    public String assertionTokenUri()      { return assertionTokenUri; }
    public String assertionClientId()      { return assertionClientId; }
    public String assertionClientSecret()  { return assertionClientSecret; }
    public String assertionScope()         { return assertionScope; }
    public String assertionAudience()      { return assertionAudience; }

    /** Time-based ingest-buffer retention window, or {@code null} for no time bound. */
    public Duration bufferRetention() { return bufferRetention; }

    /** Size-based ingest-buffer cap in bytes (per spool file), or {@code null} for no size bound. */
    public Long bufferMaxBytes() { return bufferMaxBytes; }

    /** Directory the ingest spools live in, or {@code null} to use the default ({@code .datahub-spool}). */
    public Path bufferDirectory() { return bufferDirectory; }

    /** True when either retention dimension is set, i.e. durable ingest buffering is enabled. */
    public boolean hasBuffering() { return bufferRetention != null || bufferMaxBytes != null; }

    public boolean hasStaticToken() {
        return token != null && !token.isBlank();
    }

    public boolean hasClientCredentials() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(tokenUri);
    }

    /**
     * True when an assertion source is configured, i.e. the token at {@link #tokenUri()} is obtained
     * with the RFC 7523 {@code jwt-bearer} grant rather than plain client credentials.
     */
    public boolean hasJwtBearer() {
        return hasClientCredentials() && (notBlank(assertion) || hasAssertionCredentials());
    }

    /** True when the assertion is to be fetched with client credentials from another provider. */
    public boolean hasAssertionCredentials() {
        return notBlank(assertionClientId) && notBlank(assertionClientSecret) && notBlank(assertionTokenUri);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Build from the process environment (and a {@code .env} file in the working dir, if present). */
    public static DatahubConfig fromEnv() {
        Map<String, String> env = new HashMap<>(readDotEnv(Path.of(".env")));
        env.putAll(System.getenv()); // real env vars take precedence over .env
        return fromMap(env);
    }

    /**
     * Build from a HashiCorp Vault KV v2 secret. The secret's fields use the same keys as the
     * environment: {@code BASE_URL}, and either {@code TOKEN} or
     * {@code CLIENT_ID}/{@code CLIENT_SECRET}/{@code TOKEN_URI} (optional {@code PROJECT_NAME}).
     */
    public static DatahubConfig fromVault(String address, String token, String secretPath) {
        return fromVault(address, token, "secret", secretPath);
    }

    /** As {@link #fromVault(String, String, String)} with an explicit KV v2 mount (default {@code secret}). */
    public static DatahubConfig fromVault(String address, String token, String mount, String secretPath) {
        return fromMap(VaultSecretLoader.readKvV2(address, token, mount, secretPath));
    }

    /** Build from Vault, taking {@code VAULT_ADDR} and {@code VAULT_TOKEN} from the environment. */
    public static DatahubConfig fromVaultEnv(String secretPath) {
        String address = System.getenv("VAULT_ADDR");
        String token = System.getenv("VAULT_TOKEN");
        if (address == null || address.isBlank() || token == null || token.isBlank()) {
            throw new DatahubConfigException("VAULT_ADDR and VAULT_TOKEN must be set to use fromVaultEnv(...)");
        }
        return fromVault(address, token, secretPath);
    }

    /**
     * Build from a Vault KV v2 secret, authenticating with AppRole: log in with
     * {@code roleId}/{@code secretId} to obtain a token, then read the secret. Uses the default
     * mounts ({@code approle} for auth, {@code secret} for KV).
     */
    public static DatahubConfig fromVaultAppRole(String address, String roleId, String secretId, String secretPath) {
        String token = VaultSecretLoader.appRoleLogin(address, roleId, secretId, "approle");
        return fromVault(address, token, secretPath);
    }

    /**
     * Build from Vault via AppRole, taking {@code VAULT_ADDR}, {@code VAULT_ROLE_ID} and
     * {@code VAULT_SECRET_ID} from the environment.
     */
    public static DatahubConfig fromVaultAppRoleEnv(String secretPath) {
        String address = System.getenv("VAULT_ADDR");
        String roleId = System.getenv("VAULT_ROLE_ID");
        String secretId = System.getenv("VAULT_SECRET_ID");
        if (address == null || address.isBlank() || roleId == null || roleId.isBlank()
                || secretId == null || secretId.isBlank()) {
            throw new DatahubConfigException(
                    "VAULT_ADDR, VAULT_ROLE_ID and VAULT_SECRET_ID must be set to use fromVaultAppRoleEnv(...)");
        }
        return fromVaultAppRole(address, roleId, secretId, secretPath);
    }

    private static DatahubConfig fromMap(Map<String, String> values) {
        return builder()
                .baseUrl(values.get("BASE_URL"))
                .token(values.get("TOKEN"))
                .clientCredentials(values.get("CLIENT_ID"), values.get("CLIENT_SECRET"), values.get("TOKEN_URI"))
                .scope(values.get("SCOPE"))
                .audience(values.get("AUDIENCE"))
                .assertion(values.get("ASSERTION"))
                .assertionCredentials(values.get("ASSERTION_CLIENT_ID"), values.get("ASSERTION_CLIENT_SECRET"),
                        values.get("ASSERTION_TOKEN_URI"))
                .assertionScope(values.get("ASSERTION_SCOPE"))
                .assertionAudience(values.get("ASSERTION_AUDIENCE"))
                .projectName(values.get("PROJECT_NAME"))
                .bufferRetention(parseDuration(values.get("BUFFER_RETENTION")))
                .bufferMaxBytes(parseLong(values.get("BUFFER_MAX_BYTES")))
                .bufferDirectory(parsePath(values.get("BUFFER_DIRECTORY")))
                .build();
    }

    /** Parse an ISO-8601 duration (e.g. {@code PT60M}) for {@code BUFFER_RETENTION}; null/blank → null. */
    private static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(value.trim());
        } catch (RuntimeException e) {
            throw new DatahubConfigException("BUFFER_RETENTION must be an ISO-8601 duration (e.g. PT60M): " + value);
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new DatahubConfigException("BUFFER_MAX_BYTES must be an integer number of bytes: " + value);
        }
    }

    private static Path parsePath(String value) {
        return (value == null || value.isBlank()) ? null : Path.of(value.trim());
    }

    private static Map<String, String> readDotEnv(Path path) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq > 0) {
                    String key = s.substring(0, eq).strip();
                    String val = s.substring(eq + 1).strip();
                    if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"")
                            || val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    map.put(key, val);
                }
            }
        } catch (IOException e) {
            throw new DatahubConfigException("failed to read " + path, e);
        }
        return map;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static final class Builder {
        private String baseUrl;
        private String token;
        private String clientId;
        private String clientSecret;
        private String tokenUri;
        private String scope;
        private String audience;
        private String assertion;
        private String assertionTokenUri;
        private String assertionClientId;
        private String assertionClientSecret;
        private String assertionScope;
        private String assertionAudience;
        private String projectName;
        private Duration bufferRetention;
        private Long bufferMaxBytes;
        private Path bufferDirectory;
        private boolean bufferingRequested;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder clientCredentials(String clientId, String clientSecret, String tokenUri) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.tokenUri = tokenUri;
            return this;
        }

        /**
         * OAuth2 {@code scope} for the client-credentials request; space-separated for several.
         * Against a DataHub deployment that uses Keycloak Organizations this must include
         * {@code organization:*} (or {@code organization:<alias>}), or the minted token carries no
         * tenant and every call is rejected. Not needed where the claim comes from a protocol
         * mapper instead.
         * Left unset the parameter is omitted. Entra ID needs {@code api://<app-id-uri>/.default}.
         */
        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        /**
         * OAuth2 {@code audience} for the client-credentials request — the API the token is minted
         * for. Left unset the parameter is omitted. Required by Auth0, unused by Keycloak.
         */
        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        /**
         * Present a ready-made JWT as the RFC 7523 {@code jwt-bearer} assertion, exchanging it at
         * {@link #clientCredentials(String, String, String)}'s token URI. Use
         * {@link #assertionCredentials(String, String, String)} instead to have the SDK fetch a
         * fresh assertion itself — a static one is not refreshed and will eventually expire.
         */
        public Builder assertion(String assertion) {
            this.assertion = assertion;
            return this;
        }

        /**
         * Fetch the {@code jwt-bearer} assertion with client credentials from another provider —
         * an Entra ID app registration, say — then exchange it at
         * {@link #clientCredentials(String, String, String)}'s token URI for a token this API
         * accepts. Pair with {@link #assertionScope(String)} where the provider demands one.
         */
        public Builder assertionCredentials(String clientId, String clientSecret, String tokenUri) {
            this.assertionClientId = clientId;
            this.assertionClientSecret = clientSecret;
            this.assertionTokenUri = tokenUri;
            return this;
        }

        /** {@code scope} for the assertion request; Entra ID needs {@code api://<app-id-uri>/.default}. */
        public Builder assertionScope(String assertionScope) {
            this.assertionScope = assertionScope;
            return this;
        }

        /** {@code audience} for the assertion request. Omitted when unset. */
        public Builder assertionAudience(String assertionAudience) {
            this.assertionAudience = assertionAudience;
            return this;
        }

        public Builder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        /**
         * Turn on durable ingest buffering with default bounds ({@value #DEFAULT_BUFFER_MAX_BYTES}-byte
         * cap and a 72-hour window). Override either with {@link #bufferRetention(Duration)} /
         * {@link #bufferMaxBytes(Long)}. Buffering is off unless you call this or set a dimension.
         */
        public Builder enableBuffering() {
            this.bufferingRequested = true;
            return this;
        }

        /**
         * Time-based ingest-buffer retention: datapoints/events that can't be sent are spooled to disk
         * and kept for this long before being dropped. Setting it enables buffering; the byte cap then
         * defaults to {@link #DEFAULT_BUFFER_MAX_BYTES} unless you set it too.
         */
        public Builder bufferRetention(Duration bufferRetention) {
            this.bufferRetention = bufferRetention;
            if (bufferRetention != null) {
                this.bufferingRequested = true;
            }
            return this;
        }

        /**
         * Size-based ingest-buffer cap (bytes, per spool file): when the spool would exceed this, the
         * oldest items are dropped until it fits. Setting it enables buffering; the time window then
         * defaults to {@link #DEFAULT_BUFFER_RETENTION} unless you set it too.
         */
        public Builder bufferMaxBytes(Long bufferMaxBytes) {
            this.bufferMaxBytes = bufferMaxBytes;
            if (bufferMaxBytes != null) {
                this.bufferingRequested = true;
            }
            return this;
        }

        /** Directory for the ingest spool files. {@code null} (default) uses {@code .datahub-spool}. */
        public Builder bufferDirectory(Path bufferDirectory) {
            this.bufferDirectory = bufferDirectory;
            return this;
        }

        public DatahubConfig build() {
            return new DatahubConfig(this);
        }
    }
}
