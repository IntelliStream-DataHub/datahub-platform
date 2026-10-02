// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

/**
 * The tenant's model could not answer: its provider refused the call, or could not be reached.
 *
 * <p>Thrown by every {@link LlmClient} so the panel can say which kind of problem it is. Before
 * this, a revoked key, a misspelt model and a server that is not running all reached the user as
 * the same "something went wrong", and the only way to tell them apart was the console log. Each
 * {@link Reason} is fixed in the tenant's model settings or at the provider, not by asking again,
 * and it is the tenant's administrator who can fix it, which is what the user is told.
 *
 * <p>{@link #getMessage()} is for the log and may quote what the provider answered. It never
 * reaches the browser; only the {@link #reason()} does, as a fixed sentence.
 *
 * <p>Distinct from a refusal, which is the model declining a request it understood: that comes
 * back as an answer, not as this.
 */
public class LlmException extends RuntimeException {

    public enum Reason {
        /** 401 or 403: the key is wrong, revoked, or not allowed to use this model. */
        CREDENTIAL_REJECTED,
        /** 404: no such model, or, on an OpenAI-compatible server, nothing at that base URL. */
        NOT_FOUND,
        /**
         * Any other 4xx: the provider refused the request itself. A model that does not accept the
         * thinking and effort settings the chat sends lands here, and so does an account out of
         * credit, which Anthropic answers with a 400.
         */
        REQUEST_REJECTED,
        /** 429. */
        RATE_LIMITED,
        /** 5xx, including Anthropic's 529 when it is overloaded. */
        PROVIDER_ERROR,
        /** No connection at all: a wrong host or port, a server that is not running, a firewall. */
        UNREACHABLE,
        /** Connected, but no answer within the turn's time. */
        TIMED_OUT;

        /** What an HTTP status from the model's provider means for whoever has to fix it. */
        public static Reason forStatus(int status) {
            if (status == 401 || status == 403) {
                return CREDENTIAL_REJECTED;
            }
            if (status == 404) {
                return NOT_FOUND;
            }
            if (status == 408) {
                return TIMED_OUT;
            }
            if (status == 429) {
                return RATE_LIMITED;
            }
            if (status >= 500) {
                return PROVIDER_ERROR;
            }
            return REQUEST_REJECTED;
        }
    }

    private final Reason reason;

    public LlmException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public LlmException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
