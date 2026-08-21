// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.config.UploadProperties;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.helpers.checksum.ChecksumAlgorithm;
import ai.intellistream.datahub.helpers.checksum.ChecksumFactory;
import ai.intellistream.datahub.services.DirectoryService;
import ai.intellistream.datahub.helpers.utils.HttpHelper;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import ai.intellistream.datahub.services.FileSystemService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.transformers.FileTransformer;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the upload-time guards on {@link FileController#upload}: the dataset-ACL write check (a
 * caller without write permission to the target dataset, or to an existing parent folder's dataset,
 * gets a 403 before anything is persisted or written to disk) and the server-side sanitisation of
 * the client-supplied external id.
 *
 * <p>The endpoint is a raw HTTP PUT - the file content is the request body and all metadata travels
 * in {@code X-Datahub-*} headers - so the tests drive the controller directly with a hand-built
 * request rather than through MockMvc.
 */
class FileControllerUploadTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void upload_deniedWhenCallerCannotWriteTargetDataset() {
        // --- collaborators -------------------------------------------------------------------
        FileTransformer fileTransformer = mock(FileTransformer.class);
        INodeRepository iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        Validator validator = mock(Validator.class);
        FileSystemService fileSystemService = mock(FileSystemService.class);
        HttpHelper httpHelper = mock(HttpHelper.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        DataSecurity dataSecurity = mock(DataSecurity.class);
        DirectoryService directoryService = mock(DirectoryService.class);
        ChecksumFactory checksumFactory = new ChecksumFactory(ChecksumAlgorithm.SHA_256);
        UploadProperties uploadProperties = new UploadProperties();

        FileController controller = new FileController(
                fileTransformer, iNodeRepository, filesConfig, validator, fileSystemService,
                httpHelper, jsonMapper, tenantConfigService, dataSecurity,
                checksumFactory, directoryService, uploadProperties);

        // Files feature enabled for the tenant.
        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        // Folder path is valid; bean-validation passes; a (path-only) root is available.
        when(fileSystemService.validateFolderPath("/secret")).thenReturn(true);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(filesConfig.getRoot()).thenReturn(Path.of("/tmp/datahub-test"));

        // The "dataSet" form field resolves to dataset 77 on the INode (as FileTransformer would).
        doAnswer(inv -> {
            INode file = inv.getArgument(0);
            String field = inv.getArgument(1);
            if ("dataSet".equals(field)) {
                DatasetEntity ds = new DatasetEntity();
                ds.setId(77L);
                file.setDataSet(ds);
            }
            return null;
        }).when(fileTransformer).setProperty(any(INode.class), anyString(), anyString());

        // Caller (Foo) may write datasets 55/66 but NOT 77.
        when(dataSecurity.hasWritePermissionToDataSet(77L)).thenReturn(false);

        // Metadata in headers; the file content (unread on the 403 path) is the raw body.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("PUT");
        request.addHeader("X-Datahub-Path", "/secret/strategy.txt");
        request.addHeader("X-Datahub-Dataset-Id", "77");
        request.setContent("hello world".getBytes(StandardCharsets.UTF_8));

        // The 403 branch calls TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        // there's no real transaction in a direct call, so stub the static lookup.
        try (MockedStatic<TransactionAspectSupport> tx = mockStatic(TransactionAspectSupport.class)) {
            tx.when(TransactionAspectSupport::currentTransactionStatus)
                    .thenReturn(mock(TransactionStatus.class));

            ResponseEntity<?> response = (ResponseEntity<?>) controller.upload(request);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertTrue(String.valueOf(response.getBody()).contains("77"),
                    "403 body should name the denied dataset id, was: " + response.getBody());
        }

        // The upload was rejected before any persistence.
        org.mockito.Mockito.verify(iNodeRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void upload_deniedWhenCallerCannotWriteParentFolderDataset() {
        // --- collaborators -------------------------------------------------------------------
        FileTransformer fileTransformer = mock(FileTransformer.class);
        INodeRepository iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        Validator validator = mock(Validator.class);
        FileSystemService fileSystemService = mock(FileSystemService.class);
        HttpHelper httpHelper = mock(HttpHelper.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        DataSecurity dataSecurity = mock(DataSecurity.class);
        DirectoryService directoryService = mock(DirectoryService.class);
        ChecksumFactory checksumFactory = new ChecksumFactory(ChecksumAlgorithm.SHA_256);
        UploadProperties uploadProperties = new UploadProperties();

        FileController controller = new FileController(
                fileTransformer, iNodeRepository, filesConfig, validator, fileSystemService,
                httpHelper, jsonMapper, tenantConfigService, dataSecurity,
                checksumFactory, directoryService, uploadProperties);

        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        when(fileSystemService.validateFolderPath("/org-a/team-b/plan")).thenReturn(true);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(filesConfig.getRoot()).thenReturn(Path.of("/tmp/datahub-test"));

        // File targets dataset 66 (which Foo can write).
        doAnswer(inv -> {
            INode file = inv.getArgument(0);
            String field = inv.getArgument(1);
            if ("dataSet".equals(field)) {
                DatasetEntity ds = new DatasetEntity();
                ds.setId(66L);
                file.setDataSet(ds);
            }
            return null;
        }).when(fileTransformer).setProperty(any(INode.class), anyString(), anyString());

        // The leaf folder "plan" doesn't exist yet, but its parent /org-a/team-b does and belongs
        // to dataset 55 — so the content lands inside a dataset-55 folder.
        INode teamB = new INode();
        teamB.setNodeType(INode.INodeType.FOLDER);
        teamB.setPath("/org-a/team-b");
        DatasetEntity ds55 = new DatasetEntity();
        ds55.setId(55L);
        teamB.setDataSet(ds55);
        // From the entity, not recomputed: setPath derives pathHash from the normalised path, and
        // reproducing that derivation here would make the stub agree with the fixture rather than
        // with production.
        long teamBHash = teamB.getPathHash();
        when(iNodeRepository.findByPathHashAndNodeType(any(), eq(INode.INodeType.FOLDER), eq(INode.class)))
                .thenAnswer(inv -> {
                    Long h = inv.getArgument(0);
                    return (h != null && h == teamBHash) ? java.util.Optional.of(teamB) : java.util.Optional.empty();
                });

        // Foo may write dataset 66 (the file's), but NOT 55 (the parent folder's).
        when(dataSecurity.hasWritePermissionToDataSet(66L)).thenReturn(true);
        when(dataSecurity.hasWritePermissionToDataSet(55L)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("PUT");
        request.addHeader("X-Datahub-Path", "/org-a/team-b/plan/strategy.txt");
        request.addHeader("X-Datahub-Dataset-Id", "66");
        request.setContent("hello world".getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<TransactionAspectSupport> tx = mockStatic(TransactionAspectSupport.class)) {
            tx.when(TransactionAspectSupport::currentTransactionStatus)
                    .thenReturn(mock(TransactionStatus.class));

            ResponseEntity<?> response = (ResponseEntity<?>) controller.upload(request);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            assertTrue(String.valueOf(response.getBody()).contains("55"),
                    "403 body should name the denied parent folder dataset id, was: " + response.getBody());
        }

        org.mockito.Mockito.verify(iNodeRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * On success, upload must return the {@link IndexNode} DTO produced by
     * {@link FileTransformer#transformToIndexNode}, NOT the raw {@link INode} JPA entity. The entity
     * serializes a different wire shape (nodeType/dataSet/parent nesting, base64 checksum, numeric
     * id) that the Java SDK and console cannot parse as an IndexNode — regression guard for the
     * upload endpoint that used to wrap the raw entity while every sibling endpoint transformed.
     */
    @Test
    void upload_returnsTransformedIndexNodeNotRawEntity() throws Exception {
        FileTransformer fileTransformer = mock(FileTransformer.class);
        INodeRepository iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        Validator validator = mock(Validator.class);
        FileSystemService fileSystemService = mock(FileSystemService.class);
        HttpHelper httpHelper = mock(HttpHelper.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        DataSecurity dataSecurity = mock(DataSecurity.class);
        DirectoryService directoryService = mock(DirectoryService.class);
        ChecksumFactory checksumFactory = new ChecksumFactory(ChecksumAlgorithm.SHA_256);
        UploadProperties uploadProperties = new UploadProperties();

        // Spy so we can no-op the disk streaming (checksum + write) and drive the endpoint to its
        // success return without touching the filesystem.
        FileController controller = spy(new FileController(
                fileTransformer, iNodeRepository, filesConfig, validator, fileSystemService,
                httpHelper, jsonMapper, tenantConfigService, dataSecurity,
                checksumFactory, directoryService, uploadProperties));
        doNothing().when(controller).handleUploadStream(any(), any(), any(INode.class));

        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        // No dataset header → public file, no ACL gate; root folder has no dataset either.
        when(fileSystemService.validateFolderPath("/")).thenReturn(true);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(filesConfig.getRoot()).thenReturn(Path.of("/tmp/datahub-test"));
        when(iNodeRepository.findByPathHashAndNodeType(any(), any(), any())).thenReturn(Optional.empty());

        // The transformer returns a DTO distinct from any INode entity — the value the endpoint must
        // hand back.
        IndexNode transformed = new IndexNode();
        transformed.setId(4242L);
        transformed.setType("FILE");
        transformed.setDataSetId(77L);
        transformed.setChecksum("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        when(fileTransformer.transformToIndexNode(anyList())).thenReturn(List.of(transformed));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("PUT");
        request.addHeader("X-Datahub-Path", "/report.txt");
        request.setContent("hello world".getBytes(StandardCharsets.UTF_8));

        // Files.move is the only java.nio.file.Files call left on the success path once the streaming
        // helper is stubbed; neutralise it so no real move is attempted.
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            ResponseEntity<?> response = (ResponseEntity<?>) controller.upload(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            DataWrapper<?> body = (DataWrapper<?>) response.getBody();
            assertEquals(1, body.getItems().size());
            Object item = body.getItems().iterator().next();
            assertInstanceOf(IndexNode.class, item, "upload must return IndexNode, not the raw INode entity");
            assertSame(transformed, item);
        }

        verify(fileTransformer).transformToIndexNode(anyList());
    }

    /**
     * A client-supplied {@code X-Datahub-External-Id} is percent-decoded and then ALWAYS run through
     * the slug sanitizer before it is set on the node. This is a server-side protection: a hostile
     * value (path traversal, spaces, control/special characters) can never reach storage verbatim.
     * The external id is set before the dataset-ACL check, so we drive the (denied) 403 path and
     * capture what was handed to the transformer.
     */
    @Test
    void upload_alwaysSanitizesClientSuppliedExternalId() {
        FileTransformer fileTransformer = mock(FileTransformer.class);
        INodeRepository iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        Validator validator = mock(Validator.class);
        FileSystemService fileSystemService = mock(FileSystemService.class);
        HttpHelper httpHelper = mock(HttpHelper.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        DataSecurity dataSecurity = mock(DataSecurity.class);
        DirectoryService directoryService = mock(DirectoryService.class);
        ChecksumFactory checksumFactory = new ChecksumFactory(ChecksumAlgorithm.SHA_256);
        UploadProperties uploadProperties = new UploadProperties();

        FileController controller = new FileController(
                fileTransformer, iNodeRepository, filesConfig, validator, fileSystemService,
                httpHelper, jsonMapper, tenantConfigService, dataSecurity,
                checksumFactory, directoryService, uploadProperties);

        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        when(fileSystemService.validateFolderPath("/secret")).thenReturn(true);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(filesConfig.getRoot()).thenReturn(Path.of("/tmp/datahub-test"));

        // Resolve a dataset the caller cannot write, so the upload stops at the 403 right after the
        // external id has been set on the node.
        doAnswer(inv -> {
            INode file = inv.getArgument(0);
            String field = inv.getArgument(1);
            if ("dataSet".equals(field)) {
                DatasetEntity ds = new DatasetEntity();
                ds.setId(77L);
                file.setDataSet(ds);
            }
            return null;
        }).when(fileTransformer).setProperty(any(INode.class), anyString(), anyString());
        when(dataSecurity.hasWritePermissionToDataSet(77L)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("PUT");
        request.addHeader("X-Datahub-Path", "/secret/strategy.txt");
        request.addHeader("X-Datahub-Dataset-Id", "77");
        // Percent-encoded "../etc/passwd" - a path-traversal attempt smuggled through the header.
        request.addHeader("X-Datahub-External-Id", "..%2Fetc%2Fpasswd");
        request.setContent("hello world".getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<TransactionAspectSupport> tx = mockStatic(TransactionAspectSupport.class)) {
            tx.when(TransactionAspectSupport::currentTransactionStatus)
                    .thenReturn(mock(TransactionStatus.class));

            controller.upload(request);
        }

        org.mockito.ArgumentCaptor<String> externalId = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(fileTransformer)
                .setProperty(any(INode.class), eq("externalId"), externalId.capture());

        String sanitized = externalId.getValue();
        assertEquals("_etc_passwd", sanitized,
                "decoded path-traversal id must collapse to a safe slug");
        assertTrue(sanitized.matches("[a-z0-9_]+"),
                "external id must be a lowercase [a-z0-9_] slug, was: " + sanitized);
        assertTrue(!sanitized.contains("/") && !sanitized.contains(".."),
                "external id must never contain path separators or traversal, was: " + sanitized);
    }
}
