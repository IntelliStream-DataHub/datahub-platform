/*
 * The read-only organization page: what an operator has granted this tenant.
 *
 * Feature flags come from GET /tenant/features, which every signed-in caller may read — they are
 * not settings and need no /settings grant. Nothing here is editable, deliberately: it is the
 * visible half of the split between what the operator provisions and what the tenant sets.
 */
(function () {
	"use strict";

	var list = document.querySelector('[data-type="feature-list"]');
	if (!list) {
		return;
	}

	// Rendered in a fixed order rather than whatever order the JSON arrives in, so the page does
	// not reshuffle itself between loads.
	var FEATURES = ["files", "chat", "policy", "streaming"];

	function label(name, enabled) {
		var item = document.createElement("li");
		item.className = enabled ? "feature on" : "feature off";
		var icon = document.createElement("i");
		icon.className = enabled ? "fa-solid fa-check" : "fa-solid fa-minus";
		var text = document.createElement("span");
		text.textContent = name + " — " + $L(enabled ? "settings.feature.on" : "settings.feature.off");
		item.appendChild(icon);
		item.appendChild(text);
		return item;
	}

	SettingsApi.get("/tenant/features").then(function (features) {
		list.textContent = "";
		var seen = {};
		FEATURES.forEach(function (name) {
			seen[name] = true;
			list.appendChild(label(name, features[name] === true));
		});
		// Anything the api grew that this list has not caught up with still shows, rather than
		// silently going missing because the page is older than the platform.
		Object.keys(features).forEach(function (name) {
			if (!seen[name] && typeof features[name] === "boolean") {
				list.appendChild(label(name, features[name]));
			}
		});
	}).catch(function () {
		list.textContent = "";
		var item = document.createElement("li");
		item.textContent = $L("settings.load.failed");
		list.appendChild(item);
	});
})();
