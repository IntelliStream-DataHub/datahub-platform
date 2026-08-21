// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the live-datapoint WebSocket endpoints. Both handshakes go through the normal Spring
 * Security filter chain. The subscription endpoint authenticates with a Bearer JWT header; the
 * per-timeseries tail endpoint validates a {@code token} query param itself (see its handler).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * How long a WebSocket session can be idle (no frames received from the client) before
     * the Servlet container closes it. Closure triggers our
     * {@link SubscriptionWebSocketHandler#afterConnectionClosed} so the Pulsar consumer and
     * pending-ack state are cleaned up. Required because some WS clients (including the
     * IntelliJ HTTP Client) don't reliably send a Close frame on disconnect.
     * <p>
     * Must be longer than {@code PING_INTERVAL_SECONDS} in the handler — currently 15s —
     * so a healthy client's PONG always arrives in time to reset this timer.
     */
    static final long MAX_SESSION_IDLE_MILLIS = 45_000L;

    private final SubscriptionWebSocketHandler subscriptionWebSocketHandler;
    private final DatapointListenWebSocketHandler datapointListenWebSocketHandler;

    public WebSocketConfig(SubscriptionWebSocketHandler subscriptionWebSocketHandler,
                           DatapointListenWebSocketHandler datapointListenWebSocketHandler) {
        this.subscriptionWebSocketHandler = subscriptionWebSocketHandler;
        this.datapointListenWebSocketHandler = datapointListenWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Client connects to: ws(s)://<host>/timeseries/datapoints/subscription/listen/<id>/<id>/...
        // Path segments seed the subscriptions; more can be added/removed over the socket.
        registry.addHandler(subscriptionWebSocketHandler, "/timeseries/datapoints/subscription/listen/**")
                .setAllowedOriginPatterns("*");
        // Client connects to: ws(s)://<host>/timeseries/datapoints/listen?token=<jwt> — a live
        // datapoint tail filtered per connection to the requested timeseries. Auth is the token
        // query param (browsers can't set headers on a WS handshake); see the handler.
        registry.addHandler(datapointListenWebSocketHandler, "/timeseries/datapoints/listen")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean servletServerContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(MAX_SESSION_IDLE_MILLIS);
        return container;
    }
}
