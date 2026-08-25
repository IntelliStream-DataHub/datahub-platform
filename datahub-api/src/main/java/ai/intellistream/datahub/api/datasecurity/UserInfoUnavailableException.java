// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

/**
 * Thrown when the caller's organization groups cannot be determined because UserInfo failed to
 * answer — unreachable, timed out, non-200 for a reason other than refusing the token, or
 * unparseable — and no cached answer was recent enough to serve in its place.
 *
 * <p>This is deliberately <em>not</em> an {@code AccessDeniedException}. "We could not verify your
 * permissions" and "you do not have permission" are different outcomes, and collapsing the first
 * into the second would show a user an empty dataset list during an identity-provider outage
 * rather than an error. Callers should surface it as a <strong>503</strong>, not a 403.
 *
 * <p>Nor is it the case where UserInfo <em>did</em> answer and refused the token: that is
 * {@link UserInfoRejectedException}, and it surfaces as a 401. The split matters because only this
 * one is temporary and worth retrying.
 */
public class UserInfoUnavailableException extends RuntimeException {

    public UserInfoUnavailableException(String message) {
        super(message);
    }

    public UserInfoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
