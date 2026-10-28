/*
 * The Agents section's Tool servers tab: the MCP servers an organization registers for its agents,
 * beyond DataHub's own.
 *
 * Lists, registers, edits and removes them in datahub-api's tenant settings (GET /tenant/settings/flow
 * and PUT/DELETE /tenant/settings/flow/mcp-servers/{name}, gated by the flow settings grants, like the
 * flow clients). Two things are not obvious:
 *
 *  - The credential is write-only. The API never returns it, so the field starts empty, and leaving
 *    it empty while editing omits headerValue from the payload, which the API reads as "keep the
 *    stored one". A new server needs one.
 *
 *  - Nothing is tried on save. The agent form's picker shows what each server answers, or that it
 *    did not; an agent whose tools name a removed server fails its next run.
 */
(function(){
	"use strict";

	var root = document.querySelector('[data-type="tool-servers"]');
	if(!root) return;

	var page = document.querySelector('[data-type="servers-page"]');
	var loading = document.querySelector('[data-type="servers-loading"]');
	var denied = document.querySelector('[data-type="servers-denied"]');
	var readOnlyBanner = root.querySelector('[data-type="servers-readonly"]');
	var listGroup = root.querySelector('[data-type="server-list-group"]');
	var list = root.querySelector('[data-type="server-list"]');
	var listEmpty = root.querySelector('[data-type="server-list-empty"]');
	var form = root.querySelector('[data-type="mcp-server-form"]');
	var formTitle = form.querySelector('[data-type="mcp-server-form-title"]');
	var valueHelp = form.querySelector('[data-type="headervalue-help"]');
	var saveButton = form.querySelector('[data-type="save"]');
	var cancelButton = form.querySelector('[data-type="cancel"]');

	var canRead = false;
	var canWrite = false;
	var editing = null;        // the name of the server being edited, or null for a new one

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	/** A call to datahub-api's tenant settings, with the flow pages' token handling. */
	function api(method, path, body){
		return FlowApi.request("/tenant/settings" + path, { method: method, body: body, base: FlowApi.apiBase() });
	}

	function field(name){ return form.querySelector('[name="' + name + '"]'); }
	function trimmed(name){ var value = field(name).value.trim(); return value === "" ? null : value; }

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

	/** Back to registering a new server, with nothing typed. */
	function resetForm(){
		editing = null;
		form.reset();
		clearFieldErrors();
		field("name").readOnly = false;
		formTitle.textContent = L("agents.servers.form.group");
		valueHelp.textContent = L("agents.servers.headervalue.help");
		saveButton.textContent = L("agents.servers.register");
		cancelButton.hidden = true;
	}

	/** Editing keeps the name; the credential field stays empty, which keeps the stored credential. */
	function startEdit(server){
		resetForm();
		editing = server.name;
		field("name").value = server.name;
		field("name").readOnly = true;
		field("url").value = server.url;
		field("headerName").value = server.headerName;
		formTitle.textContent = L("agents.servers.form.edit", [server.name]);
		valueHelp.textContent = L("agents.servers.headervalue.stored");
		saveButton.textContent = L("agents.servers.save");
		cancelButton.hidden = false;
		form.scrollIntoView({ behavior: "smooth", block: "start" });
		field("url").focus();
	}

	function remove(server){
		if(!confirm(L("agents.servers.remove.confirm", [server.name]))) return;
		api("DELETE", "/flow/mcp-servers/" + FlowApi.seg(server.name))
			.then(function(){
				Flash.info(L("agents.servers.removed", [server.name]));
				if(editing === server.name) resetForm();
				return refresh();
			})
			.catch(function(err){ reportFailure(err, "agents.servers.save.failed"); });
	}

	function button(labelKey, onClick){
		var b = document.createElement("button");
		b.type = "button";
		b.className = "dh-btn secondary small";
		b.textContent = L(labelKey);
		b.addEventListener("click", onClick);
		return b;
	}

	function renderList(servers){
		list.replaceChildren();
		listEmpty.hidden = servers.length > 0;
		servers.forEach(function(server){
			var item = document.createElement("li");
			item.className = "settings-list-item";

			var text = document.createElement("div");
			text.className = "settings-list-text";
			var name = document.createElement("strong");
			name.textContent = server.name;
			var url = document.createElement("span");
			url.className = "settings-list-sub";
			url.textContent = server.url;
			var header = document.createElement("span");
			header.className = "settings-list-sub";
			header.textContent = L("agents.servers.list.header", [server.headerName]);
			text.append(name, url, header);
			item.appendChild(text);

			if(canWrite){
				var actions = document.createElement("div");
				actions.className = "settings-list-actions";
				actions.append(button("agents.servers.edit", function(){ startEdit(server); }),
					button("agents.servers.remove", function(){ remove(server); }));
				item.appendChild(actions);
			}
			list.appendChild(item);
		});
	}

	/** Lists the servers, for a caller who may read them; a write-only caller sees only the form. */
	function refresh(){
		if(!canRead) return Promise.resolve();
		return api("GET", "/flow").then(function(settings){
			renderList((settings && settings.mcpServers) || []);
		});
	}

	form.addEventListener("submit", function(event){
		event.preventDefault();
		clearFieldErrors();
		var name = editing || trimmed("name");
		if(!name){
			var error = form.querySelector('[data-error-for="name"]');
			error.textContent = L("agents.servers.name.required");
			error.hidden = false;
			return;
		}
		var body = { url: trimmed("url"), headerName: trimmed("headerName") };
		// Not trimmed: a credential is what the server issued. Blank means keep the stored one.
		if(field("headerValue").value.trim() !== "") body.headerValue = field("headerValue").value;
		saveButton.disabled = true;
		api("PUT", "/flow/mcp-servers/" + FlowApi.seg(name), body)
			.then(function(){
				Flash.info(L("agents.servers.saved", [name]));
				resetForm();
				return refresh();
			})
			.catch(function(err){ reportFailure(err, "agents.servers.save.failed"); })
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
		});
	}).catch(function(err){
		if(err instanceof DataHubProblem && err.status === 403){
			showDenied();
			return;
		}
		loading.hidden = true;
		reportFailure(err, "agents.servers.load.failed");
	});
})();
