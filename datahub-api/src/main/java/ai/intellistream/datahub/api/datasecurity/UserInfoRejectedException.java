package ai.intellistream.datahub.api.datasecurity;

import lombok.Getter;

/**
 * Thrown when the identity provider's <strong>UserInfo</strong> endpoint refuses the caller's token
 * — HTTP 401 or 403 — as opposed to failing to answer at all.
 *
 * <p>Deliberately distinct from {@link UserInfoUnavailableException}, because the two have opposite
 * remedies. "We could not reach the identity provider" clears on its own and is worth retrying;
 * "the identity provider says this token is no longer good" never clears by retrying and is fixed
 * only by authenticating again. Collapsing the second into the first told users to wait out an
 * outage that was not happening, and buried the one action that would have worked.
 *
 * <p>The usual cause is a token that is still well-formed, correctly signed and unexpired — so this
 * service's own JWT validation accepts it, and the request reaches the ACL layer before anything
 * looks wrong — whose Keycloak SSO session has since ended. Keycloak reports that as
 * {@code error_description="user_session_not_found"}. It happens routinely when the access token
 * lifespan outlives the SSO session idle timeout, or when the user logs out in another tab or
 * against another origin sharing the same SSO session.
 *
 * <p>Callers should surface it as a <strong>401</strong>, not a 503 and not a 403: the caller is not
 * forbidden from anything, they are unauthenticated and need a new token.
 */
@Getter
public class UserInfoRejectedException extends RuntimeException {

    /** The status UserInfo answered with — 401 or 403. Kept for logging; never sent to the caller. */
    private final int statusCode;

    public UserInfoRejectedException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
}
