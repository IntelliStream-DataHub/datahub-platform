/*
 * Event values to pick, as chips: a search that suggests what DataHub's events have, and Enter takes
 * whatever is typed, so a value no event has yet, or a pattern such as pump-7/*, goes in too.
 *
 *   GET {api}/events/search/external-id?q=   distinct external ids, read from the events themselves
 *   GET {api}/events/search/{type|sub-type|status|source}?q=, GET {api}/events/list/...   the rest
 *
 * The picker sits on a textarea of one value per line, which stays the field: the page reads and
 * writes the textarea as before, and calls EventPicker.redraw(textarea) after setting it.
 */
(function(){
	"use strict";

	var SEARCH = { externalId: "/events/search/external-id", type: "/events/search/type",
		subType: "/events/search/sub-type", status: "/events/search/status", source: "/events/search/source" };
	// What to offer before anything is typed; external ids are too many to list.
	var LIST = { type: "/events/list/types", subType: "/events/list/sub-types",
		status: "/events/list/statuses", source: "/events/list/sources" };

	function L(key){ return $L(key); }

	function values(area){
		return area.value.split("\n").map(function(v){ return v.trim(); }).filter(Boolean);
	}

	function suggestions(field, query){
		var path = query ? SEARCH[field] + "?q=" + encodeURIComponent(query) + "&limit=20"
			: LIST[field] ? LIST[field] + "?limit=50" : null;
		return path ? FlowApi.api(path).then(function(json){ return json.items || []; }) : Promise.resolve([]);
	}

	/** Puts a picker for {@code field} (externalId, type, subType, status or source) on the textarea. */
	function attach(area, field){
		var box = Object.assign(document.createElement("div"), { className: "event-picker" });
		var chips = Object.assign(document.createElement("ul"), { className: "event-picker-chips" });
		var input = Object.assign(document.createElement("input"), { type: "text", className: "event-picker-input",
			placeholder: area.getAttribute("data-picker-placeholder") || "" });
		box.append(chips, input);
		area.hidden = true;
		area.parentNode.insertBefore(box, area.nextSibling);

		function set(list){
			area.value = list.join("\n");
			area.dispatchEvent(new Event("input", { bubbles: true }));
			draw();
		}

		function add(value){
			value = (value || "").trim();
			input.value = "";
			if(!value) return;
			var list = values(area);
			if(list.indexOf(value) < 0){ list.push(value); set(list); }
		}

		function draw(){
			chips.innerHTML = "";
			values(area).forEach(function(value){
				var li = document.createElement("li");
				var remove = Object.assign(document.createElement("button"), { type: "button", textContent: "×",
					className: "event-picker-remove", title: L("flows.picker.remove") });
				remove.addEventListener("click", function(){
					set(values(area).filter(function(v){ return v !== value; }));
					input.focus();
				});
				li.append(Object.assign(document.createElement("span"), { textContent: value }), remove);
				chips.appendChild(li);
			});
		}

		new SearchDropdown(input, {
			minChars: LIST[field] ? 0 : 1,
			emptyText: L("flows.picker.none"),
			source: function(query){
				return suggestions(field, query).then(function(found){
					var have = values(area);
					return found.filter(function(v){ return have.indexOf(v) < 0; });
				});
			},
			applySelection: function(value){ add(value); }
		});
		// After the dropdown's own handler: Enter on a highlighted suggestion is taken there.
		input.addEventListener("keydown", function(e){
			if(e.key === "Enter" && !e.defaultPrevented){
				e.preventDefault();
				add(input.value);
			} else if(e.key === "Backspace" && !input.value){
				set(values(area).slice(0, -1));
			}
		});
		// Typed and left without Enter still counts, as it would in a plain field.
		input.addEventListener("blur", function(){ if(input.value.trim()) add(input.value); });
		area._eventPickerDraw = draw;
		draw();
	}

	window.EventPicker = {
		attach: attach,
		redraw: function(area){ if(area && area._eventPickerDraw) area._eventPickerDraw(); }
	};
})();
