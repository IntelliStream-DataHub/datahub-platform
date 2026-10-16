/*
 * What is running and what is waiting: GET {flow}/runs?status=… for the runs in hand, the runs
 * queued, and the latest finished ones, across the flows the user may read.
 *
 * What a queued run is waiting for is only stated when the data says it: a run in its retry
 * back-off has a time it is not started before. Any other queued run is waiting to be started, by a
 * free slot on an instance and the next sweep, and by an earlier run of its flow when the flow
 * allows only one at a time; the page does not guess which.
 *
 * Refreshes every few seconds while anything is running or queued, and stops when nothing is, or
 * after ten minutes, so an idle page does not keep asking; Refresh starts it again.
 */
(function(){
	"use strict";

	var PAGE = 200;
	var RECENT = 20;
	var EVERY = 5000;
	var FOR_AT_MOST = 10 * 60 * 1000;

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	function time(value){
		return value ? window.formatLocalTime(value) : "";
	}

	/** 45 s, 3 min 20 s, 1 h 4 min: how long a run has run or waited. */
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
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var summary = el("runs-summary");
		var refresh = el("runs-refresh");
		var timer = null;
		var until = 0;

		function cell(tr, text, href){
			var td = document.createElement("td");
			if(href){
				td.appendChild(Object.assign(document.createElement("a"), { href: href, textContent: text }));
			} else {
				td.textContent = text;
				td.title = text;
			}
			tr.appendChild(td);
		}

		function windowText(run){
			return run.window ? time(run.window.start) + " – " + time(run.window.end) : "";
		}

		function flowCell(tr, run){
			cell(tr, run.flow + (run.preview ? " (" + L("flows.runs.preview") + ")" : ""),
				"/flows/" + FlowApi.seg(run.flow) + "/runs/" + FlowApi.seg(run.id));
		}

		function fill(type, runs, draw){
			var table = el(type);
			var body = table.querySelector("tbody");
			body.textContent = "";
			runs.forEach(function(run){
				var tr = document.createElement("tr");
				draw(tr, run);
				body.appendChild(tr);
			});
			table.hidden = runs.length === 0;
			el(type + "-empty").hidden = runs.length > 0;
		}

		function drawRunning(now){
			return function(tr, run){
				flowCell(tr, run);
				cell(tr, windowText(run));
				cell(tr, time(run.startedAt));
				cell(tr, run.startedAt ? span(now - Date.parse(run.startedAt)) : "");
				cell(tr, run.owner || "");
			};
		}

		function drawQueued(now){
			return function(tr, run){
				flowCell(tr, run);
				cell(tr, windowText(run));
				cell(tr, span(now - Date.parse(run.createdAt)));
				cell(tr, run.attempt + " / " + run.maxAttempts);
				cell(tr, run.notBefore && Date.parse(run.notBefore) > now
					? L("flows.runs.retry.at", [time(run.notBefore)]) : L("flows.runs.waiting"));
			};
		}

		function drawRecent(tr, run){
			flowCell(tr, run);
			cell(tr, windowText(run));
			cell(tr, run.status + (run.errorCode ? " (" + run.errorCode + ")" : ""));
			cell(tr, time(run.finishedAt));
		}

		/** The order the instances take queued runs in: earliest window first. */
		function queueOrder(a, b){
			var wa = a.window ? Date.parse(a.window.start) : 0;
			var wb = b.window ? Date.parse(b.window.start) : 0;
			return wa - wb || Date.parse(a.createdAt) - Date.parse(b.createdAt);
		}

		function summarise(running, queued, runningMore, queuedMore){
			var owners = {};
			running.forEach(function(r){ owners[r.owner || "?"] = (owners[r.owner || "?"] || 0) + 1; });
			var names = Object.keys(owners).sort();
			var instances = names.map(function(n){ return n + ": " + owners[n]; }).join(", ");
			summary.textContent = L("flows.runs.summary", [
				running.length + (runningMore ? "+" : ""), queued.length + (queuedMore ? "+" : "")])
				+ (instances ? " " + L("flows.runs.summary.instances", [instances]) : "");
		}

		function load(){
			var now = Date.now();
			return Promise.all([
				FlowApi.get("/runs?status=RUNNING&preview=true&limit=" + PAGE),
				FlowApi.get("/runs?status=PENDING&limit=" + PAGE),
				FlowApi.get("/runs?status=SUCCEEDED&status=FAILED&status=CANCELLED&limit=" + RECENT)
			]).then(function(answers){
				var running = (answers[0].items || []).slice().sort(function(a, b){
					return (a.startedAt ? Date.parse(a.startedAt) : 0) - (b.startedAt ? Date.parse(b.startedAt) : 0);
				});
				var queued = (answers[1].items || []).slice().sort(queueOrder);
				fill("runs-running", running, drawRunning(now));
				fill("runs-queued", queued, drawQueued(now));
				fill("runs-recent", answers[2].items || [], drawRecent);
				summarise(running, queued, !!answers[0].nextCursor, !!answers[1].nextCursor);
				return running.length + queued.length;
			});
		}

		function tick(){
			timer = null;
			if(!document.body.contains(page)) return;
			var next = function(busy){
				if(busy > 0 && Date.now() < until) timer = setTimeout(tick, EVERY);
			};
			// A tab nobody is looking at asks for nothing, and picks up again when it is shown.
			if(document.hidden){ timer = setTimeout(tick, EVERY); return; }
			load().then(next).catch(function(err){ FlowApi.flashError(err, "flows.error.load"); });
		}

		function start(){
			if(timer) clearTimeout(timer);
			until = Date.now() + FOR_AT_MOST;
			tick();
		}

		refresh.addEventListener("click", start);
		window.i18nReady.then(start);
	});
})();
