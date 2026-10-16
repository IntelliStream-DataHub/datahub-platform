/*
 * Failed runs: the run-failed lifecycles whose latest event is open, grouped per flow.
 *
 *   POST {api}/events/latest      the open lifecycles (window.flowAttentionQuery, as the nav count)
 *   POST {flow}/runs/{id}/rerun   re-run the failed window with the deployed version; its success
 *                                 writes the resolved event, so the row leaves the list on its own
 *   POST {api}/events/create      dismiss: a dismissed event in the same lifecycle, written as the
 *                                 person dismissing, who needs write on the flow's dataset
 */
(function(){
	"use strict";

	function L(key, args){ return args ? $L(key, null, args) : $L(key); }

	function time(value){
		if(value === null || value === undefined || value === "") return "";
		var iso = typeof value === "number" ? new Date(value).toISOString() : String(value);
		return window.formatLocalTime(iso);
	}

	/** Who is dismissing, for the event's metadata: the name the user signed in with. */
	function whoAmI(){
		return FlowApi.token(false).then(function(t){
			try {
				var payload = JSON.parse(atob(t.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
				return payload.preferred_username || payload.name || payload.sub || "";
			} catch(e){ return ""; }
		});
	}

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="attention-page"]');
		if(!page) return;
		var tbody = page.querySelector('[data-type="attention-table"] tbody');
		var empty = page.querySelector('[data-type="attention-empty"]');
		var more = page.querySelector('[data-type="attention-more"]');
		var cursor = null;
		var groups = {};   // flow -> its header row, so later pages add to the same group

		function groupRow(flow){
			if(groups[flow]) return groups[flow];
			var tr = document.createElement("tr");
			tr.className = "attention-group";
			var td = document.createElement("td");
			td.colSpan = 5;
			var link = Object.assign(document.createElement("a"), { href: "/flows/" + FlowApi.seg(flow), textContent: flow });
			td.appendChild(link);
			tr.appendChild(td);
			tbody.appendChild(tr);
			groups[flow] = tr;
			return tr;
		}

		function button(textKey, cls){
			return Object.assign(document.createElement("button"), { type: "button", className: "dh-btn small " + cls, textContent: L(textKey) });
		}

		function dismiss(event, tr){
			return whoAmI().then(function(who){
				var metadata = Object.assign({}, event.metadata || {},
					{ dismissedBy: who, dismissedAt: new Date().toISOString() });
				return FlowApi.api("/events/create", { items: [{
					externalId: event.externalId, type: "flow", subType: "run-failed", status: "dismissed",
					eventTime: Date.now(), dataSetId: event.dataSetId, metadata: metadata }] });
			}).then(function(){
				tr.remove();
				Flash.info(L("flows.attention.dismissed"));
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.dismiss"); });
		}

		function row(event){
			var m = event.metadata || {};
			var flow = m.flow || "";
			var tr = document.createElement("tr");
			var windowText = m.windowStart ? time(m.windowStart) + " – " + time(m.windowEnd) : "";
			var error = (m.errorCode || "") + (m.error ? ": " + m.error : "");
			[windowText, time(event.eventTime), error, m.attempts || ""].forEach(function(t){
				tr.appendChild(Object.assign(document.createElement("td"), { textContent: t, title: t }));
			});
			var actions = document.createElement("td");
			actions.className = "attention-actions";
			if(m.runId){
				var view = button("flows.attention.view.run", "secondary");
				view.addEventListener("click", function(){
					window.location.href = "/flows/" + FlowApi.seg(flow) + "/runs/" + FlowApi.seg(m.runId);
				});
				var rerun = button("flows.rerun", "primary");
				rerun.addEventListener("click", function(){
					rerun.disabled = true;
					FlowApi.post("/runs/" + FlowApi.seg(m.runId) + "/rerun", {}).then(function(run){
						window.location.href = "/flows/" + FlowApi.seg(run.flow || flow) + "/runs/" + FlowApi.seg(run.id);
					}).catch(function(err){ rerun.disabled = false; FlowApi.flashError(err, "flows.error.run"); });
				});
				actions.append(view, rerun);
			}
			var dismissBtn = button("flows.attention.dismiss", "secondary");
			dismissBtn.addEventListener("click", function(){
				if(confirm(L("flows.attention.dismiss.confirm"))) dismiss(event, tr);
			});
			actions.append(dismissBtn);
			tr.appendChild(actions);
			// After the group's last row, so a group stays together across pages.
			var group = groupRow(flow);
			var after = group;
			while(after.nextElementSibling && !after.nextElementSibling.classList.contains("attention-group")){
				after = after.nextElementSibling;
			}
			after.after(tr);
		}

		function load(){
			more.disabled = true;
			FlowApi.api("/events/latest", window.flowAttentionQuery(cursor, 100))
				.then(function(page){
					(page.items || []).forEach(row);
					cursor = page.nextCursor || null;
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
