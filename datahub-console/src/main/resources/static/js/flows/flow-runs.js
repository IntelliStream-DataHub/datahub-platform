/*
 * Runs: the list on a flow's page (FlowRuns.list), and the run page.
 *
 * The run page reads GET {flow}/runs/{id}. A run that has ended shows its records a page at a time
 * from GET /runs/{id}/records; one still going follows the live view, GET /runs/{id}/events
 * (FlowRunStream), which writes the run again whenever its status changes and its records as they
 * land. The live view starts from the run's first record every time it connects, so records are
 * kept by (attempt, sequence, ordinal) and a reconnect adds nothing twice.
 */
(function(){
	"use strict";

	var ENDED = ["SUCCEEDED", "FAILED", "CANCELLED"];

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	function windowText(w){
		if(!w) return "";
		return window.formatLocalTime(w.start) + " – " + window.formatLocalTime(w.end);
	}

	function statusText(run){
		var s = L("flows.status." + run.status.toLowerCase());
		if(run.cancelRequested && ENDED.indexOf(run.status) < 0) s += " · " + L("flows.status.cancelling");
		if(run.preview) s += " · " + L("flows.preview");
		return s;
	}

	function runPath(flowId, run){
		return "/flows/" + FlowApi.seg(run.flow || flowId) + "/runs/" + FlowApi.seg(run.id);
	}

	// ---- the list on a flow's page -------------------------------------------------------------

	var listed = null;   // what the table shows, so switching back to the tab does not reload it
	var runsCursor = null;
	var loadRuns = null; // set on the first list(); the load-more button and the toggle call it

	window.FlowRuns = {
		list: function(page, flowId){
			var previews = page.querySelector('[data-type="flow-runs-previews"]');
			var tbody = page.querySelector('[data-type="flow-runs"] tbody');
			var empty = page.querySelector('[data-type="flow-runs-empty"]');
			var more = page.querySelector('[data-type="flow-runs-more"]');
			var key = flowId + "|" + previews.checked;
			if(listed === key) return;
			listed = key;
			tbody.innerHTML = "";
			runsCursor = null;

			if(!loadRuns){
				more.addEventListener("click", function(){ loadRuns(); });
				previews.addEventListener("change", function(){ window.FlowRuns.list(page, flowId); });
			}
			loadRuns = function(){
				var cursor = runsCursor;
				var q = "?flow=" + encodeURIComponent(flowId) + "&limit=50"
					+ (previews.checked ? "&preview=true" : "") + (cursor ? "&cursor=" + encodeURIComponent(cursor) : "");
				FlowApi.get("/runs" + q).then(function(wrapper){
					(wrapper.items || []).forEach(function(run){
						var tr = document.createElement("tr");
						[statusText(run), windowText(run.window), run.trigger + (run.triggeredBy ? " · " + run.triggeredBy : ""),
							run.attempt + "/" + run.maxAttempts, window.formatLocalTime(run.createdAt)].forEach(function(t, i){
							var td = Object.assign(document.createElement("td"), { textContent: t });
							if(i === 0) td.className = "run-status-" + run.status.toLowerCase();
							tr.appendChild(td);
						});
						tr.addEventListener("click", function(){ window.location.href = runPath(flowId, run); });
						tbody.appendChild(tr);
					});
					runsCursor = wrapper.nextCursor || null;
					more.hidden = !runsCursor;
					empty.hidden = tbody.children.length > 0;
				}).catch(function(err){ FlowApi.flashError(err, "flows.error.load"); });
			};
			loadRuns();
		}
	};

	// ---- the run page --------------------------------------------------------------------------

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="run-page"]');
		if(!page) return;
		var flowId = page.getAttribute("data-flow-id");
		var runId = page.getAttribute("data-run-id");
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var tbody = el("run-records").querySelector("tbody");
		var filter = el("run-records-processor");
		var seen = {};
		var processors = {};
		var cursor = null;
		var stream = null;
		var current = null;

		function fact(dl, label, value){
			if(value === null || value === undefined || value === "") return;
			var div = document.createElement("div");
			div.appendChild(Object.assign(document.createElement("dt"), { textContent: label }));
			var dd = document.createElement("dd");
			if(typeof value === "object"){
				dd.appendChild(Object.assign(document.createElement("pre"), { textContent: JSON.stringify(value, null, 2) }));
			} else {
				dd.textContent = value;
			}
			div.appendChild(dd);
			dl.appendChild(div);
		}

		function drawRun(run){
			current = run;
			var status = el("run-status");
			status.textContent = statusText(run);
			status.className = "run-status run-status-" + run.status.toLowerCase();
			var dl = el("run-facts");
			dl.innerHTML = "";
			fact(dl, L("flows.column.version"), run.version);
			fact(dl, L("flows.column.attempt"), run.attempt + "/" + run.maxAttempts);
			fact(dl, L("flows.column.trigger"), run.trigger + (run.triggeredBy ? " · " + run.triggeredBy : ""));
			fact(dl, L("flows.column.runs.as"), run.runAs);
			fact(dl, L("flows.column.window"), windowText(run.window));
			fact(dl, L("flows.column.started"), run.startedAt ? window.formatLocalTime(run.startedAt) : null);
			fact(dl, L("flows.column.finished"), run.finishedAt ? window.formatLocalTime(run.finishedAt) : null);
			fact(dl, L("flows.column.error"), run.errorCode ? run.errorCode + (run.error ? ": " + run.error : "") : null);
			fact(dl, L("flows.column.parameters"), run.parameters && Object.keys(run.parameters).length ? run.parameters : null);
			fact(dl, L("flows.column.processor.versions"), run.processorVersions && Object.keys(run.processorVersions).length ? run.processorVersions : null);
			var stats = run.stats || {};
			var log = Array.isArray(stats.log) ? stats.log : null;
			var logBox = el("run-log");
			logBox.hidden = !log || !log.length;
			if(log) logBox.querySelector("pre").textContent = log.join("\n");
			var ended = ENDED.indexOf(run.status) >= 0;
			el("run-cancel").hidden = ended || run.cancelRequested;
			el("run-rerun").hidden = !ended || run.preview;
		}

		function shortId(id){ return id ? String(id).slice(0, 8) : ""; }

		function addRecords(records){
			records.forEach(function(r){
				var key = r.attempt + ":" + r.sequence + ":" + r.ordinal;
				if(seen[key]) return;
				seen[key] = true;
				if(r.processor && !processors[r.processor]){
					processors[r.processor] = true;
					filter.appendChild(Object.assign(document.createElement("option"), { value: r.processor, textContent: r.processor }));
				}
				var tr = document.createElement("tr");
				tr.setAttribute("data-processor", r.processor || "");
				var changed = r.changedAttributes && Object.keys(r.changedAttributes).length
					? Object.keys(r.changedAttributes).map(function(k){ return k + "=" + r.changedAttributes[k]; }).join(", ") : "";
				var content = r.contentKind ? r.contentKind + (r.size ? " · " + r.size : "") : "";
				[window.formatLocalTime(r.time, { seconds: true }), String(r.attempt), r.processor || "",
					r.action + (r.truncated ? " · " + L("flows.run.truncated") : ""), r.relationship || "",
					shortId(r.itemId), content, r.transitUri || "", changed].forEach(function(t){
					tr.appendChild(Object.assign(document.createElement("td"), { textContent: t, title: t }));
				});
				tr.hidden = !!filter.value && filter.value !== r.processor;
				tbody.appendChild(tr);
			});
			el("run-records-empty").hidden = tbody.children.length > 0;
		}

		function loadPage(){
			var q = "?limit=1000" + (cursor ? "&cursor=" + encodeURIComponent(cursor) : "");
			return FlowApi.get("/runs/" + FlowApi.seg(runId) + "/records" + q).then(function(wrapper){
				addRecords(wrapper.items || []);
				cursor = wrapper.nextCursor || null;
				el("run-records-more").hidden = !cursor;
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.load"); });
		}

		function follow(){
			var live = el("run-live");
			live.hidden = false;
			stream = new FlowRunStream({
				runId: runId,
				onEvent: function(event){
					if(event.type === "run" && event.run) drawRun(event.run);
					if(event.type === "records") addRecords(event.records || []);
				},
				onStatus: function(state, problem){
					live.textContent = L("flows.live." + state);
					if(state === "ended"){ live.hidden = true; }
					if(state === "failed" && problem) problem.flash("flows.error.load");
				}
			}).open();
		}

		filter.addEventListener("change", function(){
			Array.from(tbody.children).forEach(function(tr){
				tr.hidden = !!filter.value && tr.getAttribute("data-processor") !== filter.value;
			});
		});
		el("run-records-more").addEventListener("click", loadPage);
		el("run-cancel").addEventListener("click", function(){
			FlowApi.post("/runs/" + FlowApi.seg(runId) + "/cancel", {}).then(drawRun)
				.catch(function(err){ FlowApi.flashError(err, "flows.error.cancel"); });
		});
		el("run-rerun").addEventListener("click", function(){
			FlowApi.post("/runs/" + FlowApi.seg(runId) + "/rerun", {}).then(function(run){
				window.location.href = runPath(flowId, run);
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.run"); });
		});
		window.addEventListener("beforeunload", function(){ if(stream) stream.close(); });

		window.i18nReady.then(function(){
			return FlowApi.get("/runs/" + FlowApi.seg(runId));
		}).then(function(run){
			drawRun(run);
			if(ENDED.indexOf(run.status) >= 0) return loadPage();
			follow();
		}).catch(function(err){
			el("run-status").textContent = FlowApi.reason(err, "flows.error.load");
			FlowApi.flashError(err, "flows.error.load");
		});
	});
})();
