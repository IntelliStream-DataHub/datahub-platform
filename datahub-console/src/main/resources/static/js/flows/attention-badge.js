/*
 * The counts on the Flows and Agents entries: flow runs that failed after their last attempt and
 * that nobody has re-run or dismissed, and what agents asked a person to look at. Each is an event
 * lifecycle in datahub-api whose latest event is still open, so a count is POST /events/latest,
 * the same query the section's list is made from.
 *
 * In the site-wide bundle because the entries are in the header of every page. It does nothing on
 * a page without them, which the layout renders only for a tenant that has the section.
 */
(function(){
	"use strict";

	var PAGE = 100;

	/** The body both the Flows count and the Failed-runs page send. */
	window.flowAttentionQuery = function(cursor, limit){
		var query = { filter: { type: ["flow"], subType: ["run-failed"] }, status: ["open"],
			limit: limit || PAGE };
		if(cursor) query.cursor = cursor;
		return query;
	};

	/** The body the Agents count and the Needs-attention lists send; of one agent with `flow`. */
	window.agentAttentionQuery = function(cursor, limit, flow){
		var query = { filter: { type: ["agent"], subType: ["attention"] }, status: ["open"],
			limit: limit || PAGE };
		if(flow) query.filter.metadata = { flow: flow };
		if(cursor) query.cursor = cursor;
		return query;
	};

	function count(badge, query){
		if(!badge) return;
		Api.post("/events/latest", query)
			.then(function(r){ return r.ok ? r.json() : null; })
			.then(function(page){
				var items = page && Array.isArray(page.items) ? page.items : [];
				if(!items.length) return;
				// One page is enough for a count in the header: past it, "99+" says the same.
				badge.textContent = page.nextCursor ? (PAGE - 1) + "+" : String(items.length);
				badge.hidden = false;
			})
			.catch(function(){ /* no count is better than a wrong one */ });
	}

	document.addEventListener("DOMContentLoaded", function(){
		if(!window.Api) return;
		count(document.querySelector('[data-type="flows-attention-count"]'), window.flowAttentionQuery(null, PAGE));
		count(document.querySelector('[data-type="agents-attention-count"]'), window.agentAttentionQuery(null, PAGE));
	});
})();
