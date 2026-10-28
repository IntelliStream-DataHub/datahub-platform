/*
 * How the flows are doing: GET {flow}/runs/overview answers with one row per deployed flow the user
 * may read, each with what it has running and queued, its latest finished run, and a health the
 * service works out (PROBLEM, WARNING, OK or OFF) with the reasons. Flows with a problem or
 * something to watch are listed first; the fine ones are folded away, since the point is to see at
 * a glance that all is well, and where it is not.
 *
 * Refreshes every few seconds while anything is running or queued, and stops when nothing is, after
 * ten minutes, or while the browser tab is hidden; Refresh starts it again.
 *
 * The Agents section's Runs tab is the same page for its own kind: the page's data-kind
 * (?kind=flow or agent) picks what is listed and data-section where the rows link to.
 */
(function(){
	"use strict";

	var EVERY = 5000;
	var FOR_AT_MOST = 10 * 60 * 1000;
	var PAGE = 200;
	var MAX_PAGES = 25;
	var ORDER = { PROBLEM: 0, WARNING: 1, OK: 2, OFF: 3 };

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	function time(value){
		return value ? window.formatLocalTime(value) : "";
	}

	/** 45 s, 3 min 20 s, 1 h 4 min: how long something has waited. */
	function span(ms){
		var s = Math.max(0, Math.round(ms / 1000));
		if(s < 60) return s + " s";
		var m = Math.floor(s / 60);
		if(m < 60) return m + " min" + (s % 60 ? " " + (s % 60) + " s" : "");
		var h = Math.floor(m / 60);
		return h + " h" + (m % 60 ? " " + (m % 60) + " min" : "");
	}

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="runs-page"]');
		if(!page) return;
		var kind = page.getAttribute("data-kind");
		var section = page.getAttribute("data-section") || "flows";
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var timer = null;
		var until = 0;

		function cell(tr, text, href, cls){
			var td = document.createElement("td");
			if(href){
				td.appendChild(Object.assign(document.createElement("a"), { href: href, textContent: text }));
			} else {
				td.textContent = text;
				td.title = text;
			}
			if(cls) td.className = cls;
			tr.appendChild(td);
		}

		function reasonText(entry, reason){
			var args = reason === "SWITCHED_OFF" ? [entry.disabledReason || ""]
				: reason === "OVERDUE" ? [time(entry.nextDueAt)] : null;
			return L("flows.runs.reason." + reason, args);
		}

		function stateText(entry){
			var text = L("flows.runs.health." + entry.health);
			if(entry.reasons.length){
				text += ": " + entry.reasons.map(function(r){ return reasonText(entry, r); }).join("; ");
			}
			return text;
		}

		function runningText(entry){
			if(!entry.running) return "0";
			return entry.runningOn ? L("flows.runs.running.on", [entry.running, entry.runningOn]) : String(entry.running);
		}

		function queuedText(entry, now){
			if(!entry.queued) return "0";
			return entry.waitingSince
				? L("flows.runs.queued.oldest", [entry.queued, span(now - Date.parse(entry.waitingSince))])
				: String(entry.queued);
		}

		function lastText(entry){
			if(!entry.lastRun) return L("flows.runs.last.none");
			var last = entry.lastRun;
			return last.status + (last.errorCode ? " (" + last.errorCode + ")" : "")
				+ (last.finishedAt ? ", " + time(last.finishedAt) : "");
		}

		function row(entry, now, withNext){
			var tr = document.createElement("tr");
			cell(tr, entry.name && entry.name !== entry.flow ? entry.name + " (" + entry.flow + ")" : entry.flow,
				"/" + section + "/" + FlowApi.seg(entry.flow));
			cell(tr, stateText(entry), null, "runs-" + entry.health.toLowerCase());
			cell(tr, runningText(entry));
			cell(tr, queuedText(entry, now));
			cell(tr, lastText(entry), entry.lastRun ? "/" + section + "/" + FlowApi.seg(entry.flow) + "/runs/" + FlowApi.seg(entry.lastRun.id) : null);
			if(withNext) cell(tr, entry.nextDueAt ? time(entry.nextDueAt) : L("flows.runs.next.manual"));
			return tr;
		}

		/** Every page of the overview: one row per deployed flow, so it is bounded by the flows. */
		function loadAll(){
			var all = [];
			function next(cursor, n){
				return FlowApi.get("/runs/overview?limit=" + PAGE + (kind ? "&kind=" + encodeURIComponent(kind) : "") + (cursor ? "&cursor=" + encodeURIComponent(cursor) : ""))
					.then(function(wrapper){
						(wrapper.items || []).forEach(function(e){ all.push(e); });
						return wrapper.nextCursor && n < MAX_PAGES ? next(wrapper.nextCursor, n + 1) : all;
					});
			}
			return next(null, 1);
		}

		function draw(entries){
			var now = Date.now();
			entries.sort(function(a, b){
				return ORDER[a.health] - ORDER[b.health] || a.flow.localeCompare(b.flow);
			});
			var look = entries.filter(function(e){ return e.health === "PROBLEM" || e.health === "WARNING"; });
			var fine = entries.filter(function(e){ return e.health === "OK" || e.health === "OFF"; });
			var count = function(h){ return entries.filter(function(e){ return e.health === h; }).length; };
			var running = entries.reduce(function(n, e){ return n + e.running; }, 0);
			var queued = entries.reduce(function(n, e){ return n + e.queued; }, 0);

			el("runs-summary").textContent = L(section + ".runs.summary", [entries.length, count("PROBLEM"),
				count("WARNING"), count("OK"), count("OFF"), running, queued]);

			var attention = el("runs-attention");
			var body = attention.querySelector("tbody");
			body.textContent = "";
			look.forEach(function(e){ body.appendChild(row(e, now, false)); });
			attention.hidden = look.length === 0;
			el("runs-attention-empty").hidden = look.length > 0;

			var details = el("runs-fine");
			var fineBody = el("runs-fine-table").querySelector("tbody");
			fineBody.textContent = "";
			fine.forEach(function(e){ fineBody.appendChild(row(e, now, true)); });
			el("runs-fine-title").textContent = L("flows.runs.fine", [fine.length]);
			details.hidden = fine.length === 0;
			return running + queued;
		}

		function tick(){
			timer = null;
			if(!document.body.contains(page)) return;
			// A tab nobody is looking at asks for nothing, and picks up again when it is shown.
			if(document.hidden){ timer = setTimeout(tick, EVERY); return; }
			loadAll().then(function(entries){
				var busy = draw(entries);
				if(busy > 0 && Date.now() < until) timer = setTimeout(tick, EVERY);
			}).catch(function(err){
				if(err instanceof DataHubProblem && err.status === 404){
					Flash.error(L("flows.runs.service.old"));
				} else {
					FlowApi.flashError(err, "flows.error.load");
				}
			});
		}

		function start(){
			if(timer) clearTimeout(timer);
			until = Date.now() + FOR_AT_MOST;
			tick();
		}

		el("runs-refresh").addEventListener("click", start);
		window.i18nReady.then(start);
	});
})();
