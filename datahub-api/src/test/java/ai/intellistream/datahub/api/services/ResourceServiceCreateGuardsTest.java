// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.edge.EdgeMapper;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.dto.EdgeEndpoint;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.jpa.dto.NameAndExternalId;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two shape checks {@code /resources/create} gained in M4, which {@code /timeseries/create}
 * had run all along: a taken external id answers 409, and a data set that does not exist (or an
 * id that is some other kind of node) answers 400. Both are judged over the whole batch before
 * anything is mapped, so a rejected request must persist nothing.
 */
class ResourceServiceCreateGuardsTest {

    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final NodeService nodeService = mock(NodeService.class);
    private final EdgeRepository edgeRepository = mock(EdgeRepository.class);
    private final RelationshipTypeRepository relationshipTypeRepository = mock(RelationshipTypeRepository.class);
    private final DataSetRepository dataSetRepository = mock(DataSetRepository.class);
    private final Validator validator = mock(Validator.class);
    private final PolicyEnforcement policyEnforcement = mock(PolicyEnforcement.class);
    private final LabelService labelService = mock(LabelService.class);
    private DatasetPermissions permissions = DatasetPermissions.of(false, true, java.util.Set.of(), java.util.Set.of());
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(() -> permissions);

    private final ResourceService service = new ResourceService(
            mock(EntityManager.class), nodeRepository, nodeService, edgeRepository,
            relationshipTypeRepository, mock(RelationshipTypeService.class),
            mock(ApplicationEventPublisher.class), mock(GraphOutbox.class), mock(Neo4JService.class), dataSecurity,
            mock(SubscriptionRepository.class), validator, policyEnforcement,
            mock(DatasetClosureService.class),
            mock(IngestQuotaService.class), mock(TenantLimitsService.class),
            new EdgeMapper(nodeRepository, relationshipTypeRepository, mock(RelationshipTypeService.class)),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    nodeRepository, dataSetRepository, dataSecurity, labelService, nodeService, policyEnforcement),
            mock(ai.intellistream.datahub.api.policy.NamingPolicyResolver.class));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static GraphDataWrapper<NodeModel, RelForm> request(Long dataSetId, String... externalIds) {
        GraphDataWrapper<NodeModel, RelForm> w = new GraphDataWrapper<>();
        for (String externalId : externalIds) {
            Resource r = new Resource();
            r.setExternalId(externalId);
            r.setName(externalId);
            r.setDataSetId(dataSetId);
            w.getNodes().add(r);
        }
        return w;
    }

    private void allowValidation() {
        when(validator.validate(any())).thenReturn(Collections.emptySet());
    }

    private void nothingTaken() {
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), any())).thenReturn(List.of());
    }

    private void dataSetExists(long id) {
        resolvesTo(endpoint(id, NodeType.DATASET));
    }

    /**
     * Built before the stubbing starts, deliberately: {@link #endpoint} stubs its own mocks, and
     * Mockito treats a {@code when()} opened inside another one's argument list as an unfinished
     * stubbing.
     */
    private void resolvesTo(EdgeEndpoint... endpoints) {
        when(nodeRepository.findAllByIdIn(any(), org.mockito.ArgumentMatchers.eq(EdgeEndpoint.class)))
                .thenReturn(List.of(endpoints));
    }

    /** The projection the data-set guard queries: an id and the discriminator behind it. */
    private static EdgeEndpoint endpoint(long id, long nodeTypeId) {
        EdgeEndpoint.NodeTypeId type = mock(EdgeEndpoint.NodeTypeId.class);
        when(type.getId()).thenReturn(nodeTypeId);
        EdgeEndpoint endpoint = mock(EdgeEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        when(endpoint.getNodeType()).thenReturn(type);
        return endpoint;
    }

    @Test
    void refusesAnExternalIdAlreadyInTheTable() {
        allowValidation();
        dataSetExists(7L);
        NameAndExternalId taken = mock(NameAndExternalId.class);
        when(taken.getExternalId()).thenReturn("pipe_1");
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), any())).thenReturn(List.of(taken));

        assertThatThrownBy(() -> service.create(request(7L, "pipe_1")))
                .isInstanceOf(DuplicateDataException.class);

        verify(nodeRepository, never()).saveAll(any());
    }

    /** Two items claiming one id both pass a per-item check, so the batch is judged as a batch. */
    @Test
    void refusesAnExternalIdRepeatedWithinTheBatch() {
        allowValidation();
        dataSetExists(7L);
        nothingTaken();

        assertThatThrownBy(() -> service.create(request(7L, "pipe_1", "pipe_1")))
                .isInstanceOf(DuplicateDataException.class);

        verify(nodeRepository, never()).saveAll(any());
    }

    /** Case-insensitively, because that is how the external id is hashed everywhere else. */
    @Test
    void treatsExternalIdsCaseInsensitivelyWithinTheBatch() {
        allowValidation();
        dataSetExists(7L);
        nothingTaken();

        assertThatThrownBy(() -> service.create(request(7L, "pipe_1", "PIPE_1")))
                .isInstanceOf(DuplicateDataException.class);
    }

    @Test
    void refusesADataSetThatDoesNotExist() {
        allowValidation();
        nothingTaken();
        resolvesTo();

        assertThatThrownBy(() -> service.create(request(7L, "pipe_1")))
                .isInstanceOf(BadRequestException.class);

        verify(nodeRepository, never()).saveAll(any());
    }

    /** A foreign key alone would accept any node id; the guard resolves the type too. */
    @Test
    void refusesADataSetIdThatIsNotADataSet() {
        allowValidation();
        nothingTaken();
        resolvesTo(endpoint(7L, NodeType.RESOURCE));

        assertThatThrownBy(() -> service.create(request(7L, "pipe_1")))
                .isInstanceOf(BadRequestException.class);

        verify(nodeRepository, never()).saveAll(any());
    }

    @Test
    void allowsAFreeExternalIdInAnExistingDataSet() throws Exception {
        allowValidation();
        dataSetExists(7L);
        nothingTaken();
        ResourceEntity created = new ResourceEntity();
        created.setId(5L);
        created.setExternalId("pipe_1");
        when(nodeService.createFromResource(any())).thenReturn(created);
        when(nodeRepository.saveAll(any())).thenReturn(List.of(created));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        assertThatCode(() -> service.create(request(7L, "pipe_1"))).doesNotThrowAnyException();

        verify(nodeRepository).saveAll(any());
    }

    /** An orphan create names no data set, so the guard must not run a query or refuse it. */
    @Test
    void skipsTheDataSetGuardWhenNoDataSetIsNamed() {
        allowValidation();
        nothingTaken();
        ResourceEntity created = new ResourceEntity();
        created.setId(5L);
        created.setExternalId("pipe_1");
        when(nodeService.createFromResource(any())).thenReturn(created);
        when(nodeRepository.saveAll(any())).thenReturn(List.of(created));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        assertThatCode(() -> service.create(request(null, "pipe_1"))).doesNotThrowAnyException();

        verify(nodeRepository, never()).findAllByIdIn(any(), org.mockito.ArgumentMatchers.eq(EdgeEndpoint.class));
    }
}
