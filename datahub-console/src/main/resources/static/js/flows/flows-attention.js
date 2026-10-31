/*
 * Failed runs: the run-failed lifecycles whose latest event is open, grouped per flow.
 *
 *   POST {api}/events/latest      the open lifecycles (window.flowAttentionQuery, as the nav count)
 *   POST {flow}/runs/{id}/rerun   re-run the failed window with the deployed version; its success
 *                                 writes the resolved event, so the row leaves the list on its own.
 *   POST {flow}/flows/{id}/runs/rerun   "Re-run all": queues the listed failed windows of a flow in one
 *                                 request, then follows the list until those runs have finished: the
 *                                 service starts them as its slots free up, and a flow with a limit on
 *                                 runs at once works through its queue within that limit
 *   POST {api}/events/create      dismiss: a dismissed event in the same lifecycle, written as the
 *                                 person dismissing, who needs write on the flow's dataset. "Dismiss
 *                                 all" writes one for every listed failure, DISMISS_BATCH to a request
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
		var RERUN_BATCH = 1000;
		var DISMISS_BATCH = 100;
		var dismissAllButton = page.querySelector('[data-type="attention-dismiss-all"]');
		var listed = [];   // {event, row} of every listed failure
		var groups = {};   // flow -> its header row, so later pages add to the same group
		var collapsed = {};   // header key -> true while its rows are folded away
		var causes = {};   // flow + error code -> its header row inside the flow's group
		var rerunnable = [];   // {flow, runId, button} of every listed window that has a run to re-run

		/** A header's fold indicator and keyboard target; the rows under it follow `collapsed` (see fold). */
		function toggle(key){
			var b = Object.assign(document.createElement("button"), { type: "button", className: "attention-toggle" });
			b.setAttribute("data-type", "attention-toggle");
			return b;
		}

		// The whole header row folds, except the flow's link; the button's click arrives here too.
		tbody.addEventListener("click", function(e){
			var tr = e.target.closest("tr.attention-group, tr.attention-cause");
			if(!tr || e.target.closest("a")) return;
			var key = tr.getAttribute("data-key");
			collapsed[key] = !collapsed[key];
			tidy();
		});

		function groupRow(flow){
			if(groups[flow]) return groups[flow];
			var tr = document.createElement("tr");
			tr.className = "attention-group";
			var td = document.createElement("td");
			td.colSpan = 5;
			var link = Object.assign(document.createElement("a"), { href: "/flows/" + FlowApi.seg(flow), textContent: flow });
			td.append(toggle(flow), link, document.createTextNode(""));
			tr.setAttribute("data-key", flow);
			tr.appendChild(td);
			tbody.appendChild(tr);
			groups[flow] = tr;
			return tr;
		}

		/** The cause's header, last in its flow's group, so a flow's failures stay together by error code. */
		function causeRow(flow, code){
			var key = flow + "\u0000" + code;
			if(causes[key]) return causes[key];
			var tr = document.createElement("tr");
			tr.className = "attention-cause";
			tr.setAttribute("data-code", code);
			var td = document.createElement("td");
			td.colSpan = 5;
			td.append(toggle(key), document.createElement("span"));
			tr.appendChild(td);
			var end = groupRow(flow);
			while(end.nextElementSibling && !end.nextElementSibling.classList.contains("attention-group")){
				end = end.nextElementSibling;
			}
			end.after(tr);
			causes[key] = tr;
			tr.setAttribute("data-key", key);
			return tr;
		}

		/** The data rows under a header, up to the next header of either kind. */
		function members(header){
			var rows = [];
			for(var n = header.nextElementSibling; n && !n.classList.contains("attention-group")
					&& !n.classList.contains("attention-cause"); n = n.nextElementSibling) rows.push(n);
			return rows;
		}

		/** Hides the rows under a folded flow or cause, and shows each header's state. */
		function fold(){
			var flowFolded = false, causeFolded = false;
			Array.prototype.forEach.call(tbody.children, function(tr){
				var header = tr.classList.contains("attention-group") || tr.classList.contains("attention-cause");
				if(tr.classList.contains("attention-group")){
					flowFolded = !!collapsed[tr.getAttribute("data-key")];
					causeFolded = false;
					tr.hidden = false;
				} else if(tr.classList.contains("attention-cause")){
					causeFolded = !!collapsed[tr.getAttribute("data-key")];
					tr.hidden = flowFolded;
				} else {
					tr.hidden = flowFolded || causeFolded;
				}
				if(header){
					var folded = !!collapsed[tr.getAttribute("data-key")];
					var b = tr.querySelector('[data-type="attention-toggle"]');
					b.textContent = folded ? "\u25B8" : "\u25BE";
					b.setAttribute("aria-expanded", folded ? "false" : "true");
				}
			});
		}

		/** Drops headers left without rows, and puts the counts on the rest. */
		function tidy(){
			Object.keys(causes).forEach(function(key){
				var header = causes[key], n = members(header).length;
				if(n === 0){ header.remove(); delete causes[key]; return; }
				var code = header.getAttribute("data-code");
				header.firstChild.lastChild.textContent = (code || L("flows.attention.cause.unknown")) + " (" + n + ")";
			});
			Object.keys(groups).forEach(function(flow){
				var header = groups[flow], n = 0;
				for(var x = header.nextElementSibling; x && !x.classList.contains("attention-group"); x = x.nextElementSibling){
					if(x.hasAttribute("data-lifecycle")) n++;
				}
				if(n === 0){ header.remove(); delete groups[flow]; return; }
				header.firstChild.lastChild.textContent = " (" + n + ")";
			});
			fold();
			empty.hidden = tbody.children.length > 0;
		}

		function button(textKey, cls){
			return Object.assign(document.createElement("button"), { type: "button", className: "dh-btn small " + cls, textContent: L(textKey) });
		}

		/** The dismissed event that closes a failure's lifecycle. */
		function dismissedEvent(event, who){
			var metadata = Object.assign({}, event.metadata || {},
				{ dismissedBy: who, dismissedAt: new Date().toISOString() });
			return { externalId: event.externalId, type: "flow", subType: "run-failed", status: "dismissed",
				eventTime: Date.now(), dataSetId: event.dataSetId, metadata: metadata };
		}

		function dismiss(event, tr){
			return whoAmI().then(function(who){
				return FlowApi.api("/events/create", { items: [dismissedEvent(event, who)] });
			}).then(function(){
				tr.remove();
				tidy();
				Flash.info(L("flows.attention.dismissed"));
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.dismiss"); });
		}

		function row(event){
			var m = event.metadata || {};
			var flow = m.flow || "";
			var tr = document.createElement("tr");
			tr.setAttribute("data-lifecycle", event.externalId);
			listed.push({ event: event, row: tr });
			var windowText = m.windowStart ? time(m.windowStart) + " – " + time(m.windowEnd) : "";
			var error = m.error || m.errorCode || "";
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
			// After the cause's last row, so a flow's failures stay together by cause across pages.
			var cause = causeRow(flow, m.errorCode || "");
			var after = members(cause).pop() || cause;
			after.after(tr);
		}

		/** The windows still waiting for a re-run: listed, with a run, and not re-run from here yet. */
		function pending(){
			return rerunnable.filter(function(r){ return r.row.isConnected && !r.started; });
		}

		function showRerunAll(){
			rerunAllButton.hidden = pending().length === 0;
			dismissAllButton.hidden = !listed.some(function(l){ return l.row.isConnected; });
		}

		/** Loads one page; resolves to whether it did, so a failing load is not retried forever. */
		function load(){
			more.disabled = true;
			return FlowApi.api("/events/latest", window.flowAttentionQuery(cursor, 100))
				.then(function(page){
					(page.items || []).forEach(row);
					tidy();
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
			tidy();
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

		/**
		 * Queues a re-run of every listed window, one request per flow (and per thousand windows):
		 * the service puts them all in its queue in one go and starts them as fast as its slots
		 * allow. Stays here: the rows leave as the runs succeed.
		 */
		function rerunAll(){
			rerunAllButton.disabled = true;
			loadAll().then(function(ok){
				var todo = pending();
				if(!ok || todo.length === 0 || !confirm(L("flows.attention.rerun.all.confirm", [todo.length]))) return;
				var byFlow = {};
				todo.forEach(function(r){ (byFlow[r.flow] = byFlow[r.flow] || []).push(r); });
				var queued = 0, failed = 0, reasons = [];
				var batches = [];
				Object.keys(byFlow).forEach(function(flow){
					for(var i = 0; i < byFlow[flow].length; i += RERUN_BATCH){
						batches.push({ flow: flow, rows: byFlow[flow].slice(i, i + RERUN_BATCH) });
					}
				});
				return batches.reduce(function(chain, batch){
					return chain.then(function(){
						return FlowApi.post("/flows/" + FlowApi.seg(batch.flow) + "/runs/rerun",
							{ runs: batch.rows.map(function(r){ return r.runId; }) }).then(function(answer){
							// A window that already has a later run is not queued again, but is as good as
							// started: it leaves the list when that run succeeds.
							batch.rows.forEach(function(r){ r.started = true; r.button.disabled = true; });
							queued += answer.requeued;
						}).catch(function(err){
							failed += batch.rows.length;
							reasons.push(batch.flow + ": " + (err && err.message ? err.message : L("flows.error.run")));
						});
					});
				}, Promise.resolve()).then(function(){
					if(failed === 0) Flash.info(L("flows.attention.rerun.all.done", [queued]));
					else Flash.error(L("flows.attention.rerun.all.partial", [queued, failed]), { details: reasons });
					if(queued > 0 || failed < todo.length) follow();
				});
			}).finally(function(){
				rerunAllButton.disabled = false;
				showRerunAll();
			});
		}

		/**
		 * Dismisses every listed failure, in requests of DISMISS_BATCH. A request that is refused (no
		 * write on a flow's dataset, say) leaves its failures on the page and is reported at the end.
		 */
		function dismissAll(){
			dismissAllButton.disabled = true;
			loadAll().then(function(ok){
				var todo = listed.filter(function(l){ return l.row.isConnected; });
				if(!ok || todo.length === 0 || !confirm(L("flows.attention.dismiss.all.confirm", [todo.length]))) return;
				return whoAmI().then(function(who){
					var done = 0, failed = 0, reasons = [];
					var chain = Promise.resolve();
					for(var i = 0; i < todo.length; i += DISMISS_BATCH){
						(function(batch){
							chain = chain.then(function(){
								return FlowApi.api("/events/create", { items: batch.map(function(l){ return dismissedEvent(l.event, who); }) })
									.then(function(){
										batch.forEach(function(l){ l.row.remove(); });
										done += batch.length;
									}).catch(function(err){
										failed += batch.length;
										reasons.push(err && err.message ? err.message : L("flows.error.dismiss"));
									});
							});
						})(todo.slice(i, i + DISMISS_BATCH));
					}
					return chain.then(function(){
						tidy();
						if(failed === 0) Flash.info(L("flows.attention.dismiss.all.done", [done]));
						else Flash.error(L("flows.attention.dismiss.all.partial", [done, failed]), { details: reasons });
					});
				});
			}).catch(function(err){ FlowApi.flashError(err, "flows.error.dismiss"); })
			.finally(function(){
				dismissAllButton.disabled = false;
				showRerunAll();
			});
		}

		rerunAllButton.addEventListener("click", rerunAll);
		dismissAllButton.addEventListener("click", dismissAll);

		more.addEventListener("click", load);
		window.i18nReady.then(load);
	});
})();
