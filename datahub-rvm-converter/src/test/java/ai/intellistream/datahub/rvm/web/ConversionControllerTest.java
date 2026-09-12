// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm.web;

import ai.intellistream.datahub.rvm.RvmConversion;
import ai.intellistream.datahub.rvm.RvmConversionException;
import ai.intellistream.datahub.rvm.RvmConverter;
import ai.intellistream.datahub.rvm.config.RvmApiClientFactory;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.services.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the endpoint does with the two things it does not control: what the api hands back, and
 * whether the converter can make sense of it.
 */
class ConversionControllerTest {

    private static final byte[] GLB = "glTF   model".getBytes(StandardCharsets.UTF_8);

    private FileService files;
    private RvmConverter converter;
    private ConversionController controller;
    private final List<Path> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        files = mock(FileService.class);
        DatahubClient client = mock(DatahubClient.class);
        when(client.files()).thenReturn(files);

        RvmApiClientFactory clients = mock(RvmApiClientFactory.class);
        when(clients.forCurrentUser()).thenReturn(client);

        converter = mock(RvmConverter.class);
        controller = new ConversionController(clients, converter);
    }

    @Test
    void returnsTheConvertedModel() throws Exception {
        when(files.download("plant_rvm")).thenReturn("rvm bytes".getBytes(StandardCharsets.UTF_8));
        when(converter.convert(any(), any())).thenReturn(new RvmConversion(GLB, "ok"));

        var response = controller.toGltf("plant_rvm", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(GLB, response.getBody());
        assertEquals("model/gltf-binary", response.getHeaders().getContentType().toString());
    }

    @Test
    void stagesTheAttributeSidecarWhenOneIsNamed() throws Exception {
        when(files.download("plant_rvm")).thenReturn("rvm".getBytes(StandardCharsets.UTF_8));
        when(files.download("plant_att")).thenReturn("attributes".getBytes(StandardCharsets.UTF_8));
        // Check what actually reached the converter. The tags live in the sidecar, so a request
        // that names one and then converts without it loses them silently.
        when(converter.convert(any(), any())).thenAnswer(call -> {
            Path model = call.getArgument(0);
            Path sidecar = call.getArgument(1);
            seen.add(sidecar);
            assertEquals("rvm", Files.readString(model));
            assertEquals("attributes", Files.readString(sidecar));
            return new RvmConversion(GLB, "ok");
        });

        controller.toGltf("plant_rvm", "plant_att");

        assertEquals(1, seen.size());
        assertNotNull(seen.get(0), "the sidecar never reached the converter");
    }

    @Test
    void convertsWithoutASidecarWhenNoneIsNamed() throws Exception {
        when(files.download("plant_rvm")).thenReturn("rvm".getBytes(StandardCharsets.UTF_8));
        when(converter.convert(any(), any())).thenAnswer(call -> {
            seen.add(call.getArgument(1));
            return new RvmConversion(GLB, "ok");
        });

        controller.toGltf("plant_rvm", null);

        assertEquals(1, seen.size());
        assertNull(seen.get(0));
    }

    @Test
    void aFileTheCallerMayNotReadIsNotFound() {
        // The api hides a file outside the caller's readable datasets, so the answer has to stay
        // "no such file" rather than leaking that it exists.
        when(files.download("someone_elses")).thenThrow(new RuntimeException("404"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.toGltf("someone_elses", null));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void anUnconvertibleModelIsTheRequestsProblemNotTheServers() throws Exception {
        when(files.download("broken_rvm")).thenReturn("not an rvm".getBytes(StandardCharsets.UTF_8));
        when(converter.convert(any(), any()))
                .thenThrow(new RvmConversionException("Converter exited 1: bad chunk"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.toGltf("broken_rvm", null));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    @Test
    void cleansUpTheStagedFileAfterConverting() throws Exception {
        when(files.download("plant_rvm")).thenReturn("rvm".getBytes(StandardCharsets.UTF_8));
        when(converter.convert(any(), any())).thenAnswer(call -> {
            seen.add(call.getArgument(0));
            return new RvmConversion(GLB, "ok");
        });

        controller.toGltf("plant_rvm", null);

        assertTrue(Files.notExists(seen.get(0)), "staged model left behind at " + seen.get(0));
    }

    @Test
    void cleansUpTheStagedFileAfterAFailure() throws Exception {
        // A plant model is hundreds of megabytes. Leaving one behind per failed conversion fills
        // the disk of whatever host this runs on, so the failing path matters more than the happy one.
        when(files.download("plant_rvm")).thenReturn("rvm".getBytes(StandardCharsets.UTF_8));
        when(converter.convert(any(), any())).thenAnswer(call -> {
            seen.add(call.getArgument(0));
            throw new RvmConversionException("nope");
        });

        assertThrows(ResponseStatusException.class, () -> controller.toGltf("plant_rvm", null));

        assertTrue(Files.notExists(seen.get(0)),
                "staged model left behind after a failure at " + seen.get(0));
    }
}
