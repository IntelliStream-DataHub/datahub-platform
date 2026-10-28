/*
 * The Flows section's Clients tab: the Keycloak clients a flow's runs may act as instead of the
 * person who deployed it.
 *
 * The clients are created in Keycloak out of band; this page only lists, registers and removes
 * their credentials, in datahub-api's tenant settings (GET /tenant/settings/flow and
 * PUT/DELETE /tenant/settings/flow/clients/{name}, gated by the flow settings grants). Two things
 * are not obvious:
 *
 *  - The client secret is never returned, so replacing a client means typing its secret again.
 *    The API refuses a registration without one.
 *
 *  - The credentials are not tried on save. They are tried when a flow is deployed to run as the
 *    client, which is also where the check that the deployer holds every group the client holds
 *    happens.
 */
(function(){
	"use strict";

	var root = document.querySelector('[data-type="flow-clients"]');
	if(!root) return;

	var page = document.querySelector('[data-type="clients-page"]');
	var loading = document.querySelector('[data-type="clients-loading"]');
	var denied = document.querySelector('[data-type="clients-denied"]');
	var readOnlyBanner = root.querySelector('[data-type="clients-readonly"]');
	var setupGuide = root.querySelector('[data-type="setup-guide"]');
	var listGroup = root.querySelector('[data-type="client-list-group"]');
	var list = root.querySelector('[data-type="client-list"]');
	var listEmpty = root.querySelector('[data-type="client-list-empty"]');
	var form = root.querySelector('[data-type="client-form"]');
	var formTitle = form.querySelector('[data-type="client-form-title"]');
	var saveButton = form.querySelector('[data-type="save"]');
	var cancelButton = form.querySelector('[data-type="cancel"]');

	var canRead = false;
	var canWrite = false;

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	/** A call to datahub-api's tenant settings, with the flow pages' token handling. */
	function api(method, path, body){
		return FlowApi.request("/tenant/settings" + path, { method: method, body: body, base: FlowApi.apiBase() });
	}

	function field(name){ return form.querySelector('[name="' + name + '"]'); }

	function showDenied(){
		page.hidden = true;
		denied.hidden = false;
	}

	function clearFieldErrors(){
		form.querySelectorAll("[data-error-for]").forEach(function(element){
			element.hidden = true;
			element.textContent = "";
		});
	}

	/** Marks the fields the form has, and flashes the problem with the lines for the rest. */
	function reportFailure(err, fallbackKey){
		if(!(err instanceof DataHubProblem)) return Flash.error(String(err));
		var unshown = [];
		err.fieldErrors().forEach(function(entry){
			var element = entry.field && form.querySelector('[data-error-for="' + entry.field + '"]');
			if(element){
				element.textContent = entry.message;
				element.hidden = false;
			} else {
				unshown.push(entry.field ? entry.field + ": " + entry.message : entry.message);
			}
		});
		Flash.error(err.message(fallbackKey), { details: unshown.concat(err.details(true)) });
	}

	/** Back to registering a new client, with nothing typed. */
	function resetForm(){
		form.reset();
		clearFieldErrors();
		field("name").readOnly = false;
		formTitle.textContent = L("flows.clients.form.group");
		saveButton.textContent = L("flows.clients.register");
		cancelButton.hidden = true;
	}

	/** Replacing keeps the name and the client id; the secret has to be typed again. */
	function startReplace(client){
		resetForm();
		field("name").value = client.name;
		field("name").readOnly = true;
		field("clientId").value = client.clientId;
		formTitle.textContent = L("flows.clients.form.replace", [client.name]);
		saveButton.textContent = L("flows.clients.replace");
		cancelButton.hidden = false;
		form.scrollIntoView({ behavior: "smooth", block: "start" });
		field("clientSecret").focus();
	}

	function remove(client){
		if(!confirm(L("flows.clients.remove.confirm", [client.name]))) return;
		api("DELETE", "/flow/clients/" + FlowApi.seg(client.name))
			.then(function(){
				Flash.info(L("flows.clients.removed", [client.name]));
				if(field("name").readOnly && field("name").value === client.name) resetForm();
				return refresh();
			})
			.catch(function(err){ reportFailure(err, "flows.clients.save.failed"); });
	}

	function button(labelKey, onClick){
		var b = document.createElement("button");
		b.type = "button";
		b.className = "dh-btn secondary small";
		b.textContent = L(labelKey);
		b.addEventListener("click", onClick);
		return b;
	}

	function renderList(clients){
		list.replaceChildren();
		listEmpty.hidden = clients.length > 0;
		clients.forEach(function(client){
			var item = document.createElement("li");
			item.className = "settings-list-item";

			var text = document.createElement("div");
			text.className = "settings-list-text";
			var name = document.createElement("strong");
			name.textContent = client.name;
			var id = document.createElement("span");
			id.className = "settings-list-sub";
			id.textContent = L("flows.clients.list.clientid", [client.clientId]);
			text.append(name, id);
			item.appendChild(text);

			if(canWrite){
				var actions = document.createElement("div");
				actions.className = "settings-list-actions";
				actions.append(button("flows.clients.replace", function(){ startReplace(client); }),
					button("flows.clients.remove", function(){ remove(client); }));
				item.appendChild(actions);
			}
			list.appendChild(item);
		});
	}

	/** Lists the clients, for a caller who may read them; a write-only caller sees only the form. */
	function refresh(){
		if(!canRead) return Promise.resolve();
		return api("GET", "/flow").then(function(settings){
			renderList((settings && settings.clients) || []);
		});
	}

	form.addEventListener("submit", function(event){
		event.preventDefault();
		clearFieldErrors();
		var name = field("name").value.trim();
		if(name === ""){
			var error = form.querySelector('[data-error-for="name"]');
			error.textContent = L("flows.clients.name.required");
			error.hidden = false;
			return;
		}
		saveButton.disabled = true;
		api("PUT", "/flow/clients/" + FlowApi.seg(name), {
			clientId: field("clientId").value.trim(),
			// Not trimmed: a secret is whatever Keycloak issued.
			clientSecret: field("clientSecret").value
		})
			.then(function(){
				Flash.info(L("flows.clients.saved", [name]));
				resetForm();
				return refresh();
			})
			.catch(function(err){ reportFailure(err, "flows.clients.save.failed"); })
			.finally(function(){ saveButton.disabled = false; });
	});

	cancelButton.addEventListener("click", resetForm);

	api("GET", "/permissions").then(function(permissions){
		var flow = (permissions && permissions.flow) || { read: false, write: false };
		canRead = flow.read === true;
		canWrite = flow.write === true;
		if(!canRead && !canWrite){
			showDenied();
			return;
		}
		return refresh().then(function(){
			loading.hidden = true;
			root.hidden = false;
			listGroup.hidden = !canRead;
			form.hidden = !canWrite;
			readOnlyBanner.hidden = canWrite;
			// Open for someone with nothing registered yet, folded once there is a client.
			setupGuide.open = canWrite && (!canRead || list.children.length === 0);
		});
	}).catch(function(err){
		if(err instanceof DataHubProblem && err.status === 403){
			showDenied();
			return;
		}
		loading.hidden = true;
		reportFailure(err, "flows.clients.load.failed");
	});
})();
