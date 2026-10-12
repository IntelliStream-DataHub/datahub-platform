// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.access;

import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.web.FlowProblems;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a caller may do, as datahub-api answers it: read from {@code GET /me/*}, cached per token,
 * and never guessed when the api cannot answer.
 */
class CallerAccessTest {

    private HttpServer api;
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T10:00:00Z"));
    private CallerAccess access;

    @BeforeEach
    void setUp() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            calls.add(path + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = answers.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        access = new CallerAccess("http://127.0.0.1:" + api.getAddress().getPort(), clock);
        signIn("token-a");
    }

    @AfterEach
    void tearDown() {
        api.stop(0);
        SecurityContextHolder.clearContext();
    }

    @Test
    void theFlowGrantsAndDatasetsComeFromTheApiForTheCallersOwnToken() {
        answers.put("/me/grants", "{\"admin\":false,\"groups\":[\"/flows/read\",\"/datasets/demo/read\"]}");
        answers.put("/me/datasets?access=read", "{\"all\":false,\"ids\":[\"11\",\"12\"]}");
        answers.put("/me/datasets?access=write", "{\"all\":true,\"ids\":[]}");

        Caller caller = access.current();

        assertThat(caller.subject()).isEqualTo("user-1");
        assertThat(caller.flowsRead()).isTrue();
        assertThat(caller.flowsWrite()).isFalse();
        assertThat(caller.read()).isEqualTo(new DataSets(false, Set.of(11L, 12L)));
        assertThat(caller.write()).isEqualTo(DataSets.ALL);
        assertThat(caller.canRead(11)).isTrue();
        assertThat(caller.canRead(13)).isFalse();
        assertThat(caller.canWrite(11)).as("write access to the dataset, but no /flows/write").isFalse();
        assertThat(calls).allMatch(call -> call.endsWith("Bearer token-a"));
    }

    @Test
    void aCallerWithNoFlowGrantCostsOneCall() {
        answers.put("/me/grants", "{\"admin\":false,\"groups\":[\"/datasets/*/read\"]}");

        Caller caller = access.current();

        assertThat(caller.flowsRead()).isFalse();
        assertThat(caller.canRead(11)).isFalse();
        assertThat(calls).hasSize(1);
    }

    @Test
    void anAdminMayDoEverythingWithoutAskingForDatasets() {
        answers.put("/me/grants", "{\"admin\":true,\"groups\":[]}");

        Caller caller = access.current();

        assertThat(caller.admin()).isTrue();
        assertThat(caller.canWrite(99)).isTrue();
        assertThat(calls).hasSize(1);
    }

    @Test
    void theAnswerIsCachedPerTokenUntilItExpires() {
        answers.put("/me/grants", "{\"admin\":true,\"groups\":[]}");

        access.current();
        access.current();
        assertThat(calls).hasSize(1);

        signIn("token-b");
        access.current();
        assertThat(calls).hasSize(2);

        now.set(now.get().plus(CallerAccess.TTL).plus(Duration.ofSeconds(1)));
        access.current();
        assertThat(calls).hasSize(3);
    }

    @Test
    void aRejectedTokenIsA401AndAnApiThatCannotAnswerIsA503() {
        statuses.put("/me/grants", 401);
        assertThatThrownBy(access::current).isInstanceOfSatisfying(FlowProblems.ProblemException.class,
                e -> assertThat(e.problem().getStatus()).isEqualTo(401));

        statuses.put("/me/grants", 503);
        assertThatThrownBy(access::current).isInstanceOfSatisfying(FlowProblems.ProblemException.class,
                e -> assertThat(e.problem().getStatus()).isEqualTo(503));

        api.stop(0);
        assertThatThrownBy(access::current).isInstanceOfSatisfying(FlowProblems.ProblemException.class,
                e -> assertThat(e.problem().getStatus()).isEqualTo(503));
    }

    private static void signIn(String token) {
        Jwt jwt = Jwt.withTokenValue(token).header("alg", "none").subject("user-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
