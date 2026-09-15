// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import ai.intellistream.datahub.tenant.TenantFeatures;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

import java.util.Map;

/**
 * Your own tenant: which features are switched on, and the settings the organization administers
 * for itself.
 *
 * <p>Settings are granted per scope through Keycloak organization groups, and read and write are
 * separate grants: write does not imply read. {@link #settingsPermissions()} says which you hold
 * without having to call an endpoint and read the {@code 403}.
 */
public final class TenantService {

    private final ApiHttp http;
    private final JavaType features;    // TenantFeatures
    private final JavaType permissions; // Map<String, SettingsPermission>
    private final JavaType llmSettings; // TenantLlmSettings

    public TenantService(ApiHttp http) {
        this.http = http;
        TypeFactory tf = http.typeFactory();
        this.features = tf.constructType(TenantFeatures.class);
        this.permissions = tf.constructMapType(java.util.LinkedHashMap.class,
                String.class, SettingsPermission.class);
        this.llmSettings = tf.constructType(TenantLlmSettings.class);
    }

    /** GET /tenant/features — which optional features (files, and so on) this tenant may use. */
    public TenantFeatures features() {
        return http.get("/tenant/features", features);
    }

    /**
     * GET /tenant/settings/permissions — the read/write grants you hold, keyed by settings scope.
     *
     * <p>Deliberately ungated, so it answers for everyone: it is meant for deciding between an
     * editable form, a read-only one, and no form at all. It is not the security boundary, the
     * settings endpoints enforce the same grants regardless of what this says.
     */
    public Map<String, SettingsPermission> settingsPermissions() {
        return http.get("/tenant/settings/permissions", permissions);
    }

    /**
     * GET /tenant/settings/llm — the model the organization's assistant runs on.
     *
     * <p>The API key is never returned; {@code apiKeySet} says whether one is stored, and
     * {@code configured} whether this amounts to a model that can actually be called.
     */
    public TenantLlmSettings llmSettings() {
        return http.get("/tenant/settings/llm", llmSettings);
    }

    /**
     * PUT /tenant/settings/llm — replace the model configuration and get it back as stored.
     *
     * <p>{@code apiKey} is the exception to "replace": absent or blank leaves the stored credential
     * alone, and only a non-blank value overwrites it. That is what lets a form render the field
     * empty (the credential is never returned) and still be savable without retyping it.
     *
     * <p>Takes effect for the api immediately; other services cache the tenant registry and pick
     * the change up within five minutes.
     */
    public TenantLlmSettings updateLlmSettings(TenantLlmSettingsForm form) {
        return http.put("/tenant/settings/llm", form, llmSettings);
    }
}
