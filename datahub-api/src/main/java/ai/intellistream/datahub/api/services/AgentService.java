// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.agent.AgentEffort;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
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
 * <h3>Who may read and who may write</h3>
 * Writing needs {@code /settings/write} in the caller's organization; listing them needs
 * {@code /settings/read}. Both are configuration powers and neither follows from a data grant —
 * see {@code SettingsGrants} for why this stopped being an all-datasets write grant.
 *
 * <p><strong>Fetching one agent by name is deliberately not gated.</strong> That is the call the
 * console makes on every turn to find out what it is running, so requiring
 * {@code /settings/read} for it would mean granting the settings group to every person who uses
 * the assistant — which would leave the group naming a power nobody was actually restricted from.
 * Reading an agent in order to run it is not a settings read. Nothing in the definition is secret:
 * the credential is a tenant-level Vault value and never appears in it.
 */
@Slf4j
@Service
public class AgentService {

    private final AgentRepository repository;
    private final ToolCatalog toolCatalog;
    private final SettingsSecurity settingsSecurity;

    public AgentService(AgentRepository repository, ToolCatalog toolCatalog,
                        SettingsSecurity settingsSecurity) {
        this.repository = repository;
        this.toolCatalog = toolCatalog;
        this.settingsSecurity = settingsSecurity;
    }

    /** The management view: every agent, enabled or not. Needs {@code /settings/read}. */
    @Transactional(readOnly = true)
    public List<AgentDefinition> list() {
        settingsSecurity.assertCanReadSettings();
        return repository.findAllByOrderByExternalIdAsc().stream().map(AgentService::toDefinition).toList();
    }

    /**
     * One agent by name. Not gated — see the class javadoc: this is how anything about to run an
     * agent learns what to run.
     */
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
        settingsSecurity.assertCanWriteSettings();
        validate(externalId, submitted);

        AgentEntity entity = repository.findByExternalId(externalId).orElseGet(() -> {
            AgentEntity fresh = new AgentEntity();
            fresh.setExternalId(externalId);
            return fresh;
        });

        entity.setDisplayName(submitted.displayName());
        entity.setInstructions(blankToNull(submitted.instructions()));
        entity.setToolAllowlist(new ArrayList<>(submitted.toolAllowlist()));
        entity.setDefaultEffort(blankToNull(submitted.defaultEffort()));
        entity.setMaxOutputTokens(submitted.maxOutputTokens());
        entity.setMaxIterations(submitted.maxIterations());
        entity.setEnabled(submitted.enabled());

        AgentEntity saved = repository.save(entity);
        // Worth a line: this changes what a model may reach, for everyone in the tenant, until
        // someone changes it back.
        log.info("Agent {} saved with {} tools, enabled {}", externalId,
                saved.getToolAllowlist().size(), saved.isEnabled());
        return toDefinition(saved);
    }

    @Transactional
    public void delete(String externalId) {
        settingsSecurity.assertCanWriteSettings();
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
                entity.getInstructions(),
                entity.getToolAllowlist(),
                entity.getDefaultEffort(),
                entity.getMaxOutputTokens(),
                entity.getMaxIterations(),
                entity.isEnabled());
    }
}
