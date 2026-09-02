// SPDX-License-Identifier: AGPL-3.0-or-later
/*
 * The tenant settings page: which model this tenant's agents run on.
 *
 * Talks to datahub-api directly with the signed-in user's bearer token — no console
 * backend-for-frontend, matching the Analyze tab and what CONSTRAINTS.md asks for. The console
 * only serves the template.
 *
 * The API key is write-only by design. The api never returns it, so this page can only report
 * whether one is stored and send a new one; leaving the field untouched leaves the stored key
 * alone, which is the whole reason the api treats an absent key differently from an empty one.
 */
(function () {
	"use strict";

	var cachedToken = null;

	function apiBase() {
		var m = document.querySelector('meta[name="datahub-api-url"]');
		return (m ? m.content : "").replace(/\/+$/, "");
	}

	function L(key, fallback) {
		try {
			var v = (typeof $L === "function") ? $L(key) : null;
			return (v && v !== key) ? v : fallback;
		} catch (e) {
			return fallback;
		}
	}

	function getToken(force) {
		if (cachedToken && !force) return Promise.resolve(cachedToken);
		return fetch("/token", { credentials: "same-origin" })
			.then(function (r) { return r.ok ? r.text() : Promise.reject("could not get access token"); })
			.then(function (t) { cachedToken = t.trim(); return cachedToken; });
	}

	// Retries once on a 401, since a token can expire between page load and save.
	function api(path, opts) {
		opts = opts || {};
		var send = function (token) {
			var headers = Object.assign(
				{ Accept: "application/json", Authorization: "Bearer " + token },
				opts.headers || {});
			return fetch(apiBase() + path, Object.assign({}, opts, { headers: headers }));
		};
		return getToken(false)
			.then(send)
			.then(function (r) { return (r.status === 401) ? getToken(true).then(send) : r; })
			.then(function (r) {
				if (!r.ok) {
					return r.text().then(function (b) {
						return Promise.reject({ status: r.status, body: b });
					});
				}
				return r.status === 204 ? null : r.json();
			});
	}

	function el(id) { return document.getElementById(id); }

	function show(id, visible) {
		var node = el(id);
		if (node) node.hidden = !visible;
	}

	function setMessage(text, isError) {
		var node = el("settings-message");
		if (!node) return;
		node.textContent = text || "";
		node.hidden = !text;
		node.classList.toggle("error", !!isError);
	}

	// Only the OpenAI-compatible path uses an endpoint and reasoning_effort; hiding them for
	// Anthropic keeps the form from asking for values that would be silently ignored.
	function syncProviderFields() {
		var openAi = el("llm-provider").value === "OPENAI_COMPATIBLE";
		var rows = document.querySelectorAll("[data-openai-only]");
		for (var i = 0; i < rows.length; i++) rows[i].hidden = !openAi;
	}

	function fill(config) {
		el("llm-provider").value = config.provider || "";
		el("llm-model").value = config.model || "";
		el("llm-base-url").value = config.baseUrl || "";
		el("llm-reasoning-effort").value = config.reasoningEffort || "";
		el("llm-turn-timeout").value = config.turnTimeout || "";
		el("llm-api-key").value = "";
		el("llm-api-key").placeholder = config.hasApiKey
			? L("settings.llm.key.stored", "A key is stored. Leave blank to keep it.")
			: L("settings.llm.key.none", "No key stored.");
		show("llm-clear-key-row", config.hasApiKey);
		show("settings-using-default", !config.configured);
		syncProviderFields();
	}

	function save(event) {
		event.preventDefault();
		setMessage("");
		var clearKey = el("llm-clear-key") && el("llm-clear-key").checked;
		var typedKey = el("llm-api-key").value;

		var body = {
			provider: el("llm-provider").value || null,
			model: el("llm-model").value || null,
			baseUrl: el("llm-base-url").value || null,
			reasoningEffort: el("llm-reasoning-effort").value || null,
			turnTimeout: el("llm-turn-timeout").value || null
		};
		// Three states, and the api distinguishes all three: absent leaves the stored key alone,
		// "" clears it, a value replaces it. Sending null for "leave it" is what lets someone edit
		// the model name without having to re-enter a credential they cannot see.
		if (clearKey) {
			body.apiKey = "";
		} else if (typedKey) {
			body.apiKey = typedKey;
		}

		el("settings-save").disabled = true;
		api("/tenant/llm", {
			method: "PUT",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body)
		}).then(function (saved) {
			fill(saved);
			setMessage(L("settings.saved", "Saved."), false);
		}).catch(function (err) {
			setMessage(describe(err), true);
		}).finally(function () {
			el("settings-save").disabled = false;
		});
	}

	function describe(err) {
		if (err && err.status === 403) {
			return L("settings.forbidden.write",
				"You need the /settings/write group in your organization to change this.");
		}
		var detail = (err && err.body) ? String(err.body).slice(0, 200) : "";
		return L("settings.error", "Could not save the configuration.") + (detail ? " " + detail : "");
	}

	function start() {
		if (!el("settings-llm-form")) return;

		el("llm-provider").addEventListener("change", syncProviderFields);
		el("settings-llm-form").addEventListener("submit", save);

		Promise.all([api("/tenant/permissions"), api("/tenant/llm")])
			.then(function (results) {
				var permissions = results[0];
				fill(results[1]);
				show("settings-llm-form", true);
				if (!permissions.canWriteSettings) {
					// Readable but not writable: show what is configured and disable the controls
					// rather than hiding the page, which would look like the feature is missing.
					var inputs = el("settings-llm-form").querySelectorAll("input, select, button");
					for (var i = 0; i < inputs.length; i++) inputs[i].disabled = true;
					setMessage(L("settings.readonly",
						"You can view this configuration but not change it."), false);
				}
			})
			.catch(function (err) {
				show("settings-llm-form", false);
				setMessage(err && err.status === 403
					? L("settings.forbidden.read",
						"You need the /settings/read group in your organization to see this.")
					: describe(err), true);
			});
	}

	if (document.readyState === "loading") {
		document.addEventListener("DOMContentLoaded", start);
	} else {
		start();
	}
})();
