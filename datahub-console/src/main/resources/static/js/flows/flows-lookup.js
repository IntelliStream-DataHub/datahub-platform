/*
 * Which run produced a value: GET {flow}/records/lookup?timeseries=&at= answers with the latest
 * recorded write of that series whose window covers the instant, and the earlier ones. It knows
 * only writes made by flows the user may read, and what they wrote, not what ClickHouse holds now.
 */
(function(){
	"use strict";

	function L(key, args){ return $L(key, null, args); }

	function pad(n){ return String(n).padStart(2, "0"); }
	function localInput(date){
		return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
			+ "T" + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
	}

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="lookup-page"]');
		if(!page) return;
		var el = function(type){ return page.querySelector('[data-type="' + type + '"]'); };
		var form = el("lookup-form");

		function runLink(w){
			return "/flows/" + FlowApi.seg(w.flow) + "/runs/" + FlowApi.seg(w.run);
		}

		function fact(dl, label, value, href){
			if(value === null || value === undefined || value === "") return;
			var div = document.createElement("div");
			div.appendChild(Object.assign(document.createElement("dt"), { textContent: label }));
			var dd = document.createElement("dd");
			if(href){
				dd.appendChild(Object.assign(document.createElement("a"), { href: href, textContent: value }));
			} else if(typeof value === "object"){
				dd.appendChild(Object.assign(document.createElement("pre"), { textContent: JSON.stringify(value, null, 2) }));
			} else {
				dd.textContent = value;
			}
			div.appendChild(dd);
			dl.appendChild(div);
		}

		function windowText(w){
			return w.windowStart ? window.formatLocalTime(w.windowStart) + " – " + window.formatLocalTime(w.windowEnd) : "";
		}

		function show(answer){
			var latest = answer.latest;
			var dl = el("lookup-latest");
			dl.innerHTML = "";
			fact(dl, L("flows.column.run"), latest.run, runLink(latest));
			fact(dl, L("flows.column.flow"), latest.flow, "/flows/" + FlowApi.seg(latest.flow));
			fact(dl, L("flows.column.version"), latest.flowVersion);
			fact(dl, L("flows.column.attempt"), latest.attempt);
			fact(dl, L("flows.column.processor"), latest.processor);
			fact(dl, L("flows.column.time"), window.formatLocalTime(latest.time, { seconds: true }));
			fact(dl, L("flows.column.window"), windowText(latest));
			fact(dl, L("flows.column.status"), latest.runStatus);
			fact(dl, L("flows.column.parameters"), latest.parameters);
			fact(dl, L("flows.column.processor.versions"), latest.processorVersions);
			var tbody = el("lookup-history").querySelector("tbody");
			tbody.innerHTML = "";
			(answer.history || []).forEach(function(w){
				var tr = document.createElement("tr");
				[window.formatLocalTime(w.time, { seconds: true }), w.flow, String(w.flowVersion), w.processor,
					windowText(w), w.runStatus || ""].forEach(function(t){
					tr.appendChild(Object.assign(document.createElement("td"), { textContent: t }));
				});
				tr.addEventListener("click", function(){ window.location.href = runLink(w); });
				tbody.appendChild(tr);
			});
			el("lookup-result").hidden = false;
		}

		function ask(){
			var series = form.elements.timeseries.value.trim();
			var at = new Date(form.elements.at.value);
			if(!series || isNaN(at.getTime())) return;
			el("lookup-none").hidden = true;
			el("lookup-result").hidden = true;
			FlowApi.get("/records/lookup?timeseries=" + encodeURIComponent(series) + "&at=" + encodeURIComponent(at.toISOString()))
				.then(show)
				.catch(function(err){
					if(err instanceof DataHubProblem && err.status === 404){ el("lookup-none").hidden = false; return; }
					FlowApi.flashError(err, "flows.error.load");
				});
		}

		form.addEventListener("submit", function(e){ e.preventDefault(); ask(); });

		var params = new URLSearchParams(window.location.search);
		if(params.get("timeseries")) form.elements.timeseries.value = params.get("timeseries");
		var at = params.get("at") ? new Date(params.get("at")) : null;
		if(at && !isNaN(at.getTime())) form.elements.at.value = localInput(at);
		if(params.get("timeseries") && at) window.i18nReady.then(ask);
	});
})();
