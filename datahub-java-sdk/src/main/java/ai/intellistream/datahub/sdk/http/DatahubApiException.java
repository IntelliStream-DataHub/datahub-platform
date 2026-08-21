// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.http;

/**
 * Thrown when the DataHub API returns a non-2xx response (or the request fails to
 * complete). Carries the HTTP status code and the raw response body, when available.
 */
public class DatahubApiException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public DatahubApiException(int statusCode, String message, String body) {
        super(message);
        this.statusCode = statusCode;
        this.body = body;
    }

    /** HTTP status code, or 0 if the request never produced a response. */
    public int statusCode() {
        return statusCode;
    }

    /** Raw response body, or {@code null} if none was read. */
    public String body() {
        return body;
    }
}
