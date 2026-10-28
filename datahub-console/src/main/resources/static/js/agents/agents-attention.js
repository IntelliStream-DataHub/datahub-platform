/*
 * Needs attention: what agents asked a person to look at, the agent/attention lifecycles whose
 * latest event is open. On the section's tab for every agent, and on an agent's page for that one
 * (data-flow narrows the query by the event's flow metadata).
 *
 *   POST {api}/events/latest    the open lifecycles (window.agentAttentionQuery, as the nav count)
 *   POST {api}/events/create    dismiss: a dismissed event in the same lifecycle, built from the
 *                               row's own type, subtype and dataset, written as the person
 *                               dismissing, who needs write on the agent's dataset. "Dismiss all"
 *                               writes one for every listed entry, DISMISS_BATCH to a request.
 */
(function(){
	"use strict";

	var DISMISS_BATCH = 100;

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
		var page = document.querySelector('[data-type="agents-attention-page"]');
		if(!page) return;
		var scope = page.getAttribute("data-flow") || null;
		var tbody = page.querySelector('[data-type="attention-table"] tbody');
		var empty = page.querySelector('[data-type="attention-empty"]');
		var more = page.querySelector('[data-type="attention-more"]');
		var dismissAllButton = page.querySelector('[data-type="attention-dismiss-all"]');
		var cursor = null;
		var listed = [];   // {event, row}

		function button(textKey, cls){
			return Object.assign(document.createElement("button"), { type: "button", className: "dh-btn small " + cls, textContent: L(textKey) });
		}

		/** The dismissed event that closes an entry's lifecycle, from the entry itself. */
		function dismissedEvent(event, who){
			var metadata = Object.assign({}, event.metadata || {}, { dismissedBy: who, dismissedAt: new Date().toISOString() });
			return { externalId: event.externalId, type: event.type, subType: event.subType, status: "dismissed",
				eventTime: Date.now(), dataSetId: event.dataSetId, metadata: metadata };
		}

		function tidy(){
			empty.hidden = tbody.children.length > 0;
			dismissAllButton.hidden = !listed.some(function(l){ return l.row.isConnected; });
		}

		function dismiss(event, tr){
			return whoAmI().then(function(who){
				return FlowApi.createEvents([dismissedEvent(event, who)]);
			}).then(function(result){
				if(result.skipped.length) return Flash.error(L("flows.attention.dismiss.gone"));
				tr.remove();
				tidy();
				Flash.info(L("agents.attention.dismissed"));
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.dismiss"); });
		}

		function row(event){
			var m = event.metadata || {};
			var flow = m.flow || "";
			var tr = document.createElement("tr");
			listed.push({ event: event, row: tr });
			var agentCell = document.createElement("td");
			if(flow) agentCell.appendChild(Object.assign(document.createElement("a"), { href: "/agents/" + FlowApi.seg(flow), textContent: flow }));
			tr.appendChild(agentCell);
			tr.appendChild(Object.assign(document.createElement("td"), { textContent: time(event.eventTime) }));
			tr.appendChild(Object.assign(document.createElement("td"), { className: "agent-summary", textContent: m.summary || event.description || "" }));
			var actions = document.createElement("td");
			actions.className = "attention-actions";
			if(m.runId && flow){
				var view = button("agents.attention.view.run", "secondary");
				view.addEventListener("click", function(){
					window.location.href = "/agents/" + FlowApi.seg(flow) + "/runs/" + FlowApi.seg(m.runId);
				});
				actions.appendChild(view);
			}
			var dismissBtn = button("agents.attention.dismiss", "secondary");
			dismissBtn.addEventListener("click", function(){
				if(confirm(L("agents.attention.dismiss.confirm"))) dismiss(event, tr);
			});
			actions.appendChild(dismissBtn);
			tr.appendChild(actions);
			tbody.appendChild(tr);
		}

		function load(){
			more.disabled = true;
			return FlowApi.api("/events/latest", window.agentAttentionQuery(cursor, 100, scope))
				.then(function(result){
					(result.items || []).forEach(row);
					cursor = result.nextCursor || null;
					more.hidden = !cursor;
					tidy();
					return true;
				})
				.catch(function(err){ FlowApi.flashError(err, "flows.error.load"); return false; })
				.finally(function(){ more.disabled = false; });
		}

		function loadAll(){
			return cursor ? load().then(function(ok){ return ok ? loadAll() : false; }) : Promise.resolve(true);
		}

		function dismissAll(){
			dismissAllButton.disabled = true;
			loadAll().then(function(ok){
				var todo = listed.filter(function(l){ return l.row.isConnected; });
				if(!ok || todo.length === 0 || !confirm(L("agents.attention.dismiss.all.confirm", [todo.length]))) return;
				return whoAmI().then(function(who){
					var done = 0, failed = 0, gone = 0, reasons = [];
					var chain = Promise.resolve();
					for(var i = 0; i < todo.length; i += DISMISS_BATCH){
						(function(batch){
							chain = chain.then(function(){
								return FlowApi.createEvents(batch.map(function(l){ return dismissedEvent(l.event, who); }))
									.then(function(result){
										gone += result.skipped.length;
										result.written.forEach(function(i){ batch[i].row.remove(); });
										done += result.written.length;
									})
									.catch(function(err){ failed += batch.length; reasons.push(FlowApi.reason(err, "flows.error.dismiss")); });
							});
						})(todo.slice(i, i + DISMISS_BATCH));
					}
					return chain.then(function(){
						tidy();
						if(gone > 0){
							failed += gone;
							reasons.unshift(L("flows.attention.dismiss.gone.some", [gone]));
						}
						if(failed === 0) Flash.info(L("agents.attention.dismiss.all.done", [done]));
						else Flash.error(L("agents.attention.dismiss.all.partial", [done, failed]), { details: reasons });
					});
				});
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.dismiss"); })
			.finally(function(){ dismissAllButton.disabled = false; tidy(); });
		}

		dismissAllButton.addEventListener("click", dismissAll);
		more.addEventListener("click", load);
		window.i18nReady.then(load);
	});
})();
