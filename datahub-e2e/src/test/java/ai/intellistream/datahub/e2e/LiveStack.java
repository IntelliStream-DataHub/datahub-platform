// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Where the live platform is, and how to reach it. Everything comes from the environment so the
 * same test runs against a local compose stack or anywhere else; when the stack is not configured
 * or not answering, the tests skip rather than fail, because a red build on a machine with no
 * platform says nothing about the code.
 *
 * <p>Required: {@code DATAHUB_BASE_URL}, {@code DATAHUB_TOKEN_URI}, {@code DATAHUB_CLIENT_ID},
 * {@code DATAHUB_CLIENT_SECRET}. Optional: {@code DATAHUB_SCOPE} (defaults to the organization
 * selector the api needs), {@code CLICKHOUSE_URL}, {@code CLICKHOUSE_USER},
 * {@code CLICKHOUSE_PASSWORD}, {@code CLICKHOUSE_DB} for the read-back checks.
 */
final class LiveStack {

    private LiveStack() {
    }

    /**
     * Client credentials rather than a pasted token: a benchmark runs longer than a token lives,
     * and the SDK refreshes these on its own.
     */
    static DatahubClient client(String baseUrl) {
        DatahubConfig config = DatahubConfig.builder()
                .baseUrl(baseUrl)
                .clientCredentials(require("DATAHUB_CLIENT_ID"),
                        require("DATAHUB_CLIENT_SECRET"),
                        require("DATAHUB_TOKEN_URI"))
                // Without an organization selector Keycloak emits no organization claim and the
                // api rejects every call as 401, which reads like a bad secret.
                .scope(env("DATAHUB_SCOPE", "openid organization:*"))
                .build();
        return new DatahubClient(config);
    }

    static DatahubClient client() {
        return client(baseUrl());
    }

    static String baseUrl() {
        return require("DATAHUB_BASE_URL");
    }

    /** Skips the test unless the api answers; a configured-but-down stack is not a code failure. */
    static void requireReachable() {
        Assumptions.assumeTrue(env("DATAHUB_BASE_URL", null) != null,
                "DATAHUB_BASE_URL is not set; see datahub-e2e/README.md");
        Assumptions.assumeTrue(env("DATAHUB_CLIENT_ID", null) != null,
                "DATAHUB_CLIENT_ID is not set; see datahub-e2e/README.md");
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpRequest probe = HttpRequest.newBuilder(URI.create(baseUrl() + "/timeseries"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<Void> response = http.send(probe, HttpResponse.BodyHandlers.discarding());
            // 401 is a healthy api refusing an unauthenticated probe, which is all this checks.
            Assumptions.assumeTrue(response.statusCode() < 500,
                    "api at " + baseUrl() + " answered " + response.statusCode());
        } catch (Exception e) {
            Assumptions.abort("api at " + baseUrl() + " is unreachable: " + e);
        }
    }

    static String clickHouseUrl() {
        return env("CLICKHOUSE_URL", "http://localhost:18123");
    }

    static String clickHouseDatabase() {
        return env("CLICKHOUSE_DB", "foo");
    }

    /**
     * Counts rows straight out of ClickHouse. The read-back API path applies its own limits and
     * paging, so for "did every point land" this asks storage directly.
     */
    static long clickHouseCount(String table, java.util.Collection<Long> timeseriesIds) {
        String ids = timeseriesIds.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("0");
        String sql = "SELECT count() FROM " + clickHouseDatabase() + "." + table
                + " WHERE timeseries_id IN (" + ids + ")";
        return Long.parseLong(clickHouseQuery(sql).trim());
    }

    static String clickHouseQuery(String sql) {
        String url = clickHouseUrl() + "/?user=" + env("CLICKHOUSE_USER", "foobar")
                + "&password=" + env("CLICKHOUSE_PASSWORD", "changeme");
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(sql))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ClickHouse " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ClickHouse query failed: " + sql, e);
        }
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static int envInt(String name, int fallback) {
        String value = env(name, null);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    static long envLong(String name, long fallback) {
        String value = env(name, null);
        return value == null ? fallback : Long.parseLong(value.trim());
    }

    private static String require(String name) {
        String value = env(name, null);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; see datahub-e2e/README.md");
        }
        return value;
    }
}
