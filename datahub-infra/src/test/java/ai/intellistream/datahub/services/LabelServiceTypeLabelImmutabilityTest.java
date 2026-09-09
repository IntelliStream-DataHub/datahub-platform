// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An update may not change what kind of node something is. A node's type is fixed by its
 * discriminator at create time, and three stores agree on it (the Postgres row, the Neo4j labels,
 * the DTO the read path returns), so letting a label update re-type a node would desync all three
 * — CONSTRAINTS.md's "one type label per node".
 *
 * <p>{@code resolveLabelUpdate} is the authority for the labels <em>on a node</em>: whatever
 * type-labels the caller sends are stripped, and the node's intrinsic one is re-added.
 *
 * <p>That left the other half open. The type-label <em>row</em> was ordinary vocabulary, so
 * {@code POST /labels/update} could rename {@code ASSET} and {@code POST /labels/delete} could
 * remove it while unattached. The name is what everything keys on — {@code Label.setName} derives
 * the hash {@code NodeFilter.getLabelHashes()} matches, and the Neo4j projection writes the
 * canonical name — so a rename empties every filter for that label and leaves the graph disagreeing
 * with Postgres, while {@code TypeLabels.forEntity} keeps handing out the old name and mints a
 * second row. The second half of this class covers the row.
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

    // ---- the label row itself ------------------------------------------------------------------

    private LabelRepository rowRepository;

    private LabelService serviceOver(LabelRepository repository) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new LabelService(repository, validator, mock(PlatformTransactionManager.class));
    }

    private static Label labelRow(long id, String name) {
        Label label = new Label();
        label.setId(id);
        label.setName(name);   // canonicalises, and derives the hash filters match on
        label.setColor("#aabbcc");
        return label;
    }

    private static DataWrapper<LabelForm> renameTo(long id, String newName) {
        LabelForm form = new LabelForm();
        form.setId(id);
        form.setName(newName);
        DataWrapper<LabelForm> wrapper = new DataWrapper<>();
        wrapper.getItems().add(form);
        return wrapper;
    }

    @Test
    @DisplayName("a type-label row cannot be renamed")
    void aTypeLabelRowCannotBeRenamed() {
        rowRepository = mock(LabelRepository.class);
        when(rowRepository.findById(1L)).thenReturn(Optional.of(labelRow(1L, "ASSET")));

        assertThatThrownBy(() -> serviceOver(rowRepository).updateLabels(renameTo(1L, "equipment")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASSET")
                .hasMessageContaining("cannot be renamed");
    }

    /** Only the name is load-bearing; a type label still has to render in the UI. */
    @Test
    @DisplayName("a type-label row can still be recoloured and described")
    void aTypeLabelRowStaysEditableApartFromItsName() {
        rowRepository = mock(LabelRepository.class);
        Label asset = labelRow(1L, "ASSET");
        when(rowRepository.findById(1L)).thenReturn(Optional.of(asset));

        LabelForm form = new LabelForm();
        form.setId(1L);
        form.setDescription("Physical equipment");
        form.setColor("#ff0000");
        DataWrapper<LabelForm> wrapper = new DataWrapper<>();
        wrapper.getItems().add(form);

        assertThatCode(() -> serviceOver(rowRepository).updateLabels(wrapper)).doesNotThrowAnyException();
        assertTrue("ASSET".equals(asset.getName()), "the name is untouched");
    }

    @Test
    @DisplayName("an ordinary label cannot be renamed onto a reserved type-label")
    void anOrdinaryLabelCannotBecomeAType() {
        rowRepository = mock(LabelRepository.class);
        when(rowRepository.findById(2L)).thenReturn(Optional.of(labelRow(2L, "PUMP")));

        assertThatThrownBy(() -> serviceOver(rowRepository).updateLabels(renameTo(2L, "asset")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("an ordinary label renames freely")
    void anOrdinaryRenameIsStillAllowed() {
        rowRepository = mock(LabelRepository.class);
        Label pump = labelRow(2L, "PUMP");
        when(rowRepository.findById(2L)).thenReturn(Optional.of(pump));

        assertThatCode(() -> serviceOver(rowRepository).updateLabels(renameTo(2L, "centrifugal_pump")))
                .doesNotThrowAnyException();
        assertTrue("CENTRIFUGAL_PUMP".equals(pump.getName()), "renamed to " + pump.getName());
    }

    /**
     * The in-use check already blocks a type-label that any node carries. This is the gap it leaves:
     * a type whose nodes have all been deleted, or whose first node has yet to be created.
     */
    @Test
    @DisplayName("a type-label row cannot be deleted even with nothing attached")
    void anUnattachedTypeLabelRowStillCannotBeDeleted() {
        rowRepository = mock(LabelRepository.class);
        when(rowRepository.findAllByIdInFetchNodes(java.util.Set.of(3L)))
                .thenReturn(java.util.List.of(labelRow(3L, "FUNCTION")));

        DataWrapper<IdCollection> wrapper = new DataWrapper<>();
        IdCollection target = new IdCollection();
        target.setId(3L);
        wrapper.getItems().add(target);

        assertThatThrownBy(() -> serviceOver(rowRepository).delete(wrapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FUNCTION");
    }

    /**
     * PATCH semantics do not mean unvalidated. A name that is sent is still held to
     * {@code @Size(min = 3, max = 128)}; the earlier gap was that update ran no validation at all,
     * so any length went through on both the REST and MCP paths.
     */
    @Test
    @DisplayName("a name sent on update is still held to its length cap")
    void anOversizedNameOnUpdateIsStillRejected() {
        rowRepository = mock(LabelRepository.class);
        when(rowRepository.findById(2L)).thenReturn(Optional.of(labelRow(2L, "PUMP")));

        assertThatThrownBy(() -> serviceOver(rowRepository).updateLabels(renameTo(2L, "x".repeat(129))))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }
}
