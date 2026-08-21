// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI doesn't model WebSocket endpoints — it's designed for request/response
 * REST. To still surface the listen endpoint in the generated spec and Swagger UI, this
 * customizer injects a pseudo-path whose HTTP description explains the WS handshake, auth,
 * message formats, close-code meanings, and keepalive behavior.
 * <p>
 * Nothing in Spring actually handles GET on this path — the real endpoint is the WebSocket
 * handler registered in {@link WebSocketConfig}. The entry is purely documentation.
 */
@Configuration
public class WebSocketOpenApiConfig {

    private static final String LISTEN_PATH = "/timeseries/datapoints/subscription/listen/{externalIds}";

    @Bean
    public OpenApiCustomizer subscriptionListenOpenApiCustomizer() {
        return openApi -> openApi.path(LISTEN_PATH, new PathItem().get(buildListenOperation()));
    }

    private Operation buildListenOperation() {
        Parameter externalId = new Parameter()
                .in("path")
                .name("externalIds")
                .required(false)
                .description("One or more subscription external ids as additional slash-separated path "
                        + "segments (e.g. `.../listen/sub_a/sub_b`). Each must match an existing "
                        + "subscription for the caller's tenant. May be omitted to connect with none and "
                        + "subscribe dynamically over the socket.")
                .schema(new StringSchema().example("boiler_room_readings_sub/turbine_3_vibration_sub"));

        Parameter authorization = new Parameter()
                .in("header")
                .name("Authorization")
                .required(true)
                .description("Bearer JWT — same token used for all REST endpoints.")
                .schema(new StringSchema().example("Bearer eyJhbGciOi..."));

        Operation op = new Operation()
                // Hand-built operations get no operationId for free, and Redoc anchors deep links
                // on it (#operation/<id>) — without one this endpoint cannot be linked to.
                .operationId("listenToSubscription")
                .summary("Listen to subscription (WebSocket)")
                .description(DESCRIPTION)
                .addTagsItem("Subscriptions")
                .addParametersItem(externalId)
                .addParametersItem(authorization)
                .responses(new ApiResponses()
                        .addApiResponse("101", new ApiResponse().description(
                                "Switching Protocols — handshake accepted, the connection is now a WebSocket."))
                        .addApiResponse("401", new ApiResponse().description(
                                "Missing or invalid Authorization header; no upgrade happens."))
                );
        op.addExtension("x-sort", "50");
        return op;
    }

    private static final String DESCRIPTION = """
            > **WebSocket endpoint.** This operation is documented under HTTP for discoverability
            > in Swagger UI, but the underlying protocol is WebSocket (RFC 6455). The client
            > initiates an HTTP/1.1 upgrade; on success the server returns `101 Switching
            > Protocols` and the connection becomes a long-lived duplex channel.

            ## Purpose

            Proxies one or more Pulsar consumers over a single WebSocket so clients can stream
            datapoints from several subscriptions' fan-out topics at once and drive acks/nacks from
            their side. The set of subscriptions is seeded from the path and can be changed at
            runtime.

            ## Connection URL

            ```
            ws(s)://<host>/timeseries/datapoints/subscription/listen/<id1>/<id2>/...
            ```

            Each path segment after `.../listen/` is a subscription external id. The path may also be
            empty (`.../listen`) — connect with no subscriptions and add them with a `subscribe`
            message.

            ## Authentication

            The same Bearer JWT used for every other REST endpoint, verified during the HTTP handshake
            by the standard OAuth2 resource server filter. Missing or invalid tokens get a `401` and no
            upgrade occurs. A subscription that can't be resolved is reported as an error frame (see
            below) and skipped — it does not close the connection.

            ## Subscription semantics

            The per-subscription Pulsar subscription type is **Failover** (or **Key_Shared**): the
            durable cursor lives in Pulsar, so a reconnect resumes from where it left off on whichever
            instance the connection lands on. No load-balancer session affinity is required.

            ## Server → client frames

            One WS text frame per batch per subscription (up to 500 messages or every 500 ms,
            whichever comes first), tagged with the owning subscription:

            ```json
            {
              "subscriptionExternalId": "heater_2012_sub",
              "messages": [
                {
                  "messageId": "aGVhdGVyXzIwMTJfc3Vi.CAEQABgAIAAwAA",
                  "payload": {
                    "eventAction": "CREATE",
                    "eventObject": "DATAPOINTS",
                    "tenantId": "ebd85a20-...",
                    "items": [
                      {
                        "id": 29,
                        "externalId": "heater_2012_temp",
                        "valueType": "FLOAT",
                        "datapoints": [
                          { "timestamp": "2026-04-17T18:04:33Z", "value": "20.5" }
                        ]
                      }
                    ]
                  }
                }
              ]
            }
            ```

            `messageId` is opaque (it encodes the subscription so the ack is routed correctly). Use it
            to ack or nack later — do not parse it.

            An unresolvable subscription produces an error frame instead:

            ```json
            { "error": true, "subscriptionExternalId": "typo_sub", "reason": "not-found" }
            ```

            ## Client → server frames

            Change which subscriptions are streamed:

            ```json
            { "action": "subscribe",   "externalIds": ["turbine_3_sub"] }
            { "action": "unsubscribe", "externalIds": ["heater_2012_sub"] }
            { "action": "set",         "externalIds": ["a_sub", "b_sub"] }
            ```

            Ack or nack delivered messages:

            ```json
            { "action": "ack",  "messageIds": ["aGVhdGVyXzIwMTJfc3Vi.CAEQABgAIAAwAA", "..."] }
            { "action": "nack", "messageIds": ["..."] }
            ```

            - `subscribe`/`add` — attach more subscriptions; `unsubscribe`/`remove` — detach;
              `set` — replace the whole set.
            - `ack` — Pulsar forgets the message; `nack` — schedules redelivery.
            - Unknown `messageIds`/`externalIds` are silently ignored.
            - Messages left un-acked for Pulsar's `ackTimeout` are automatically redelivered.

            ## Keepalive

            The server sends a WS PING every 15 seconds. Any compliant client auto-replies with
            PONG. The container closes sessions idle for 45 seconds, so a dead client is detected
            within roughly that window.

            ## Close codes you may observe

            | Code | Reason                                              |
            |------|-----------------------------------------------------|
            | 1000 | Normal closure (initiated by either side)           |
            | 1008 | Policy violation — missing tenant context           |
            | 1011 | Internal server error                               |
            """;
}
