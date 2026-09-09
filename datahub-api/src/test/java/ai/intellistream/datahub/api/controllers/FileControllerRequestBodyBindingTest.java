// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.config.UploadProperties;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.filters.CachingBodyFilter;
import ai.intellistream.datahub.api.filters.RequestBodySizeLimitFilter;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.checksum.ChecksumAlgorithm;
import ai.intellistream.datahub.helpers.checksum.ChecksumFactory;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.helpers.utils.HttpHelper;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three JSON endpoints on {@code /files} have to receive the body the caller sent.
 *
 * <p>They did not. {@code FileController} imported Swagger's
 * {@code io.swagger.v3.oas.annotations.parameters.RequestBody} — documentation only — so the bare
 * {@code @RequestBody} on each parameter was not Spring's, no message converter ever ran, and the
 * argument arrived as an empty object built by model-attribute binding from the (absent) query
 * string. The controller compensated by reading {@code HttpServletRequest.getInputStream()} and
 * parsing the JSON itself, which is why the endpoints appeared to work and why the annotation was
 * never suspected.
 *
 * <p>Calling the controller method directly cannot catch this — the caller passes the object in.
 * These go through MockMvc so the body has to travel the same argument-resolution path it does in
 * production. The {@code /files/update} case is the sharpest: an unbound {@link
 * ai.intellistream.datahub.models.files.FileUpdate} has neither id nor externalId and answers
 * <b>400</b>, so the 404 asserted here is only reachable once the body is genuinely bound.
 *
 * <p>The body-wrapping filters are in the chain here too. The upload endpoint reads its body off the
 * raw stream by design — headers first, so it authorises before touching a multi-GB body — and
 * {@code StreamingEndpoints} exempts {@code PUT /files} from both wrappers to keep that possible.
 * These three endpoints are <em>not</em> exempt and never were, so nothing about that design
 * required them to parse by hand.
 */
class FileControllerRequestBodyBindingTest {

    private INodeRepository iNodeRepository;
    private FileSystemService fileSystemService;
    private DataSecurity dataSecurity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FileTransformer fileTransformer = mock(FileTransformer.class);
        iNodeRepository = mock(INodeRepository.class);
        FilesConfig filesConfig = mock(FilesConfig.class);
        fileSystemService = mock(FileSystemService.class);
        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        dataSecurity = mock(DataSecurity.class);

        when(filesConfig.getRoot()).thenReturn(Path.of("/tmp/datahub-test"));

        TenantContext.setTenantId("tenant-1");
        Tenant tenant = mock(Tenant.class);
        TenantFeatures features = mock(TenantFeatures.class);
        when(tenantConfigService.getConfig("tenant-1")).thenReturn(tenant);
        when(tenant.getFeatures()).thenReturn(features);
        when(features.isFilesEnabled()).thenReturn(true);

        FileController controller = new FileController(fileTransformer, iNodeRepository, filesConfig,
                mock(Validator.class), fileSystemService, mock(HttpHelper.class), tenantConfigService,
                dataSecurity, new ChecksumFactory(ChecksumAlgorithm.SHA_256), mock(DirectoryService.class),
                new UploadProperties());

        // The two filters every request passes through in production, both of which wrap the body:
        // CachingBodyFilter in a ContentCachingRequestWrapper and RequestBodySizeLimitFilter in a
        // counting one. Neither exempts these three endpoints — StreamingEndpoints covers only the
        // upload and download — so the bound body has to survive both wrappers.
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RequestBodySizeLimitFilter(new LimitsProperties(), mock(IngestQuotaService.class)),
                        new CachingBodyFilter())
                .build();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void deleteReceivesTheIdsInTheBody() throws Exception {
        when(dataSecurity.hasWriteAccessToEverything()).thenReturn(true);

        mvc.perform(post("/files/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":4242}]}"))
                .andExpect(status().isNoContent());

        verify(fileSystemService).delete(Set.of(4242L), Set.of());
    }

    @Test
    void restoreReceivesTheExternalIdsInTheBody() throws Exception {
        mvc.perform(post("/files/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"externalId\":\"DELETED_ab12_99_1700000000000\"}]}"))
                // Nothing matches the (mocked) repository, but the lookup ran with the hash below.
                .andExpect(status().isNotFound());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Long>> hashes = ArgumentCaptor.forClass(Set.class);
        verify(iNodeRepository).findAllByIdOrExternalIdHashAndDeleted(any(), hashes.capture());
        assertThat(hashes.getValue())
                .containsExactly(ExternalIds.hash("DELETED_ab12_99_1700000000000"));
    }

    @Test
    void updateReceivesTheExternalIdInTheBody() throws Exception {
        // 404, not the 400 an unbound body produces: the externalId reached the lookup.
        mvc.perform(post("/files/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"folder/file.csv\",\"description\":\"d\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("File or folder not found."));
    }

    @Test
    void updateStillRejectsABodyThatIdentifiesNothing() throws Exception {
        mvc.perform(post("/files/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("A file id or externalId is required."));
    }

    @Test
    void restoreRejectsAnEmptyBody() throws Exception {
        mvc.perform(post("/files/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());

        verify(iNodeRepository, never())
                .findAllByIdOrExternalIdHashAndDeleted(any(), any());
    }
}
