/*
 * Data lineage on the Insights chart: which flow run wrote a value. The lineage button opens a
 * panel beside the chart; a click in the chart then asks GET {flow}/records/lookup?timeseries=&at=
 * for each charted series at its nearest datapoint. The answer is the run of the latest recorded
 * write whose window covers that instant, and its flow, which the panel links to. It knows only
 * writes made by flows the user may read, and what they wrote, not what ClickHouse holds now.
 */
(function(){
	"use strict";

	function L(key){ return $L(key); }

	document.addEventListener("DOMContentLoaded", function(){
		var panel = document.querySelector('[data-type="lineage-panel"]');
		var toggle = document.querySelector('[data-type="lineage-toggle"]');
		if(!panel || !toggle) return;
		var result = panel.querySelector('[data-type="lineage-result"]');
		// A later click supersedes answers still on their way for an earlier one.
		var asked = 0;

		function link(href, key){
			return Object.assign(document.createElement("a"), { className: "dh-btn secondary small", href: href, textContent: L(key) });
		}

		function written(section, answer){
			var links = Object.assign(document.createElement("div"), { className: "lineage-links" });
			links.appendChild(link("/flows/" + FlowApi.seg(answer.flow) + "/runs/" + FlowApi.seg(answer.run),
				"insights.lineage.run"));
			links.appendChild(link("/flows/" + FlowApi.seg(answer.flow), "insights.lineage.flow"));
			section.appendChild(links);
		}

		function show(picks){
			if(panel.hidden || picks.length === 0) return;
			var mine = ++asked;
			result.innerHTML = "";
			picks.forEach(function(pick){
				var section = Object.assign(document.createElement("section"), { className: "lineage-series" });
				var head = document.createElement("h4");
				head.appendChild(Object.assign(document.createElement("span"), { className: "ts-color-swatch " + pick.color }));
				head.appendChild(document.createTextNode(pick.externalId));
				head.appendChild(Object.assign(document.createElement("span"), {
					className: "flows-hint", textContent: window.formatLocalTime(pick.timestamp.toISOString(), { seconds: true })
				}));
				section.appendChild(head);
				result.appendChild(section);
				FlowApi.get("/records/lookup?timeseries=" + encodeURIComponent(pick.externalId)
						+ "&at=" + encodeURIComponent(pick.timestamp.toISOString()))
					.then(function(answer){
						if(mine !== asked) return;
						if(answer.run){ written(section, answer); return; }
						section.appendChild(Object.assign(document.createElement("p"), {
							className: "flows-empty", textContent: L("insights.lineage.none")
						}));
					})
					.catch(function(err){ if(mine === asked) FlowApi.flashError(err, "flows.error.load"); });
			});
		}

		toggle.addEventListener("click", function(){
			panel.hidden = !panel.hidden;
			toggle.classList.toggle("active", !panel.hidden);
			toggle.querySelector(".ts-lineage-label").textContent = L(panel.hidden ? "insights.lineage.show" : "insights.lineage.hide");
			// The chart takes its width from its container, which the panel narrows or widens.
			if(window.InsightsChart && window.InsightsChart.dataSets.length) window.InsightsChart.render();
		});

		window.DataLineage = { show: show };
	});
})();
