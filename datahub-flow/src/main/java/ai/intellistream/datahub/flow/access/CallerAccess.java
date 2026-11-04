// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.access;

import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.models.me.MeGrants;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.client.MeService.Access;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Who is calling, and what they may do with flows — asked of datahub-api, for the caller's own
 * token, through {@code GET /me/grants} and {@code GET /me/datasets}. The ACL stays in the api: this
 * service reads its answer and never evaluates a dataset grant itself.
 *
 * <p>The answer is cached per token for {@link #TTL}, about as long as the api caches the UserInfo it
 * is computed from, so a flow list does not cost three api calls per page. A token is short-lived and
 * its grants are what they were when the api answered, so a cached answer can be at most that much
 * behind a change of grants in Keycloak — the same staleness the api itself has.
 *
 * <p>Also hands out the {@link DatahubClient} that calls the api <em>as the caller</em>, forwarding
 * their JWT, for what a request does on their behalf: resolving a dataset, creating the series a
 * flow declares.
 */
@Slf4j
@Component
public class CallerAccess {

    static final Duration TTL = Duration.ofSeconds(45);
    static final int MAX_ENTRIES = 10_000;
    static final Duration API_TIMEOUT = Duration.ofSeconds(10);

    private final String apiBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(API_TIMEOUT).build();
    private final Clock clock;

    /** Insertion-ordered, so the oldest entry is the first to go when it is full. */
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    private record Cached(Caller caller, Instant expires) {
    }

    @Autowired
    public CallerAccess(@Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl) {
        this(apiBaseUrl, Clock.systemUTC());
    }

    CallerAccess(String apiBaseUrl, Clock clock) {
        this.apiBaseUrl = apiBaseUrl;
        this.clock = clock;
    }

    /** The current request's caller. */
    public Caller current() {
        Jwt jwt = currentJwt();
        String token = jwt.getTokenValue();
        Instant now = clock.instant();
        synchronized (cache) {
            Cached cached = cache.get(token);
            if (cached != null && cached.expires().isAfter(now)) {
                return cached.caller();
            }
        }
        Caller caller = ask(jwt);
        synchronized (cache) {
            cache.remove(token);
            cache.put(token, new Cached(caller, now.plus(TTL)));
            var oldest = cache.keySet().iterator();
            while (cache.size() > MAX_ENTRIES && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        return caller;
    }

    /** A client that calls datahub-api as the current caller. */
    public DatahubClient client() {
        DatahubConfig config = DatahubConfig.builder()
                .baseUrl(apiBaseUrl)
                .token(currentJwt().getTokenValue())
                .requestTimeout(API_TIMEOUT)
                .build();
        return DatahubClient.create(config, httpClient);
    }

    private Caller ask(Jwt jwt) {
        DatahubClient api = client();
        try {
            MeGrants grants = api.me().grants();
            boolean admin = grants.admin();
            boolean read = admin || grants.groups().contains(Caller.FLOWS_READ);
            boolean write = admin || grants.groups().contains(Caller.FLOWS_WRITE);
            // A caller without either flow grant can do nothing here; spare the api two calls.
            if (admin || (!read && !write)) {
                DataSets none = admin ? DataSets.ALL : new DataSets(false, Set.of());
                return new Caller(jwt.getSubject(), admin, read, write, none, none);
            }
            return new Caller(jwt.getSubject(), false, read, write,
                    dataSets(api.me().datasets(Access.READ)), dataSets(api.me().datasets(Access.WRITE)));
        } catch (DatahubApiException e) {
            throw translate(e);
        }
    }

    /**
     * The api's refusal, as this service's: a rejected token stays a 401; anything that leaves the
     * caller's grants unknown — the api or its identity provider down, a timeout — is a 503, never
     * an answer that would let through or refuse on a guess.
     */
    public static FlowProblems.ProblemException translate(DatahubApiException e) {
        if (e.statusCode() == 401) {
            return FlowProblems.tokenRejected("datahub-api refused your token.");
        }
        log.warn("Could not resolve the caller's grants from datahub-api: HTTP {}: {}", e.statusCode(),
                e.getMessage());
        return FlowProblems.apiUnavailable("Your permissions could not be resolved: datahub-api did "
                + "not answer. Retry shortly.");
    }

    private static DataSets dataSets(MeDataSets answer) {
        return answer.all() ? DataSets.ALL : new DataSets(false, new HashSet<>(answer.ids()));
    }

    /** The current request's token: what a preview, which acts as its caller, runs with. */
    public static Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        throw new IllegalStateException("No JWT in the security context.");
    }
}
