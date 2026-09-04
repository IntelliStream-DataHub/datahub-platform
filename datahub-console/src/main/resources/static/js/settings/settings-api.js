/*
 * Talking to datahub-api from the settings pages.
 *
 * Straight to the api with a bearer token from the console's same-origin /token endpoint — no
 * console relay, per CONSTRAINTS.md. The base URL comes from <meta name="datahub-api-url">, the
 * same tag the resource and analyze pages read.
 *
 * The token is short-lived and this page is one a user leaves open: a 401 is retried once with a
 * freshly fetched token before it is reported, so an idle tab does not fail the first save.
 */
(function () {
	"use strict";

	function apiBase() {
		var meta = document.querySelector('meta[name="datahub-api-url"]');
		return (meta && meta.getAttribute("content")) || "http://localhost:8081";
	}

	var cachedToken = null;

	function token(forceRefresh) {
		if (cachedToken && !forceRefresh) {
			return Promise.resolve(cachedToken);
		}
		return fetch("/token", { credentials: "same-origin", headers: { Accept: "text/plain" } })
			.then(function (response) {
				if (!response.ok) {
					throw new ApiError(response.status, null);
				}
				return response.text();
			})
			.then(function (value) {
				cachedToken = value.trim();
				return cachedToken;
			});
	}

	/** Carries the status so callers can tell 403 (no grant) from 400 (bad field) from the rest. */
	function ApiError(status, body) {
		this.status = status;
		this.body = body;
	}
	ApiError.prototype = Object.create(Error.prototype);

	function call(method, path, payload, isRetry) {
		return token(isRetry === true).then(function (bearer) {
			var options = {
				method: method,
				headers: {
					Accept: "application/json",
					Authorization: "Bearer " + bearer
				}
			};
			if (payload !== undefined && payload !== null) {
				options.headers["Content-Type"] = "application/json";
				options.body = JSON.stringify(payload);
			}
			return fetch(apiBase() + path, options).then(function (response) {
				if (response.status === 401 && !isRetry) {
					cachedToken = null;
					return call(method, path, payload, true);
				}
				if (response.status === 204) {
					return null;
				}
				return response.text().then(function (text) {
					var parsed = null;
					if (text) {
						try {
							parsed = JSON.parse(text);
						} catch (e) {
							parsed = null;
						}
					}
					if (!response.ok) {
						throw new ApiError(response.status, parsed);
					}
					return parsed;
				});
			});
		});
	}

	window.SettingsApi = {
		get: function (path) { return call("GET", path, null, false); },
		put: function (path, payload) { return call("PUT", path, payload, false); },
		ApiError: ApiError
	};
})();
