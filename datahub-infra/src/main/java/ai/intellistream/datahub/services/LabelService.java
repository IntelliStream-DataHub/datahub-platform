// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.errors.EntityInUseException;
import ai.intellistream.datahub.errors.FieldError;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.helpers.utils.ColorHelper;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Service class responsible for managing and processing operations related to {@link Label} entities.
 * Provides functionality to find, create, update, and list labels, including handling inputs of
 * label names and {@link LabelForm} objects for processing labels.
 * This class uses {@link LabelRepository} for interacting with the database.
 */
@Service
@Slf4j
public class LabelService {

    private static final int MAX_RACE_RETRIES = 3;



    private final LabelRepository labelRepository;

    private final Validator validator;

    private final TransactionTemplate requiresNewTx;

    public LabelService(
                        LabelRepository labelRepository,
                        Validator validator,
                        PlatformTransactionManager transactionManager) {
        this.labelRepository = labelRepository;
        this.validator = validator;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Find labels by name, creating any that don't yet exist.
     * <p>
     * Runs in a nested REQUIRES_NEW transaction with bounded retry. Two concurrent requests can
     * pass the "does PUMP exist?" check simultaneously and both try to INSERT; the loser hits the
     * UNIQUE(hash) constraint. A retry sees the winner's row and reuses it, so the caller's
     * outer transaction never sees the race.
     */
    public List<Label> findAllAndCreateFromNames(List<String> labelnames){
        int attempts = 0;
        while (true) {
            try {
                return requiresNewTx.execute(status -> doFindAllAndCreateFromNames(labelnames));
            } catch (DataIntegrityViolationException e) {
                attempts++;
                if (attempts >= MAX_RACE_RETRIES) {
                    log.error("Label creation exhausted {} retries for {}", MAX_RACE_RETRIES, labelnames);
                    throw e;
                }
                log.warn("Concurrent label creation race for {}; retry {}/{}",
                        labelnames, attempts, MAX_RACE_RETRIES);
            }
        }
    }

    private List<Label> doFindAllAndCreateFromNames(List<String> labelnames){
        // Label names are canonicalised on persist (Label.setName -> toSnakeUpperCased), but the name
        // lookup below matches exactly. Normalise the query keys with the SAME canonicaliser —
        // otherwise an existing label is never matched for a differently-cased/formatted input, so we
        // fall through to re-INSERT it and hit the UNIQUE(hash) constraint (surfaced as HTTP 409).
        // Reject invalid label names up-front. Label.name is @Size(2,512), and canonicalising can
        // shorten/empty a name (e.g. an all-digit "1" -> ""), so persisting it would throw deep in
        // Hibernate (a 500). Fail fast with InvalidResourceException instead — the resource
        // create/update paths turn that into a clean 400 that names the offending label.
        for (String raw : labelnames) {
            String canonical = TextValidator.toSnakeUpperCased(raw);
            if (canonical == null || canonical.length() < 2 || canonical.length() > 512) {
                var fieldError = new FieldError();
                fieldError.setField("labels");
                fieldError.setErrorMessage("Invalid label name '" + raw + "': labels must be 2-512 characters.");
                var error = new ResponseError<FieldError>();
                error.setError(fieldError);
                throw new InvalidResourceException(error);
            }
        }
        List<String> normalizedNames = labelnames.stream().map(TextValidator::toSnakeUpperCased).toList();
        Set<String> labelnamesSet = new HashSet<>(normalizedNames);
        Set<Label> existinglabels = labelRepository.findAllByNameIn(labelnamesSet);
        Set<String> existinglabelnames = existinglabels.stream().map(Label::getName).collect(Collectors.toSet());
        Set<String> notFoundLabels = new HashSet<>(normalizedNames);
        notFoundLabels.removeAll(existinglabelnames);
        if (notFoundLabels.isEmpty()) return existinglabels.stream().toList();

        List<Label> notfoundLabelEntities = notFoundLabels.stream().map(this::createFromName).toList();
        labelRepository.saveAll(notfoundLabelEntities);
        existinglabels.addAll(notfoundLabelEntities);
        return existinglabels.stream().toList();
    }

    private Label createFromName(String name){
        Label label = new Label();
        // setName() is the single canonicaliser (toSnakeUpperCased) and derives the hash from the
        // canonical name. Don't pre-transform or overwrite the hash — that's how the two-canonicaliser
        // drift (plain upper-case here vs toSnakeUpperCased in LabelForm) used to creep in.
        label.setName(name);
        label.setColor(ColorHelper.generateRandomBrightColor());
        return label;
    }

    /**
     * Find labels by id or name, creating any that don't yet exist.
     * <p>
     * Same concurrency shield as {@link #findAllAndCreateFromNames(List)}: two concurrent
     * callers creating the same label can both miss the existence check and both issue an
     * INSERT; the loser hits the UNIQUE(hash) constraint. REQUIRES_NEW + bounded retry lets
     * the loser re-read and return the winner's row without surfacing the violation.
     */
    public List<Label> findAllAndCreateMissing(List<LabelForm> labels){
        int attempts = 0;
        while (true) {
            try {
                return requiresNewTx.execute(status -> doFindAllAndCreateMissing(labels));
            } catch (DataIntegrityViolationException e) {
                attempts++;
                if (attempts >= MAX_RACE_RETRIES) {
                    log.error("Label creation exhausted {} retries for {}", MAX_RACE_RETRIES, labels);
                    throw e;
                }
                log.warn("Concurrent label creation race for {}; retry {}/{}",
                        labels, attempts, MAX_RACE_RETRIES);
            }
        }
    }

    private List<Label> doFindAllAndCreateMissing(List<LabelForm> labels){
        Set<Long> idCollection = labels.stream()
                .map(LabelForm::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Iterable<Label> labelList = labelRepository.findAllById(idCollection);

        Set<Long> hashes = labels.stream()
                .map(LabelForm::getName)
                .filter(Objects::nonNull)
                .map(LongHashFunction.xx3()::hashChars)
                .collect(Collectors.toSet());

        Iterable<Label> labelList2 = labelRepository.findAllByHashList(hashes);

        Set<Label> labelsFound =
                StreamSupport
                        .stream(labelList.spliterator(), false)
                        .collect(Collectors.toSet());
        labelsFound.addAll(
                StreamSupport
                        .stream(labelList2.spliterator(), false)
                        .collect(Collectors.toSet())
        );

        List<Label> unsavedLabels = new ArrayList<>();

        for(LabelForm f : labels){
            boolean labelExists = false;
            for(Label label : labelsFound){
                if(f.getName().equals(label.getName())){
                    labelExists = true;
                    break;
                }
            }
            if(!labelExists){
                unsavedLabels.add( createLabel(f) );
            }
        }

        Iterable<Label> savedLabels = labelRepository.saveAll(unsavedLabels);

        List<Label> labelCollection = new ArrayList<>(labelsFound);
        savedLabels.forEach(labelCollection::add);

        return labelCollection;
    }

    private Label createLabel(LabelForm form){
        Label label = new Label();
        bindLabelData(form, label);
        return label;
    }

    private void bindLabelData(LabelForm form, Label label){
        // PATCH semantics on update — only apply fields the caller sent. On create, the
        // form validator upstream requires name, so the non-null branch always runs.
        if(form.getName() != null) label.setName(form.getName());
        if(form.getDescription() != null) label.setDescription(form.getDescription());
        if(form.getI18nCode() != null) label.setI18nCode(form.getI18nCode());

        String color = form.getColor();
        if(color != null){
            // Explicit color provided — validate, else fall back to a fresh random.
            if(color.isBlank() || !ColorHelper.validateHTML(color)){
                color = ColorHelper.generateRandomBrightColor();
            }
            label.setColor(color);
        } else if(label.getColor() == null){
            // Create path with no color supplied — seed one so the UI always has something.
            label.setColor(ColorHelper.generateRandomBrightColor());
        }
    }

    private Label updateLabel(LabelForm form){
        // Callers usually identify a label by its name — the id is synthetic and unknown to them —
        // so accept either. Prefer id when present; otherwise resolve by name via the same canonical
        // hash the entity is stored under (LabelForm.setName has already canonicalised the name, so
        // hashing it directly matches Label.setName's hash of the canonical form). To *rename* a
        // label, identify it by id, since a name used as the lookup key can't also be the new name.
        Label label = (form.getId() != null)
                ? labelRepository.findById(form.getId()).orElse(null)
                : labelRepository.findByHash(LongHashFunction.xx3().hashChars(form.getName()));
        if(label != null){
            bindLabelData(form, label);
            return label;
        }
        // Returning null here let a null slip into saveAll(), which threw IllegalArgumentException
        // and surfaced as a bodyless 500. An unknown label is a not-found, so say so — the API's
        // ObjectNotFoundException handler turns it into a 404.
        throw new ObjectNotFoundException("Label not found: " + (form.getId() != null ? form.getId() : form.getName()));
    }

    public Collection<Label> list() {
        return labelRepository.findAll();
    }

    /**
     * Resolve a node's new label names for an update, enforcing the immutable type-label. Applies the
     * requested {@code set}/{@code add}/{@code remove} to the node's current labels, then strips every
     * type-label the caller sent and forces back exactly the type-label the node's type requires
     * ({@link TypeLabels#forEntity}) — so an update can never add, remove, or swap a node's type-label
     * (and a node whose stored labels drifted from its type is repaired). Non-type labels pass through
     * untouched.
     *
     * <p>{@code set} (a wholesale replace) and {@code add}/{@code remove} (a delta on the current
     * labels) are mutually exclusive — combining them is ambiguous and almost always a mistake, so a
     * request that supplies {@code set} together with {@code add} or {@code remove} is rejected.
     *
     * @param node   the node being updated (source of its current labels and required type-label)
     * @param update the requested label update (set/add/remove); may be null
     * @return the resulting label names, or empty if no label change was requested (in which case the
     *         caller must leave the node's labels untouched)
     * @throws InvalidResourceException if {@code set} is combined with {@code add}/{@code remove}
     */
    public Optional<List<String>> resolveLabelUpdate(NodeEntity node, UpdateListField update) {
        if (update == null) {
            return Optional.empty();
        }
        boolean hasSet = update.getSet() != null && !update.getSet().isEmpty();
        boolean hasAdd = update.getAdd() != null && !update.getAdd().isEmpty();
        boolean hasRemove = update.getRemove() != null && !update.getRemove().isEmpty();
        if (!hasSet && !hasAdd && !hasRemove) {
            return Optional.empty();
        }
        if (hasSet && (hasAdd || hasRemove)) {
            var fieldError = new FieldError();
            fieldError.setField("labels");
            fieldError.setErrorMessage("A label update must use either 'set' (replace) or "
                    + "'add'/'remove' (delta), not both.");
            var error = new ResponseError<FieldError>();
            error.setError(fieldError);
            throw new InvalidResourceException(error);
        }

        Set<String> labels;
        if (hasSet) {
            labels = new LinkedHashSet<>(update.getSet());
        } else {
            labels = new LinkedHashSet<>();
            if (node.getLabels() != null && !node.getLabels().isBlank()) {
                labels.addAll(Arrays.asList(node.getLabels().split(",")));
            }
            if (hasAdd) {
                labels.addAll(update.getAdd());
            }
            if (hasRemove) {
                labels.removeAll(update.getRemove());
            }
        }
        // Enforce the intrinsic type-label regardless of what the caller sent.
        labels.removeIf(TypeLabels::isTypeLabel);
        TypeLabels.forEntity(node).ifPresent(labels::add);
        return Optional.of(new ArrayList<>(labels));
    }

    @Transactional
    public void delete(DataWrapper<IdCollection> form) {
        Set<Long> idList = new HashSet<>();
        Set<Long> hashList = new HashSet<>();
        for (IdCollection r : form.getItems()) {
            if (r.getId() != null) {
                idList.add(r.getId());
            } else if (r.getExternalId() != null) {
                // Label.hash is xx3 of the name canonicalised with toSnakeUpperCased (via
                // Label/LabelForm.setName). getExternalId() lower-cases, so hash it through the SAME
                // canonicaliser — otherwise the hash never matches and the delete silently no-ops.
                hashList.add(LongHashFunction.xx3().hashChars(
                        TextValidator.toSnakeUpperCased(r.getExternalId())));
            }
        }

        // Load the candidate labels with their referencing nodes in a single fetch-join query each
        // (no N+1), so we can decide and report which are still in use.
        List<Label> labels = new ArrayList<>();
        if (!idList.isEmpty()) {
            labels.addAll(labelRepository.findAllByIdInFetchNodes(idList));
        }
        if (!hashList.isEmpty()) {
            labels.addAll(labelRepository.findAllByHashInFetchNodes(hashList));
        }

        // Split into blocked (still referenced) and deletable, collecting EVERY blocker so a batch
        // delete reports all of them at once rather than failing on the first.
        List<EntityInUseException.Blocked> blocked = new ArrayList<>();
        List<Long> deletableIds = new ArrayList<>();
        for (Label label : labels) {
            if (label.getNodes().isEmpty()) {
                deletableIds.add(label.getId());
            } else {
                List<Map<String, String>> usages = label.getNodes().stream()
                        .map(node -> Map.of("externalId", node.getExternalId(), "id", String.valueOf(node.getId())))
                        .collect(Collectors.toList());
                blocked.add(new EntityInUseException.Blocked("Label", label.getName(), usages));
            }
        }

        if (!blocked.isEmpty()) {
            // All-or-nothing: if anything is blocked, delete nothing and report every blocker.
            throw new EntityInUseException(blocked);
        }

        if (!deletableIds.isEmpty()) {
            labelRepository.deleteAllByIdIn(deletableIds);
        }
    }

    @Transactional
    public Collection<Label> createLabels(DataWrapper<LabelForm> form) {
        // Enforce bean validation here, not just at the controller: callers such as the MCP
        // label_create tool reach this method directly and would otherwise persist a blank/null
        // name (NPE in Label.setName, or a NOT NULL violation). @NotBlank on LabelForm.name is
        // cascaded via DataWrapper.items.
        Set<ConstraintViolation<DataWrapper<LabelForm>>> violations = validator.validate(form);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        Collection<Label> savedLabels = new ArrayList<>();
        for(LabelForm lf : form.getItems()) {
            Label label = createLabel(lf);
            savedLabels.add(label);
        }
        labelRepository.saveAll(savedLabels);
        return savedLabels;
    }

    @Transactional
    public Collection<Label> updateLabels(DataWrapper<LabelForm> form){
        Collection<Label> savedLabels = new ArrayList<>();
        for(LabelForm lf : form.getItems()){
            Label label = updateLabel(lf);
            savedLabels.add(label);
        }
        labelRepository.saveAll(savedLabels);
        return savedLabels;
    }

}
