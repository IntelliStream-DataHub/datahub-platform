/*
 * The transcript on an agent's run page: what the model was given, said and did, from
 * GET {flow}/runs/{id}/transcript, polled every two seconds with the row after the last one seen
 * while the run has not ended. The run's facts and records on the same page are flows/flow-runs.js's.
 *
 * Only people who may change the agent can read it (the rows hold tool results): a 403 leaves the
 * panel with a line saying so, and the rest of the page stands.
 */
(function(){
	"use strict";

	var ENDED = ["SUCCEEDED", "FAILED", "CANCELLED"];
	var POLL_MS = 2000;
	var RESULT_CHARS = 4000;
	var PREVIEW_CHARS = 160;

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="run-page"][data-section="agents"]');
		if(!page) return;
		var runId = page.getAttribute("data-run-id");
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var rows = el("transcript-rows");
		var after = -1;
		var attempt = null;
		var calls = 0, tokensIn = 0, tokensOut = 0;

		function details(summary, text, cls){
			var d = Object.assign(document.createElement("details"), { className: cls || "" });
			d.appendChild(Object.assign(document.createElement("summary"), { textContent: summary }));
			d.appendChild(Object.assign(document.createElement("pre"), { textContent: text }));
			return d;
		}

		function json(value){
			try { return JSON.stringify(value, null, 2); } catch(e){ return String(value); }
		}

		/**
		 * One line of a tool result, shown on its fold so the run can be followed without opening
		 * each: a list's count and first names, otherwise the text itself, cut short.
		 */
		function preview(text){
			var line = text;
			try {
				var value = JSON.parse(text);
				var items = Array.isArray(value) ? value : value && Array.isArray(value.items) ? value.items : null;
				if(items){
					var names = items.slice(0, 3).map(function(i){
						return i && typeof i === "object" ? i.name || i.externalId || i.id || "" : String(i);
					}).filter(Boolean);
					line = L("agents.transcript.items", [items.length]) + (names.length ? ": " + names.join(", ")
						+ (items.length > names.length ? ", …" : "") : "");
				} else if(value && typeof value === "object"){
					line = JSON.stringify(value);
				}
			} catch(e){ /* not JSON: the text as it is */ }
			line = line.replace(/\s+/g, " ").trim();
			return line.length > PREVIEW_CHARS ? line.slice(0, PREVIEW_CHARS) + "…" : line;
		}

		function outcome(args){
			var badge = el("transcript-outcome");
			var key = args && args.outcome === "attention" ? "agents.outcome.attention" : "agents.outcome.done";
			badge.textContent = L(key);
			badge.className = "agent-outcome " + (args && args.outcome === "attention" ? "attention" : "done");
			badge.hidden = false;
		}

		function draw(row){
			var li = Object.assign(document.createElement("li"), { className: "transcript-row transcript-" + row.kind });
			var p = row.payload || {};
			switch(row.kind){
				case "system": li.appendChild(details(L("agents.transcript.system"), p.text || "")); break;
				case "user": li.appendChild(details(L("agents.transcript.user"), p.text || "")); break;
				case "thinking": li.appendChild(Object.assign(document.createElement("p"), { className: "transcript-thinking", textContent: p.text || "" })); break;
				case "assistant": li.appendChild(Object.assign(document.createElement("p"), { textContent: p.text || "" })); break;
				case "tool_call": {
					var head = document.createElement("div");
					head.appendChild(Object.assign(document.createElement("span"), { className: "transcript-label", textContent: L("agents.transcript.call") }));
					head.appendChild(Object.assign(document.createElement("strong"), { textContent: " " + (p.name || "") }));
					if(p.mutating) head.appendChild(Object.assign(document.createElement("em"), { className: "agent-tool-badge warn", textContent: L("agents.tools.mutating") }));
					li.appendChild(head);
					if(p.name === "finish"){
						outcome(p.args);
						li.appendChild(Object.assign(document.createElement("p"), { textContent: p.args && p.args.summary ? String(p.args.summary) : "" }));
					} else {
						li.appendChild(details(L("agents.transcript.arguments"), json(p.args || {})));
					}
					break;
				}
				case "tool_result": {
					var text = String(p.text || "");
					var head = L(p.isError ? "agents.transcript.result.error" : "agents.transcript.result");
					var shown = preview(text);
					if(text.length > RESULT_CHARS) text = text.slice(0, RESULT_CHARS) + "…";
					li.appendChild(details(shown ? head + ": " + shown : head, text, p.isError ? "error" : ""));
					break;
				}
				case "usage":
					calls++;
					tokensIn += Number(p.inputTokens || 0);
					tokensOut += Number(p.outputTokens || 0);
					li.appendChild(Object.assign(document.createElement("span"), { className: "flows-hint",
						textContent: L("agents.transcript.usage", [p.iteration, p.inputTokens || 0, p.outputTokens || 0, p.millis || 0]) }));
					break;
				case "truncated":
					li.appendChild(Object.assign(document.createElement("p"), { className: "transcript-note", textContent: L("agents.transcript.truncated", [p.bytes]) }));
					break;
				default:
					li.appendChild(Object.assign(document.createElement("p"), { className: "transcript-note", textContent: p.text || row.kind }));
			}
			rows.appendChild(li);
		}

		function poll(){
			var q = "?after=" + after + (attempt ? "&attempt=" + attempt : "");
			FlowApi.get("/runs/" + FlowApi.seg(runId) + "/transcript" + q).then(function(t){
				attempt = t.attempt;
				(t.rows || []).forEach(function(r){ draw(r); after = r.seq; });
				el("transcript-empty").hidden = rows.children.length > 0;
				el("transcript-usage").textContent = calls ? L("agents.run.usage", [calls, tokensIn, tokensOut])
					+ (attempt > 1 ? " · " + L("agents.run.attempt", [attempt]) : "") : "";
				if(ENDED.indexOf(t.status) < 0) setTimeout(poll, POLL_MS);
			}).catch(function(err){
				if(err instanceof DataHubProblem && (err.status === 403 || err.status === 404)){
					el("transcript-empty").textContent = L("agents.run.transcript.forbidden");
					el("transcript-empty").hidden = false;
					return;
				}
				FlowApi.flashError(err, "flows.error.load");
			});
		}

		window.i18nReady.then(poll);
	});
})();
