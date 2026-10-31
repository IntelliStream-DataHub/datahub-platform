/*
 * The count on the Flows entry: flow runs that failed after their last attempt and that nobody has
 * re-run or dismissed. Each is a run-failed event lifecycle in datahub-api whose latest event is
 * still open, so the count is POST /events/latest — the same query the Failed-runs page lists.
 *
 * In the site-wide bundle because the entry is in the header of every page. It does nothing on a
 * page without the entry, which the layout renders only for a tenant with flows.
 */
(function(){
	"use strict";

	var PAGE = 100;

	/** The body both this count and the Failed-runs page send. */
	window.flowAttentionQuery = function(cursor, limit){
		var query = { filter: { type: ["flow"], subType: ["run-failed"] }, status: ["open"],
			limit: limit || PAGE };
		if(cursor) query.cursor = cursor;
		return query;
	};

	document.addEventListener("DOMContentLoaded", function(){
		var badge = document.querySelector('[data-type="flows-attention-count"]');
		if(!badge || !window.Api) return;
		Api.post("/events/latest", window.flowAttentionQuery(null, PAGE))
			.then(function(r){ return r.ok ? r.json() : null; })
			.then(function(page){
				var items = page && Array.isArray(page.items) ? page.items : [];
				if(!items.length) return;
				// One page is enough for a count in the header: past it, "99+" says the same.
				badge.textContent = page.nextCursor ? (PAGE - 1) + "+" : String(items.length);
				badge.hidden = false;
			})
			.catch(function(){ /* no count is better than a wrong one */ });
	});
})();
