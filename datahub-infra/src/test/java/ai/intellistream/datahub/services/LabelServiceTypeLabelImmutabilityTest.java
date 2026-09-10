// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * An update may not change what kind of node something is. A node's type is fixed by its
 * discriminator at create time, and three stores agree on it (the Postgres row, the Neo4j labels,
 * the DTO the read path returns), so letting a label update re-type a node would desync all three
 * — CONSTRAINTS.md's "one type label per node".
 *
 * <p>{@code resolveLabelUpdate} is the single authority: whatever type-labels the caller sends are
 * stripped, and the node's intrinsic one is re-added.
 */
class LabelServiceTypeLabelImmutabilityTest {

    private final LabelService labelService = newService();

    private static LabelService newService() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new LabelService(mock(LabelRepository.class), validator,
                mock(PlatformTransactionManager.class));
    }

    private static NodeEntity assetNode() {
        AssetEntity node = new AssetEntity();
        node.setLabels("ASSET,PUMP");
        return node;
    }

    @Test
    @DisplayName("a set that swaps the type-label keeps the node's own type")
    void aSetCannotRetypeANode() {
        UpdateListField update = new UpdateListField().set(List.of("DATASET", "PUMP"));

        List<String> resolved = labelService.resolveLabelUpdate(assetNode(), update).orElseThrow();

        assertTrue(resolved.contains("ASSET"), "the node's intrinsic type-label survives: " + resolved);
        assertFalse(resolved.contains("DATASET"), "a foreign type-label must not stick: " + resolved);
        assertTrue(resolved.contains("PUMP"), "ordinary labels are still free to change");
    }

    @Test
    @DisplayName("an add cannot smuggle a second type-label in")
    void anAddCannotSmuggleAType() {
        UpdateListField update = new UpdateListField().add(List.of("TIMESERIES"));

        List<String> resolved = labelService.resolveLabelUpdate(assetNode(), update).orElseThrow();

        assertTrue(resolved.contains("ASSET"));
        assertFalse(resolved.contains("TIMESERIES"));
    }

    @Test
    @DisplayName("a remove cannot strip the type-label off a node")
    void aRemoveCannotStripTheType() {
        UpdateListField update = new UpdateListField().remove(List.of("ASSET"));

        List<String> resolved = labelService.resolveLabelUpdate(assetNode(), update).orElseThrow();

        assertTrue(resolved.contains("ASSET"), "removing the type-label is a no-op: " + resolved);
    }

    /** A plain dataset behaves the same way from the other direction. */
    @Test
    void aDatasetKeepsItsTypeToo() {
        DatasetEntity node = new DatasetEntity();
        node.setLabels("DATASET");
        UpdateListField update = new UpdateListField().set(List.of("ASSET"));

        List<String> resolved = labelService.resolveLabelUpdate(node, update).orElseThrow();

        assertTrue(resolved.contains("DATASET"));
        assertFalse(resolved.contains("ASSET"));
    }
}
