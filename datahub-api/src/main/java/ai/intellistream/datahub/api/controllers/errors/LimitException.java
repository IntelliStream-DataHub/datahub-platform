// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

/**
 * A refusal because the tenant has reached a limit, rather than because the request was wrong.
 *
 * <p>The common base exists so the write endpoints can let both kinds past their terminal
 * {@code catch (RuntimeException) -> 500} with a single rethrow. Without it a quota refusal reaches
 * the caller as an unexplained 500 and reads as a platform fault instead of an answer.
 */
public abstract class LimitException extends RuntimeException {

    private final String detail;

    protected LimitException(String detail) {
        super(detail);
        this.detail = detail;
    }

    /**
     * The sentence the caller is shown. The same text as the exception message, kept as its own
     * field because it is composed here from the metric and the number, never from anything a
     * request or a lower layer supplied: it is an answer, not an error report.
     */
    public String detail() {
        return detail;
    }
}
