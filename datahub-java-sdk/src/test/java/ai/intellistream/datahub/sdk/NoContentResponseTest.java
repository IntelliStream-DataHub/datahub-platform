// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every write endpoint that answers {@code 204 No Content} must be typed {@code void} here.
 *
 * <p>Seven of them were not. {@code ApiHttp} returns {@code null} for a 204, so
 * {@code resources().delete}, {@code timeseries().delete}, {@code timeseries().insertDatapoints},
 * {@code datasets().delete}, {@code events().delete}, {@code subscriptions().delete} and
 * {@code files().delete} each promised a wrapper and handed back {@code null} on every successful
 * call — {@code client.datasets().delete(ids).getItems()} was an NPE, not a compile error.
 *
 * <p>It survived because the SDK's tests stubbed {@code 200} with a body the api never sends. The
 * two calls that were already {@code void} ({@code edges().delete},
 * {@code timeseries().deleteDatapoints}) are also the only two whose tests stubbed a 204. So this
 * stubs what the api actually returns, and asserts the return type as well as the call.
 */
class NoContentResponseTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);   // what the api answers, no body
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DatahubClient client() {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }

    @Test
    @DisplayName("a 204 from any no-body endpoint completes instead of yielding null")
    void noBodyEndpointsCompleteOnA204() {
        DatahubClient c = client();
        List<IdCollection> ids = List.of(IdCollection.createFromExternalId("x"));
        DatapointsCollection points = new DatapointsCollection();
        points.setExternalId("x");

        assertAll(
                () -> assertDoesNotThrow(() -> c.resources().delete(ids)),
                () -> assertDoesNotThrow(() -> c.timeseries().delete(ids)),
                () -> assertDoesNotThrow(() -> c.timeseries().insertDatapoints(List.of(points))),
                () -> assertDoesNotThrow(() -> c.timeseries().deleteDatapoints(new DeleteDatapoint())),
                () -> assertDoesNotThrow(() -> c.datasets().delete(ids)),
                () -> assertDoesNotThrow(() -> c.events().delete(
                        List.of(UUIDAndExternalIdCollection.createFromExternalId("e")))),
                () -> assertDoesNotThrow(() -> c.subscriptions().delete(ids)),
                () -> assertDoesNotThrow(() -> c.files().delete(ids)),
                () -> assertDoesNotThrow(() -> c.edges().delete(ids)));

        assertEquals(9, calls.get(), "every call should have reached the server");
    }

    /**
     * The type check the call above cannot make: a declared return type is what a caller writes
     * against, so a 204 endpoint typed as a wrapper compiles at the call site and fails at runtime.
     */
    @Test
    @DisplayName("no-body calls are declared void, so a caller cannot dereference a null")
    void noBodyCallsAreDeclaredVoid() {
        record Call(Class<?> service, String method, Class<?>... parameters) {}
        List<Call> noBody = List.of(
                new Call(ai.intellistream.datahub.sdk.services.ResourceService.class, "delete", List.class),
                new Call(ai.intellistream.datahub.sdk.services.TimeseriesService.class, "delete", List.class),
                new Call(ai.intellistream.datahub.sdk.services.TimeseriesService.class, "insertDatapoints", List.class),
                new Call(ai.intellistream.datahub.sdk.services.TimeseriesService.class, "deleteDatapoints", List.class),
                new Call(ai.intellistream.datahub.sdk.services.DatasetService.class, "delete", List.class),
                new Call(ai.intellistream.datahub.sdk.services.EventService.class, "delete", List.class),
                new Call(ai.intellistream.datahub.sdk.services.SubscriptionService.class, "delete", List.class),
                new Call(ai.intellistream.datahub.sdk.services.FileService.class, "delete", List.class),
                new Call(ai.intellistream.datahub.sdk.services.EdgeService.class, "delete", List.class));

        List<String> wrongly = new ArrayList<>();
        for (Call call : noBody) {
            Method m = assertDoesNotThrow(
                    () -> call.service().getMethod(call.method(), call.parameters()));
            if (m.getReturnType() != void.class) {
                wrongly.add(call.service().getSimpleName() + "." + call.method()
                        + " returns " + m.getReturnType().getSimpleName()
                        + " but the endpoint answers 204, so it can only ever be null");
            }
        }
        assertEquals(List.of(), wrongly, String.join("; ", wrongly));
    }
}
