// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * The {@code state} a deploy's authorization round-trip carries through Keycloak and back.
 *
 * <p>The service keeps no session, so the state <em>is</em> the pending deployment: which tenant,
 * which flow and version, who started it, and until when it may be completed. It is signed (HMAC
 * SHA-256) so that the callback can trust it — whichever instance the browser comes back to, since
 * the key derives from the client secret every instance has. It is not secret: it holds nothing a
 * person who sees the URL could not know already.
 *
 * <p>Together with the callback's check that the token Keycloak issues belongs to the person named
 * here, the signature is what stops a code obtained from someone else's sign-in from being redeemed
 * into another person's deployment: an attacker's own state names the attacker.
 *
 * @param tenantId  the organization id the deployment is for
 * @param flowId    the flow's id in that tenant's database
 * @param version   the flow version to deploy
 * @param enabled   whether the deployment is to be switched on
 * @param initiator the {@code sub} of the person who started the deployment
 * @param nonce     random, so that no two states are alike
 * @param expires   after this, the round-trip has to start again
 */
public record DeployState(String tenantId, long flowId, int version, boolean enabled, String initiator,
                          String nonce, Instant expires) {

    /** The state could not be trusted: forged, altered, malformed or expired. */
    public static class InvalidState extends RuntimeException {
        public InvalidState(String message) {
            super(message);
        }
    }

    /** {@code payload.signature}, both base64url without padding. */
    String sign(byte[] key, JsonMapper json) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsBytes(this));
        return payload + "." + mac(key, payload);
    }

    /** The state {@code signed} carries, if it is genuine and not past {@code now}. */
    static DeployState verify(String signed, byte[] key, JsonMapper json, Instant now) {
        if (signed == null) {
            throw new InvalidState("The deployment's state is missing.");
        }
        int dot = signed.indexOf('.');
        if (dot <= 0) {
            throw new InvalidState("The deployment's state is malformed.");
        }
        String payload = signed.substring(0, dot);
        byte[] given = signed.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = mac(key, payload).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, expected)) {
            throw new InvalidState("The deployment's state is not this service's.");
        }
        DeployState state;
        try {
            state = json.readValue(Base64.getUrlDecoder().decode(payload), DeployState.class);
        } catch (RuntimeException e) {
            throw new InvalidState("The deployment's state is malformed.");
        }
        if (state.expires() == null || !now.isBefore(state.expires())) {
            throw new InvalidState("The deployment took too long; start it again.");
        }
        return state;
    }

    /** The signing key: derived from the client secret, so it is the same on every instance. */
    static byte[] key(String clientSecret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("intellistream-flow deploy state\0" + clientSecret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
