// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.agent.AgentEffort;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.mcp.ToolCatalog;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.jpa.domains.AgentEntity;
import ai.intellistream.datahub.repositories.agent.AgentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The tenant's agent definitions: read by anything about to run one, written by whoever curates
 * them.
 *
 * <h3>Why writes are validated here and not only in the database</h3>
 * An agent's tool allowlist is the explicit half of what it may do, and it is a list of strings.
 * A string that names no tool is indistinguishable, at run time, from a tool the caller happens
 * not to have access to — both simply do not appear. So a typo would present as an assistant that
 * quietly cannot do something, with nothing anywhere saying why. Rejecting the name on write is
 * the only point at which the mistake is still attributable.
 *
 * <h3>Why an allowlist may not name a mutating tool</h3>
 * The assistant is read-only by design, and that promise is worth more than the flexibility of
 * letting a tenant opt out of it by editing a row. Widening it is a deliberate change to this
 * class, reviewable in one place, rather than a configuration a customer can talk themselves
 * into. The catalogue already knows which tools those are.
 *
 * <h3>Who may write</h3>
 * The same grant that lets a caller manage datasets — an all-datasets write grant, or
 * {@code DATAHUB_ADMIN}. An agent definition is a tenant-wide governance object in the same sense
 * a dataset is: it decides what an assistant may reach across the whole tenant, so it is not
 * something a per-dataset grant should confer.
 */
@Slf4j
@Service
public class AgentService {

    private final AgentRepository repository;
    private final ToolCatalog toolCatalog;
    private final DataSecurity dataSecurity;

    public AgentService(AgentRepository repository, ToolCatalog toolCatalog, DataSecurity dataSecurity) {
        this.repository = repository;
        this.toolCatalog = toolCatalog;
        this.dataSecurity = dataSecurity;
    }

    @Transactional(readOnly = true)
    public List<AgentDefinition> list() {
        return repository.findAllByOrderByExternalIdAsc().stream().map(AgentService::toDefinition).toList();
    }

    @Transactional(readOnly = true)
    public AgentDefinition get(String externalId) {
        return repository.findByExternalId(externalId)
                .map(AgentService::toDefinition)
                .orElseThrow(() -> new ObjectNotFoundException("Agent not found: " + externalId));
    }

    /**
     * Create or replace the agent named by {@code externalId}. An upsert rather than separate
     * create and update paths: the name is the identity, callers are configuring a known agent far
     * more often than inventing one, and a PUT that has to be preceded by an existence check is a
     * race waiting to be lost.
     */
    @Transactional
    public AgentDefinition save(String externalId, AgentDefinition submitted) {
        dataSecurity.assertCanManageDataSets();
        validate(externalId, submitted);

        AgentEntity entity = repository.findByExternalId(externalId).orElseGet(() -> {
            AgentEntity fresh = new AgentEntity();
            fresh.setExternalId(externalId);
            return fresh;
        });

        entity.setDisplayName(submitted.displayName());
        entity.setBackendRef(blankToNull(submitted.backendRef()));
        entity.setInstructions(blankToNull(submitted.instructions()));
        entity.setToolAllowlist(new ArrayList<>(submitted.toolAllowlist()));
        entity.setDefaultEffort(blankToNull(submitted.defaultEffort()));
        entity.setMaxOutputTokens(submitted.maxOutputTokens());
        entity.setMaxIterations(submitted.maxIterations());
        entity.setEnabled(submitted.enabled());

        AgentEntity saved = repository.save(entity);
        // Worth a line: this changes what a model may reach, for everyone in the tenant, until
        // someone changes it back.
        log.info("Agent {} saved with {} tools, backend {}, enabled {}", externalId,
                saved.getToolAllowlist().size(), saved.getBackendRef(), saved.isEnabled());
        return toDefinition(saved);
    }

    @Transactional
    public void delete(String externalId) {
        dataSecurity.assertCanManageDataSets();
        AgentEntity entity = repository.findByExternalId(externalId)
                .orElseThrow(() -> new ObjectNotFoundException("Agent not found: " + externalId));
        repository.delete(entity);
        log.info("Agent {} deleted", externalId);
    }

    private void validate(String externalId, AgentDefinition submitted) {
        if (submitted.displayName() == null || submitted.displayName().isBlank()) {
            throw badRequest("An agent needs a display name.", externalId);
        }

        List<String> unknown = submitted.toolAllowlist().stream()
                .filter(name -> !toolCatalog.isKnown(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw badRequest("No such tool: " + String.join(", ", unknown)
                    + ". See GET /agents/tools for the tools this platform serves.", externalId);
        }

        List<String> mutating = submitted.toolAllowlist().stream()
                .filter(name -> !toolCatalog.isReadOnly(name))
                .toList();
        if (!mutating.isEmpty()) {
            throw badRequest("An agent may only be given read-only tools; these write: "
                    + String.join(", ", mutating) + ".", externalId);
        }

        if (submitted.defaultEffort() != null && !submitted.defaultEffort().isBlank()) {
            try {
                AgentEffort.parse(submitted.defaultEffort());
            } catch (IllegalArgumentException e) {
                throw badRequest("Not an effort level: " + submitted.defaultEffort()
                        + ". Expected one of low, medium, high, xhigh, max.", externalId);
            }
        }

        if (submitted.maxOutputTokens() != null && submitted.maxOutputTokens() < 1) {
            throw badRequest("maxOutputTokens must be positive, or absent to let effort decide.",
                    externalId);
        }
        if (submitted.maxIterations() != null && submitted.maxIterations() < 1) {
            // Zero would define an agent that is offered, accepts a question, and can never look
            // anything up — a confusing way to spell `enabled: false`.
            throw badRequest("maxIterations must be at least 1.", externalId);
        }
    }

    private static BadRequestException badRequest(String message, String externalId) {
        return new BadRequestException(BadRequestError.createError(message, externalId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static AgentDefinition toDefinition(AgentEntity entity) {
        return new AgentDefinition(
                entity.getExternalId(),
                entity.getDisplayName(),
                entity.getBackendRef(),
                entity.getInstructions(),
                entity.getToolAllowlist(),
                entity.getDefaultEffort(),
                entity.getMaxOutputTokens(),
                entity.getMaxIterations(),
                entity.isEnabled());
    }
}
