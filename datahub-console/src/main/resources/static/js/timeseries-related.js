/*
 * Explore page — "Related series" side panel (right pane).
 *
 * When a focus timeseries is picked on the Explore page, this runs the SAME relationship analysis the
 * Analyze tab uses (POST {datahub-analysis}/analysis, Bearer token from /token) over the current
 * time window, and renders a compact ranked shortlist. Hovering a row highlights that series' node in
 * the cytoscape graph; clicking a row opens the Analyze tab focused on that series (via sessionStorage,
 * consumed on the Analyze page's boot).
 *
 * Exposed as window.RelatedSeries = { load(externalId, range), clear() }. The Explore page's inline
 * script calls load() from leftMenuTsClickEvent and whenever the time range changes.
 *
 * i18n: uses $L with English inline fallbacks (the analyze page does the same). The api helpers mirror
 * analyze.js — kept local to avoid coupling this standalone script to the analyze bundle.
 */
(function(){
	"use strict";

	var cachedToken = null;
	var runToken = 0;      // monotonic; only the latest run may touch the panel
	// Mirror the Analyze tab's soft relevance cutoff, but here we HIDE below-cutoff series so the panel
	// stays a clean shortlist (the full Analyze tab greys them instead).
	var RELEVANCE_CUTOFF = 0.2;
	var LIMIT = 10;        // nearest-N graph BFS cap for the shortlist (the Analyze tab can expand it)

	function apiBase(){
		var m = document.querySelector('meta[name="datahub-api-url"]');
		return (m ? m.content : "").replace(/\/+$/, "");
	}
	function analysisBase(){
		var m = document.querySelector('meta[name="datahub-analysis-url"]');
		return (m ? m.content : "").replace(/\/+$/, "");
	}
	function L(key, fallback){
		try { var v = (typeof $L === "function") ? $L(key) : null; return (v && v !== key) ? v : fallback; }
		catch(e){ return fallback; }
	}
	function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]; }); }

	function getToken(force){
		if(cachedToken && !force) return Promise.resolve(cachedToken);
		return fetch("/token", { credentials: "same-origin" })
			.then(function(r){ return r.ok ? r.text() : Promise.reject("could not get access token"); })
			.then(function(t){ cachedToken = t.trim(); return cachedToken; });
	}
	function api(path, opts, base){
		opts = opts || {};
		var send = function(token){
			var headers = Object.assign({ "Accept": "application/json", "Authorization": "Bearer " + token }, opts.headers || {});
			return fetch((base || apiBase()) + path, Object.assign({}, opts, { headers: headers }));
		};
		return getToken(false).then(send)
			.then(function(r){ return (r.status === 401) ? getToken(true).then(send) : r; })
			.then(function(r){
				if(!r.ok) return r.text().then(function(b){ return Promise.reject("HTTP " + r.status + (b ? ": " + b.slice(0,200) : "")); });
				return r.status === 204 ? null : r.json();
			});
	}

	function container(){ return document.getElementById("related-series"); }
	function setBody(html){ var c = container(); if(c) c.innerHTML = html; }
	function head(){ return '<div class="rel-head">' + esc(L("related.series.title", "Related series")) + '</div>'; }

	// Run the analysis for `externalId` over `range` ({start:Date, end:Date}) and render the shortlist.
	function load(externalId, range){
		if(!container()) return;
		if(!externalId || !range || !range.start || !range.end){ clear(); return; }
		var body = {
			focusExternalId: externalId,
			start: range.start.toISOString(),
			end: range.end.toISOString(),
			limit: LIMIT
		};
		var token = ++runToken;
		setBody(head() + '<div class="rel-status">' + esc(L("related.series.loading", "Finding related series…")) + '</div>');
		api("/analysis", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body)
		}, analysisBase())
			.then(function(resp){ if(token === runToken) render(resp); })
			.catch(function(){ if(token === runToken) setBody(head() + '<div class="rel-status">' + esc(L("related.series.error", "Could not load related series.")) + '</div>'); });
	}

	function render(resp){
		// Empty window: the backend returns 200 with a message and no results. Show it as a note.
		if(resp && resp.message){ setBody(head() + '<div class="rel-status">' + esc(resp.message) + '</div>'); return; }
		var results = (resp && resp.results ? resp.results : []).filter(function(r){
			return typeof r.rankScore !== "number" || r.rankScore >= RELEVANCE_CUTOFF;
		});
		if(!results.length){
			setBody(head() + '<div class="rel-status">' + esc(L("related.series.none", "No related series found.")) + '</div>');
			return;
		}
		var html = head() + '<ul class="rel-list">';
		results.forEach(function(r, i){ html += row(r, i + 1); });
		setBody(html + '</ul>');
		wire();
	}

	function row(r, rank){
		var parts = [];
		var corr = (typeof r.whitenedCorrelation === "number") ? r.whitenedCorrelation
			: (typeof r.rawCorrelation === "number" ? r.rawCorrelation : null);
		if(corr !== null) parts.push(esc(L("related.series.corr", "corr")) + " " + corr.toFixed(2));
		if(typeof r.peakCoherence === "number") parts.push(esc(L("insights.analysis.coherence", "coherence")) + " " + r.peakCoherence.toFixed(2));
		if(r.cointegrated === true) parts.push('<span class="rel-flag pos">' + esc(L("insights.analysis.cointegrated", "lasting link")) + '</span>');
		if(r.stable === true) parts.push('<span class="rel-flag pos">' + esc(L("insights.analysis.stable", "consistent")) + '</span>');
		else if(r.stable === false) parts.push('<span class="rel-flag warn">' + esc(L("insights.analysis.unstable", "inconsistent")) + '</span>');
		return '<li class="rel-item" data-external-id="' + esc(r.externalId) + '">'
			+ '<div class="rel-name"><span class="rel-rank">#' + rank + '</span> ' + esc(r.name || r.externalId) + '</div>'
			+ '<div class="rel-metrics">' + parts.join(" · ") + '</div>'
			+ '</li>';
	}

	function wire(){
		var c = container();
		if(!c) return;
		Array.prototype.forEach.call(c.querySelectorAll(".rel-item"), function(li){
			var extId = li.getAttribute("data-external-id");
			// Hover: glow this series' node in the graph (no-op if it isn't in the current view).
			li.addEventListener("mouseenter", function(){
				if(window.resourceNetwork && window.resourceNetwork.highlightElements)
					window.resourceNetwork.highlightElements([extId]);
			});
			li.addEventListener("mouseleave", function(){
				if(window.resourceNetwork && window.resourceNetwork.clearHighlight)
					window.resourceNetwork.clearHighlight();
			});
			// Click: open the Analyze tab focused on this series (consumed on analyze.js boot).
			li.addEventListener("click", function(){
				try { sessionStorage.setItem("dh.selectedTimeseries", extId); } catch(e){ /* storage unavailable */ }
				window.location = "/timeseries/analyze";
			});
		});
	}

	function clear(){ setBody(""); }

	window.RelatedSeries = { load: load, clear: clear };
})();
