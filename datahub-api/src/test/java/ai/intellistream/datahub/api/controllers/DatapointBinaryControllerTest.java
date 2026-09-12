// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockRejectedException;
import ai.intellistream.datahub.api.services.DatapointBinaryIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer of the binary insert: the media type routes to it, a body-level
 * {@code Content-Encoding} is refused, the body cap holds, and a rejection comes back as a
 * problem response naming the reason.
 */
class DatapointBinaryControllerTest {

    private DatapointBinaryIngestService service;
    private LimitsProperties limits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DatapointBinaryIngestService.class);
        limits = new LimitsProperties();
        mvc = MockMvcBuilders.standaloneSetup(new DatapointBinaryController(service, limits))
                .setControllerAdvice(new DatapointBlockExceptionHandler())
                .build();
    }

    private static byte[] frame() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(1, "a");
        w.addFloat32(1, 1000, 1f);
        return w.build(new ZstdPayloadCodec(1));
    }

    @Test
    void aFrameIsAcceptedWithNoContent() throws Exception {
        when(service.ingest(any(), anyLong())).thenReturn(new DatapointBinaryIngestService.Summary(1, 1, 1));
        byte[] body = frame();
        mvc.perform(post("/timeseries/data/binary").contentType(FrameLimits.MEDIA_TYPE).content(body))
                .andExpect(status().isNoContent());
        verify(service).ingest(body, body.length);
    }

    @Test
    void jsonOnTheBinaryPathIsUnsupported() throws Exception {
        mvc.perform(post("/timeseries/data/binary").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verify(service, never()).ingest(any(), anyLong());
    }

    @Test
    void aBodyLevelContentEncodingIsRefused() throws Exception {
        mvc.perform(post("/timeseries/data/binary").contentType(FrameLimits.MEDIA_TYPE)
                        .header(HttpHeaders.CONTENT_ENCODING, "gzip").content(frame()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.reason").value("unsupported-content-encoding"))
                .andExpect(jsonPath("$.type").value(DatapointBlockExceptionHandler.TYPE));
        verify(service, never()).ingest(any(), anyLong());
    }

    @Test
    void aBodyOverTheCapIs413() throws Exception {
        limits.setMaxBodyBytesDatapointsBinary(16);
        mvc.perform(post("/timeseries/data/binary").contentType(FrameLimits.MEDIA_TYPE).content(frame()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.reason").value("request-too-large"));
        verify(service, never()).ingest(any(), anyLong());
    }

    @Test
    void aRejectionIsAProblemWithReasonFrameAndSeries() throws Exception {
        when(service.ingest(any(), anyLong()))
                .thenThrow(DatapointBlockRejectedException.valueTypeMismatch(2, List.of(7L, 9L)));
        mvc.perform(post("/timeseries/data/binary").contentType(FrameLimits.MEDIA_TYPE).content(frame()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("value-type-mismatch"))
                .andExpect(jsonPath("$.frameIndex").value(2))
                .andExpect(jsonPath("$.timeseriesIds[0]").value(7))
                .andExpect(jsonPath("$.timeseriesIds[1]").value(9));
    }

    @Test
    void tooManyInFlightCarriesRetryAfter() throws Exception {
        when(service.ingest(any(), anyLong())).thenThrow(DatapointBlockRejectedException.tooManyInFlight(16));
        mvc.perform(post("/timeseries/data/binary").contentType(FrameLimits.MEDIA_TYPE).content(frame()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.reason").value("too-many-in-flight"));
    }
}
