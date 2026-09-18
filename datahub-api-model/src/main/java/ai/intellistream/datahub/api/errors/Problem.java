// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.errors;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An error answer from datahub-api, read: the client side of the RFC 9457
 * {@code application/problem+json} contract the API serves.
 *
 * <h2>Why a type rather than a raw body</h2>
 * The API answers every failure with one shape, and the member a caller is meant to branch on is
 * {@code type} — a URI, because prose changes and a URI does not. A client holding only the raw
 * body either parses it itself or matches substrings, and a substring match cannot tell
 * {@code .../errors/unknown-timeseries} appearing as the type from the same words appearing in a
 * {@code detail} sentence.
 *
 * <h2>Unknown members are kept, not refused</h2>
 * RFC 9457 §3.2 makes extension members the way a problem says more than the five standard ones —
 * {@code fields}, {@code duplicated}, {@code blockedBy}, {@code missing}, {@code reason},
 * {@code pointer} — and requires a conforming consumer to ignore ones it does not recognise. Only
 * the members every problem carries are typed here; the rest land in {@link #extensions()} so the
 * API can add one without breaking a client compiled against an older version of this class.
 *
 * <h2>Never null, never throwing</h2>
 * {@link #of(int, String)} always returns a problem. A body that is empty, HTML from a proxy, or a
 * JSON document of some other shape yields one carrying just the HTTP status, so a caller reads
 * {@code status()} and {@code slug()} the same way whatever came back. This mirrors the console's
 * {@code DataHubProblem}, which reads the same wire shape in the browser.
 */
public final class Problem {

    /** Every problem type this API mints lives under this prefix. */
    public static final String TYPE_BASE = "https://intellistream.ai/errors/";

    /** {@code retry}: the same request can succeed later; honour {@code Retry-After} when it is sent. */
    public static final String RETRY_SAME_REQUEST = "same-request";
    /** {@code retry}: only a different request can succeed. */
    public static final String RETRY_CHANGE_REQUEST = "change-request";
    /** {@code retry}: nothing the caller sends will succeed until an operator acts; quote the requestId. */
    public static final String RETRY_NEEDS_OPERATOR = "needs-operator";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String type;
    private final String title;
    private final int status;
    private final String detail;
    private final String instance;
    private final String retry;
    private final String requestId;
    private final List<FieldProblem> fields;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    /** The status the response came with, used when the body carries none. Set by {@link #of}. */
    private int httpStatus;

    @JsonCreator
    public Problem(@JsonProperty("type") String type,
                   @JsonProperty("title") String title,
                   @JsonProperty("status") Integer status,
                   @JsonProperty("detail") String detail,
                   @JsonProperty("instance") String instance,
                   @JsonProperty("retry") String retry,
                   @JsonProperty("requestId") String requestId,
                   @JsonProperty("fields") List<FieldProblem> fields) {
        this.type = type;
        this.title = title;
        this.status = status == null ? 0 : status;
        this.detail = detail;
        this.instance = instance;
        this.retry = retry;
        this.requestId = requestId;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    @JsonAnySetter
    void extension(String name, Object value) {
        extensions.put(name, value);
    }

    /**
     * Reads a response body as a problem. Never throws and never returns null: what cannot be read
     * as one becomes a problem carrying only {@code httpStatus}.
     *
     * @param httpStatus the HTTP status the response came with, used when the body has none
     * @param body       the response body, or null
     */
    public static Problem of(int httpStatus, String body) {
        Problem parsed = read(body);
        if (parsed == null) {
            return new Problem(null, null, httpStatus, null, null, null, null, null);
        }
        parsed.httpStatus = httpStatus;
        return parsed;
    }

    private static Problem read(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(body, Problem.class);
        } catch (RuntimeException notAProblemDocument) {
            // A proxy's HTML, a token endpoint's OAuth2 error, a JSON array — none of them are one.
            return null;
        }
    }

    /** The problem type URI, or null when the body was not a problem document. */
    public String type() {
        return type;
    }

    /** Short human-readable summary of the type, e.g. {@code Conflict}. */
    public String title() {
        return title;
    }

    /** The status from the body, falling back to the HTTP status the response came with. */
    public int status() {
        return status != 0 ? status : httpStatus;
    }

    /** What went wrong on this occurrence, in prose. For a human reading a log, not to branch on. */
    public String detail() {
        return detail;
    }

    /** The request path this problem refers to. */
    public String instance() {
        return instance;
    }

    /**
     * What the caller can do about it: {@link #RETRY_SAME_REQUEST}, {@link #RETRY_CHANGE_REQUEST} or
     * {@link #RETRY_NEEDS_OPERATOR}. Null when the body was not a problem document.
     */
    public String retry() {
        return retry;
    }

    /** True when the API said this same request can succeed later. */
    public boolean retryable() {
        return RETRY_SAME_REQUEST.equals(retry);
    }

    /**
     * The id this request was served under, the same value as the {@code X-Request-Id} response
     * header. Quote it to an operator, who can find the request in the logs by it.
     */
    public String requestId() {
        return requestId;
    }

    /** The inputs the API refused, when it named any. Empty otherwise. */
    public List<FieldProblem> fields() {
        return fields;
    }

    /**
     * The type with {@link #TYPE_BASE} stripped — {@code "duplicate"} for
     * {@code https://intellistream.ai/errors/duplicate} — and null for a type from somewhere else
     * or none at all. The short form is what a switch reads best.
     */
    public String slug() {
        return type != null && type.startsWith(TYPE_BASE) ? type.substring(TYPE_BASE.length()) : null;
    }

    /** True when this problem's type is the given slug. */
    public boolean is(String slug) {
        return slug != null && slug.equals(slug());
    }

    /**
     * Every member beyond the typed ones, by name: {@code duplicated}, {@code blockedBy},
     * {@code missing}, {@code reason}, {@code pointer}, {@code permission} and whatever the API adds
     * later. Values are Jackson's defaults — {@code Map}, {@code List}, {@code String},
     * {@code Integer}.
     */
    public Map<String, Object> extensions() {
        return Collections.unmodifiableMap(extensions);
    }

    @Override
    public String toString() {
        return "Problem{type=" + type + ", status=" + status() + ", retry=" + retry
                + ", requestId=" + requestId + "}";
    }

    /**
     * One input the API refused.
     *
     * @param field    the property path, e.g. {@code externalId} or {@code items[0].name}
     * @param message  the resolved, human-readable reason
     * @param code     an i18n key for the rule that rejected it, so the message can be phrased in
     *                 the caller's own words instead of parsed out of prose; absent where the throw
     *                 site had no key
     * @param rejected the bound a length or count broke, or for an {@code externalId} outside the
     *                 allowed characters, the externalId itself; absent for every other rule, so a
     *                 refused value is never echoed back
     */
    public record FieldProblem(String field, String message, String code, Object rejected) {
    }
}
