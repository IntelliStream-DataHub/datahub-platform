// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import ai.intellistream.datahub.filter.EventFilterParser;
import ai.intellistream.datahub.filter.Predicate;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every accepted expression, run against a real ClickHouse.
 *
 * <p>This exists because "renders" and "ClickHouse accepts" are different claims, and the gap
 * between them is exactly where this language's hard parts live. A comparison between a String and
 * a DateTime renders perfectly well and fails at execution; so does a converter applied to the
 * wrong type, a function whose ClickHouse arity differs from ours, and a parameter bound with a
 * type the column will not take. A parse-and-render suite would have passed on all of them, which
 * is what makes the unit corpus insufficient on its own rather than merely incomplete.
 *
 * <p>The queries run against an empty table on purpose: what is under test is whether ClickHouse
 * will plan and execute the SQL, not which rows come back. Row semantics belong in the tests that
 * seed data.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
class EventFilterCorpusIT {

    private static Client client;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("event_filter_corpus_it");
        // The production events DDL, so column types and the metadata Map are the real ones —
        // a stand-in schema would hide precisely the type errors this test is here to catch.
        SharedClickHouse.execute(client, """
                CREATE TABLE events (
                    id                                   UUID,
                    external_id                          LowCardinality(String),
                    external_id_hash                     Int128,
                    type                                 LowCardinality(String),
                    sub_type                             Nullable(String),
                    status                               Nullable(String),
                    description                          String,
                    data_set_id                          Int64,
                    source                               LowCardinality(String),
                    date_created                         DateTime64(3, 'UTC'),
                    last_updated                         DateTime64(3, 'UTC'),
                    event_time                           DateTime64(3, 'UTC'),
                    related_resources_id                 Array(Int64),
                    related_resources_external_id        Array(LowCardinality(String)),
                    related_resources_external_id_hash   Array(Int64),
                    metadata                             Map(LowCardinality(String), String)
                ) ENGINE = ReplacingMergeTree
                  ORDER BY id PRIMARY KEY id
                  PARTITION BY (toYYYYMM(event_time))
                """);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void everyAcceptedExpressionIsExecutableByClickHouse() {
        List<String> failures = new ArrayList<>();

        for (String expression : acceptedExpressions()) {
            Map<String, Object> params = new LinkedHashMap<>();
            String where;
            try {
                Predicate parsed = EventFilterParser.parse(expression);
                where = new EventFilterRenderer(params, expression).render(parsed);
            } catch (RuntimeException e) {
                failures.add(expression + "\n    did not render: " + e.getMessage());
                continue;
            }

            // The ACL is conjoined the way the service does it, so the shape under test is the
            // shape that actually ships rather than the expression on its own.
            String sql = "SELECT count() FROM events WHERE (" + where + ") AND data_set_id >= 0";
            try (QueryResponse ignored = client.query(sql, params).get(30, TimeUnit.SECONDS)) {
                // Executing is the assertion.
            } catch (Exception e) {
                failures.add(expression + "\n    -> " + sql + "\n    rejected by ClickHouse: "
                        + rootCause(e));
            }
        }

        assertThat(failures)
                .as("expressions the corpus accepts but ClickHouse will not run")
                .isEmpty();
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = String.valueOf(cause.getMessage());
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static List<String> acceptedExpressions() {
        List<String> expressions = new ArrayList<>();
        try (InputStream in = EventFilterCorpusIT.class.getResourceAsStream("/filter/accepted.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                expressions.add(line.substring(line.indexOf('|') + 1).trim()
                        .replace("\\n", "\n").replace("\\t", "\t"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return expressions;
    }
}
