/*
 * FlowRunAs.pick(current) — asks which registered client a flow's runs should act as, from the
 * names the flow service lists (GET /clients), and resolves with the chosen name, or null when the
 * dialog is cancelled. Shared by the flow page and the agent page, which both deploy with runAs.
 *
 * Every registered client is offered, also those the caller may not assign: whether they hold every
 * group the client holds is the flow service's check on deploy, and its refusal names what is missing.
 */
(function(){
	"use strict";

	function L(key){ return $L(key); }

	function node(tag, className, text){
		var e = document.createElement(tag);
		if(className) e.className = className;
		if(text !== undefined) e.textContent = text;
		return e;
	}

	function pick(current){
		return FlowApi.get("/clients").then(function(answer){
			var names = (answer && answer.clients) || [];
			return new Promise(function(resolve){
				var dialog = node("dialog", "run-as-dialog");
				// datahub-form-fields themes the select; bare, it takes the global #333 on the
				// browser's dark control and can hardly be read.
				var form = node("form", "datahub-form-fields");
				form.method = "dialog";
				form.appendChild(node("h3", null, L("flows.run.as")));

				var choice = null;
				if(names.length === 0){
					form.appendChild(node("p", "run-as-help", L("flows.run.as.none")));
				} else {
					var label = node("label", "run-as-label", L("flows.run.as.choose"));
					choice = node("select");
					choice.name = "runAs";
					names.forEach(function(name){
						var option = node("option", null, name);
						option.value = name;
						option.selected = name === current;
						choice.appendChild(option);
					});
					label.appendChild(choice);
					form.appendChild(label);
					form.appendChild(node("p", "run-as-help", L("flows.run.as.help")));
				}

				var manage = node("a", "run-as-manage", L("flows.run.as.manage"));
				manage.href = "/flows?tab=clients";
				form.appendChild(manage);

				var actions = node("div", "run-as-actions");
				var cancel = node("button", "dh-btn secondary", L("flows.run.as.cancel"));
				cancel.type = "button";
				cancel.addEventListener("click", function(){ dialog.close(); });
				actions.appendChild(cancel);
				if(choice){
					var ok = node("button", "dh-btn primary", L("flows.run.as.confirm"));
					ok.type = "submit";
					ok.value = "ok";
					actions.appendChild(ok);
				}
				form.appendChild(actions);
				dialog.appendChild(form);

				dialog.addEventListener("close", function(){
					var chosen = dialog.returnValue === "ok" && choice ? choice.value : null;
					dialog.remove();
					resolve(chosen);
				});
				document.body.appendChild(dialog);
				dialog.showModal();
				(choice || cancel).focus();
			});
		});
	}

	window.FlowRunAs = { pick: pick };
})();
