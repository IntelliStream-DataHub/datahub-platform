// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.http;

import ai.intellistream.datahub.api.errors.Problem;

/**
 * Thrown when the DataHub API returns a non-2xx response (or the request fails to
 * complete). Carries the HTTP status code and the raw response body, when available.
 *
 * <p>{@link #problem()} is the same answer read as the RFC 9457 document the API actually sends,
 * and is never null: branch on {@code problem().slug()} rather than on prose or on the status
 * alone, since one status covers several types — a 403 is a dataset ACL, a disabled feature or a
 * tenant ceiling, and only the type says which.
 */
public class DatahubApiException extends RuntimeException {

    private final int statusCode;
    private final String body;
    private final transient Problem problem;
    private final long retryAfterSeconds;

    public DatahubApiException(int statusCode, String message, String body) {
        this(statusCode, message, body, -1);
    }

    private DatahubApiException(int statusCode, String message, String body, long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.body = body;
        this.problem = Problem.of(statusCode, body);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * The exception for a non-2xx answer: reads the problem document and writes the message from it,
     * so what is caught says what the API said rather than only which status it said it with.
     *
     * @param retryAfterSeconds the {@code Retry-After} delay the response carried, or -1
     */
    public static DatahubApiException of(int statusCode, String method, String path, String body,
                                         long retryAfterSeconds) {
        Problem problem = Problem.of(statusCode, body);
        StringBuilder message = new StringBuilder("HTTP ").append(statusCode)
                .append(" for ").append(method).append(' ').append(path);
        if (problem.title() != null) {
            message.append(": ").append(problem.title());
        }
        if (problem.detail() != null && !problem.detail().isBlank()) {
            message.append(problem.title() == null ? ": " : " — ").append(problem.detail());
        }
        for (Problem.FieldProblem field : problem.fields()) {
            message.append(" [").append(field.field()).append(": ").append(field.message()).append(']');
        }
        if (problem.requestId() != null) {
            message.append(" [requestId=").append(problem.requestId()).append(']');
        }
        return new DatahubApiException(statusCode, message.toString(), body, retryAfterSeconds);
    }

    /** HTTP status code, or 0 if the request never produced a response. */
    public int statusCode() {
        return statusCode;
    }

    /** Raw response body, or {@code null} if none was read. */
    public String body() {
        return body;
    }

    /**
     * The failure as the API describes it. Never null — a body that was empty, HTML from a proxy or
     * JSON of some other shape yields a problem carrying only {@link #statusCode()}, so
     * {@code problem().slug()} is simply null there.
     */
    public Problem problem() {
        // Rebuilt rather than returned blank if this exception was serialized: Problem is not
        // Serializable, so the field does not survive the round trip, and "never null" should hold
        // for a caller who has no idea one happened.
        return problem != null ? problem : Problem.of(statusCode, body);
    }

    /**
     * The {@code Retry-After} delay in seconds the response asked for, or -1 when it sent none. The
     * API sends it with the 429s and some 503s; a caller backing off should not wait less than this.
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
