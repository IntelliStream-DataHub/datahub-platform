/*
 * The flow page: a flow's processing tree, edited as a graph with a form per processor, and the
 * JSON it is saved as. Everything goes to datahub-flow directly (FlowApi):
 *
 *   GET  /catalog/processors          what can be added, and each processor's form (JSON Schema)
 *   GET  /flows/{id}                  the flow; a 404 opens the id as a new flow
 *   POST /flows/validate              problems by JSON Pointer, and the series a save would create
 *   POST /flows, PUT /flows/{id}      save (a new version; 409 when someone saved first)
 *   POST /previews                    run it as the user, writing nothing; then the run page
 *   POST /flows/{id}/runs             a manual run of the deployed version
 *   POST /flows/{id}/deployment/authorize, PUT /flows/{id}/deployment, DELETE /flows/{id}
 *
 * The definition object is the one source of truth on the page: the graph, the JSON view and the
 * forms are all drawn from it, and every edit changes it and redraws them. The page does not check
 * what the service checks; Validate and Save show the service's answer, pinned to the processors it
 * names.
 *
 * The processor and flow-settings forms extend DatasetFormAbstract from the right-form bundle, so
 * they look and behave like every other form in the console. They are declared inside the page
 * handler: this bundle also runs on pages without that base class.
 */
(function(){
	"use strict";

	var NUMBER = /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/;

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="flow-page"]');
		if(!page) return;

		var flowId = page.getAttribute("data-flow-id");   // empty until a new flow is named
		var idFromName = !flowId;                          // opened by New flow: its id follows the name
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };

		var catalog = [];          // the catalog entries, sorted by name
		var byType = {};           // name -> entry
		var flow = null;           // FlowView, or null for a flow not saved yet
		var deployment = null;     // DeploymentView, or null when never deployed
		var def = null;            // the definition being edited
		var savedJson = null;      // def as last loaded or saved, to tell whether it changed
		var problems = [];         // {field, message} from the last validate or save
		var cy = null;             // the cytoscape instance
		var selected = null;       // the selected graph element's data, for Remove

		function L(key, args){ return args ? $L(key, null, args) : $L(key); }
		function isDirty(){ return savedJson !== JSON.stringify(def); }

		// ---- forms ---------------------------------------------------------------------------------

		/** A processor's form, generated from its catalog entry's JSON Schema. */
		// Class expressions rather than declarations: Closure Compiler fails on a class declared
		// inside a function and referenced from a closure.
		var ProcessorForm = class extends DatasetFormAbstract {
			constructor(spec, entry){
				super({ title: labelOf(spec) });
				this.spec = spec;
				this.entry = entry;
				this.deleteUrl = "local";   // shows the form's delete button; delete() is local
				this.confirmDeleteMessage = L("flows.remove.processor.confirm", [labelOf(spec)]);
				this.render();
			}

			render(){
				super.render();
				this.formElement.querySelectorAll("input[data-lookup='timeseries']").forEach(lookupTimeseries);
				this.formElement.querySelectorAll("[data-series]").forEach(seriesPicker);
				this.formElement.querySelectorAll("select[data-lookup='dataset']")
					.forEach(select => fillDataSets(select, select.dataset.current, L("flows.field.dataset.flow")));
			}

			schemaProperties(){
				return (this.entry && this.entry.properties && this.entry.properties.properties) || {};
			}

			relationships(){
				return relationshipsOf(this.spec);
			}

			getFormFields(){
				var spec = this.spec;
				var props = this.schemaProperties();
				var required = (this.entry && this.entry.properties && this.entry.properties.required) || [];
				var html = this.entry ? `<p class="field-hint">${escapeHtml(this.entry.description || "")}</p>`
					: `<p class="flows-banner">${L("flows.processor.unknown", [escapeHtml(spec.type)])}</p>`;
				// Only what has no default shows; the rest is folded, and opened when something in it is set.
				var advanced = "", open = false;
				Object.keys(props).forEach(name => {
					var value = spec.properties ? spec.properties[name] : undefined;
					var field = this.propertyField(name, props[name], required.indexOf(name) >= 0, value);
					if(props[name]["default"] !== undefined || props[name]["x-advanced"]){
						advanced += field;
						open = open || value !== undefined;
					} else {
						html += field;
					}
				});
				var additional = this.entry && this.entry.properties && this.entry.properties.additionalProperties;
				if(additional && typeof additional === "object"){
					var lines = Object.keys(spec.properties || {})
						.filter(k => !(k in props))
						.map(k => k + " = " + text(spec.properties[k]))
						.join("\n");
					var dynamic = `
						<label>${L("flows.field.dynamic")}</label>
						<textarea name="__dynamic" rows="4" class="w100">${escapeHtml(lines)}</textarea>
						<p class="field-hint">${escapeHtml(additional["x-keyDescription"] || "")} — ${escapeHtml(additional.description || "")}</p>
						<p class="field-hint">${L("flows.field.dynamic.help")}</p>`;
					advanced += dynamic;
					open = open || lines !== "";
				}
				var rels = this.relationships();
				if(rels.length){
					advanced += `<label>${L("flows.field.terminate")}</label><div class="flow-checks">`;
					rels.forEach(r => {
						var checked = (spec.terminate || []).indexOf(r) >= 0 ? "checked" : "";
						advanced += `<label class="flows-check"><input type="checkbox" name="__terminate" value="${escapeHtml(r)}" ${checked}/> <span>${escapeHtml(r)}</span></label>`;
					});
					advanced += `</div><p class="field-hint">${L("flows.field.terminate.help")}</p>`;
					open = open || (spec.terminate || []).length > 0;
				}
				var retry = spec.retry || {};
				open = open || spec.retry != null;
				html += `
					<details class="flow-advanced" ${open ? "open" : ""}><summary>${L("flows.field.advanced")}</summary>
					${advanced}
					<label>${L("flows.field.retry")}</label>
					<div class="flow-inline">
						<input type="number" name="__retryAttempts" min="1" step="1"
							   value="${retry.maxAttempts == null ? "" : escapeHtml(String(retry.maxAttempts))}"
							   placeholder="${L("flows.field.retry.attempts")}"/>
						<input type="text" name="__retryBackoff" value="${escapeHtml(friendlyDuration(retry.backoff))}"
							   placeholder="${L("flows.field.retry.backoff")}"/>
					</div>
					</details>`;
				return html;
			}

			propertyField(name, schema, required, value){
				var kind = schemaKind(schema);
				var label = escapeHtml(schema.title || name) + (required ? " *" : "");
				var hint = schema.description ? `<p class="field-hint">${escapeHtml(schema.description)}</p>` : "";
				var placeholder = schema["default"] === undefined ? ""
					: escapeHtml(kind.format === "duration" ? friendlyDuration(text(schema["default"])) : text(schema["default"]));
				var field = "f_" + name;
				var lookup = schema["x-lookup"] ? `data-lookup="${escapeHtml(schema["x-lookup"])}"` : "";
				var current = value === undefined ? "" : kind.format === "duration" ? friendlyDuration(text(value)) : text(value);
				var input;
				if(kind.type === "enum"){
					input = `<select name="${escapeHtml(field)}">
						<option value="">${placeholder ? L("flows.field.default", [placeholder]) : ""}</option>
						${kind.values.map(v => `<option value="${escapeHtml(v.const)}" ${current === v.const ? "selected" : ""}>${escapeHtml(v.const)}${v.description ? " — " + escapeHtml(v.description) : ""}</option>`).join("")}
					</select>`;
				} else if(kind.type === "boolean" && !kind.references){
					input = `<select name="${escapeHtml(field)}">
						<option value="">${placeholder ? L("flows.field.default", [placeholder]) : ""}</option>
						<option value="true" ${current === "true" ? "selected" : ""}>true</option>
						<option value="false" ${current === "false" ? "selected" : ""}>false</option>
					</select>`;
				} else if(schema["x-lookup"] === "dataset"){
					// Filled with the writable datasets in render().
					input = `<select name="${escapeHtml(field)}" ${lookup} data-current="${escapeHtml(current)}"></select>`;
				} else if(kind.type === "array" && schema["x-lookup"] === "timeseries"){
					// The chosen series, looked up in DataHub; the hidden list is what is submitted.
					input = `<div class="flow-series" data-series>
						<ul class="flow-series-list" hidden></ul>
						<input type="text" placeholder="${L("flows.series.search")}"/>
						<textarea name="${escapeHtml(field)}" hidden>${escapeHtml(Array.isArray(value) ? value.join("\n") : current)}</textarea>
					</div>`;
				} else if(kind.type === "array"){
					input = `<textarea name="${escapeHtml(field)}" rows="3" class="w100" placeholder="${L("flows.field.list.placeholder")}">${escapeHtml(Array.isArray(value) ? value.join("\n") : current)}</textarea>`;
				} else {
					var ph = placeholder ? placeholder : kind.format === "duration" ? "1h" : kind.format === "date-time" ? "${run.window.start}"
						: kind.secret ? "${secrets.name}" : placeholder;
					input = `<input type="text" name="${escapeHtml(field)}" value="${escapeHtml(current)}" ${lookup}
						placeholder="${escapeHtml(schema["x-lookup"] ? L("flows.field.lookup.placeholder") : ph)}" ${required && !placeholder ? "required" : ""}/>`;
				}
				return `<label>${label}</label>${input}${hint}`;
			}

			submit(){
				var form = this.formElement;
				var props = this.schemaProperties();
				var properties = {};
				Object.keys(props).forEach(name => {
					var input = form.elements["f_" + name];
					if(!input) return;
					var raw = input.value.trim();
					if(raw === "") return;
					var kind = schemaKind(props[name]);
					if(kind.type === "array"){
						properties[name] = raw.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
					} else if(kind.type === "number" && NUMBER.test(raw)){
						properties[name] = Number(raw);
					} else if(kind.type === "boolean" && (raw === "true" || raw === "false")){
						properties[name] = raw === "true";
					} else if(kind.format === "duration"){
						properties[name] = isoDuration(raw);
					} else {
						properties[name] = raw;
					}
				});
				if(form.elements.__dynamic){
					form.elements.__dynamic.value.split(/\r?\n/).forEach(line => {
						var eq = line.indexOf("=");
						if(eq > 0) properties[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
					});
				}
				var terminate = Array.from(form.querySelectorAll('input[name="__terminate"]:checked')).map(c => c.value);
				var attempts = form.elements.__retryAttempts.value.trim();
				var backoff = isoDuration(form.elements.__retryBackoff.value.trim());
				this.spec.properties = properties;
				if(terminate.length) this.spec.terminate = terminate; else delete this.spec.terminate;
				if(attempts || backoff){
					this.spec.retry = {};
					if(attempts) this.spec.retry.maxAttempts = Number(attempts);
					if(backoff) this.spec.retry.backoff = backoff;
				} else {
					delete this.spec.retry;
				}
				changed();
				this.cancelButtonElement.dispatchEvent(new Event("click"));
			}

			delete(){
				removeProcessor(this.spec.id);
				changed();
				this.cancelButtonElement.dispatchEvent(new Event("click"));
			}
		};

		/** Name, dataset, trigger and execution limits. Parameters are edited in the JSON view. */
		var FlowSettingsForm = class extends DatasetFormAbstract {
			constructor(){
				super({ title: L("flows.settings") });
				this.render();
			}

			render(){
				super.render();
				fillDataSets(this.formElement.elements.dataSet, def.dataSet);
			}

			getFormFields(){
				var t = def.trigger || {};
				var x = def.execution || {};
				var v = s => escapeHtml(s == null ? "" : String(s));
				return `
					<label>${L("flows.column.name")}</label>
					<input type="text" name="name" required value="${v(def.name)}"/>
					<label>${L("description")}</label>
					<textarea name="description" rows="2" class="w100">${v(def.description)}</textarea>
					<label>${L("flows.field.dataset")}</label>
					<select name="dataSet" required></select>
					<p class="field-hint">${L("flows.field.dataset.help")}</p>

					<label>${L("flows.field.trigger")}</label>
					<select name="triggerType">
						<option value="manual" ${t.type !== "schedule" ? "selected" : ""}>${L("flows.trigger.manual")}</option>
						<option value="schedule" ${t.type === "schedule" ? "selected" : ""}>${L("flows.trigger.schedule")}</option>
					</select>
					<label>${L("flows.field.cron")}</label>
					<input type="text" name="cron" value="${v(t.cron)}" placeholder="0 0 * * * *"/>
					<label>${L("flows.field.timezone")}</label>
					<input type="text" name="timezone" value="${v(t.timezone)}" placeholder="Europe/Oslo"/>
					<label>${L("flows.field.window")}</label>
					<input type="text" name="window" value="${v(friendlyDuration(t.window))}" placeholder="1h"/>
					<label>${L("flows.field.delay")}</label>
					<input type="text" name="delay" value="${v(friendlyDuration(t.delay))}" placeholder="5m"/>
					<p class="field-hint">${L("flows.field.trigger.help")}</p>

					<label>${L("flows.field.execution")}</label>
					<div class="flow-inline">
						<input type="number" name="maxConcurrentRuns" min="1" value="${v(x.maxConcurrentRuns)}" placeholder="${L("flows.field.max.concurrent")}"/>
						<input type="number" name="maxAttempts" min="1" value="${v(x.maxAttempts)}" placeholder="${L("flows.field.max.attempts")}"/>
					</div>
					<div class="flow-inline">
						<input type="text" name="timeout" value="${v(friendlyDuration(x.timeout))}" placeholder="15m"/>
						<input type="text" name="memory" value="${v(x.memory)}" placeholder="512MB"/>
					</div>
					<p class="field-hint">${L("flows.field.parameters.help")}</p>`;
			}

			submit(){
				var f = this.formElement.elements;
				var val = name => f[name].value.trim();
				def.name = val("name");
				if(val("description")) def.description = val("description"); else delete def.description;
				def.dataSet = val("dataSet");
				var trigger = { type: f.triggerType.value };
				["cron", "timezone"].forEach(k => { if(val(k)) trigger[k] = val(k); });
				["window", "delay"].forEach(k => { if(val(k)) trigger[k] = isoDuration(val(k)); });
				def.trigger = trigger;
				var execution = {};
				["maxConcurrentRuns", "maxAttempts"].forEach(k => { if(val(k)) execution[k] = Number(val(k)); });
				if(val("timeout")) execution.timeout = isoDuration(val("timeout"));
				if(val("memory")) execution.memory = val("memory");
				if(Object.keys(execution).length) def.execution = execution; else delete def.execution;
				changed();
				this.cancelButtonElement.dispatchEvent(new Event("click"));
			}
		};

		// ---- the definition ------------------------------------------------------------------------

		function text(value){
			if(value === null || value === undefined) return "";
			if(typeof value === "string") return value;
			if(Array.isArray(value)) return value.join(", ");
			return JSON.stringify(value);
		}

		/** What an input should be for a property's schema. */
		function schemaKind(schema){
			var references = !!schema["x-references"];
			var base = schema;
			if(Array.isArray(schema.anyOf)) base = schema.anyOf[0];
			if(Array.isArray(base.oneOf)) return { type: "enum", values: base.oneOf, references: references };
			if(base.pattern && base.pattern.indexOf("secrets") >= 0) return { type: "string", secret: true };
			return { type: base.type || "string", format: base.format, references: references };
		}

		function processorById(id){
			return (def.processors || []).find(p => p.id === id) || null;
		}

		/** What the editor calls a processor: its catalog name, numbered when the flow has several. */
		function labelOf(spec){
			var entry = byType[spec.type];
			var name = entry && entry.displayName ? entry.displayName : spec.type;
			var same = (def.processors || []).filter(p => p.type === spec.type);
			return same.length > 1 ? name + " " + (same.indexOf(spec) + 1) : name;
		}

		/** A series as a search suggestion: its name, and its external id when that differs. */
		function seriesLabel(ts){
			return ts.name && ts.name !== ts.externalId ? ts.name + " — " + ts.externalId : ts.externalId;
		}

		/**
		 * The tenant's series as the user types: the newest when nothing is typed, a search from three
		 * letters (the api's minimum). Suggestions are labels; {@code picked} maps them back.
		 */
		function searchSeries(query, picked, skip){
			var found = query.length >= 3 ? FlowApi.api("/timeseries/search", { search: { query: query }, limit: 20 })
				: query.length === 0 ? FlowApi.api("/timeseries?limit=20")
				: Promise.resolve({ items: [] });
			return found.then(json => (json.items || [])
				.filter(ts => !skip || skip.indexOf(ts.externalId) < 0)
				.map(ts => { var label = seriesLabel(ts); picked[label] = ts; return label; }));
		}

		/** A single series field, such as a write target: a search that fills in the external id. */
		function lookupTimeseries(input){
			var picked = {};
			new SearchDropdown(input, {
				minChars: 3,
				emptyText: L("no.results.found"),
				source: query => searchSeries(query, picked),
				applySelection: (label, el) => { el.value = picked[label] ? picked[label].externalId : label; }
			});
		}

		/**
		 * A list of series picked from DataHub: one row per series with its name, flagged when it does
		 * not exist or the user may not read it, and a search to add more. The hidden textarea holds
		 * the external ids, one per line, as the form submits them.
		 */
		function seriesPicker(box){
			var store = box.querySelector("textarea"), list = box.querySelector("ul"), search = box.querySelector("input");
			var known = {};   // externalId -> the series, or null when DataHub has none it may read
			var ids = () => store.value.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
			var save = next => { store.value = next.join("\n"); draw(); };

			function draw(){
				list.innerHTML = "";
				ids().forEach(id => {
					var ts = known[id];
					var li = document.createElement("li");
					if(ts === null) li.className = "missing";
					var name = Object.assign(document.createElement("span"), { className: "flow-series-name",
						textContent: ts ? ts.name || id : id });
					var detail = Object.assign(document.createElement("span"), { className: "flow-series-id",
						textContent: ts === null ? L("flows.series.missing") : ts ? id + (ts.unit ? " · " + ts.unit : "") : "" });
					var remove = Object.assign(document.createElement("button"), { type: "button",
						className: "flow-series-remove", textContent: "×", title: L("flows.series.remove") });
					remove.addEventListener("click", () => save(ids().filter(i => i !== id)));
					li.append(name, detail, remove);
					list.appendChild(li);
				});
				list.hidden = list.childElementCount === 0;
			}

			var unresolved = ids().filter(id => !(id in known) && id.indexOf("${") < 0);
			if(unresolved.length){
				FlowApi.api("/timeseries/byids", { items: unresolved.map(id => ({ externalId: id })) })
					.then(json => {
						unresolved.forEach(id => { known[id] = null; });
						(json.items || []).forEach(ts => { known[ts.externalId] = ts; });
					})
					.catch(err => FlowApi.flashError(err, "flows.error.load"))
					.then(draw);
			}
			draw();

			var picked = {};
			new SearchDropdown(search, {
				minChars: 0,
				emptyText: L("flows.series.none"),
				source: query => searchSeries(query, picked, ids()),
				applySelection: (label, el) => {
					el.value = "";
					var ts = picked[label];
					if(!ts) return;
					known[ts.externalId] = ts;
					save(ids().concat([ts.externalId]));
				}
			});
		}

		/** A processor's outcomes, from its catalog entry. */
		function relationshipsOf(spec){
			var entry = byType[spec.type];
			return entry ? (entry.relationships || []).map(r => r.name) : [];
		}

		/** A new flow's external id, from its name: Pump 3 alarms → pump_3_alarms. */
		function externalIdOf(name){
			return name.normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase()
				.replace(/æ/g, "ae").replace(/ø/g, "o").replace(/ß/g, "ss")
				.replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 128);
		}

		var writable = null;

		/**
		 * The datasets the user may write, which a flow can belong to: those datahub-api lists for
		 * them, narrowed by their write grants. Loaded once per page.
		 */
		function writableDataSets(){
			writable = writable || Promise.all([FlowApi.api("/datasets?limit=1000"), FlowApi.api("/me/datasets?access=write")])
				.then(answers => {
					var grants = answers[1], ids = (grants.ids || []).map(String);
					return (answers[0].items || [])
						.filter(d => d.externalId && (grants.all || ids.indexOf(String(d.id)) >= 0))
						.map(d => ({ value: d.externalId, label: d.name && d.name !== d.externalId ? d.name + " (" + d.externalId + ")" : d.externalId }))
						.sort((a, b) => a.label.localeCompare(b.label));
				});
			return writable;
		}

		/**
		 * A dataset select: the writable datasets, with {@code current} kept even when it is not one
		 * of them. {@code none} labels the empty choice.
		 */
		function fillDataSets(select, current, none){
			writableDataSets().then(list => {
				if(current && !list.some(d => d.value === current)) list = list.concat([{ value: current, label: current }]);
				fillSelect(select, [{ value: "", label: none || L("flows.field.dataset.choose") }].concat(list), false);
				select.value = current || "";
			}).catch(err => FlowApi.flashError(err, "flows.error.datasets"));
		}

		// Durations are ISO-8601 in the definition (PT1H30M) and written as 1h 30m in the forms.
		var DURATION_UNITS = [["d", 86400], ["h", 3600], ["m", 60], ["s", 1]];

		/** An ISO-8601 duration as 1h 30m; anything else (a reference, a typo) as it is. */
		function friendlyDuration(iso){
			if(!iso) return "";
			var m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso);
			if(!m || /^P$|T$/.test(iso)) return iso;
			var seconds = Number(m[1] || 0) * 86400 + Number(m[2] || 0) * 3600 + Number(m[3] || 0) * 60 + Number(m[4] || 0);
			var parts = [];
			DURATION_UNITS.forEach(u => {
				if(seconds >= u[1]){ parts.push(Math.floor(seconds / u[1]) + u[0]); seconds %= u[1]; }
			});
			return parts.length ? parts.join(" ") : "0s";
		}

		/** 1h 30m, 90 min or 2 days as ISO-8601; anything it does not read is left for the validator. */
		function isoDuration(value){
			if(!value) return value;
			var rest = value.trim().toLowerCase(), seconds = 0, m;
			var unit = /^(\d+)\s*(d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)(?![a-z])\s*,?\s*/;
			if(!rest || !unit.test(rest)) return value.trim();
			while((m = unit.exec(rest))){
				seconds += Number(m[1]) * DURATION_UNITS.find(u => u[0] === m[2][0])[1];
				rest = rest.slice(m[0].length);
			}
			if(rest) return value.trim();
			var days = Math.floor(seconds / 86400), h = Math.floor(seconds % 86400 / 3600),
				min = Math.floor(seconds % 3600 / 60), s = seconds % 60;
			var time = (h ? h + "H" : "") + (min ? min + "M" : "") + (s ? s + "S" : "");
			return "P" + (days ? days + "D" : "") + (time ? "T" + time : days ? "" : "T0S");
		}

		function removeProcessor(id){
			def.processors = (def.processors || []).filter(p => p.id !== id);
			def.connections = (def.connections || []).filter(c => c.from !== id && c.to !== id);
		}

		function newId(type){
			var entry = byType[type];
			var stem = (entry && entry.displayName ? entry.displayName : type.split(".").pop())
				.toLowerCase().replace(/[^a-z0-9_\-]+/g, "-");
			for(var i = 1; ; i++){
				var candidate = stem + (i === 1 ? "" : i);
				if(!processorById(candidate)) return candidate;
			}
		}

		// ---- drawing -------------------------------------------------------------------------------

		function palette(){
			var light = document.documentElement.getAttribute("data-theme") === "light";
			return light
				? { node: "#ffffff", text: "#17313d", edge: "#9fb9c8", edgeText: "#64808d", accent: "#f6783c", problem: "#d64545", end: "#c9d6de" }
				: { node: "#0c2233", text: "#e8f1f6", edge: "#4a6472", edgeText: "#c7d6e0", accent: "#ffb155", problem: "#ff6b6b", end: "#2b4452" };
		}

		function problemProcessors(){
			var ids = {};
			problems.forEach(p => {
				var m = /^\/processors\/(\d+)/.exec(p.field || "");
				if(m && def.processors[Number(m[1])]) ids[def.processors[Number(m[1])].id] = true;
			});
			return ids;
		}

		function elements(){
			var bad = problemProcessors();
			var out = [];
			(def.processors || []).forEach(p => {
				out.push({ data: { id: "p:" + p.id, pid: p.id, label: labelOf(p), kind: "processor" },
					classes: bad[p.id] ? "problem" : "" });
				(p.terminate || []).forEach(rel => {
					var endId = "end:" + p.id + ":" + rel;
					out.push({ data: { id: endId, label: "", kind: "end" } });
					out.push({ data: { id: "t:" + p.id + ":" + rel, source: "p:" + p.id, target: endId,
						label: rel, kind: "terminate", pid: p.id, rel: rel } });
				});
			});
			(def.connections || []).forEach((c, i) => {
				if(!processorById(c.from) || !processorById(c.to)) return;
				out.push({ data: { id: "c:" + i, source: "p:" + c.from, target: "p:" + c.to,
					label: c.relationship, kind: "connection", index: i } });
			});
			return out;
		}

		function style(){
			var c = palette();
			return [
				{ selector: "node[kind = 'processor']", style: {
					"shape": "round-rectangle", "background-color": c.node, "border-width": 1, "border-color": c.edge,
					"label": "data(label)", "color": c.text, "text-wrap": "wrap", "text-valign": "center",
					"font-size": 11, "width": "label", "height": "label", "padding": "10px" } },
				{ selector: "node.problem", style: { "border-color": c.problem, "border-width": 2 } },
				{ selector: "node[kind = 'end']", style: {
					"shape": "ellipse", "width": 8, "height": 8, "background-color": c.end, "border-width": 0 } },
				{ selector: "edge", style: {
					"curve-style": "bezier", "target-arrow-shape": "triangle", "width": 1.5,
					"line-color": c.edge, "target-arrow-color": c.edge, "label": "data(label)",
					"font-size": 10, "color": c.edgeText, "text-background-color": c.node,
					"text-background-opacity": 1, "text-background-padding": "2px" } },
				{ selector: "edge[kind = 'terminate']", style: {
					"line-style": "dashed", "target-arrow-shape": "none", "line-color": c.end, "color": c.end } },
				{ selector: ":selected", style: { "border-color": c.accent, "line-color": c.accent,
					"target-arrow-color": c.accent, "border-width": 2 } }
			];
		}

		function drawGraph(){
			var container = el("flow-canvas");
			if(typeof cytoscape !== "function") return;
			if(cy) cy.destroy();
			cy = cytoscape({
				container: container,
				elements: elements(),
				style: style(),
				// cytoscape-dagre registers the layout when its script loads (see flows/flow.html).
				layout: { name: "dagre", rankDir: "LR", nodeSep: 30, rankSep: 90, fit: true, padding: 30 },
				wheelSensitivity: 0.3,
				// Fitting a flow of one or two processors would otherwise blow them up to the canvas.
				maxZoom: 1.25
			});
			selected = null;
			el("flow-remove").disabled = true;
			cy.on("tap", "node[kind = 'processor']", function(e){
				var spec = processorById(e.target.data("pid"));
				if(spec) new ProcessorForm(spec, byType[spec.type]);
			});
			cy.on("select", "node[kind = 'processor'], edge", function(e){
				selected = e.target.data();
				el("flow-remove").disabled = false;
			});
			cy.on("unselect", function(){
				selected = null;
				el("flow-remove").disabled = true;
			});
		}

		function fillSelect(select, values, keep){
			var previous = keep ? select.value : null;
			select.innerHTML = "";
			values.forEach(v => select.appendChild(Object.assign(document.createElement("option"),
				{ value: v.value, textContent: v.label })));
			if(previous && values.some(v => v.value === previous)) select.value = previous;
		}

		function drawToolbar(){
			var ids = (def.processors || []).map(p => ({ value: p.id, label: labelOf(p) }));
			fillSelect(el("flow-connect-from"), ids, true);
			fillSelect(el("flow-connect-to"), ids, true);
			drawRelationships();
		}

		function drawRelationships(){
			var spec = processorById(el("flow-connect-from").value);
			fillSelect(el("flow-connect-rel"), spec ? relationshipsOf(spec).map(r => ({ value: r, label: r })) : [], true);
		}

		function drawProblems(){
			var list = el("flow-problems");
			list.innerHTML = "";
			problems.forEach(p => {
				var li = document.createElement("li");
				li.textContent = (p.field ? p.field + ": " : "") + p.message;
				list.appendChild(li);
			});
			list.hidden = problems.length === 0;
		}

		function stateLine(){
			if(!flow) return L("flows.state.new");
			var parts = [L("flows.state.version", [flow.version])];
			if(flow.disabledReason) parts.push(L("flows.state.disabled.reason", [flow.disabledReason]));
			if(!deployment){
				parts.push(L("flows.state.not.deployed"));
			} else {
				parts.push(L(deployment.enabled ? "flows.state.enabled" : "flows.state.disabled", [deployment.version]));
				if(deployment.runAs) parts.push(L(deployment.identity === "client" ? "flows.state.runs.as.client" : "flows.state.runs.as.deployer", [deployment.runAs]));
				if(deployment.identityState === "needs-reauthorisation"){
					parts.push(L("flows.state.reauthorise", [deployment.identityStateReason || ""]));
				}
				if(deployment.nextDueAt) parts.push(L("flows.state.next", [window.formatLocalTime(deployment.nextDueAt)]));
			}
			if(isDirty()) parts.push(L("flows.state.unsaved"));
			return parts.join(" · ");
		}

		function drawHeader(){
			el("flow-title").textContent = flowId ? (def.name ? def.name + " · " : "") + flowId : L("flows.new.title");
			var state = el("flow-state");
			state.textContent = stateLine();
			state.classList.toggle("flows-warn", !!(deployment && deployment.identityState === "needs-reauthorisation") || !!(flow && flow.disabledReason));
			el("flow-new").hidden = !!flow;
			var saved = !!flow && !isDirty();
			el("flow-run").hidden = !(saved && deployment && deployment.enabled);
			el("flow-deploy").hidden = !saved;
			el("flow-deploy").textContent = L(deployment ? "flows.redeploy" : "flows.deploy");
			el("flow-run-as").hidden = !saved;
			el("flow-toggle").hidden = !(saved && deployment);
			el("flow-toggle").textContent = L(deployment && deployment.enabled ? "flows.disable" : "flows.enable");
			el("flow-delete").hidden = !flow;
		}

		function drawJson(){
			var area = el("flow-json");
			if(document.activeElement !== area) area.value = JSON.stringify(def, null, 2);
		}

		/** After every edit: redraw everything from the definition. */
		function changed(){
			drawGraph();
			drawToolbar();
			drawJson();
			drawHeader();
			drawProblems();
		}

		// ---- actions -------------------------------------------------------------------------------

		/** The definition as it will be sent: a new flow takes its name and dataset from the form above the tree. */
		function outgoing(){
			if(!flow){
				var form = el("flow-new");
				if(!def.name) def.name = form.elements.name.value.trim();
				if(!def.dataSet) def.dataSet = form.elements.dataSet.value.trim();
			}
			return JSON.stringify(def);
		}

		function showProblems(list){
			problems = list || [];
			changed();
		}

		function problemsOf(err){
			if(err instanceof DataHubProblem && Array.isArray(err.body.fields)){
				return err.body.fields.map(f => ({ field: f.field, message: f.message }));
			}
			return [];
		}

		function readOnly(){
			el("flow-readonly").hidden = false;
			["flow-save", "flow-deploy", "flow-run-as", "flow-toggle", "flow-delete", "flow-run", "flow-preview"]
				.forEach(t => { el(t).disabled = true; });
		}

		function refuse(err, fallbackKey){
			if(err instanceof DataHubProblem && err.status === 403 && err.slug === "forbidden") readOnly();
			var list = problemsOf(err);
			if(list.length) showProblems(list);
			// The service's own refusals that the shared reader only knows by status, which would
			// say "signed out" for a short-lived token or "unavailable" for a full instance.
			if(err instanceof DataHubProblem && err.slug === "token-expiring"){ Flash.error(L("flows.error.token.expiring")); return; }
			if(err instanceof DataHubProblem && err.slug === "busy"){ Flash.error(L("flows.error.busy")); return; }
			FlowApi.flashError(err, fallbackKey);
		}

		function validate(){
			return FlowApi.post("/flows/validate", outgoing())
				.then(answer => {
					showProblems(answer.problems);
					if(answer.valid){
						var series = answer.series && answer.series.length ? " " + L("flows.validate.series", [answer.series.join(", ")]) : "";
						Flash.info(L("flows.validate.ok") + series);
					} else {
						Flash.error(L("flows.validate.failed", [answer.problems.length]));
					}
				})
				.catch(err => refuse(err, "flows.error.validate"));
		}

		function save(){
			var body = outgoing();
			var call = flow ? FlowApi.put("/flows/" + FlowApi.seg(flowId), body) : FlowApi.post("/flows", body);
			return call.then(view => {
				if(!flow){
					// Created: from here on the page is the flow's own.
					history.replaceState(null, "", "/flows/" + FlowApi.seg(flowId));
					page.setAttribute("data-flow-id", flowId);
				}
				flow = view;
				def = view.definition || def;
				savedJson = JSON.stringify(def);
				problems = [];
				changed();
				var created = view.createdSeries && view.createdSeries.length
					? { details: view.createdSeries.map(s => L("flows.saved.series", [s])) } : undefined;
				Flash.info(L("flows.saved", [view.version]), created);
			}).catch(err => {
				if(err instanceof DataHubProblem && err.status === 409 && flow){
					Flash.error(L("flows.error.conflict"));
					return;
				}
				refuse(err, "flows.error.save");
			});
		}

		function openRun(run){
			window.location.href = "/flows/" + FlowApi.seg(flowId) + "/runs/" + FlowApi.seg(run.id);
		}

		function preview(){
			var body = flow && !isDirty() ? { flow: flowId, version: flow.version } : { definition: JSON.parse(outgoing()) };
			return FlowApi.post("/previews", body).then(openRun).catch(err => refuse(err, "flows.error.preview"));
		}

		function runNow(){
			return FlowApi.post("/flows/" + FlowApi.seg(flowId) + "/runs", {}).then(openRun)
				.catch(err => refuse(err, "flows.error.run"));
		}

		function deploy(){
			return FlowApi.post("/flows/" + FlowApi.seg(flowId) + "/deployment/authorize",
				{ version: flow.version, enabled: true })
				.then(answer => { window.location.href = answer.authorizationUrl; })
				.catch(err => refuse(err, "flows.error.deploy"));
		}

		function runAs(){
			var name = window.prompt(L("flows.run.as.prompt"), deployment && deployment.identity === "client" ? deployment.runAs : "");
			if(!name) return;
			return FlowApi.put("/flows/" + FlowApi.seg(flowId) + "/deployment",
				{ runAs: name.trim(), version: flow.version, enabled: true })
				.then(d => { deployment = d; drawHeader(); Flash.info(L("flows.run.as.done", [name.trim()])); })
				.catch(err => refuse(err, "flows.error.deploy"));
		}

		function toggle(){
			return FlowApi.put("/flows/" + FlowApi.seg(flowId) + "/deployment", { enabled: !deployment.enabled })
				.then(d => { deployment = d; drawHeader(); })
				.catch(err => refuse(err, "flows.error.deploy"));
		}

		function remove(){
			if(!confirm(L("flows.delete.confirm", [flowId]))) return;
			return FlowApi.del("/flows/" + FlowApi.seg(flowId))
				.then(() => { savedJson = JSON.stringify(def); window.location.href = "/flows"; })
				.catch(err => refuse(err, "flows.error.delete"));
		}

		// The catalog's segments, in the order the add panel lists them.
		var CATEGORIES = ["read-write", "transform", "detect"];

		function toggleAddPanel(open){
			var panel = el("flow-add-panel");
			panel.hidden = open === undefined ? !panel.hidden : !open;
			if(!panel.hidden){
				el("flow-add-search").value = "";
				drawAddList();
				el("flow-add-search").focus();
			}
		}

		/** The processors matching the search, under their segment's heading. */
		function drawAddList(){
			var query = el("flow-add-search").value.trim().toLowerCase();
			var list = el("flow-add-list");
			list.innerHTML = "";
			var order = CATEGORIES.concat(catalog.map(e => e.category).filter(c => CATEGORIES.indexOf(c) < 0));
			order.filter((c, i) => order.indexOf(c) === i).forEach(category => {
				var entries = catalog.filter(e => e.category === category && (!query
					|| [e.displayName, e.name, e.description].join(" ").toLowerCase().indexOf(query) >= 0));
				if(!entries.length) return;
				list.appendChild(Object.assign(document.createElement("h4"), { textContent: L("flows.category." + category) }));
				entries.forEach(e => {
					var item = Object.assign(document.createElement("button"), { type: "button", className: "flow-add-item" });
					item.append(Object.assign(document.createElement("strong"), { textContent: e.displayName || e.name }),
						Object.assign(document.createElement("span"), { textContent: e.description || "" }));
					item.addEventListener("click", () => { toggleAddPanel(false); addProcessor(e.name); });
					list.appendChild(item);
				});
			});
			if(!list.childElementCount){
				list.appendChild(Object.assign(document.createElement("p"), { className: "flows-hint", textContent: L("no.results.found") }));
			}
		}

		function addProcessor(type){
			var id = newId(type);
			def.processors = def.processors || [];
			def.processors.push({ id: id, type: type, properties: {} });
			changed();
			new ProcessorForm(processorById(id), byType[type]);
		}

		function connect(){
			var from = el("flow-connect-from").value, rel = el("flow-connect-rel").value, to = el("flow-connect-to").value;
			if(!from || !rel || !to) return;
			if(from === to){ Flash.error(L("flows.error.self.connection")); return; }
			def.connections = def.connections || [];
			if(def.connections.some(c => c.from === from && c.relationship === rel && c.to === to)) return;
			def.connections.push({ from: from, relationship: rel, to: to });
			var spec = processorById(from);
			if(spec && spec.terminate) spec.terminate = spec.terminate.filter(r => r !== rel);
			if(spec && spec.terminate && !spec.terminate.length) delete spec.terminate;
			changed();
		}

		function removeSelected(){
			if(!selected) return;
			if(selected.kind === "processor"){
				if(!confirm(L("flows.remove.processor.confirm", [labelOf(processorById(selected.pid))]))) return;
				removeProcessor(selected.pid);
			} else if(selected.kind === "connection"){
				def.connections.splice(selected.index, 1);
			} else if(selected.kind === "terminate"){
				var spec = processorById(selected.pid);
				spec.terminate = (spec.terminate || []).filter(r => r !== selected.rel);
				if(!spec.terminate.length) delete spec.terminate;
			}
			changed();
		}

		function applyJson(){
			try {
				var parsed = JSON.parse(el("flow-json").value);
				if(!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
				def = parsed;
				changed();
			} catch(e){
				Flash.error(L("flows.error.json", [e.message]));
			}
		}

		// ---- tabs, versions, runs ------------------------------------------------------------------

		function showTab(name){
			page.querySelectorAll(".flow-tabs button").forEach(b => b.classList.toggle("selected", b.getAttribute("data-tab") === name));
			page.querySelectorAll(".flow-pane").forEach(p => { p.hidden = p.getAttribute("data-pane") !== name; });
			if(name === "tree" && cy){ cy.resize(); cy.fit(undefined, 30); }
			if(name === "json") drawJson();
			if(name === "versions") loadVersions();
			if(name === "runs" && flow) FlowRuns.list(page, flowId);
		}

		function loadVersions(){
			if(!flow) return;
			var tbody = el("flow-versions").querySelector("tbody");
			FlowApi.get("/flows/" + FlowApi.seg(flowId) + "/versions").then(list => {
				tbody.innerHTML = "";
				list.forEach(v => {
					var tr = document.createElement("tr");
					[String(v.version), v.createdBy || "", window.formatLocalTime(v.createdAt)].forEach(t => {
						tr.appendChild(Object.assign(document.createElement("td"), { textContent: t }));
					});
					var td = document.createElement("td");
					var open = Object.assign(document.createElement("button"),
						{ type: "button", className: "dh-btn secondary small", textContent: L("flows.version.open") });
					open.addEventListener("click", () => {
						FlowApi.get("/flows/" + FlowApi.seg(flowId) + "/versions/" + v.version).then(full => {
							def = full.definition;
							changed();
							showTab("tree");
							Flash.info(L("flows.version.opened", [v.version]));
						}).catch(err => FlowApi.flashError(err, "flows.error.load"));
					});
					td.appendChild(open);
					tr.appendChild(td);
					tbody.appendChild(tr);
				});
			}).catch(err => FlowApi.flashError(err, "flows.error.load"));
		}

		// ---- start ---------------------------------------------------------------------------------

		function deployReturn(){
			var params = new URLSearchParams(window.location.search);
			if(params.get("deployed")){
				Flash.info(L("flows.deployed"));
			} else if(params.get("deployError")){
				var code = params.get("deployError");
				var key = "flows.deploy.error." + code;
				var message = $L(key);
				Flash.error(message && message !== key ? message : L("flows.deploy.error", [code]));
			}
			if(params.get("deployed") || params.get("deployError")){
				history.replaceState(null, "", window.location.pathname);
			}
		}

		function load(){
			var notFound = err => (err instanceof DataHubProblem && err.status === 404) ? null : Promise.reject(err);
			return Promise.all([
				FlowApi.get("/catalog/processors"),
				flowId ? FlowApi.get("/flows/" + FlowApi.seg(flowId)).catch(notFound) : null
			]).then(results => {
				catalog = results[0] || [];
				catalog.forEach(e => { byType[e.name] = e; });
				flow = results[1];
				def = flow ? flow.definition : { schemaVersion: 1, externalId: flowId, name: "", dataSet: "",
					processors: [], connections: [], trigger: { type: "schedule", cron: "0 0 * * * *", window: "PT1H" } };
				savedJson = flow ? JSON.stringify(def) : null;
				if(!flow) fillDataSets(el("flow-new").elements.dataSet, def.dataSet);
				return flow ? FlowApi.get("/flows/" + FlowApi.seg(flowId) + "/deployment").catch(notFound) : null;
			}).then(d => {
				deployment = d;
				changed();
				deployReturn();
			}).catch(err => {
				el("flow-state").textContent = FlowApi.reason(err, "flows.error.load");
				FlowApi.flashError(err, "flows.error.load");
			});
		}

		var on = (type, fn) => el(type).addEventListener("click", fn);
		on("flow-validate", validate);
		on("flow-save", save);
		on("flow-preview", preview);
		on("flow-run", runNow);
		on("flow-deploy", deploy);
		on("flow-run-as", runAs);
		on("flow-toggle", toggle);
		on("flow-delete", remove);
		on("flow-add", () => toggleAddPanel());
		el("flow-add-search").addEventListener("input", drawAddList);
		el("flow-add-search").addEventListener("keydown", e => {
			if(e.key === "Escape") toggleAddPanel(false);
			if(e.key === "Enter"){
				e.preventDefault();
				var first = el("flow-add-list").querySelector(".flow-add-item");
				if(first) first.click();
			}
		});
		document.addEventListener("pointerdown", e => {
			if(!el("flow-add-panel").hidden && !e.target.closest(".flow-add")) toggleAddPanel(false);
		});
		on("flow-connect", connect);
		on("flow-remove", removeSelected);
		on("flow-json-apply", applyJson);
		on("flow-settings", () => new FlowSettingsForm());
		el("flow-connect-from").addEventListener("change", drawRelationships);
		var onNewFlow = () => {
			var form = el("flow-new");
			def.name = form.elements.name.value.trim();
			def.dataSet = form.elements.dataSet.value;
			if(idFromName){
				flowId = def.externalId = externalIdOf(def.name);
				el("flow-new-id").textContent = flowId ? L("flows.new.id", [flowId]) : "";
			}
			drawJson();
			drawHeader();
		};
		el("flow-new").addEventListener("input", onNewFlow);
		el("flow-new").addEventListener("change", onNewFlow);
		page.querySelectorAll(".flow-tabs button").forEach(b => b.addEventListener("click", () => showTab(b.getAttribute("data-tab"))));
		window.addEventListener("beforeunload", e => {
			if(def && isDirty() && (flow || (def.processors || []).length)){ e.preventDefault(); e.returnValue = ""; }
		});

		window.i18nReady.then(load);
	});
})();
