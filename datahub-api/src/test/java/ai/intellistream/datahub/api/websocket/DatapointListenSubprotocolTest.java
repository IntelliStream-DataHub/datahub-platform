// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.datasecurity.StreamAccessAuthorizer;
import ai.intellistream.datahub.pulsar.TopicNames;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The live-tail handshake carries its credential as the {@code datahub.bearer.<jwt>} subprotocol,
 * and a WebSocket server may only echo back a subprotocol the client offered. These tests pin the
 * Spring side of that negotiation: that declaring {@link org.springframework.web.socket.SubProtocolCapable}
 * is enough for the handshake to select {@code datahub.v1}, and that the credential is never what
 * gets selected — echoing it would copy the token into the 101 response headers, undoing the point
 * of moving it out of the query string.
 */
class DatapointListenSubprotocolTest {

    private DatapointListenWebSocketHandler handler;
    private final DefaultHandshakeHandler handshakeHandler = new DefaultHandshakeHandler();

    @BeforeEach
    void setUp() {
        handler = new DatapointListenWebSocketHandler(
                mock(PulsarClient.class), mock(TopicNames.class), mock(JwtDecoder.class),
                JsonMapper.builder().build(), mock(StreamAccessAuthorizer.class),
                mock(WebSocketConnectionLimiter.class));
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    @Test
    @DisplayName("The handshake selects datahub.v1 out of a browser's offer, not the bearer element")
    void selectsThePlainProtocolFromABrowserOffer() {
        List<String> offered = List.of(
                DatapointListenWebSocketHandler.BEARER_SUBPROTOCOL_PREFIX + "eyJhbGciOiJSUzI1NiJ9.e30.sig",
                DatapointListenWebSocketHandler.NEGOTIATED_SUBPROTOCOL);

        assertThat(selectProtocol(offered))
                .isEqualTo(DatapointListenWebSocketHandler.NEGOTIATED_SUBPROTOCOL);
    }

    @Test
    @DisplayName("A client offering only its credential gets no subprotocol echoed back")
    void offeringOnlyTheCredentialSelectsNothing() {
        List<String> offered = List.of(
                DatapointListenWebSocketHandler.BEARER_SUBPROTOCOL_PREFIX + "eyJhbGciOiJSUzI1NiJ9.e30.sig");

        // Selecting it would put the token in the response; RFC 6455 lets the server answer with
        // no Sec-WebSocket-Protocol at all, and browsers accept that, so the connection still opens.
        assertThat(selectProtocol(offered)).isNull();
    }

    private String selectProtocol(List<String> offeredProtocols) {
        return ReflectionTestUtils.invokeMethod(handshakeHandler, "selectProtocol", offeredProtocols, handler);
    }
}
