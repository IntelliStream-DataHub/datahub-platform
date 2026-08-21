// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.services.FileUploadRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileServiceTest {

    @Test
    void uploadSendsContentAndMetadataHeaders() throws Exception {
        AtomicReference<String> pathHeader = new AtomicReference<>();
        AtomicReference<String> contentTypeHeader = new AtomicReference<>();
        AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String responseJson = "{\"items\":[{\"id\":\"99\"}]}";
        server.createContext("/files", exchange -> {
            pathHeader.set(exchange.getRequestHeaders().getFirst("X-Datahub-Path"));
            contentTypeHeader.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploadedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            byte[] content = "col1,col2\n1,2\n".getBytes(StandardCharsets.UTF_8);
            DataWrapper<IndexNode> result = client(server).files().upload(
                    FileUploadRequest.builder()
                            .path("my report.csv")
                            .content(content)
                            .externalId("doc1")
                            .contentType("text/csv")
                            .build());

            assertEquals("my%20report.csv", pathHeader.get()); // the space is percent-encoded
            assertEquals("text/csv", contentTypeHeader.get());
            assertArrayEquals(content, uploadedBody.get());
            assertEquals(1, result.getItems().size());
            assertEquals(99L, result.getItems().iterator().next().getId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadReturnsRawBytes() throws Exception {
        byte[] fileBytes = {1, 2, 3, 4, 5, 6, 7};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/files/download/abc", exchange -> {
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(fileBytes);
            }
        });
        server.start();
        try {
            byte[] result = client(server).files().download("abc");
            assertArrayEquals(fileBytes, result);
        } finally {
            server.stop(0);
        }
    }

    private static DatahubClient client(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }
}
