// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.unit.UnitModel;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitServiceTest {

    @Test
    void listReturnsUnits() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = "{\"items\":[{\"id\":\"7\",\"externalId\":\"celsius\",\"name\":\"Celsius\",\"symbol\":\"degC\"}]}";
        server.createContext("/units", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .build());

            DataWrapper<UnitModel> result = client.units().list();

            assertEquals(1, result.getItems().size());
            UnitModel unit = result.getItems().iterator().next();
            assertEquals(7L, unit.getId());
            assertEquals("celsius", unit.getExternalId());
            assertEquals("Celsius", unit.getName());
            assertEquals("degC", unit.getSymbol());
        } finally {
            server.stop(0);
        }
    }
}
