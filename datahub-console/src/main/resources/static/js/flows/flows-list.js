/*
 * The flows list: GET {flow}/flows a page at a time, newest cursor first, as datahub-flow filters
 * it to the flows whose dataset the user may read. A new flow is made on the flow page. Each row
 * can deploy its flow (through Keycloak, as the flow page does) or switch it on or off, in place.
 *
 * The Agents section lists the same way: its page says data-kind="agent" (GET /flows?kind=agent)
 * and data-section="agents", where the rows link to.
 */
(function(){
	"use strict";

	document.addEventListener("DOMContentLoaded", function(){
		var page = document.querySelector('[data-type="flows-list-page"]');
		if(!page) return;
		var tbody = page.querySelector('[data-type="flows-table"] tbody');
		var empty = page.querySelector('[data-type="flows-empty"]');
		var more = page.querySelector('[data-type="flows-load-more"]');
		var kind = page.getAttribute("data-kind");
		var section = page.getAttribute("data-section") || "flows";
		var cursor = null;

		function deploymentText(flow){
			if(flow.disabledReason) return $L("flows.state.disabled.reason", null, [flow.disabledReason]);
			if(flow.deployedVersion == null) return $L("flows.state.not.deployed");
			return $L(flow.enabled ? "flows.state.enabled" : "flows.state.disabled", null, [flow.deployedVersion]);
		}

		function button(text, cls, onClick){
			var b = Object.assign(document.createElement("button"), { type: "button", className: "dh-btn small " + cls, textContent: text });
			b.addEventListener("click", function(e){
				e.stopPropagation();   // the row itself opens the flow
				b.disabled = true;
				Promise.resolve(onClick()).finally(function(){ b.disabled = false; });
			});
			return b;
		}

		/** Deploying as yourself goes through Keycloak and back, to the flow's page. */
		function deploy(flow){
			return FlowApi.post("/flows/" + FlowApi.seg(flow.externalId) + "/deployment/authorize",
				{ version: flow.version, enabled: true })
				.then(function(answer){ window.location.href = answer.authorizationUrl; })
				.catch(function(err){ FlowApi.flashError(err, "flows.error.deploy"); });
		}

		/** Changes a deployed flow's deployment (switch it on or off, or move it to the latest version). */
		function redeploy(flow, tr, change){
			return FlowApi.put("/flows/" + FlowApi.seg(flow.externalId) + "/deployment", change)
				.then(function(d){
					flow.deployedVersion = d.version;
					flow.enabled = d.enabled;
					flow.disabledReason = d.disabledReason;
					draw(flow, tr);
				})
				.catch(function(err){ FlowApi.flashError(err, "flows.error.deploy"); });
		}

		/** What can be done to the flow here: deploy it, move it to its latest version, switch it on or off. */
		function actions(flow, tr){
			var out = [];
			if(flow.deployedVersion == null){
				out.push(button($L("flows.deploy"), "primary", function(){ return deploy(flow); }));
			} else if(flow.disabledReason){
				return out;   // switched off by the service (its dataset is gone): fixed on the flow's page
			} else if(flow.enabled){
				if(flow.version > flow.deployedVersion){
					out.push(button($L("flows.deploy.version", null, [flow.version]), "primary", function(){
						return redeploy(flow, tr, { version: flow.version, enabled: true });
					}));
				}
				out.push(button($L("flows.disable"), "secondary", function(){
					return redeploy(flow, tr, { enabled: false });
				}));
			} else {
				out.push(button($L("flows.enable"), "secondary", function(){
					return redeploy(flow, tr, { enabled: true });
				}));
			}
			return out;
		}

		function draw(flow, tr){
			tr.textContent = "";
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
			var td = document.createElement("td");
			td.className = "flows-row-actions";
			actions(flow, tr).forEach(function(b){ td.appendChild(b); });
			tr.appendChild(td);
		}

		function row(flow){
			var tr = document.createElement("tr");
			draw(flow, tr);
			tr.addEventListener("click", function(){
				window.location.href = "/" + section + "/" + FlowApi.seg(flow.externalId);
			});
			return tr;
		}

		function load(){
			var q = "?limit=100" + (kind ? "&kind=" + encodeURIComponent(kind) : "")
				+ (cursor ? "&cursor=" + encodeURIComponent(cursor) : "");
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
