// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the SDK puts on the wire has to be what the endpoint binds.
 *
 * <p>Two calls could not succeed. {@code units().byIds} posted whole {@code UnitModel}s — name,
 * symbol, quantity, conversion — into a body that binds {@code IdCollection}; the api rejects
 * unknown request fields, so every call was a 400, and {@code aliasNames} initialises to a
 * {@code TreeSet} so it was emitted even for a default instance. {@code events().byIds} posted
 * {@code IdCollection}, whose id is a {@code Long}, where an event id is a UUID — the by-id half of
 * the call could not be expressed at all.
 *
 * <p>Neither was caught because the SDK's tests asserted on responses. These assert on the request,
 * against the field set of the type the endpoint actually binds — both of which now live in
 * api-model, which is the only module the SDK can see.
 */
class SdkRequestBodyContractTest {

    /** The declared wire fields of a binding type, which is what the api will accept. */
    private static Set<String> wireFields(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        return Set.copyOf(names);
    }

    /** Runs one SDK call against a stub server and hands back the request body it sent. */
    private static JsonNode bodySentBy(String path, java.util.function.Consumer<DatahubClient> call)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> captured = new AtomicReference<>();
        server.createContext(path, exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            call.accept(DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .build()));
        } finally {
            server.stop(0);
        }
        return JsonMapper.builder().build().readTree(captured.get());
    }

    private static Set<String> keysOfFirstItem(JsonNode body) {
        return Set.copyOf(body.path("items").get(0).propertyNames());
    }

    @Test
    @DisplayName("units().byIds sends only what /units/byids binds")
    void unitByIdsSendsOnlyReferenceFields() throws Exception {
        JsonNode body = bodySentBy("/units/byids", client ->
                client.units().byIds(List.of(IdCollection.createFromExternalId("celsius"))));

        Set<String> sent = keysOfFirstItem(body);
        assertTrue(wireFields(IdCollection.class).containsAll(sent),
                "the api rejects unknown request fields, so every key sent must exist on "
                        + "IdCollection; sent " + sent);
    }

    @Test
    @DisplayName("events().byIds sends a UUID id, which is what /events/byids binds")
    void eventByIdsSendsAUuidId() throws Exception {
        UUID eventId = UUID.fromString("0195f3a2-1111-7000-8000-000000000001");
        UUIDAndExternalIdCollection reference = new UUIDAndExternalIdCollection();
        reference.setId(eventId);

        JsonNode body = bodySentBy("/events/byids", client ->
                client.events().byIds(List.of(reference)));

        Set<String> sent = keysOfFirstItem(body);
        assertTrue(wireFields(UUIDAndExternalIdCollection.class).containsAll(sent),
                "every key sent must exist on UUIDAndExternalIdCollection; sent " + sent);
        assertEquals(eventId.toString(), body.path("items").get(0).path("id").asString(),
                "an event id is a UUID; IdCollection's Long could not carry it");
    }

    @Test
    @DisplayName("events().delete uses the same reference type as byIds")
    void eventDeleteSendsTheSameShape() throws Exception {
        JsonNode body = bodySentBy("/events/delete", client ->
                client.events().delete(List.of(
                        UUIDAndExternalIdCollection.createFromExternalId("alarm_pipe_overpressure"))));

        assertTrue(wireFields(UUIDAndExternalIdCollection.class).containsAll(keysOfFirstItem(body)));
    }
}
