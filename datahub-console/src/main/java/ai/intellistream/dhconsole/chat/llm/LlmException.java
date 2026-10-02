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

    /**
     * The statuses follow Anthropic's published error list
     * (platform.claude.com/docs/en/api/errors); OpenAI-compatible servers use the same codes for
     * the same things where they use them at all.
     */
    public enum Reason {
        /**
         * 401: the key is malformed, revoked or expired. 403: the key may not use this model or
         * workspace.
         */
        CREDENTIAL_REJECTED,
        /** 402: a billing or payment problem on the provider account. */
        BILLING,
        /** 404: no such model, or, on an OpenAI-compatible server, nothing at that base URL. */
        NOT_FOUND,
        /**
         * Any other 4xx: the provider refused the request itself. A model that does not accept the
         * adaptive thinking the chat asks for lands here (Anthropic's 4.5 models), as do a key not
         * scoped to a workspace and a spend limit the organization set, which Anthropic both
         * answer with a 400.
         */
        REQUEST_REJECTED,
        /** 429: too many requests, or the account's spend cap is reached. */
        RATE_LIMITED,
        /** Any other 5xx, including Anthropic's 529 when it is overloaded. */
        PROVIDER_ERROR,
        /** No connection at all: a wrong host or port, a server that is not running, a firewall. */
        UNREACHABLE,
        /** No answer in time: within the turn here, or the provider's own 408 or 504. */
        TIMED_OUT;

        /** What an HTTP status from the model's provider means for whoever has to fix it. */
        public static Reason forStatus(int status) {
            if (status == 401 || status == 403) {
                return CREDENTIAL_REJECTED;
            }
            if (status == 402) {
                return BILLING;
            }
            if (status == 404) {
                return NOT_FOUND;
            }
            if (status == 408 || status == 504) {
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
