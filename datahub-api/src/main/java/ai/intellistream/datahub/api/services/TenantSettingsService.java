// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.tenant.TenantLlmWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and changes the calling tenant's own settings.
 *
 * <p>Validation is the point of this class. The settings land in Vault, which will accept any
 * string, and are then read by a different process that has no way to complain — a tenant that
 * saves a model name with a typo does not find out here, it finds out when its assistant stops
 * answering. So everything that can be checked is checked before the write.
 *
 * <p>Access is <strong>not</strong> checked here; the controller does that. Kept there because
 * this class holds the platform's Vault credential and a service that both authorises and acts is
 * one refactor away from being called from somewhere that skipped the check.
 */
@Slf4j
@Service
public class TenantSettingsService {

    private final TenantConfigService tenantConfigService;
    private final TenantLlmWriter llmWriter;

    public TenantSettingsService(TenantConfigService tenantConfigService, TenantLlmWriter llmWriter) {
        this.tenantConfigService = tenantConfigService;
        this.llmWriter = llmWriter;
    }

    /** Never includes the credential — see {@link TenantLlmSettings#apiKeySet()}. */
    public TenantLlmSettings readLlm() {
        TenantLlm llm = currentTenant().getLlm();
        if (llm == null) {
            return TenantLlmSettings.none();
        }
        return new TenantLlmSettings(
                llm.getProvider() == null ? null : llm.getProvider().wireName(),
                llm.getModel(),
                llm.getBaseUrl(),
                llm.getReasoningEffort(),
                llm.getEffort(),
                llm.getTurnTimeout(),
                llm.getMaxOutputTokensValue(),
                llm.getMaxIterationsValue(),
                llm.getInstructions(),
                llm.getApiKey() != null && !llm.getApiKey().isBlank(),
                llm.isUsable());
    }

    /**
     * Validates and writes, then reloads the tenant registry so this instance answers with what it
     * just stored rather than what it had cached.
     *
     * <p>Other processes — the console, other api instances — keep their own caches and pick the
     * change up on their own refresh, within five minutes. Nothing here can shorten that, so the UI
     * says so rather than implying the change is live everywhere the moment it is saved.
     */
    public TenantLlmSettings updateLlm(TenantLlmSettingsForm form) {
        Tenant tenant = currentTenant();
        TenantLlm existing = tenant.getLlm();
        Map<String, String> section = validated(form, existing);

        llmWriter.writeLlmSection(tenant.getOrganizationName(), section);
        tenantConfigService.refreshCache();
        return readLlm();
    }

    /**
     * The {@code llm.*} section this form means, or a 400 naming every field that is wrong.
     *
     * <p>All problems are collected rather than thrown on the first, so a half-filled form comes
     * back marked up once instead of one field at a time.
     */
    private Map<String, String> validated(TenantLlmSettingsForm form, TenantLlm existing) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        errors.setError(new BadRequestError());
        boolean[] failed = {false};

        LlmProvider provider = null;
        String rawProvider = trimmed(form.provider());
        if (rawProvider == null) {
            failed[0] = true;
            errors.getError().addFieldError("provider", "A provider is required: "
                    + String.join(" or ", TenantLlmSettings.PROVIDERS));
        } else {
            try {
                provider = LlmProvider.parse(rawProvider);
            } catch (RuntimeException e) {
                failed[0] = true;
                errors.getError().addFieldError("provider", "Unknown provider '" + rawProvider
                        + "'. Use " + String.join(" or ", TenantLlmSettings.PROVIDERS) + ".");
            }
        }

        String model = trimmed(form.model());
        if (model == null) {
            failed[0] = true;
            errors.getError().addFieldError("model", "A model name is required.");
        }

        // Three-valued: absent keeps what is stored, empty clears it, a value replaces it.
        String apiKey = form.apiKey() == null ? keyOf(existing) : trimmed(form.apiKey());
        String baseUrl = trimmed(form.baseUrl());

        if (provider == LlmProvider.ANTHROPIC && apiKey == null) {
            failed[0] = true;
            errors.getError().addFieldError("apiKey", "Anthropic needs an API key.");
        }
        if (provider == LlmProvider.OPENAI_COMPATIBLE && baseUrl == null) {
            failed[0] = true;
            errors.getError().addFieldError("baseUrl",
                    "An OpenAI-compatible provider needs a base URL, e.g. http://localhost:11434/v1");
        }

        String effort = trimmed(form.effort());
        if (effort != null && !TenantLlmSettings.EFFORT_LEVELS.contains(effort.toLowerCase())) {
            failed[0] = true;
            errors.getError().addFieldError("effort", "Unknown effort level '" + effort + "'. Use one of "
                    + String.join(", ", TenantLlmSettings.EFFORT_LEVELS) + ".");
        }

        String turnTimeout = trimmed(form.turnTimeout());
        if (turnTimeout != null) {
            try {
                DurationStyle.detectAndParse(turnTimeout);
            } catch (IllegalArgumentException e) {
                failed[0] = true;
                errors.getError().addFieldError("turnTimeout",
                        "Not a duration: '" + turnTimeout + "'. Try 10m, 90s or PT10M.");
            }
        }

        if (form.maxOutputTokens() != null && form.maxOutputTokens() < 1) {
            failed[0] = true;
            errors.getError().addFieldError("maxOutputTokens", "Must be a positive number, or left empty.");
        }
        if (form.maxIterations() != null && form.maxIterations() < 1) {
            failed[0] = true;
            errors.getError().addFieldError("maxIterations", "Must be a positive number, or left empty.");
        }

        if (failed[0]) {
            throw new BadRequestException(errors);
        }

        Map<String, String> section = new LinkedHashMap<>();
        section.put("provider", provider.wireName());
        section.put("model", model);
        section.put("api-key", apiKey);
        section.put("base-url", baseUrl);
        section.put("reasoning-effort", trimmed(form.reasoningEffort()));
        section.put("effort", effort == null ? null : effort.toLowerCase());
        section.put("turn-timeout", turnTimeout);
        section.put("max-output-tokens", asString(form.maxOutputTokens()));
        section.put("max-iterations", asString(form.maxIterations()));
        section.put("instructions", trimmed(form.instructions()));
        return section;
    }

    private static String keyOf(TenantLlm existing) {
        return existing == null ? null : trimmed(existing.getApiKey());
    }

    private static String asString(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private Tenant currentTenant() {
        Tenant tenant = tenantConfigService.getConfig(TenantContext.getTenantId());
        if (tenant == null) {
            throw new IllegalStateException("No tenant configuration for " + TenantContext.getTenantId());
        }
        return tenant;
    }
}
