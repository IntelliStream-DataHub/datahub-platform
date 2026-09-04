/*
 * The AI assistant settings form.
 *
 * Loads GET /tenant/settings/llm and PUT-s it back. Two things here are not obvious:
 *
 *  - The API key is write-only. The server never returns it, only whether one is stored, so the
 *    field starts empty with a help line saying a key is on file. Leaving it empty omits `apiKey`
 *    from the payload entirely, which the API reads as "keep what you have". That is why the
 *    payload is built by omission rather than by sending every field.
 *
 *  - Clearing a key is a separate action, because "empty" already means "unchanged". The Clear
 *    button arms an explicit empty string for the next save.
 */
(function () {
	"use strict";

	var form = document.querySelector('[data-type="ai-settings-form"]');
	if (!form) {
		return;
	}

	var loading = document.querySelector('[data-type="settings-loading"]');
	var denied = document.querySelector('[data-type="settings-denied"]');
	var readOnlyBanner = document.querySelector('[data-type="settings-readonly"]');
	var unconfiguredBanner = document.querySelector('[data-type="settings-unconfigured"]');
	var apiKeyHelp = document.querySelector('[data-type="apikey-help"]');
	var clearKeyButton = document.querySelector('[data-type="clear-api-key"]');
	var saveButton = document.querySelector('[data-type="save"]');
	var providerSelect = form.querySelector('[name="provider"]');

	// Set when the user asks to remove the stored key, so the next save sends "" rather than
	// omitting the field. Reset after a successful save.
	var clearApiKey = false;

	// Whether the server says a credential is stored. The key itself is never sent here, so this
	// is the only way to know an empty key field still amounts to a configured provider.
	var apiKeyOnFile = false;

	/** Nothing on this page applies, so the whole column goes and the message stands alone. */
	function showDenied() {
		loading.hidden = true;
		var page = document.querySelector(".settings-page");
		if (page) {
			page.hidden = true;
		}
		if (denied) {
			denied.hidden = false;
		}
	}

	function field(name) {
		return form.querySelector('[name="' + name + '"]');
	}

	function setValue(name, value) {
		var input = field(name);
		if (input) {
			input.value = value === null || value === undefined ? "" : value;
		}
	}

	function trimmedValue(name) {
		var input = field(name);
		return input && input.value.trim() !== "" ? input.value.trim() : null;
	}

	function numberValue(name) {
		var raw = trimmedValue(name);
		if (raw === null) {
			return null;
		}
		var parsed = parseInt(raw, 10);
		return isNaN(parsed) ? null : parsed;
	}

	/** Only the fields the chosen provider actually uses are shown; the rest would be noise. */
	function applyProviderVisibility() {
		var chosen = providerSelect.value;
		form.querySelectorAll("[data-when-provider]").forEach(function (block) {
			block.hidden = block.getAttribute("data-when-provider") !== chosen;
		});
	}

	function clearFieldErrors() {
		form.querySelectorAll("[data-error-for]").forEach(function (element) {
			element.hidden = true;
			element.textContent = "";
		});
	}

	function showFieldErrors(fields) {
		(fields || []).forEach(function (entry) {
			Object.keys(entry).forEach(function (name) {
				var element = form.querySelector('[data-error-for="' + name + '"]');
				if (element) {
					element.textContent = entry[name];
					element.hidden = false;
				} else {
					// A field the form does not render still has to reach the user somehow.
					Flash.error(name + ": " + entry[name]);
				}
			});
		});
	}

	function renderApiKeyHelp(stored) {
		if (!apiKeyHelp) {
			return;
		}
		if (clearApiKey) {
			apiKeyHelp.textContent = $L("settings.ai.apikey.cleared");
		} else if (stored) {
			apiKeyHelp.textContent = $L("settings.ai.apikey.stored");
		} else {
			apiKeyHelp.textContent = $L("settings.ai.apikey.none");
		}
	}

	function render(settings) {
		setValue("provider", settings.provider || "anthropic");
		setValue("model", settings.model);
		setValue("baseUrl", settings.baseUrl);
		setValue("reasoningEffort", settings.reasoningEffort);
		setValue("effort", settings.effort || "");
		setValue("maxOutputTokens", settings.maxOutputTokens);
		setValue("maxIterations", settings.maxIterations);
		setValue("turnTimeout", settings.turnTimeout);
		setValue("instructions", settings.instructions);
		setValue("apiKey", "");
		clearApiKey = false;
		apiKeyOnFile = settings.apiKeySet === true;
		renderApiKeyHelp(settings.apiKeySet);
		applyProviderVisibility();

		refreshUnconfiguredBanner();
	}

	/**
	 * Whether the fields as they stand name a model that can be called - the same rule the server
	 * applies, deliberately duplicated.
	 *
	 * The server also sends `configured`, but that is computed from its own cached copy of the
	 * tenant registry, which refreshes on a timer. Reading it left the banner able to contradict
	 * the fields printed directly beneath it: a complete, working configuration under a warning
	 * saying it was not one. Deriving the banner from what is actually on screen cannot disagree
	 * with what is on screen.
	 */
	function looksConfigured() {
		if (trimmedValue("model") === null) {
			return false;
		}
		if (providerSelect.value === "openai-compatible") {
			return trimmedValue("baseUrl") !== null;
		}
		// Anthropic. An empty key field means the stored one is kept, so a key on file counts.
		return trimmedValue("apiKey") !== null || (apiKeyOnFile && !clearApiKey);
	}

	function refreshUnconfiguredBanner() {
		if (unconfiguredBanner) {
			unconfiguredBanner.hidden = looksConfigured();
		}
	}

	function payload() {
		var body = {
			provider: trimmedValue("provider"),
			model: trimmedValue("model"),
			baseUrl: trimmedValue("baseUrl"),
			reasoningEffort: trimmedValue("reasoningEffort"),
			effort: trimmedValue("effort"),
			turnTimeout: trimmedValue("turnTimeout"),
			maxOutputTokens: numberValue("maxOutputTokens"),
			maxIterations: numberValue("maxIterations"),
			instructions: trimmedValue("instructions")
		};
		var typedKey = trimmedValue("apiKey");
		if (typedKey !== null) {
			body.apiKey = typedKey;
		} else if (clearApiKey) {
			body.apiKey = "";
		}
		// Otherwise apiKey is absent, which the API reads as "keep the stored one".
		return body;
	}

	function reportFailure(error, fallbackKey) {
		if (error && error.status === 403) {
			Flash.error($L("settings.denied"));
			return;
		}
		if (error && error.status === 401) {
			Flash.error($L("settings.session.expired"));
			return;
		}
		if (error && error.status === 400 && error.body && error.body.error) {
			showFieldErrors(error.body.error.fields);
			Flash.error(error.body.error.message || $L(fallbackKey));
			return;
		}
		Flash.error($L(fallbackKey));
	}

	function setEditable(canWrite) {
		if (canWrite) {
			return;
		}
		if (readOnlyBanner) {
			readOnlyBanner.hidden = false;
		}
		form.querySelectorAll("input, select, textarea, button").forEach(function (element) {
			element.disabled = true;
		});
	}

	form.addEventListener("submit", function (event) {
		event.preventDefault();
		clearFieldErrors();
		saveButton.disabled = true;
		SettingsApi.put("/tenant/settings/llm", payload())
			.then(function (saved) {
				render(saved);
				Flash.info($L("settings.saved"));
			})
			.catch(function (error) {
				reportFailure(error, "settings.save.failed");
			})
			.finally(function () {
				saveButton.disabled = false;
			});
	});

	// Live, so fixing what is missing clears the warning as you type rather than on the next save.
	providerSelect.addEventListener("change", function () {
		applyProviderVisibility();
		refreshUnconfiguredBanner();
	});
	form.addEventListener("input", refreshUnconfiguredBanner);

	if (clearKeyButton) {
		clearKeyButton.addEventListener("click", function () {
			clearApiKey = true;
			setValue("apiKey", "");
			renderApiKeyHelp(false);
			refreshUnconfiguredBanner();
		});
	}

	// Permissions are per scope, keyed by scope name, with wildcard grants already resolved by the
	// server. A caller with read but not write gets the form disabled rather than one that looks
	// editable and 403s on save.
	function llmPermission(permissions) {
		return (permissions && permissions.llm) || { read: false, write: false };
	}

	SettingsApi.get("/tenant/settings/permissions").then(function (permissions) {
		var llm = llmPermission(permissions);
		if (!llm.read) {
			// Asking for the settings would only 403. Say so from what we already know rather than
			// spending a round trip to be told.
			showDenied();
			return;
		}
		return SettingsApi.get("/tenant/settings/llm").then(function (settings) {
			render(settings);
			loading.hidden = true;
			form.hidden = false;
			setEditable(llm.write === true);
		});
	}).catch(function (error) {
		if (error && error.status === 403 && denied) {
			showDenied();
			return;
		}
		loading.hidden = true;
		reportFailure(error, "settings.load.failed");
	});
})();
