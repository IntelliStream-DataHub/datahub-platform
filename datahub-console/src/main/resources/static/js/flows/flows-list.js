/*
 * The flows list: GET {flow}/flows a page at a time, newest cursor first, as datahub-flow filters
 * it to the flows whose dataset the user may read. A new flow is made on the flow page.
 */
(function(){
	"use strict";

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="flows-list-page"]');
		if(!page) return;
		var tbody = page.querySelector('[data-type="flows-table"] tbody');
		var empty = page.querySelector('[data-type="flows-empty"]');
		var more = page.querySelector('[data-type="flows-load-more"]');
		var cursor = null;

		function deploymentText(flow){
			if(flow.disabledReason) return $L("flows.state.disabled.reason", null, [flow.disabledReason]);
			if(flow.deployedVersion == null) return $L("flows.state.not.deployed");
			return $L(flow.enabled ? "flows.state.enabled" : "flows.state.disabled", null, [flow.deployedVersion]);
		}

		function row(flow){
			var tr = document.createElement("tr");
			var cells = [
				flow.name + " (" + flow.externalId + ")",
				flow.dataSet || "",
				String(flow.version),
				deploymentText(flow),
				flow.updatedAt ? window.formatLocalTime(flow.updatedAt) : ""
			];
			cells.forEach(function(text, i){
				var td = document.createElement("td");
				td.textContent = text;
				if(i === 3 && flow.disabledReason) td.className = "flows-warn";
				tr.appendChild(td);
			});
			tr.addEventListener("click", function(){
				window.location.href = "/flows/" + FlowApi.seg(flow.externalId);
			});
			return tr;
		}

		function load(){
			var q = "?limit=100" + (cursor ? "&cursor=" + encodeURIComponent(cursor) : "");
			more.disabled = true;
			FlowApi.get("/flows" + q)
				.then(function(wrapper){
					(wrapper.items || []).forEach(function(f){ tbody.appendChild(row(f)); });
					cursor = wrapper.nextCursor || null;
					more.hidden = !cursor;
					empty.hidden = tbody.children.length > 0;
				})
				.catch(function(err){ FlowApi.flashError(err, "flows.error.load"); })
				.finally(function(){ more.disabled = false; });
		}

		more.addEventListener("click", load);

		window.i18nReady.then(load);
	});
})();
