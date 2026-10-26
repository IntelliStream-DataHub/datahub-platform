/*
 * Runs: the list on a flow's page (FlowRuns.list), and the run page.
 *
 * The run page reads GET {flow}/runs/{id}. A run that has ended shows its records a page at a time
 * from GET /runs/{id}/records; one still going follows the live view, GET /runs/{id}/events
 * (FlowRunStream), which writes the run again whenever its status changes and its records as they
 * land. The live view starts from the run's first record every time it connects, so records are
 * kept by (attempt, sequence, ordinal) and a reconnect adds nothing twice.
 *
 * Items are numbered in the order they first appear in the run and named after the series or events
 * they carry. Clicking a record shows only that item's records and those of the items it came from
 * or turned into: following one piece of data through the flow. Fork and copy records name the parent, which is how the two are linked.
 */
(function(){
	"use strict";

	var ENDED = ["SUCCEEDED", "FAILED", "CANCELLED"];

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	// What a record's action is called. A drop with a relationship is an item reaching the end of
	// the flow; one without is the processor removing it.
	function actionText(r){
		if(r.action === "drop") return L(r.relationship ? "flows.action.end" : "flows.action.removed");
		var key = "flows.action." + r.action;
		var text = L(key);
		return text === key ? r.action : text;
	}

	// A write with a relationship ended the item there; there is no separate record of its end.
	function outcomeText(r){
		if(!r.relationship) return "";
		return r.action === "send" ? L("flows.outcome.ended", [r.relationship]) : r.relationship;
	}

	// DataHub's own transit URIs, datahub://timeseries/{externalId}?from=&to= and
	// datahub://events?type=&subType=&from=&to=, in words; anything else as it is.
	function parseTransit(uri){
		var m = /^datahub:\/\/(timeseries)\/([^?]*)(?:\?(.*))?$/.exec(uri) || /^datahub:\/\/(events)()(?:\?(.*))?$/.exec(uri);
		if(!m) return null;
		var q = new URLSearchParams(m[3] || "");
		var what = m[1] === "timeseries" ? decodeURIComponent(m[2])
			: (q.get("type") || "*") + (q.get("subType") ? " / " + q.get("subType") : "");
		return { kind: m[1], what: what, from: q.get("from"), to: q.get("to") };
	}

	function transitText(uri){
		if(!uri) return "";
		var t = parseTransit(uri);
		if(!t) return uri;
		var text = L("flows.transit." + t.kind, [t.what]);
		if(t.from && t.to) text += " · " + window.formatLocalTime(t.from, { seconds: true }) + " – " + window.formatLocalTime(t.to, { seconds: true });
		return text;
	}
	// How much data a record's content holds, in the unit of its kind.
	var CONTENT_KEYS = { timeseries: "flows.content.timeseries", events: "flows.content.events" };

	function contentText(r){
		if(!r.contentKind) return "";
		var n = Number(r.size || 0).toLocaleString(document.documentElement.lang || undefined);
		return CONTENT_KEYS[r.contentKind] ? L(CONTENT_KEYS[r.contentKind], [n]) : r.contentKind + " · " + n;
	}

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

	/** Where a run's page is: in Flows, or in Agents when the page says data-section="agents". */
	function runPath(section, flowId, run){
		return "/" + (section || "flows") + "/" + FlowApi.seg(run.flow || flowId) + "/runs/" + FlowApi.seg(run.id);
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
			var section = page.getAttribute("data-section");
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
						tr.addEventListener("click", function(){ window.location.href = runPath(section, flowId, run); });
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
		var section = page.getAttribute("data-section");
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var tbody = el("run-records").querySelector("tbody");
		var filter = el("run-records-processor");
		var seen = {};
		var processors = {};
		var itemNumbers = {};   // item id -> its number in this run, from 1
		var itemNames = {};     // item id -> the series or events it carries, where a record says
		var parentsOf = {};     // item id -> the ids of the items it came from
		var following = null;   // the item id being followed, or null
		var followingBox = el("run-records-following");
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

		function itemNumber(id){
			if(!itemNumbers[id]) itemNumbers[id] = Object.keys(itemNumbers).length + 1;
			return itemNumbers[id];
		}

		/**
		 * Names an item after the data it carries: its timeseries.externalId attribute, else what
		 * its read named, else what the item it came from carried. Later records do not rename it.
		 */
		function nameItem(r){
			if(itemNames[r.itemId]) return;
			var attrs = r.changedAttributes || {};
			var t = r.action === "receive" && r.transitUri ? parseTransit(r.transitUri) : null;
			var parent = (r.parentItemIds || []).map(function(p){ return itemNames[p]; }).filter(Boolean)[0];
			var name = attrs["timeseries.externalId"] || (t && t.what) || parent;
			if(name) itemNames[r.itemId] = name;
		}

		function itemText(id){
			return "#" + itemNumber(id) + (itemNames[id] ? " · " + itemNames[id] : "");
		}

		/** The item and every item it came from, through fork and copy records. */
		function ancestors(id){
			var found = {};
			var stack = [id];
			while(stack.length){
				var a = stack.pop();
				if(found[a]) continue;
				found[a] = true;
				(parentsOf[a] || []).forEach(function(p){ stack.push(p); });
			}
			return found;
		}

		/** Whether a record's item is the followed one, one it came from, or one that came from it. */
		function related(itemId){
			return ancestors(following)[itemId] || ancestors(itemId)[following];
		}

		function applyFilters(){
			Array.from(tbody.children).forEach(function(tr){
				var item = tr.getAttribute("data-item");
				tr.hidden = (!!filter.value && tr.getAttribute("data-processor") !== filter.value)
					|| (!!following && !(item && related(item)));
			});
			followingBox.hidden = !following;
			if(following){
				followingBox.querySelector("span").textContent = L("flows.run.records.following", [itemText(following)]);
			}
		}

		function followItem(id){
			following = following === id ? null : id;
			applyFilters();
		}

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
				if(r.parentItemIds && r.parentItemIds.length) parentsOf[r.itemId] = r.parentItemIds;
				// The cap's marker row has no item: it is not something to follow.
				var item = r.truncated || !r.itemId ? null : itemNumber(r.itemId);
				if(item) nameItem(r);
				if(item){
					tr.setAttribute("data-item", r.itemId);
					tr.classList.add("pointer");
					tr.addEventListener("click", function(){ followItem(r.itemId); });
				}
				var changed = r.changedAttributes && Object.keys(r.changedAttributes).length
					? Object.keys(r.changedAttributes).map(function(k){ return k + "=" + r.changedAttributes[k]; }).join(", ") : "";
				[window.formatLocalTime(r.time, { seconds: true }), String(r.attempt), r.processor || "",
					actionText(r) + (r.truncated ? " · " + L("flows.run.truncated") : ""), outcomeText(r),
					item ? itemText(r.itemId) : "", contentText(r), transitText(r.transitUri), changed].forEach(function(t, i){
					var td = Object.assign(document.createElement("td"), { textContent: t, title: t });
					if(i === 5 && item) td.title = r.itemId;
					if(i === 7 && r.transitUri) td.title = r.transitUri;
					tr.appendChild(td);
				});
				tbody.appendChild(tr);
			});
			applyFilters();
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

		filter.addEventListener("change", applyFilters);
		followingBox.querySelector("button").addEventListener("click", function(){ followItem(following); });
		el("run-records-more").addEventListener("click", loadPage);
		el("run-cancel").addEventListener("click", function(){
			FlowApi.post("/runs/" + FlowApi.seg(runId) + "/cancel", {}).then(drawRun)
				.catch(function(err){ FlowApi.flashError(err, "flows.error.cancel"); });
		});
		el("run-rerun").addEventListener("click", function(){
			FlowApi.post("/runs/" + FlowApi.seg(runId) + "/rerun", {}).then(function(run){
				window.location.href = runPath(section, flowId, run);
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
