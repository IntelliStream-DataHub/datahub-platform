// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.asset.GraphReadScope;
import ai.intellistream.datahub.asset.NetworkSink;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A playground tenant's network reads stop at its limit, however much the request asks for; every
 * other tenant gets what it asks for. Both ways of reading are covered: the whole network at once
 * ({@code fetchRelatedResources}, used by MCP) and piece by piece (the fetch-related endpoint).
 */
class ResourceServiceNetworkCapTest {

    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final Neo4JService neo4JService = mock(Neo4JService.class);
    private final DataSecurity dataSecurity = mock(DataSecurity.class);
    private final TenantLimitsService tenantLimits = mock(TenantLimitsService.class);

    private final ResourceService service = new ResourceService(
            mock(EntityManager.class), nodeRepository, mock(NodeService.class),
            mock(EdgeRepository.class),
            mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class),
            mock(ApplicationEventPublisher.class), mock(GraphOutbox.class), neo4JService,
            dataSecurity,
            mock(SubscriptionRepository.class), mock(Validator.class),
            mock(PolicyEnforcement.class), mock(DatasetClosureService.class),
            mock(IngestQuotaService.class), tenantLimits,
            new ai.intellistream.datahub.api.edge.EdgeMapper(
                    nodeRepository, mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class)),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    nodeRepository, mock(DataSetRepository.class), dataSecurity,
                    mock(LabelService.class), mock(NodeService.class), mock(PolicyEnforcement.class)),
            mock(ai.intellistream.datahub.api.policy.NamingPolicyResolver.class));

    @BeforeEach
    void aReadableStartNode() {
        AssetEntity start = new AssetEntity();
        start.setId(7L);
        start.setExternalId("site");
        when(nodeRepository.findById(7L)).thenReturn(Optional.of(start));
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
    }

    private void networkCap(long cap) {
        when(tenantLimits.current()).thenReturn(new TenantLimits(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, cap));
    }

    private static RelatedResourcesForm asking(Integer limit) {
        RelatedResourcesForm form = new RelatedResourcesForm();
        form.setId(7L);
        form.setDepth(5);
        form.setLimit(limit);
        return form;
    }

    @ParameterizedTest(name = "cap {0}, asked {1}: {2}")
    @CsvSource(nullValues = "null", value = {
            // A playground tenant: asking for everything, for too much or for nothing in particular gets the limit.
            "5000, -1,    5000",
            "5000, 9000,  5000",
            "5000, null,  5000",
            "5000, 100,   100",
            "5000, 5000,  5000",
            // Any other tenant: what it asked for, everything included.
            "0,    -1,    -1",
            "0,    9000,  9000",
            "0,    100,   100",
    })
    void theWholeNetworkIsCappedForAPlaygroundTenantOnly(long cap, Integer asked, int sent) {
        networkCap(cap);

        service.fetchRelatedResources(asking(asked));

        verify(neo4JService).fetchRelatedNodes(eq(7L), eq(5), any(), eq(sent), any(), any(GraphReadScope.class));
    }

    @ParameterizedTest(name = "cap {0}, asked {1}: {2}, reported cap {3}")
    @CsvSource(nullValues = "null", value = {
            // Reported only when the limit lowered what was asked for.
            "5000, -1,    5000, 5000",
            "5000, 9000,  5000, 5000",
            "5000, 100,   100,  0",
            "5000, 5000,  5000, 0",
            "0,    -1,    -1,   0",
    })
    void theStreamedNetworkIsCappedTheSameWayAndSaysSo(long cap, Integer asked, int sent, long reported) {
        networkCap(cap);

        ResourceService.PreparedNetwork prepared = service.prepareRelatedResources(asking(asked));
        service.streamRelatedResources(prepared, mock(NetworkSink.class));

        verify(neo4JService).streamRelatedNodes(eq(7L), eq(5), any(), eq(sent), any(), any(GraphReadScope.class), any());
        assertThat(prepared.cappedAt()).isEqualTo(reported);
    }
}
