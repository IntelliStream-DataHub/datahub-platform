/*
 * The agent page: a form over a flow of kind "agent", saved and run against datahub-flow (FlowApi):
 *
 *   GET  /flows/{id}                  the agent; a 404 opens the id as a new agent
 *   GET  /agents/tools                what the agent may be given, with the servers' hints
 *   POST /flows, PUT /flows/{id}      save (a new version; 409 when someone saved first)
 *   POST /previews                    run it now as the user, writing nothing; then the run page
 *   POST /flows/{id}/runs             a manual run of the deployed version
 *   POST /flows/{id}/deployment/authorize, PUT /flows/{id}/deployment, DELETE /flows/{id}
 *
 * The form writes a fixed definition: agent (the instructions, tools and model overrides, done ends
 * there) → write-events (what the agent asks a person to look at, as an agent/attention lifecycle in
 * the agent's dataset). How it runs is the definition's shape, not a field of its own:
 *
 *   manual     a manual trigger, the agent on its own
 *   schedule   a schedule, the agent on its own: every tick is a run of the model
 *   events     a schedule with read-events into the agent (the filter, bounded by when events were
 *              recorded, never events agents wrote): a tick with no matching events never runs it
 *
 * Reading it back finds the steps by type, so a definition edited in Flows keeps whatever else it has.
 */
(function(){
	"use strict";

	var ENDED = ["SUCCEEDED", "FAILED", "CANCELLED"];
	var CRON_DEFAULT = "0 */15 * * * *";
	var ATTENTION_TYPE = "agent", ATTENTION_SUBTYPE = "attention";

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="agent-page"]');
		if(!page) return;

		var flowId = page.getAttribute("data-flow-id");   // empty until a new agent is named
		var idFromName = !flowId;
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var form = el("agent-form");

		var flow = null;           // FlowView, or null for an agent not saved yet
		var deployment = null;     // DeploymentView, or null when never deployed
		var def = null;            // the definition as loaded or last saved
		var savedJson = null;
		var problems = [];         // {field, message} from the last save
		var sent = null;           // the definition the problems are about
		var tools = [];            // {server, name, description, readOnly, destructive}
		var toolsLoaded = false;

		function L(key, args){ return args ? $L(key, null, args) : $L(key); }
		function field(name){ return form.elements[name]; }
		function val(name){ var f = field(name); return f ? f.value.trim() : ""; }
		function setVal(name, value){ var f = field(name); if(f) f.value = value === null || value === undefined ? "" : String(value); }
		function lines(name){ return val(name) ? val(name).split(/\r?\n/).map(function(s){ return s.trim(); }).filter(Boolean) : []; }
		function setLines(name, list){ setVal(name, Array.isArray(list) ? list.join("\n") : ""); }

		/** A new agent's external id, from its name: Pump watch → pump_watch. */
		function externalIdOf(name){
			return name.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase()
				.replace(/æ/g, "ae").replace(/ø/g, "o").replace(/ß/g, "ss")
				.replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 128);
		}

		// Durations are ISO-8601 in the definition (PT2M) and written as 2m in the form.
		var UNITS = [["d", 86400], ["h", 3600], ["m", 60], ["s", 1]];
		function friendlyDuration(iso){
			if(!iso) return "";
			var m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso);
			if(!m || /^P$|T$/.test(iso)) return iso;
			var seconds = Number(m[1] || 0) * 86400 + Number(m[2] || 0) * 3600 + Number(m[3] || 0) * 60 + Number(m[4] || 0);
			var parts = [];
			UNITS.forEach(function(u){ if(seconds >= u[1]){ parts.push(Math.floor(seconds / u[1]) + u[0]); seconds %= u[1]; } });
			return parts.length ? parts.join(" ") : "0s";
		}
		function isoDuration(value){
			if(!value) return value;
			var rest = value.trim().toLowerCase(), seconds = 0, m;
			var unit = /^(\d+)\s*(d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)(?![a-z])\s*,?\s*/;
			if(!rest || !unit.test(rest)) return value.trim();
			while((m = unit.exec(rest))){
				seconds += Number(m[1]) * UNITS.find(function(u){ return u[0] === m[2][0]; })[1];
				rest = rest.slice(m[0].length);
			}
			if(rest) return value.trim();
			var days = Math.floor(seconds / 86400), h = Math.floor(seconds % 86400 / 3600), min = Math.floor(seconds % 3600 / 60), s = seconds % 60;
			var time = (h ? h + "H" : "") + (min ? min + "M" : "") + (s ? s + "S" : "");
			return "P" + (days ? days + "D" : "") + (time ? "T" + time : days ? "" : "T0S");
		}

		// ---- the definition and the form -----------------------------------------------------------

		function step(type){ return (def.processors || []).find(function(p){ return p.type === type; }); }

		function readEvents(){ return { id: "events", type: "read-events", properties: { timeField: "createdTime", excludeSource: ["agent:*"] } }; }

		function emptyDefinition(){
			return { schemaVersion: 1, kind: "agent", externalId: flowId, name: "", dataSet: "",
				processors: [
					readEvents(),
					{ id: "agent", type: "agent", properties: { instructions: "", tools: [] }, terminate: ["done"] },
					{ id: "attention", type: "write-events", properties: { type: ATTENTION_TYPE, subType: ATTENTION_SUBTYPE, source: "agent:" + flowId } }
				],
				connections: [
					{ from: "events", relationship: "success", to: "agent" },
					{ from: "agent", relationship: "attention", to: "attention" }
				],
				trigger: { type: "schedule", cron: CRON_DEFAULT, timezone: localZone(), delay: "PT1M" },
				execution: { maxAttempts: 2, timeout: "PT20M" } };
		}

		function localZone(){
			try { return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"; } catch(e){ return "UTC"; }
		}

		/** The schedule's time zone: one set in the definition stays, else the browser's. Not on the form. */
		function zone(){ return (def && def.trigger && def.trigger.timezone) || localZone(); }

		/** The form from the definition. */
		function fill(){
			var events = (step("read-events") || {}).properties || {};
			var agent = (step("agent") || {}).properties || {};
			var t = def.trigger || {};
			setVal("name", def.name);
			setVal("description", def.description);
			setVal("instructions", agent.instructions);
			field("triggerType").value = t.type === "manual" ? "manual" : step("read-events") ? "events" : "schedule";
			setVal("cron", t.cron || CRON_DEFAULT);
			field("every").value = isPreset(val("cron")) ? val("cron") : "";
			setLines("eventTypes", events.type);
			setLines("eventSubTypes", events.subType);
			setLines("eventStatuses", events.status);
			setLines("eventSources", events.source);
			setLines("eventDataSets", events.dataSet);
			setLines("eventMetadata", events.metadata);
			setVal("eventAdvancedFilter", events.advancedFilter);
			setVal("effort", agent.effort);
			setVal("maxIterations", agent.maxIterations);
			setVal("maxOutputTokens", agent.maxOutputTokens);
			setVal("turnTimeout", friendlyDuration(agent.turnTimeout));
			applyTrigger();
			drawTools();
		}

		/** The definition from the form: the three steps updated in place, anything else kept. */
		function outgoing(){
			var out = JSON.parse(JSON.stringify(def));
			out.kind = "agent";
			out.externalId = flowId;
			out.name = val("name");
			if(val("description")) out.description = val("description"); else delete out.description;
			out.dataSet = val("dataSet");
			var mode = field("triggerType").value;
			out.processors = out.processors || [];
			out.connections = out.connections || [];
			var find = function(type){ return out.processors.find(function(p){ return p.type === type; }); };
			var agent = find("agent"), attention = find("write-events");
			// Only an events agent reads events; for the others the step and its connections go.
			var unread = out.processors.filter(function(p){ return p.type === "read-events"; }).map(function(p){ return p.id; });
			if(mode !== "events" && unread.length){
				out.processors = out.processors.filter(function(p){ return unread.indexOf(p.id) < 0; });
				out.connections = out.connections.filter(function(c){ return unread.indexOf(c.from) < 0 && unread.indexOf(c.to) < 0; });
			}
			var events = mode === "events" ? find("read-events") : null;
			if(mode === "events" && !events){
				events = readEvents();
				out.processors.unshift(events);
				if(agent) out.connections.unshift({ from: events.id, relationship: "success", to: agent.id });
			}
			if(events){
				var ep = events.properties = events.properties || {};
				[["type", "eventTypes"], ["subType", "eventSubTypes"], ["status", "eventStatuses"], ["source", "eventSources"],
					["dataSet", "eventDataSets"], ["metadata", "eventMetadata"]].forEach(function(k){
					var list = lines(k[1]);
					if(list.length) ep[k[0]] = list; else delete ep[k[0]];
				});
				if(val("eventAdvancedFilter")) ep.advancedFilter = val("eventAdvancedFilter"); else delete ep.advancedFilter;
				ep.timeField = "createdTime";
				ep.excludeSource = ["agent:*"];
			}
			if(agent){
				var ap = agent.properties = agent.properties || {};
				ap.instructions = val("instructions");
				ap.tools = checkedTools();
				["effort", "maxIterations", "maxOutputTokens"].forEach(function(k){
					var v = val(k);
					if(!v) delete ap[k]; else ap[k] = k === "effort" ? v : Number(v);
				});
				if(val("turnTimeout")) ap.turnTimeout = isoDuration(val("turnTimeout")); else delete ap.turnTimeout;
			}
			if(attention && attention.properties) attention.properties.source = "agent:" + flowId;
			if(mode === "manual"){
				// A manual run is given a window, though an agent on its own reads nothing in it.
				out.trigger = { type: "manual", window: "PT1H" };
			} else {
				out.trigger = { type: "schedule", cron: val("cron") || CRON_DEFAULT, timezone: zone() };
				// An events check waits a minute past the tick, so events still on their way have arrived.
				if(mode === "events") out.trigger.delay = "PT1M";
			}
			return out;
		}

		function isDirty(){ return savedJson !== JSON.stringify(outgoing()); }

		function applyTrigger(){
			var mode = field("triggerType").value;
			form.querySelectorAll("[data-when-trigger]").forEach(function(block){
				block.hidden = block.getAttribute("data-when-trigger").split(" ").indexOf(mode) < 0;
			});
			el("agent-every-label").textContent = L(mode === "events" ? "agents.field.every.events" : "agents.field.every");
			// The cron box only for a custom schedule; a preset is its own cron.
			el("agent-cron-field").hidden = mode === "manual" || !!field("every").value;
			el("agent-zone").textContent = L("agents.field.every.zone", [zone()]);
		}

		function isPreset(cron){
			return Array.from(field("every").options).some(function(o){ return o.value && o.value === cron; });
		}

		// ---- the tools -----------------------------------------------------------------------------

		/** Whether a tool name matches one of the agent's patterns (event_*, resource_get). */
		function matches(name, patterns){
			return (patterns || []).some(function(p){
				return new RegExp("^" + String(p).replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".") + "$").test(name);
			});
		}

		function checkedTools(){
			return Array.from(form.querySelectorAll('input[name="tools"]:checked')).map(function(c){ return c.value; });
		}

		function drawTools(){
			if(!toolsLoaded) return;
			var box = el("agent-tools");
			box.innerHTML = "";
			var patterns = ((step("agent") || {}).properties || {}).tools || [];
			if(!tools.length){
				box.appendChild(Object.assign(document.createElement("p"), { className: "flows-hint", textContent: L("agents.tools.none") }));
				return;
			}
			var servers = [];
			tools.forEach(function(t){ if(servers.indexOf(t.server) < 0) servers.push(t.server); });
			servers.forEach(function(server){
				box.appendChild(Object.assign(document.createElement("h4"), { textContent: server }));
				var grid = Object.assign(document.createElement("div"), { className: "agent-tool-grid" });
				tools.filter(function(t){ return t.server === server; }).forEach(function(t){
					var label = Object.assign(document.createElement("label"), { className: "agent-tool" + (t.destructive === true ? " disabled" : "") });
					var input = Object.assign(document.createElement("input"), { type: "checkbox", name: "tools", value: t.name });
					input.checked = t.destructive !== true && matches(t.name, patterns);
					input.disabled = t.destructive === true;
					var name = Object.assign(document.createElement("strong"), { textContent: t.name });
					var desc = Object.assign(document.createElement("span"), { textContent: t.description || "" });
					label.append(input, name);
					if(t.destructive === true){
						label.appendChild(Object.assign(document.createElement("em"), { className: "agent-tool-badge", textContent: L("agents.tools.destructive") }));
					} else if(t.readOnly !== true){
						label.appendChild(Object.assign(document.createElement("em"), { className: "agent-tool-badge warn", textContent: L("agents.tools.mutating") }));
					}
					label.appendChild(desc);
					label.title = t.description || "";
					grid.appendChild(label);
				});
				box.appendChild(grid);
			});
		}

		function loadTools(){
			return FlowApi.get("/agents/tools").then(function(answer){
				tools = answer.tools || [];
				toolsLoaded = true;
				drawTools();
			}).catch(function(err){
				toolsLoaded = true;
				tools = [];
				drawTools();
				FlowApi.flashError(err, "agents.error.tools");
			});
		}

		// ---- datasets ----------------------------------------------------------------------------

		/** The datasets the user may write, which an agent can belong to. */
		function fillDataSets(current){
			return Promise.all([FlowApi.api("/datasets?limit=1000"), FlowApi.api("/me/datasets?access=write")]).then(function(answers){
				var grants = answers[1], ids = (grants.ids || []).map(String);
				var list = (answers[0].items || [])
					.filter(function(d){ return d.externalId && (grants.all || ids.indexOf(String(d.id)) >= 0); })
					.map(function(d){ return { value: d.externalId, label: d.name && d.name !== d.externalId ? d.name + " (" + d.externalId + ")" : d.externalId }; })
					.sort(function(a, b){ return a.label.localeCompare(b.label); });
				if(current && !list.some(function(d){ return d.value === current; })) list.push({ value: current, label: current });
				var select = field("dataSet");
				select.innerHTML = "";
				[{ value: "", label: L("flows.field.dataset.choose") }].concat(list).forEach(function(d){
					select.appendChild(Object.assign(document.createElement("option"), { value: d.value, textContent: d.label }));
				});
				select.value = current || "";
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.datasets"); });
		}

		// ---- the header and the problems -----------------------------------------------------------

		function stateLine(){
			if(!flow) return L("agents.state.new");
			var parts = [L("flows.state.version", [flow.version])];
			if(flow.disabledReason) parts.push(L("flows.state.disabled.reason", [flow.disabledReason]));
			if(!deployment){
				parts.push(L("flows.state.not.deployed"));
			} else {
				parts.push(L(deployment.enabled ? "flows.state.enabled" : "flows.state.disabled", [deployment.version]));
				if(deployment.runAs) parts.push(L(deployment.identity === "client" ? "flows.state.runs.as.client" : "flows.state.runs.as.deployer", [deployment.runAs]));
				if(deployment.identityState === "needs-reauthorisation") parts.push(L("flows.state.reauthorise", [deployment.identityStateReason || ""]));
				if(deployment.nextDueAt) parts.push(L("flows.state.next", [window.formatLocalTime(deployment.nextDueAt)]));
			}
			if(isDirty()) parts.push(L("flows.state.unsaved"));
			return parts.join(" · ");
		}

		function drawHeader(){
			el("agent-title").textContent = flowId ? (val("name") ? val("name") + " · " : "") + flowId : L("agents.new.title");
			var state = el("agent-state");
			state.textContent = stateLine();
			state.classList.toggle("flows-warn", !!(deployment && deployment.identityState === "needs-reauthorisation") || !!(flow && flow.disabledReason));
			var saved = !!flow && !isDirty();
			el("agent-run").hidden = !(saved && deployment && deployment.enabled);
			el("agent-deploy").hidden = !saved;
			el("agent-deploy").textContent = L(deployment ? "flows.redeploy" : "flows.deploy");
			el("agent-run-as").hidden = !saved;
			el("agent-toggle").hidden = !(saved && deployment);
			el("agent-toggle").textContent = L(deployment && deployment.enabled ? "flows.disable" : "flows.enable");
			el("agent-delete").hidden = !flow;
			var open = el("agent-open-flow");
			open.hidden = !flow;
			open.href = "/flows/" + FlowApi.seg(flowId || "");
		}

		/** The form field a problem's JSON Pointer lands on, or null for the list. */
		function fieldOf(pointer){
			var path = String(pointer || "").replace(/^#?\//, "").split("/").filter(Boolean);
			var top = { name: "name", description: "description", dataSet: "dataSet" };
			if(path.length === 1 && top[path[0]]) return top[path[0]];
			// A bad time zone came from the JSON, so it goes in the list rather than on a field.
			if(path[0] === "trigger") return path[1] === "timezone" ? null : "cron";
			// The pointers are into what was sent, which can have a step more or less than what was loaded.
			var processors = (sent || def).processors || [];
			if(path[0] === "processors" && path[2] === "properties" && processors[Number(path[1])]){
				var type = processors[Number(path[1])].type;
				if(type === "read-events") return { type: "eventTypes", subType: "eventSubTypes", status: "eventStatuses", source: "eventSources",
					dataSet: "eventDataSets", metadata: "eventMetadata", advancedFilter: "eventAdvancedFilter" }[path[3]] || null;
				if(type === "agent") return ["instructions", "tools", "effort", "maxIterations", "maxOutputTokens", "turnTimeout"].indexOf(path[3]) >= 0 ? path[3] : null;
			}
			return null;
		}

		function drawProblems(){
			form.querySelectorAll("[data-error-for]").forEach(function(e){ e.hidden = true; e.textContent = ""; });
			var list = el("agent-problems");
			list.innerHTML = "";
			problems.forEach(function(p){
				var name = fieldOf(p.field);
				var box = name ? form.querySelector('[data-error-for="' + name + '"]') : null;
				if(box){
					box.textContent = p.message;
					box.hidden = false;
				} else {
					list.appendChild(Object.assign(document.createElement("li"), { textContent: (p.field ? p.field + ": " : "") + p.message }));
				}
			});
			list.hidden = !list.childElementCount;
		}

		function changed(){
			drawHeader();
			drawProblems();
		}

		// ---- actions -------------------------------------------------------------------------------

		function problemsOf(err){
			if(err instanceof DataHubProblem && Array.isArray(err.body.fields)){
				return err.body.fields.map(function(f){ return { field: f.field, message: f.message }; });
			}
			return [];
		}

		function readOnly(){
			el("agent-readonly").hidden = false;
			["agent-save", "agent-deploy", "agent-run-as", "agent-toggle", "agent-delete", "agent-run", "agent-preview"]
				.forEach(function(t){ el(t).disabled = true; });
		}

		function refuse(err, fallbackKey){
			if(err instanceof DataHubProblem && err.status === 403 && err.slug === "forbidden") readOnly();
			var list = problemsOf(err);
			if(list.length){
				problems = list;
				changed();
				Flash.error(L(list.length === 1 ? "agents.validate.failed.one" : "agents.validate.failed", [list.length]),
					{ details: list.map(function(p){ return (p.field ? p.field + ": " : "") + p.message; }) });
				return;
			}
			if(err instanceof DataHubProblem && err.slug === "token-expiring"){ Flash.error(L("flows.error.token.expiring")); return; }
			if(err instanceof DataHubProblem && err.slug === "busy"){ Flash.error(L("flows.error.busy")); return; }
			FlowApi.flashError(err, fallbackKey);
		}

		function save(){
			sent = outgoing();
			var body = JSON.stringify(sent);
			var call = flow ? FlowApi.put("/flows/" + FlowApi.seg(flowId), body) : FlowApi.post("/flows", body);
			return call.then(function(view){
				if(!flow){
					history.replaceState(null, "", "/agents/" + FlowApi.seg(flowId));
					page.setAttribute("data-flow-id", flowId);
					el("agent-new-id").textContent = "";
				}
				flow = view;
				def = view.definition || def;
				savedJson = JSON.stringify(outgoing());
				problems = [];
				changed();
				Flash.info(L("agents.saved", [view.version]));
			}).catch(function(err){
				if(err instanceof DataHubProblem && err.status === 409 && flow){ Flash.error(L("flows.error.conflict")); return; }
				refuse(err, "flows.error.save");
			});
		}

		function openRun(run){
			window.location.href = "/agents/" + FlowApi.seg(flowId) + "/runs/" + FlowApi.seg(run.id);
		}

		function preview(){
			sent = flow && !isDirty() ? def : outgoing();
			var body = flow && !isDirty() ? { flow: flowId, version: flow.version } : { definition: sent };
			return FlowApi.post("/previews", body).then(openRun).catch(function(err){ refuse(err, "flows.error.preview"); });
		}

		function runNow(){
			return FlowApi.post("/flows/" + FlowApi.seg(flowId) + "/runs", {}).then(openRun)
				.catch(function(err){ refuse(err, "flows.error.run"); });
		}

		function deploy(){
			return FlowApi.post("/flows/" + FlowApi.seg(flowId) + "/deployment/authorize", { version: flow.version, enabled: true })
				.then(function(answer){ window.location.href = answer.authorizationUrl; })
				.catch(function(err){ refuse(err, "flows.error.deploy"); });
		}

		function runAs(){
			var name = window.prompt(L("flows.run.as.prompt"), deployment && deployment.identity === "client" ? deployment.runAs : "");
			if(!name) return;
			return FlowApi.put("/flows/" + FlowApi.seg(flowId) + "/deployment", { runAs: name.trim(), version: flow.version, enabled: true })
				.then(function(d){ deployment = d; drawHeader(); Flash.info(L("flows.run.as.done", [name.trim()])); })
				.catch(function(err){ refuse(err, "flows.error.deploy"); });
		}

		function toggle(){
			return FlowApi.put("/flows/" + FlowApi.seg(flowId) + "/deployment", { enabled: !deployment.enabled })
				.then(function(d){ deployment = d; drawHeader(); })
				.catch(function(err){ refuse(err, "flows.error.deploy"); });
		}

		function remove(){
			if(!confirm(L("agents.delete.confirm", [flowId]))) return;
			return FlowApi.del("/flows/" + FlowApi.seg(flowId))
				.then(function(){ savedJson = JSON.stringify(outgoing()); window.location.href = "/agents"; })
				.catch(function(err){ refuse(err, "flows.error.delete"); });
		}

		// ---- tabs and versions ---------------------------------------------------------------------

		function showTab(name){
			page.querySelectorAll(".flow-tabs button").forEach(function(b){ b.classList.toggle("selected", b.getAttribute("data-tab") === name); });
			page.querySelectorAll(".flow-pane").forEach(function(p){ p.hidden = p.getAttribute("data-pane") !== name; });
			if(name === "versions") loadVersions();
			if(name === "runs" && flow) FlowRuns.list(page.querySelector('[data-pane="runs"]'), flowId);
		}

		function loadVersions(){
			if(!flow) return;
			var tbody = el("flow-versions").querySelector("tbody");
			FlowApi.get("/flows/" + FlowApi.seg(flowId) + "/versions").then(function(list){
				tbody.innerHTML = "";
				list.forEach(function(v){
					var tr = document.createElement("tr");
					[String(v.version), v.createdBy || "", window.formatLocalTime(v.createdAt)].forEach(function(t){
						tr.appendChild(Object.assign(document.createElement("td"), { textContent: t }));
					});
					var td = document.createElement("td");
					var open = Object.assign(document.createElement("button"), { type: "button", className: "dh-btn secondary small", textContent: L("flows.version.open") });
					open.addEventListener("click", function(){
						FlowApi.get("/flows/" + FlowApi.seg(flowId) + "/versions/" + v.version).then(function(full){
							def = full.definition;
							fill();
							changed();
							showTab("agent");
							Flash.info(L("flows.version.opened", [v.version]));
						}).catch(function(err){ FlowApi.flashError(err, "flows.error.load"); });
					});
					td.appendChild(open);
					tr.appendChild(td);
					tbody.appendChild(tr);
				});
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.load"); });
		}

		// ---- start ---------------------------------------------------------------------------------

		function deployReturn(){
			var params = new URLSearchParams(window.location.search);
			if(params.get("deployed")){
				Flash.info(L("agents.deployed"));
			} else if(params.get("deployError")){
				var code = params.get("deployError"), key = "flows.deploy.error." + code, message = $L(key);
				Flash.error(message && message !== key ? message : L("flows.deploy.error", [code]));
			}
			if(params.get("deployed") || params.get("deployError")) history.replaceState(null, "", window.location.pathname);
		}

		function load(){
			var notFound = function(err){ return (err instanceof DataHubProblem && err.status === 404) ? null : Promise.reject(err); };
			return (flowId ? FlowApi.get("/flows/" + FlowApi.seg(flowId)).catch(notFound) : Promise.resolve(null)).then(function(view){
				flow = view;
				def = flow ? flow.definition : emptyDefinition();
				fill();
				return Promise.all([
					fillDataSets(def.dataSet),
					loadTools(),
					flow ? FlowApi.get("/flows/" + FlowApi.seg(flowId) + "/deployment").catch(notFound) : null
				]);
			}).then(function(results){
				deployment = results[2];
				savedJson = flow ? JSON.stringify(outgoing()) : null;
				changed();
				deployReturn();
			}).catch(function(err){
				el("agent-state").textContent = FlowApi.reason(err, "flows.error.load");
				FlowApi.flashError(err, "flows.error.load");
			});
		}

		var on = function(type, fn){ el(type).addEventListener("click", fn); };
		on("agent-save", save);
		on("agent-preview", preview);
		on("agent-run", runNow);
		on("agent-deploy", deploy);
		on("agent-run-as", runAs);
		on("agent-toggle", toggle);
		on("agent-delete", remove);
		field("triggerType").addEventListener("change", applyTrigger);
		field("every").addEventListener("change", function(){
			if(field("every").value) setVal("cron", field("every").value);
			applyTrigger();
			if(!field("every").value) field("cron").focus();
		});
		form.addEventListener("input", function(){
			if(!flow && idFromName){
				flowId = externalIdOf(val("name"));
				el("agent-new-id").textContent = flowId ? L("flows.new.id", [flowId]) : "";
			}
			drawHeader();
		});
		form.addEventListener("submit", function(e){ e.preventDefault(); save(); });
		page.querySelectorAll(".flow-tabs button").forEach(function(b){ b.addEventListener("click", function(){ showTab(b.getAttribute("data-tab")); }); });
		window.addEventListener("beforeunload", function(e){
			if(def && isDirty() && (flow || val("name"))){ e.preventDefault(); e.returnValue = ""; }
		});

		window.i18nReady.then(load);
	});
})();
