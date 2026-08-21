/*
 * Timeseries "Analyze" tab logic.
 *
 * Runs inside the app shell (templates/timeseries/analyze.html) but does all data work CLIENT-SIDE
 * — no console BFF. Base URLs come from the <meta name="datahub-api-url"> and
 * <meta name="datahub-analysis-url"> tags, with a Bearer token from the same-origin /token endpoint.
 * The focus list is read from the api; the relationship analysis lives on the separate analysis
 * service. Bundled through Closure Compiler (see assets.gradle).
 *
 *   GET  {api}/timeseries?limit=...   -> focus picker list
 *   POST {analysis}/analysis          -> the relationship analysis
 */
(function(){
	"use strict";

	var cachedToken = null;
	var allSeries = [];
	var focusExternalId = null;
	var chart = null;
	var plotted = {};      // externalId -> chart color, for series currently overlaid
	var lastWindow = null; // { start, end } the current results were computed over
	var autoRunTimer = null; // debounce handle for auto-run on focus/param changes
	var runToken = 0;      // monotonic; a run only touches the UI if it's still the latest
	// Candidates whose composite relevance score falls below this are greyed out as likely-unrelated.
	// Soft cutoff (still visible + clickable), so err low; tune against real results.
	var RELEVANCE_CUTOFF = 0.2;
	// Nearest-N graph BFS: the analysis returns the closest DEFAULT_LIMIT timeseries; "Expand search"
	// adds EXPAND_STEP each click. currentLimit resets to the default whenever the focus changes.
	var DEFAULT_LIMIT = 10;
	var EXPAND_STEP = 10;
	var currentLimit = 10;

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

	// ---- auth ---------------------------------------------------------------------------------
	function getToken(force){
		if(cachedToken && !force) return Promise.resolve(cachedToken);
		return fetch("/token", { credentials: "same-origin" })
			.then(function(r){ return r.ok ? r.text() : Promise.reject("could not get access token"); })
			.then(function(t){ cachedToken = t.trim(); return cachedToken; });
	}

	// Call a datahub service with a Bearer token; refetch the token once on a 401. `base` defaults to
	// the api; the relationship analysis lives on the separate analysis service, so it passes its own.
	function api(path, opts, base){
		opts = opts || {};
		var send = function(token){
			var headers = Object.assign({ "Accept": "application/json", "Authorization": "Bearer " + token }, opts.headers || {});
			return fetch((base || apiBase()) + path, Object.assign({}, opts, { headers: headers }));
		};
		return getToken(false)
			.then(send)
			.then(function(r){ return (r.status === 401) ? getToken(true).then(send) : r; })
			.then(function(r){
				if(!r.ok) return r.text().then(function(b){ return Promise.reject("HTTP " + r.status + (b ? ": " + b.slice(0,200) : "")); });
				return r.status === 204 ? null : r.json();
			});
	}

	// ---- time range ---------------------------------------------------------------------------
	// Reads the shared sub-nav datepicker controls (ts-start-date/time, ts-end-date/time,
	// ts-quick-range) — the same controls the Explore and Insights pages use.
	var QUICK_RANGE_MS = {
		"5m": 5*60000, "30m": 30*60000, "1h": 60*60000, "3h": 3*60*60000, "12h": 12*60*60000,
		"1d": 24*60*60000, "2d": 2*24*60*60000, "1w": 7*24*60*60000,
		"1M": 30*24*60*60000, "3M": 90*24*60*60000, "1y": 365*24*60*60000
	};
	function pad2(n){ return String(n).padStart(2, "0"); }
	function writeDateTime(dateEl, timeEl, date){
		if(dateEl) dateEl.value = date.getFullYear() + "-" + pad2(date.getMonth()+1) + "-" + pad2(date.getDate());
		if(timeEl) timeEl.value = pad2(date.getHours()) + ":" + pad2(date.getMinutes());
	}
	function readLocalIso(dateEl, timeEl){
		if(!dateEl || !dateEl.value) return null;
		return dateEl.value + "T" + ((timeEl && timeEl.value) || "00:00");
	}
	function applyQuickRange(preset){
		var ms = QUICK_RANGE_MS[preset];
		if(!ms) return;
		var end = new Date();
		var start = new Date(end.getTime() - ms);
		writeDateTime(document.getElementById("ts-start-date"), document.getElementById("ts-start-time"), start);
		writeDateTime(document.getElementById("ts-end-date"), document.getElementById("ts-end-time"), end);
	}
	function getCurrentRange(){
		var s = readLocalIso(document.getElementById("ts-start-date"), document.getElementById("ts-start-time"));
		var e = readLocalIso(document.getElementById("ts-end-date"), document.getElementById("ts-end-time"));
		if(!s || !e) return null;
		var start = new Date(s), end = new Date(e);
		if(isNaN(start.getTime()) || isNaN(end.getTime())) return null;
		return { start: start, end: end };
	}

	// ---- chart overlay ------------------------------------------------------------------------
	// Reuse the insights chart (bundled via charts/insights.js). Clicking a result card fetches that
	// series' datapoints from the api and overlays them, the same way the insights tab does.
	function ensureChart(){
		if(chart) return chart;
		if(typeof InsightsChart === "undefined") return null;
		var el = document.getElementById("datapoints-chart");
		if(!el) return null;
		chart = new InsightsChart({ container: el });
		chart.height = 360 - chart.margin.top - chart.margin.bottom;
		chart.refreshSVG();
		return chart;
	}

	// InsightsChart.render() assumes at least one dataset (it reads dataSets[0]); refreshSVG()
	// is what the constructor uses and is safe when empty. Draw via this so an empty chart clears
	// instead of throwing.
	function drawChart(){
		if(!chart) return;
		if(Object.keys(plotted).length > 0) chart.render();
		else chart.refreshSVG();
	}

	function fetchDatapoints(externalId){
		if(!lastWindow) return Promise.resolve(null);
		// The chart picks its own display granularity for the window (independent of the analysis).
		var spanSec = (new Date(lastWindow.end) - new Date(lastWindow.start)) / 1000;
		var gran = (typeof InsightsChart !== "undefined" && InsightsChart.pickGranularityForSpan)
			? InsightsChart.pickGranularityForSpan(spanSec) : "5 min";
		var body = { items: [ {
			externalId: externalId,
			start: lastWindow.start,
			end: lastWindow.end,
			aggregates: ["avg", "min", "max"],
			granularity: gran,
			limit: 100000
		} ] };
		return api("/timeseries/data/list", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body)
		}).then(function(d){ return (d && d.items && d.items[0]) ? d.items[0] : null; })
		  .catch(function(){ return null; });
	}

	// Toggle a series on the chart. Resolves to the assigned colour when added, or false when
	// removed / unavailable (e.g. no data, or the chart's colour pool is full).
	function overlaySeries(externalId){
		var c = ensureChart();
		if(!c) return Promise.resolve(false);
		if(plotted[externalId]){
			c.removeDataSet(externalId);
			delete plotted[externalId];
			drawChart();
			return Promise.resolve(false);
		}
		return fetchDatapoints(externalId).then(function(item){
			if(!item || !item.datapoints || !item.datapoints.length) return false;
			var color = c.addDataSet(externalId, item);
			if(!color) return false; // colour pool exhausted (max series reached)
			plotted[externalId] = color;
			try { c.setTimeWindow(new Date(lastWindow.start), new Date(lastWindow.end)); } catch(e){ /* noop */ }
			drawChart();
			return color;
		});
	}

	function resetChart(){
		var c = ensureChart();
		if(!c) return;
		Object.keys(plotted).forEach(function(id){ c.removeDataSet(id); });
		plotted = {};
		Array.prototype.forEach.call(document.querySelectorAll(".an-card.active"), function(el){
			el.classList.remove("active");
			el.style.borderLeftColor = "transparent";
		});
		drawChart();
	}

	// ---- focus list ---------------------------------------------------------------------------
	function loadSeries(){
		setStatus(L("insights.analysis.computing", "Loading…"));
		return api("/timeseries?limit=1000")
			.then(function(data){
				allSeries = (data && data.items ? data.items : []).slice()
					.sort(function(a,b){ return (a.name||a.externalId||"").localeCompare(b.name||b.externalId||""); });
				renderList();
				setStatus("");
				// A chat "open the analysis" hand-off wins over the plain carried selection: it also
				// carries the time window and limit, not just the focus series.
				if(!applyPendingView()) consumePreselect();
			})
			.catch(function(err){ setStatus("Failed to load time series: " + err); });
	}

	// If another page (e.g. Explore's "Related series" panel) navigated here with a series pre-selected
	// in sessionStorage, focus it once the list has loaded — the same key the Insights page reads. Left
	// in place (not removed) so the shared cross-tab selection survives; harmless when absent.
	function consumePreselect(){
		var pre;
		try { pre = sessionStorage.getItem("dh.selectedTimeseries"); } catch(e){ pre = null; }
		if(!pre) return;
		var ts = allSeries.filter(function(s){ return s.externalId === pre; })[0];
		if(ts) selectFocus(ts.externalId, ts.name);
	}
		// Chat hand-off: the assistant navigated here with a seed series + a time window (e.g. an
		// anomaly period). Reads the same sessionStorage key the other pages use, applies the window
		// (UTC ISO -> local datepicker fields, matching the events page), focuses the seed even if it
		// isn't in the loaded list, and runs. Returns true when it consumed a pending analyze view.
		function applyPendingView(){
			var pending;
			try { pending = JSON.parse(sessionStorage.getItem("dh-pending-view") || "null"); } catch(e){ pending = null; }
			if(!pending || pending.page !== "analyze") return false;
			try { sessionStorage.removeItem("dh-pending-view"); } catch(e){}
			var f = pending.filter || {};
			if(f.start) writeDateTime(document.getElementById("ts-start-date"), document.getElementById("ts-start-time"), new Date(f.start));
			if(f.end) writeDateTime(document.getElementById("ts-end-date"), document.getElementById("ts-end-time"), new Date(f.end));
			var quick = document.getElementById("ts-quick-range");
			if(quick) quick.value = ""; // a custom window, not a preset
			if(!f.focusExternalId) return true; // window only; let the user pick a focus
			// Set the focus directly (not via a list click) so a seed outside the top-1000 list works.
			var match = allSeries.filter(function(s){ return s.externalId === f.focusExternalId; })[0];
			focusExternalId = f.focusExternalId;
			currentLimit = (typeof f.limit === "number" && f.limit > 0) ? f.limit : DEFAULT_LIMIT;
			var ul = document.getElementById("an-ts-list");
			if(ul) Array.prototype.forEach.call(ul.querySelectorAll("li"), function(li){
				li.classList.toggle("selected", li.getAttribute("data-external-id") === focusExternalId);
			});
			var label = document.getElementById("an-focus-label");
			if(label) label.innerHTML = L("insights.analysis.focus", "Focus") + ": <b>" + esc(match ? (match.name || match.externalId) : f.focusExternalId) + "</b>";
			runAnalyze();
			return true;
		}

	function renderList(){
		var ul = document.getElementById("an-ts-list");
		if(!ul) return;
		var filterEl = document.getElementById("an-filter");
		var filter = ((filterEl && filterEl.value) || "").toLowerCase();
		ul.innerHTML = "";
		allSeries
			.filter(function(ts){ return !filter || (ts.externalId + " " + (ts.name||"")).toLowerCase().indexOf(filter) >= 0; })
			.forEach(function(ts){
				var li = document.createElement("li");
				li.setAttribute("data-external-id", ts.externalId);
				li.textContent = ts.name || ts.externalId;
				if(ts.externalId === focusExternalId) li.classList.add("selected");
				li.addEventListener("click", function(){ selectFocus(ts.externalId, ts.name); });
				ul.appendChild(li);
			});
	}

	function selectFocus(externalId, name){
		focusExternalId = externalId;
		currentLimit = DEFAULT_LIMIT; // a new focus starts from the nearest N; expand grows from there
		var ul = document.getElementById("an-ts-list");
		Array.prototype.forEach.call(ul.querySelectorAll("li"), function(li){
			li.classList.toggle("selected", li.getAttribute("data-external-id") === externalId);
		});
		var label = document.getElementById("an-focus-label");
		label.innerHTML = L("insights.analysis.focus", "Focus") + ": <b>" + esc(name || externalId) + "</b>";
		autoRun();
	}

	// ---- analysis -----------------------------------------------------------------------------
	// Re-analyze automatically when the focus or a parameter changes. Debounced so a burst of
	// changes (e.g. clicking through the range or depth) collapses into one run; the
	// run-token in runAnalyze then guarantees only the latest run's results reach the UI.
	function autoRun(){
		if(!focusExternalId) return; // nothing to analyze until a focus is picked
		clearTimeout(autoRunTimer);
		autoRunTimer = setTimeout(runAnalyze, 250);
	}

	function runAnalyze(){
		if(!focusExternalId){
			if(typeof Flash !== "undefined" && Flash.warning) Flash.warning(L("insights.analysis.selectFocus", "Select a focus time series first."));
			else setStatus(L("insights.analysis.selectFocus", "Select a focus time series first."));
			return;
		}
		var range = getCurrentRange();
		if(!range){ setStatus(L("insights.analysis.pickRange", "Pick a start and end time.")); return; }
		// No granularity knob: the backend derives the bucket width from the window.
		lastWindow = { start: range.start.toISOString(), end: range.end.toISOString() };
		var body = {
			focusExternalId: focusExternalId,
			start: lastWindow.start,
			end: lastWindow.end,
			limit: currentLimit
		};
		var token = ++runToken; // supersede any in-flight run; only this one may touch the UI
		document.getElementById("an-run").disabled = true;
		document.getElementById("an-band").textContent = "";
		setStatus(L("insights.analysis.computing", "Computing…"));
		api("/analysis", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body)
		}, analysisBase())
			.then(function(resp){ if(token === runToken) render(resp); })
			.catch(function(err){ if(token === runToken) setStatus(L("insights.analysis.error", "Analysis failed.") + " " + err); })
			.then(function(){ if(token === runToken) document.getElementById("an-run").disabled = false; });
	}

	// ---- rendering ----------------------------------------------------------------------------
	function setStatus(msg){
		document.getElementById("an-out").innerHTML = msg ? '<div class="an-status">' + esc(msg) + '</div>' : "";
	}

	function render(resp){
		// The backend returns 200 with a `message` and no results when the window is empty — a normal
		// outcome, not a failure. Show it as a plain status rather than an error.
		if(resp && resp.message){
			document.getElementById("an-band").textContent = "";
			setStatus(resp.message);
			return;
		}
		var band = resp.band || {};
		var parts = [];
		if(band.coherenceMinPeriodSeconds && band.coherenceMaxPeriodSeconds)
			parts.push(L("insights.analysis.coherence","coherence") + " " + dur(band.coherenceMinPeriodSeconds) + " – " + dur(band.coherenceMaxPeriodSeconds));
		if(band.maxLagSeconds) parts.push(L("insights.analysis.maxDelay","max delay") + " ±" + dur(band.maxLagSeconds));
		parts.push("bucket " + dur(resp.bucketSeconds));
		var bandEl = document.getElementById("an-band");
		bandEl.textContent = parts.join("  ·  ") + "  ⓘ"; // trailing ⓘ hints the hover note
		bandEl.title = L("insights.analysis.resolutionNote",
			"Lead/lag is measured in whole buckets, and the bucket width is derived from the selected "
			+ "range. An offset shorter than one bucket can't be told apart from zero, so it shows as "
			+ "'synchronous'. Choose a shorter range for finer lead/lag resolution.");

		var out = document.getElementById("an-out");
		out.innerHTML = "";
		var results = resp.results || [];
		if(!results.length){
			out.innerHTML = '<div class="an-status">' + esc(L("insights.analysis.empty", "No related time series found.")) + "</div>";
		}
		// Results arrive already ranked (backend composite score, descending). Number them, and once
		// we cross below the relevance cutoff, drop a divider and grey the rest out.
		var weakShown = false;
		results.forEach(function(r, i){
			var weak = isNum(r.rankScore) && r.rankScore < RELEVANCE_CUTOFF;
			if(weak && !weakShown){
				weakShown = true;
				var divider = document.createElement("div");
				divider.className = "an-weak-divider";
				divider.textContent = L("insights.analysis.weakDivider", "Below relevance threshold");
				out.appendChild(divider);
			}
			out.appendChild(card(r, i + 1, resp.bucketSeconds, resp.band || {}, weak));
		});

		if(resp.skipped && resp.skipped.length){
			var s = document.createElement("div");
			s.className = "an-skipped";
			s.textContent = L("insights.analysis.skipped", "Skipped (too sparse / no access):") + " " + resp.skipped.join(", ");
			out.appendChild(s);
		}

		// Start the chart fresh and plot the focus as the baseline; candidates overlay on click.
		resetChart();
		overlaySeries(focusExternalId);
	}

	function card(r, rank, bucketSeconds, band, weak){
		var c = document.createElement("div");
		c.className = "an-card" + (weak ? " weak" : "");

		var h = document.createElement("h4");
		var rankEl = document.createElement("span");
		rankEl.className = "an-rank";
		rankEl.textContent = "#" + rank;
		h.appendChild(rankEl);
		h.appendChild(document.createTextNode(" " + (r.name || r.externalId)));
		c.appendChild(h);

		var m = document.createElement("div");
		m.className = "an-metrics";
		if(isNum(r.rawCorrelation)) m.appendChild(metric(L("insights.analysis.rawCorr","raw"), r.rawCorrelation.toFixed(2)));
		if(isNum(r.whitenedCorrelation)) m.appendChild(metric(L("insights.analysis.whitenedCorr","whitened"), r.whitenedCorrelation.toFixed(2)));
		if(isNum(r.peakCoherence)) m.appendChild(metric(L("insights.analysis.coherence","coherence"), r.peakCoherence.toFixed(2)));
		var lag = isNum(r.whitenedLagSeconds) ? r.whitenedLagSeconds : r.rawLagSeconds;
		if(isNum(lag)){
			var lagNote = (lag === 0) ? (L("insights.analysis.synchronousNote",
				"No lead/lag at this resolution — any offset is smaller than one bucket. "
				+ "Choose a shorter range to resolve finer lead/lags.")
				+ " (bucket " + dur(bucketSeconds) + ")") : null;
			m.appendChild(metric(L("insights.analysis.lag","lead/lag"), lagText(lag), lagNote));
		}
		c.appendChild(m);

		// Flags describe what the result MEANS for trusting the relationship (not the statistic
		// behind it — that's for the docs). State is shown when tested; "read with care" is loud.
		var b = document.createElement("div");
		b.className = "an-metrics an-flags";
		b.style.marginTop = "6px";
		if(r.cointegrated === true)
			b.appendChild(badge("✓ " + L("insights.analysis.cointegrated","lasting link"), "pos",
				L("insights.analysis.cointegratedNote","These two stay tied together over the long run rather than drifting apart — a dependable relationship you can lean on.")));
		else if(r.cointegrated === false)
			b.appendChild(badge("✗ " + L("insights.analysis.notCointegrated","no lasting link"), "muted",
				L("insights.analysis.notCointegratedNote","Any resemblance here is short-term — over a longer span these two drift apart, so don't count on the relationship holding.")));
		if(r.stable === true)
			b.appendChild(badge("✓ " + L("insights.analysis.stable","consistent"), "pos",
				L("insights.analysis.stableNote","This relationship holds up across the whole range, not just one stretch — so it's more trustworthy.")));
		else if(r.stable === false)
			b.appendChild(badge("⚠ " + L("insights.analysis.unstable","inconsistent"), "warn",
				L("insights.analysis.unstableNote","Be careful reading into this. The relationship shows up in some parts of the range but not others — it may be a coincidence or a one-off event rather than a dependable link.")));
		if(isNum(r.peakCoherencePeriodSeconds))
			b.appendChild(badge(L("insights.analysis.timescale","peak shared period") + " ~" + dur(r.peakCoherencePeriodSeconds), "muted",
				L("insights.analysis.timescaleNote","The period where these signals move together most.")));
		if(b.childElementCount) c.appendChild(b);

		if(r.path && r.path.length){
			var p = document.createElement("div");
			p.className = "an-path";
			p.textContent = "↪ " + r.path.map(function(hop){ return hop.relationshipType; }).join(" → ");
			c.appendChild(p);
		}

		if(r.coherenceSpectrum && r.coherenceSpectrum.length > 1){
			var wrap = document.createElement("div");
			wrap.className = "an-coh";
			wrap.title = L("insights.analysis.coherencePlotNote","Shows the period of shared oscillations in these timeseries.");
			wrap.innerHTML = coherenceSvg(r.coherenceSpectrum, band.coherenceMinPeriodSeconds, band.coherenceMaxPeriodSeconds);
			c.appendChild(wrap);
		}

		// Click the card to overlay/remove this series on the chart (like the insights tab).
		c.addEventListener("click", function(){
			overlaySeries(r.externalId).then(function(color){
				c.classList.toggle("active", !!color);
				c.style.borderLeftColor = color ? String(color) : "transparent";
			});
		});
		return c;
	}

	function metric(label, value, title){
		var s = document.createElement("span");
		s.innerHTML = esc(label) + " <b>" + esc(String(value)) + "</b>";
		if(title){ s.title = title; s.style.cursor = "help"; s.style.textDecoration = "underline dotted"; }
		return s;
	}
	function badge(text, kind, title){
		var s = document.createElement("span");
		s.className = "an-badge " + kind;
		s.textContent = text;
		if(title){ s.title = title; s.style.cursor = "help"; }
		return s;
	}

	// Coherence sparkline on FIXED axes so cards are comparable: y = coherence 0..1 (with 0/.5/1
	// gridlines), x = log period over the analysis's probed band [domainMin, domainMax] — the same
	// domain for every card. Falls back to the series' own period span if the band is unavailable.
	function coherenceSvg(spec, domainMin, domainMax){
		var W = 264, H = 76, left = 24, right = 8, top = 6, bottom = 16;
		var plotW = W - left - right, plotH = H - top - bottom;
		var pts = spec.filter(function(p){ return p.periodSeconds > 0; })
			.sort(function(a,b){ return a.periodSeconds - b.periodSeconds; });
		if(pts.length < 2) return "";
		var lmin = Math.log10(domainMin), lmax = Math.log10(domainMax);
		if(!(isFinite(lmin) && isFinite(lmax) && lmax > lmin)){
			lmin = Math.log10(pts[0].periodSeconds);
			lmax = Math.log10(pts[pts.length - 1].periodSeconds);
		}
		var xs = function(v){
			var t = (lmax === lmin) ? 0 : (Math.log10(v) - lmin) / (lmax - lmin);
			return left + Math.max(0, Math.min(1, t)) * plotW;
		};
		var ys = function(c){ return top + (1 - Math.max(0, Math.min(1, c))) * plotH; };
		var poly = pts.map(function(p){ return xs(p.periodSeconds).toFixed(1) + "," + ys(p.coherence).toFixed(1); }).join(" ");
		var peak = pts.reduce(function(a,b){ return b.coherence > a.coherence ? b : a; }, pts[0]);
		var grid = [0, 0.5, 1].map(function(v){
			var y = ys(v).toFixed(1);
			return '<line class="an-grid" x1="' + left + '" y1="' + y + '" x2="' + (W - right) + '" y2="' + y + '"/>' +
				'<text class="an-axis" x="' + (left - 3) + '" y="' + (parseFloat(y) + 3) + '" text-anchor="end" font-size="8">' + (v === 0.5 ? '.5' : v) + '</text>';
		}).join("");
		var yMid = top + plotH / 2;
		var loEdge = isFinite(domainMin) ? domainMin : pts[0].periodSeconds;
		var hiEdge = isFinite(domainMax) ? domainMax : pts[pts.length - 1].periodSeconds;
		return '<svg width="' + W + '" height="' + H + '">' + grid +
			'<text class="an-axis" x="8" y="' + yMid + '" text-anchor="middle" font-size="8" transform="rotate(-90 8 ' + yMid + ')">coherence</text>' +
			'<polyline class="an-spark" fill="none" stroke-width="1.5" points="' + poly + '"/>' +
			'<circle cx="' + xs(peak.periodSeconds).toFixed(1) + '" cy="' + ys(peak.coherence).toFixed(1) + '" r="2.5" fill="#ffb155"/>' +
			'<text class="an-axis" x="' + left + '" y="' + (H - 4) + '" text-anchor="start" font-size="8">' + dur(loEdge) + '</text>' +
			'<text class="an-axis" x="' + (left + plotW / 2) + '" y="' + (H - 4) + '" text-anchor="middle" font-size="8">period →</text>' +
			'<text class="an-axis" x="' + (W - right) + '" y="' + (H - 4) + '" text-anchor="end" font-size="8">' + dur(hiEdge) + '</text>' +
			'</svg>';
	}

	// ---- helpers ------------------------------------------------------------------------------
	function isNum(x){ return typeof x === "number" && !isNaN(x); }
	function lagText(seconds){
		if(seconds === 0) return L("insights.analysis.synchronous", "synchronous");
		return (seconds > 0 ? L("insights.analysis.focusLeads","focus leads by") + " " : L("insights.analysis.focusLags","focus lags by") + " ") + dur(Math.abs(seconds));
	}
	function dur(seconds){
		seconds = Math.round(Math.abs(seconds));
		if(seconds === 0) return "0s";
		var u = [["d",86400],["h",3600],["m",60],["s",1]];
		var out = [];
		for(var i = 0; i < u.length; i++){
			var lab = u[i][0], sz = u[i][1];
			if(seconds >= sz){ out.push(Math.floor(seconds/sz) + lab); seconds %= sz; if(out.length === 2) break; }
		}
		return out.join(" ");
	}
	function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]; }); }

	// ---- boot ---------------------------------------------------------------------------------
	function boot(){
		// Inert unless we're on the analyze page (guards against a stale/mismatched bundle load).
		if(!document.getElementById("an-ts-list")) return;
		var filter = document.getElementById("an-filter");
		if(filter) filter.addEventListener("input", renderList);
		var run = document.getElementById("an-run");
		if(run) run.addEventListener("click", runAnalyze);
		// Default the window to the last day, then auto-run on range/depth changes (focus clicks
		// trigger via selectFocus). The window uses the shared sub-nav datepicker controls.
		var quick = document.getElementById("ts-quick-range");
		if(quick){
			applyQuickRange("1d");
			quick.value = "1d";
			quick.addEventListener("change", function(){
				if(quick.value) applyQuickRange(quick.value);
				autoRun();
			});
		}
		["ts-start-date", "ts-start-time", "ts-end-date", "ts-end-time"].forEach(function(id){
			var el = document.getElementById(id);
			if(el) el.addEventListener("change", function(){ if(quick) quick.value = ""; autoRun(); });
		});
		var expand = document.getElementById("an-expand");
		if(expand) expand.addEventListener("click", function(){
			if(!focusExternalId) return;
			currentLimit += EXPAND_STEP; // widen the nearest-N graph search and re-run
			runAnalyze();
		});
		loadSeries();
	}
	if(document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
	else boot();
})();
