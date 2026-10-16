/*
 * Failed runs: the run-failed lifecycles whose latest event is open, grouped per flow.
 *
 *   POST {api}/events/latest      the open lifecycles (window.flowAttentionQuery, as the nav count)
 *   POST {flow}/runs/{id}/rerun   re-run the failed window with the deployed version; its success
 *                                 writes the resolved event, so the row leaves the list on its own.
 *                                 "Re-run all" does this for every listed window, one after another,
 *                                 then follows the list until those runs have finished: a re-run only
 *                                 queues, and a flow works through its queue one run at a time
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
		var rerunAllButton = page.querySelector('[data-type="attention-rerun-all"]');
		var progress = page.querySelector('[data-type="attention-progress"]');
		var following = false;
		var groups = {};   // flow -> its header row, so later pages add to the same group
		var rerunnable = [];   // {flow, runId, button} of every listed window that has a run to re-run

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
			tr.setAttribute("data-lifecycle", event.externalId);
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
				rerunnable.push({ flow: flow, runId: m.runId, button: rerun, row: tr });
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

		/** The windows still waiting for a re-run: listed, with a run, and not re-run from here yet. */
		function pending(){
			return rerunnable.filter(function(r){ return r.row.isConnected && !r.started; });
		}

		function showRerunAll(){
			rerunAllButton.hidden = pending().length === 0;
		}

		/** Loads one page; resolves to whether it did, so a failing load is not retried forever. */
		function load(){
			more.disabled = true;
			return FlowApi.api("/events/latest", window.flowAttentionQuery(cursor, 100))
				.then(function(page){
					(page.items || []).forEach(row);
					cursor = page.nextCursor || null;
					more.hidden = !cursor;
					empty.hidden = tbody.children.length > 0;
					showRerunAll();
					return true;
				})
				.catch(function(err){ FlowApi.flashError(err, "flows.error.load"); return false; })
				.finally(function(){ more.disabled = false; });
		}

		function loadAll(){
			return cursor ? load().then(function(ok){ return ok ? loadAll() : false; }) : Promise.resolve(true);
		}

		/** The lifecycles that are open now: all pages of the same query the list is made from. */
		function openLifecycles(){
			var ids = new Set();
			function next(cur, n){
				return FlowApi.api("/events/latest", window.flowAttentionQuery(cur, 100)).then(function(p){
					(p.items || []).forEach(function(e){ ids.add(e.externalId); });
					return p.nextCursor && n < 50 ? next(p.nextCursor, n + 1) : ids;
				});
			}
			return next(null, 0);
		}

		/** Takes the rows whose lifecycle is no longer open off the page, and a group left empty with them. */
		function dropResolved(open){
			tbody.querySelectorAll("tr[data-lifecycle]").forEach(function(tr){
				if(!open.has(tr.getAttribute("data-lifecycle"))) tr.remove();
			});
			Object.keys(groups).forEach(function(flow){
				var next = groups[flow].nextElementSibling;
				if(!next || next.classList.contains("attention-group")){
					groups[flow].remove();
					delete groups[flow];
				}
			});
			empty.hidden = tbody.children.length > 0;
			showRerunAll();
		}

		/**
		 * Keeps the list current while re-runs started here finish, which takes a while: a flow runs
		 * one window at a time, and its next one starts at the next sweep. Stops when they are done,
		 * or after ten minutes; a window that failed again stays, and can be re-run once more.
		 */
		function follow(){
			if(following) return;
			following = true;
			var until = Date.now() + 10 * 60 * 1000;
			function waiting(){
				return rerunnable.filter(function(r){ return r.started && r.row.isConnected; }).length;
			}
			function tick(){
				var left = waiting();
				if(left === 0 || Date.now() > until || !document.body.contains(page)){
					progress.hidden = true;
					following = false;
					return;
				}
				progress.textContent = L("flows.attention.rerun.progress", [left]);
				progress.hidden = false;
				openLifecycles().then(dropResolved).catch(function(){ /* try again at the next tick */ })
					.finally(function(){ setTimeout(tick, 5000); });
			}
			tick();
		}

		/** Re-runs every listed window, one after another, and stays here: the rows leave as the runs succeed. */
		function rerunAll(){
			rerunAllButton.disabled = true;
			loadAll().then(function(ok){
				var todo = pending();
				if(!ok || todo.length === 0 || !confirm(L("flows.attention.rerun.all.confirm", [todo.length]))) return;
				var started = 0, failed = 0, reasons = [];
				return todo.reduce(function(chain, r){
					return chain.then(function(){
						r.button.disabled = true;
						return FlowApi.post("/runs/" + FlowApi.seg(r.runId) + "/rerun", {}).then(function(){
							r.started = true;
							started++;
						}).catch(function(err){
							r.button.disabled = false;
							failed++;
							reasons.push(r.flow + ": " + (err && err.message ? err.message : L("flows.error.run")));
						});
					});
				}, Promise.resolve()).then(function(){
					if(failed === 0) Flash.info(L("flows.attention.rerun.all.done", [started]));
					else Flash.error(L("flows.attention.rerun.all.partial", [started, failed]), { details: reasons });
					if(started > 0) follow();
				});
			}).finally(function(){
				rerunAllButton.disabled = false;
				showRerunAll();
			});
		}

		rerunAllButton.addEventListener("click", rerunAll);

		more.addEventListener("click", load);
		window.i18nReady.then(load);
	});
})();
