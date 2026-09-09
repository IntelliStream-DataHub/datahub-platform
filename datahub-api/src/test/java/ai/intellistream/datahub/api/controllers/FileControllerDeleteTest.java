// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.config.UploadProperties;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetAccessDeniedException;
import ai.intellistream.datahub.helpers.checksum.ChecksumAlgorithm;
import ai.intellistream.datahub.helpers.checksum.ChecksumFactory;
import ai.intellistream.datahub.services.DirectoryService;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.utils.HttpHelper;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.models.IdCollection;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that deleting a folder enforces dataset write permission across the folder's whole
 * subtree, not just the directly targeted node.
 */
class FileControllerDeleteTest {

    private FileTransformer fileTransformer;
    private INodeRepository iNodeRepository;
    private FileSystemService fileSystemService;
    private TenantConfigService tenantConfigService;
    private DataSecurity dataSecurity;
    private FileController controller;

    private FileController newController() {
        fileTransformer = mock(FileTransformer.class);
        iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        Validator validator = mock(Validator.class);
        fileSystemService = mock(FileSystemService.class);
        HttpHelper httpHelper = mock(HttpHelper.class);
        tenantConfigService = mock(TenantConfigService.class);
        dataSecurity = mock(DataSecurity.class);

        when(filesConfig.getRoot()).thenReturn(Path.of("/tmp/datahub-test"));

        // Files feature enabled for the tenant.
        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        return new FileController(fileTransformer, iNodeRepository, filesConfig, validator,
                fileSystemService, httpHelper, tenantConfigService, dataSecurity,
                new ChecksumFactory(ChecksumAlgorithm.SHA_256), mock(DirectoryService.class),
                new UploadProperties());
    }

    private static DataWrapper<IdCollection> deleteBodyForId(long id) {
        return new DataWrapper<IdCollection>().setItems(List.of(IdCollection.createFromId(id)));
    }

    private static INode folder(long id) {
        INode folder = new INode();
        folder.setId(id);
        folder.setNodeType(INode.INodeType.FOLDER);
        return folder; // public folder: no dataset of its own
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void delete_deniedWhenSubtreeContainsDatasetCallerCannotWrite() {
        controller = newController();

        INode publicFolder = folder(999L); // no dataset, but contains a dataset-55 child
        when(iNodeRepository.findAllByIdOrExternalIdHashAndNotDeleted(any(), any()))
                .thenReturn(List.of(publicFolder));
        // The subtree under folder 999 includes content in dataset 55.
        when(iNodeRepository.findSubtreeDataSetIds(any())).thenReturn(List.of(55L));

        // Foo cannot write dataset 55 → the subtree check denies the whole delete.
        doThrow(new DatasetAccessDeniedException("write", 55L))
                .when(dataSecurity).assertCanWriteDataSet(55L);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> controller.delete(deleteBodyForId(999L)));
        assertEquals(55L, ((DatasetAccessDeniedException) ex).getDataSetId());

        // Nothing was deleted.
        org.mockito.Mockito.verifyNoInteractions(fileSystemService);
    }

    @Test
    void delete_allowedWhenSubtreeIsAllPublic() throws Exception {
        controller = newController();

        INode publicFolder = folder(1000L);
        when(iNodeRepository.findAllByIdOrExternalIdHashAndNotDeleted(any(), any()))
                .thenReturn(List.of(publicFolder));
        // Entirely public subtree → no dataset to check.
        when(iNodeRepository.findSubtreeDataSetIds(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.delete(deleteBodyForId(1000L));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        // The delete proceeded to the filesystem layer.
        org.mockito.Mockito.verify(fileSystemService).delete(Set.of(1000L), Set.of());
    }
}
