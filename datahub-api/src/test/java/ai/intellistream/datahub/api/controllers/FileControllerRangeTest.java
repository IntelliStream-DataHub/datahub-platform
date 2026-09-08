// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.config.UploadProperties;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.checksum.ChecksumAlgorithm;
import ai.intellistream.datahub.helpers.checksum.ChecksumFactory;
import ai.intellistream.datahub.helpers.utils.HttpHelper;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.dto.INodeDownload;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import ai.intellistream.datahub.services.DirectoryService;
import ai.intellistream.datahub.services.FileSystemService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.transformers.FileTransformer;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Byte-range, conditional-read and whole-file behaviour of {@link FileController#download}.
 *
 * <p>The endpoint streams straight to the servlet response rather than returning a body, so the
 * tests drive the controller directly with a mock request/response pair, as the upload tests do.
 */
class FileControllerRangeTest {

    private static final byte[] CONTENT = content(1000);

    @TempDir
    Path root;

    private FileController controller;
    private String etag;

    @BeforeEach
    void setUp() throws IOException, NoSuchAlgorithmException {
        Path file = root.resolve("model.bin");
        Files.write(file, CONTENT);

        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(CONTENT);
        etag = "\"" + HexFormat.of().formatHex(checksum) + "\"";

        INodeRepository iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        DataSecurity dataSecurity = mock(DataSecurity.class);

        controller = new FileController(
                mock(FileTransformer.class), iNodeRepository, filesConfig, mock(Validator.class),
                mock(FileSystemService.class), mock(HttpHelper.class),
                tenantConfigService, dataSecurity,
                new ChecksumFactory(ChecksumAlgorithm.SHA_256), mock(DirectoryService.class),
                new UploadProperties());

        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        when(filesConfig.getRoot()).thenReturn(root);
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);

        INodeDownload inode = mock(INodeDownload.class);
        when(inode.getExternalId()).thenReturn("model_bin");
        when(inode.getName()).thenReturn("model.bin");
        when(inode.getPath()).thenReturn("/model.bin");
        when(inode.getSize()).thenReturn((long) CONTENT.length);
        when(inode.getMimeType()).thenReturn("application/octet-stream");
        when(inode.getChecksum()).thenReturn(checksum);
        when(iNodeRepository.findByIdAndIsDeletedEquals(5L, false, INodeDownload.class))
                .thenReturn(Optional.of(inode));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void noRange_servesWholeFileAndAdvertisesRangeSupport() {
        MockHttpServletResponse res = download(null);

        assertEquals(200, res.getStatus());
        assertEquals("bytes", res.getHeader("Accept-Ranges"));
        assertEquals(etag, res.getHeader("ETag"));
        assertNull(res.getHeader("Content-Range"));
        assertEquals(CONTENT.length, res.getContentLength());
        assertArrayEquals(CONTENT, res.getContentAsByteArray());
    }

    @Test
    void range_returnsThatSliceAs206() {
        MockHttpServletResponse res = download("bytes=0-99");

        assertEquals(206, res.getStatus());
        assertEquals("bytes 0-99/1000", res.getHeader("Content-Range"));
        assertEquals(100, res.getContentLength());
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 0, 100), res.getContentAsByteArray());
    }

    @Test
    void range_suffix_returnsTheLastBytes() {
        MockHttpServletResponse res = download("bytes=-100");

        assertEquals(206, res.getStatus());
        assertEquals("bytes 900-999/1000", res.getHeader("Content-Range"));
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 900, 1000), res.getContentAsByteArray());
    }

    @Test
    void range_openEnded_returnsTheRemainder() {
        MockHttpServletResponse res = download("bytes=900-");

        assertEquals(206, res.getStatus());
        assertEquals("bytes 900-999/1000", res.getHeader("Content-Range"));
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 900, 1000), res.getContentAsByteArray());
    }

    @Test
    void range_endBeyondEof_isClampedToTheLastByte() {
        MockHttpServletResponse res = download("bytes=990-99999");

        assertEquals(206, res.getStatus());
        assertEquals("bytes 990-999/1000", res.getHeader("Content-Range"));
        assertArrayEquals(Arrays.copyOfRange(CONTENT, 990, 1000), res.getContentAsByteArray());
    }

    @Test
    void range_startBeyondEof_is416WithTheUnsatisfiedSize() {
        MockHttpServletResponse res = download("bytes=5000-6000");

        assertEquals(416, res.getStatus());
        assertEquals("bytes */1000", res.getHeader("Content-Range"));
        assertEquals(0, res.getContentAsByteArray().length);
    }

    @Test
    void range_malformed_isIgnoredAndServesTheWholeFile() {
        MockHttpServletResponse res = download("bytes=not-a-range");

        assertEquals(200, res.getStatus());
        assertNull(res.getHeader("Content-Range"));
        assertArrayEquals(CONTENT, res.getContentAsByteArray());
    }

    @Test
    void range_unknownUnit_isIgnoredAndServesTheWholeFile() {
        MockHttpServletResponse res = download("items=0-99");

        assertEquals(200, res.getStatus());
        assertArrayEquals(CONTENT, res.getContentAsByteArray());
    }

    @Test
    void range_multipleRanges_servesTheWholeFileRatherThanMultipart() {
        MockHttpServletResponse res = download("bytes=0-99,200-299");

        assertEquals(200, res.getStatus());
        assertNull(res.getHeader("Content-Range"));
        assertArrayEquals(CONTENT, res.getContentAsByteArray());
    }

    @Test
    void ifNoneMatch_currentEtag_is304WithNoBody() {
        MockHttpServletRequest req = request(null);
        req.addHeader("If-None-Match", etag);
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.download(req, res, Optional.of("5"));

        assertEquals(304, res.getStatus());
        assertEquals(0, res.getContentAsByteArray().length);
    }

    @Test
    void ifRange_staleEtag_servesTheWholeFileInsteadOfSplicing() {
        MockHttpServletRequest req = request("bytes=0-99");
        req.addHeader("If-Range", "\"0000000000000000000000000000000000000000000000000000000000000000\"");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.download(req, res, Optional.of("5"));

        assertEquals(200, res.getStatus());
        assertNull(res.getHeader("Content-Range"));
        assertArrayEquals(CONTENT, res.getContentAsByteArray());
    }

    @Test
    void ifRange_currentEtag_stillServesTheRange() {
        MockHttpServletRequest req = request("bytes=0-99");
        req.addHeader("If-Range", etag);
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.download(req, res, Optional.of("5"));

        assertEquals(206, res.getStatus());
        assertEquals("bytes 0-99/1000", res.getHeader("Content-Range"));
    }

    private MockHttpServletResponse download(String range) {
        MockHttpServletResponse res = new MockHttpServletResponse();
        controller.download(request(range), res, Optional.of("5"));
        return res;
    }

    private static MockHttpServletRequest request(String range) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/files/download/5");
        if (range != null) {
            req.addHeader("Range", range);
        }
        return req;
    }

    /** Deterministic bytes, so a slice can be checked against its offset. */
    private static byte[] content(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }
}
