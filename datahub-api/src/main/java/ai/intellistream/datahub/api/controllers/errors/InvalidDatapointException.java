// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

/**
 * A datapoint the caller can fix: a value that does not parse against its timeseries' declared
 * {@code valueType}, or a timestamp that is neither ISO-8601 nor epoch milliseconds. This is what
 * {@code POST /timeseries/data} answers 422 for.
 *
 * <p>It exists so that answer is reserved for the caller's own payload. Every one of these used to
 * be a bare {@link RuntimeException}, which since the catch-alls were removed means a 500 — and a
 * 500 tells the Java SDK to retry and to spool to its durable buffer. Neither can ever succeed
 * here: the same payload will fail the same way forever, so the caller has to be told to change it.
 * The converse matters just as much and is why the exception is narrow: a Pulsar outage must
 * <em>not</em> land here, because a 4xx is terminal to the SDK and an infrastructure blip would
 * then silently discard the datapoints.
 *
 * <p>That split only holds while nothing the caller controls can reach the 500 branch, so the two
 * validation passes must agree with the encoders they guard. {@code prepareDatapointInsert} checks
 * each value with the same parser that will encode it, and the conversion to the binary message —
 * which is where timestamps are parsed — is wrapped so a malformed one surfaces here rather than as
 * a server fault. Structural problems (a null datapoint list, a missing timestamp, a blank value)
 * are rejected earlier still, by bean validation on the request body.
 *
 * @see InvalidDatapointExceptionHandler
 */
public class InvalidDatapointException extends RuntimeException {

    public InvalidDatapointException(String message) {
        super(message);
    }
}
