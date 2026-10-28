// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.api.services.TenantSettingsService;
import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.SettingsScopes;
import ai.intellistream.datahub.models.tenant.TenantFlowClientForm;
import ai.intellistream.datahub.models.tenant.TenantFlowMcpServerForm;
import ai.intellistream.datahub.models.tenant.TenantFlowSecretForm;
import ai.intellistream.datahub.models.tenant.TenantFlowSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmModelsQuery;
import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The settings a tenant administers for itself, as opposed to the entitlements an operator grants
 * it: which model its AI assistant runs on, and the credentials its flows use.
 *
 * <p>Lives in datahub-api rather than the console because the console is a frontend: it renders the
 * form and the browser calls this directly. That also keeps the Vault write credential in one
 * service instead of two.
 */
@RestController
@RequestMapping("/tenant/settings")
@Tag(name = "Tenant settings", description = """
        Settings your organization administers for itself, granted per scope: the
        /settings/<scope>/read and /settings/<scope>/write groups in your Keycloak
        organization, or /settings/*/read and /settings/*/write for every scope. Read and
        write are separate grants and write does not imply read.""")
public class TenantSettingsController {

    private final TenantSettingsService settingsService;
    private final SettingsSecurity settingsSecurity;

    public TenantSettingsController(TenantSettingsService settingsService,
                                    SettingsSecurity settingsSecurity) {
        this.settingsService = settingsService;
        this.settingsSecurity = settingsSecurity;
    }

    @Operation(summary = "What this caller may do with settings",
            description = """
                    What you may read and change, per settings scope. Wildcard grants are already
                    resolved, so every scope this platform knows is listed by name. Intended for
                    gating UI: a client should use this to decide between showing a form, showing
                    it read-only, or not showing it at all. It is not the security boundary — the
                    endpoints below enforce the same grants regardless.
                    """)
    @GetMapping("/permissions")
    public ResponseEntity<Map<String, SettingsPermission>> permissions() {
        // Deliberately ungated: answering "you may do nothing" is not a disclosure, and a client
        // that must call a 403-ing endpoint to discover it may not call it has learned nothing.
        return ResponseEntity.ok(settingsSecurity.grants().byScope());
    }

    @Operation(summary = "Get your organization's model configuration",
            description = """
                    The model your AI assistant runs on. The API key is never returned; `apiKeySet`
                    says whether one is stored. `configured` is whether this amounts to a model that
                    can actually be called — when it is false your organization has no assistant.
                    """)
    @GetMapping("/llm")
    public ResponseEntity<TenantLlmSettings> getLlm() {
        settingsSecurity.assertCanRead(SettingsScopes.LLM);
        return ResponseEntity.ok(settingsService.readLlm());
    }

    @Operation(summary = "Change your organization's model configuration",
            description = """
                    Replaces the model configuration and returns it as stored. `apiKey` is the one
                    exception: absent or empty leaves the stored credential untouched, and only a
                    non-blank value replaces it. The credential is never returned, so a form renders
                    that field empty and must be savable without retyping it.

                    Takes effect for this API immediately. Other services cache the tenant registry
                    and pick the change up within five minutes.
                    """)
    @PutMapping("/llm")
    public ResponseEntity<TenantLlmSettings> updateLlm(@RequestBody TenantLlmSettingsForm form) {
        settingsSecurity.assertCanWrite(SettingsScopes.LLM);
        return ResponseEntity.ok(settingsService.updateLlm(form));
    }

    @Operation(summary = "List the models an OpenAI-compatible server offers",
            description = """
                    Asks `<baseUrl>/models` and returns the model ids it lists, as suggestions for
                    the model field. Any name can still be saved: some hosts accept names they do
                    not list. A server this API cannot reach, or that answers with anything but a
                    model list, gives an empty list. `apiKey` absent or empty lists with the stored
                    key. Requires the same grant as changing the configuration.
                    """)
    @PostMapping("/llm/models")
    public ResponseEntity<List<String>> listLlmModels(@RequestBody TenantLlmModelsQuery query) {
        settingsSecurity.assertCanWrite(SettingsScopes.LLM);
        return ResponseEntity.ok(settingsService.listModels(query.baseUrl(), query.apiKey()));
    }

    @Operation(summary = "List your organization's flow credentials",
            description = """
                    The named secrets your flows may refer to, the registered clients a flow's
                    runs may act as instead of the person who deployed it, and the tool servers
                    your agents may call. Names, client ids, URLs and header names only: a
                    secret's value, a client's secret and a tool server's credential are never
                    returned.
                    """)
    @GetMapping("/flow")
    public ResponseEntity<TenantFlowSettings> getFlow() {
        settingsSecurity.assertCanRead(SettingsScopes.FLOW);
        return ResponseEntity.ok(settingsService.readFlow());
    }

    @Operation(summary = "Store a flow secret",
            description = """
                    Stores `value` under `name`, replacing any value it had. A name is lowercase letters, digits and dashes, starting with
                    a letter or digit, at most 63 characters. The value is never returned.

                    Removing or replacing a secret does not erase the old value from Vault's version
                    history, so rotate it at its source as well.
                    """)
    @PutMapping("/flow/secrets/{name}")
    public ResponseEntity<Void> putFlowSecret(@PathVariable String name,
                                              @RequestBody TenantFlowSecretForm form) {
        settingsSecurity.assertCanWrite(SettingsScopes.FLOW);
        settingsService.putFlowSecret(name, form);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a flow secret",
            description = """
                    Removes the secret. Removing a secret that is not there is not an error.
                    """)
    @DeleteMapping("/flow/secrets/{name}")
    public ResponseEntity<Void> removeFlowSecret(@PathVariable String name) {
        settingsSecurity.assertCanWrite(SettingsScopes.FLOW);
        settingsService.removeFlowSecret(name);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Register a client flows may run as",
            description = """
                    Registers a confidential Keycloak client under `name`, replacing it if the name
                    is taken. Its service-account user must belong to your organization, with the
                    groups the flows acting as it should have. Both `clientId` and `clientSecret`
                    are required every time, since the secret is never returned.

                    The credentials are not tried here. A deployment that names the client checks
                    them, and may only name it if the person deploying holds every group the client
                    holds.
                    """)
    @PutMapping("/flow/clients/{name}")
    public ResponseEntity<Void> putFlowClient(@PathVariable String name,
                                              @RequestBody TenantFlowClientForm form) {
        settingsSecurity.assertCanWrite(SettingsScopes.FLOW);
        settingsService.putFlowClient(name, form);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a registered client",
            description = """
                    Removes the client's registration. Removing a client that is not registered is
                    not an error. The client itself stays in
                    Keycloak; disable it or rotate its secret there.
                    """)
    @DeleteMapping("/flow/clients/{name}")
    public ResponseEntity<Void> removeFlowClient(@PathVariable String name) {
        settingsSecurity.assertCanWrite(SettingsScopes.FLOW);
        settingsService.removeFlowClient(name);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Register a tool server for agents",
            description = """
                    Registers an MCP server under `name`, replacing it if the name is taken: its
                    Streamable HTTP endpoint (`url`, https) and the one header its credential travels
                    in (`headerName`, `Authorization` when left out; `headerValue`, e.g. `Bearer …`).
                    An agent names its tools as `<name>__<tool>`; a name is lowercase letters, digits
                    and dashes, starting with a letter or digit, at most 24 characters.

                    The credential is never returned. Leave `headerValue` empty to keep the stored one
                    when changing the URL or the header; it is required for a new server. A URL that
                    resolves to a private or local address is refused unless the deployment allows
                    them, and so is plain http.
                    """)
    @PutMapping("/flow/mcp-servers/{name}")
    public ResponseEntity<Void> putFlowMcpServer(@PathVariable String name,
                                                 @RequestBody TenantFlowMcpServerForm form) {
        settingsSecurity.assertCanWrite(SettingsScopes.FLOW);
        settingsService.putFlowMcpServer(name, form);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove a tool server",
            description = """
                    Removes the registration, credential included. Removing a server that is not
                    registered is not an error. An agent whose tools still name the server fails its
                    next run until it is edited.
                    """)
    @DeleteMapping("/flow/mcp-servers/{name}")
    public ResponseEntity<Void> removeFlowMcpServer(@PathVariable String name) {
        settingsSecurity.assertCanWrite(SettingsScopes.FLOW);
        settingsService.removeFlowMcpServer(name);
        return ResponseEntity.noContent().build();
    }
}
