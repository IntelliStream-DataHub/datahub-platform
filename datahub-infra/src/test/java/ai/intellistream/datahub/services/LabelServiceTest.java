// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.errors.EntityInUseException;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link LabelService#createLabels} enforces bean validation itself, so callers that
 * bypass the controller's {@code @Valid} — notably the {@code label_create} MCP tool — can't
 * persist a blank/null label name (which would NPE in {@code Label.setName} or hit the DB NOT NULL
 * constraint).
 */
class LabelServiceTest {

    private LabelService newService(LabelRepository repo) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new LabelService(repo, validator,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void createLabels_blankName_throwsAndNeverSaves() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        LabelForm form = new LabelForm();
        form.setName("");
        DataWrapper<LabelForm> wrapper = new DataWrapper<>();
        wrapper.setItems(List.of(form));

        assertThrows(ConstraintViolationException.class, () -> service.createLabels(wrapper));
        verify(repo, never()).saveAll(any());
    }

    @Test
    void createLabels_validName_saves() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        LabelForm form = new LabelForm();
        form.setName("pump station");
        DataWrapper<LabelForm> wrapper = new DataWrapper<>();
        wrapper.setItems(List.of(form));

        var result = service.createLabels(wrapper);

        assertEquals(1, result.size());
        assertEquals("PUMP_STATION", result.iterator().next().getName());
        verify(repo).saveAll(any());
    }

    /**
     * Regression: labels are stored upper-cased ({@link Label#setName}), but the name lookup is
     * case-sensitive. A lower/mixed-case input for an already-existing label must be matched and
     * REUSED — not re-inserted (which hits UNIQUE(hash) and surfaces as HTTP 409, breaking resource
     * creation with any repeated label, e.g. two "Sensor" nodes).
     */
    @Test
    void findAllAndCreateFromNames_reusesExistingLabel_forDifferentCaseInput() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        Label existing = new Label();
        existing.setName("SENSOR"); // labels are persisted upper-cased
        // the service must query with the canonical (upper-cased) name
        when(repo.findAllByNameIn(Set.of("SENSOR"))).thenReturn(Set.of(existing));

        List<Label> result = service.findAllAndCreateFromNames(List.of("sensor")); // lower-case input

        assertEquals(1, result.size());
        assertEquals("SENSOR", result.get(0).getName());
        verify(repo, never()).saveAll(any()); // reused, not re-inserted
    }

    @Test
    void findAllAndCreateFromNames_rejectsInvalidLabelName_insteadOfCrashingOnPersist() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        // "1" canonicalises to "" (leading digits stripped) — not a valid Label.name (@Size 2..512).
        assertThrows(InvalidResourceException.class,
                () -> service.findAllAndCreateFromNames(List.of("PIPE", "1")));
        verify(repo, never()).saveAll(any());
    }

    /**
     * Regression for the two-canonicaliser drift: {@link Label#setName} and the name lookup in
     * {@link LabelService#findAllAndCreateFromNames} must use the SAME canonicaliser
     * ({@code toSnakeUpperCased}). A name that snake-casing changes (a space -> underscore) proves
     * it: the lookup key must match how the label was stored, so an existing label is reused rather
     * than re-inserted (which would hit UNIQUE(hash)).
     */
    @Test
    void findAllAndCreateFromNames_normalizesLikeLabelSetName_forSnakeCasedName() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        Label existing = new Label();
        existing.setName("pump station"); // stored canonical form -> "PUMP_STATION"
        assertEquals("PUMP_STATION", existing.getName());
        when(repo.findAllByNameIn(Set.of("PUMP_STATION"))).thenReturn(Set.of(existing));

        List<Label> result = service.findAllAndCreateFromNames(List.of("Pump Station"));

        assertEquals(1, result.size());
        assertEquals("PUMP_STATION", result.get(0).getName());
        verify(repo, never()).saveAll(any()); // reused, not re-inserted
    }

    @Test
    void delete_byId_deletesUnusedLabel() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        Label label = new Label();
        label.setName("PUMP");
        label.setId(5L);
        when(repo.findAllByIdInFetchNodes(Set.of(5L))).thenReturn(List.of(label));

        DataWrapper<IdCollection> form = new DataWrapper<>();
        form.getItems().add(IdCollection.createFromId(5L));
        service.delete(form);

        verify(repo).deleteAllByIdIn(List.of(5L));
    }

    /**
     * Regression: {@link Label#setName} stores the hash of the name canonicalised with
     * {@code toSnakeUpperCased}, but {@link IdCollection#getExternalId()} lower-cases. Deleting by
     * external id must hash through the SAME canonicaliser so the computed hash matches the stored
     * one — otherwise the lookup finds nothing and the delete silently no-ops.
     */
    @Test
    void delete_byExternalId_hashesCanonicalUpperCasedNameToMatchStoredHash() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        Label stored = new Label();
        stored.setName("SDK_PROBE_LABEL"); // stored.getHash() = xx3(canonical upper-cased name)
        stored.setId(7L);
        when(repo.findAllByHashInFetchNodes(any())).thenReturn(List.of(stored));

        // Delete by external id in the WRONG case — must still resolve to the stored hash.
        DataWrapper<IdCollection> form = new DataWrapper<>();
        form.getItems().add(IdCollection.createFromExternalId("sdk_probe_label"));
        service.delete(form);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> hashes = ArgumentCaptor.forClass(Collection.class);
        verify(repo).findAllByHashInFetchNodes(hashes.capture());
        assertTrue(hashes.getValue().contains(stored.getHash()),
                "delete must hash the canonical upper-cased name so it matches Label.hash");
        verify(repo).deleteAllByIdIn(List.of(7L));
    }

    @Test
    void delete_inUseLabel_throwsAndDeletesNothing() {
        LabelRepository repo = mock(LabelRepository.class);
        LabelService service = newService(repo);

        Label label = new Label();
        label.setName("PIPE");
        label.setId(9L);
        var node = new ResourceEntity();
        node.setId(10L);
        node.setExternalId("some_resource");
        label.getNodes().add(node); // still referenced -> not deletable
        when(repo.findAllByIdInFetchNodes(Set.of(9L))).thenReturn(List.of(label));

        DataWrapper<IdCollection> form = new DataWrapper<>();
        form.getItems().add(IdCollection.createFromId(9L));

        assertThrows(EntityInUseException.class, () -> service.delete(form));
        verify(repo, never()).deleteAllByIdIn(any());
    }

    // --- resolveLabelUpdate: the immutable type-label guard ------------------------------------

    /** The resolved names as a set, so assertions don't depend on ordering. */
    private static Set<String> namesOf(Optional<List<String>> result) {
        return Set.copyOf(result.orElseThrow());
    }

    /** A dataset node (requires the DATASET type-label) with the given current labels. */
    private static DatasetEntity dataset(String labelsCsv) {
        var node = new DatasetEntity();
        node.setLabels(labelsCsv);
        return node;
    }

    /** A plain resource node (no type-label) with the given current labels. */
    private static ResourceEntity resource(String labelsCsv) {
        var node = new ResourceEntity();
        node.setLabels(labelsCsv);
        return node;
    }

    private static UpdateListField set(Collection<String> values) {
        return new UpdateListField().set(values);
    }

    private static UpdateListField add(Collection<String> values) {
        return new UpdateListField().add(values);
    }

    private static UpdateListField remove(Collection<String> values) {
        return new UpdateListField().remove(values);
    }

    @Test
    void resolveLabelUpdate_noChangeRequested_returnsEmpty() {
        LabelService service = newService(mock(LabelRepository.class));
        assertTrue(service.resolveLabelUpdate(dataset("DATASET,CHEMICALS"), null).isEmpty());
        assertTrue(service.resolveLabelUpdate(dataset("DATASET"), new UpdateListField()).isEmpty());
        assertTrue(service.resolveLabelUpdate(dataset("DATASET"), set(List.of())).isEmpty());
    }

    @Test
    void resolveLabelUpdate_setCombinedWithAddOrRemove_isRejected() {
        LabelService service = newService(mock(LabelRepository.class));
        assertThrows(InvalidResourceException.class, () -> service.resolveLabelUpdate(
                dataset("DATASET"), new UpdateListField().set(List.of("DATASET", "ALPHA")).add(List.of("BETA"))));
        assertThrows(InvalidResourceException.class, () -> service.resolveLabelUpdate(
                dataset("DATASET"), new UpdateListField().set(List.of("DATASET", "ALPHA")).remove(List.of("ALPHA"))));
    }

    @Test
    void resolveLabelUpdate_setKeepsRequiredTypeAndPassesNonTypeLabelsThrough() {
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(dataset("DATASET,OLD"), set(List.of("DATASET", "CHEMICALS")));
        assertEquals(Set.of("DATASET", "CHEMICALS"), namesOf(out));
    }

    @Test
    void resolveLabelUpdate_setWithoutTypeLabel_reAddsRequiredType() {
        // A caller (or a drifted node) that omits DATASET still ends up with it.
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(dataset("DATASET"), set(List.of("CHEMICALS")));
        assertEquals(Set.of("DATASET", "CHEMICALS"), namesOf(out));
    }

    @Test
    void resolveLabelUpdate_removeOfTypeLabel_isIgnored() {
        // remove works from the node's current labels; DATASET can't be dropped.
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(dataset("DATASET,CHEMICALS"), remove(List.of("DATASET")));
        assertEquals(Set.of("DATASET", "CHEMICALS"), namesOf(out));
    }

    @Test
    void resolveLabelUpdate_addingADifferentTypeLabel_isStripped() {
        // Trying to turn a dataset into an asset by adding ASSET must not stick.
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(dataset("DATASET"), add(List.of("ASSET", "CHEMICALS")));
        assertEquals(Set.of("DATASET", "CHEMICALS"), namesOf(out));
    }

    @Test
    void resolveLabelUpdate_setWithForeignTypeLabel_replacesItWithRequiredType() {
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(dataset("DATASET"), set(List.of("ASSET", "POLICY", "CHEMICALS")));
        assertEquals(Set.of("DATASET", "CHEMICALS"), namesOf(out));
    }

    @Test
    void resolveLabelUpdate_plainResourceNeverGainsATypeLabel() {
        // A plain resource has no required type-label: any type-label the caller sends is stripped.
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(resource("PIPE"), set(List.of("ASSET", "PIPE", "SENSOR")));
        assertEquals(Set.of("PIPE", "SENSOR"), namesOf(out));
    }

    @Test
    void resolveLabelUpdate_typeLabelStrippingIsCaseInsensitive() {
        LabelService service = newService(mock(LabelRepository.class));
        var out = service.resolveLabelUpdate(dataset("DATASET"), set(List.of("dataset", "asset", "chemicals")));
        // lowercase type-labels are stripped; the canonical DATASET is forced back.
        assertEquals(Set.of("DATASET", "chemicals"), namesOf(out));
    }
}
