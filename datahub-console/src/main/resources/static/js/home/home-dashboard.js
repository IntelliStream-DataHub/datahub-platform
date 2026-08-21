// /static/js/home/home-dashboard.js
//
// Home dashboard behaviour. The page is a shell; this fills every widget by calling the datahub API
// DIRECTLY from the browser — a bearer token minted from the console session (/token), then fetch()
// against the API base URL in <meta name="datahub-api-url"> (the same path the tutorial seeding and
// file upload use). No console-side relay. Three reads:
//   - stats    : GET /stats   (the API caches each metric per-key; poll fast metrics often, slow rarely)
//   - spotlight: GET /timeseries + POST /timeseries/data/list   (cache-aside in sessionStorage)
//   - activity : POST /events/filter                            (cache-aside in sessionStorage)
// Tile navigation lives in tutorials/dashboard.js.
//
// "datapoints" is a live, incrementally-maintained Valkey counter on the API side (see
// DatapointIngestCounter) rather than a recomputed ClickHouse estimate, so it's cheap to poll on its
// own fast cadence (FAST_MS) alongside events/alarms/topEventTypes — no extra endpoint needed.
(function () {
    "use strict";
    var NAVY = "#105984";

    var STATS_FAST = ["events", "alarms", "datapoints", "topEventTypes"];          // change often
    var STATS_SLOW = ["rootAssets", "allAssets", "timeseries", "datasets", "policies"]; // rarely
    var STATS_ALL = STATS_SLOW.concat(STATS_FAST);
    var FAST_MS = 3000, SLOW_MS = 300000;

    var SPOTLIGHT = { key: "dh:spotlight", ttl: 60000, count: 6, hours: 24 };
    var ACTIVITY = { key: "dh:activity", ttl: 60000, limit: 8 };

    var apiBase = (document.querySelector('meta[name="datahub-api-url"]') || {}).content || "";
    var i18n = window.DASHBOARD_I18N || {};
    var locale = window.DASHBOARD_LOCALE || "en";
    var currentStats = {};   // metric -> last known number; drives the tile empty-states

    document.addEventListener("DOMContentLoaded", function () {
        loadStats(STATS_ALL);
        loadSpotlight();
        loadActivity();
        wireLearnTutorial();
        setInterval(function () { loadStats(STATS_FAST); }, FAST_MS);
        setInterval(function () { loadStats(STATS_SLOW); }, SLOW_MS);
    });

    // The "Learn DataHub" bar opens the guided tutorial by triggering the existing header menu entry
    // (whose click handler lives in the tutorial bundle). The button is only rendered when the tutorial
    // is enabled — the same flag that loads that bundle — so the entry and its handler are present here.
    function wireLearnTutorial() {
        var btn = document.getElementById("home-learn-tutorial");
        if (!btn) return;
        btn.addEventListener("click", function () {
            var entry = document.querySelector('[data-type="guided-tutorial"]');
            if (entry) entry.click();
        });
    }

    // --- API access -----------------------------------------------------------------------
    // The bearer is the logged-in user's access token; cache it briefly (well under its lifetime) so
    // the initial loads and polls don't each hit /token. Cleared implicitly when it ages out.
    var _tok = null, _tokAt = 0;
    function token() {
        if (_tok && (Date.now() - _tokAt) < 60000) return Promise.resolve(_tok);
        return fetch("/token", { headers: { Accept: "text/plain" }, credentials: "same-origin" })
            .then(function (r) { return r.ok ? r.text() : Promise.reject(r.status); })
            .then(function (t) { _tok = t; _tokAt = Date.now(); return t; });
    }
    function apiGet(path) {
        return token().then(function (t) {
            return fetch(apiBase + path, {
                headers: { "Authorization": "Bearer " + t, "Accept": "application/json" },
                signal: AbortSignal.timeout(10000)
            });
        }).then(readJson);
    }
    function apiPost(path, body) {
        return token().then(function (t) {
            return fetch(apiBase + path, {
                method: "POST",
                headers: { "Authorization": "Bearer " + t, "Accept": "application/json", "Content-Type": "application/json" },
                body: JSON.stringify(body),
                signal: AbortSignal.timeout(10000)
            });
        }).then(readJson);
    }
    function readJson(r) { return r.ok ? r.json() : Promise.reject(r.status); }

    // sessionStorage cache-aside: { t: epoch, v: value }
    function readCache(key, ttl) {
        try {
            var o = JSON.parse(sessionStorage.getItem(key));
            return (o && (Date.now() - o.t) < ttl) ? o.v : null;
        } catch (e) { return null; }
    }
    function writeCache(key, value) {
        try { sessionStorage.setItem(key, JSON.stringify({ t: Date.now(), v: value })); } catch (e) { /* quota / private mode */ }
    }

    // --- Stats: tile counts + event-type bars ---------------------------------------------
    function loadStats(keys) {
        apiGet("/stats?keys=" + keys.join(",")).then(applyStats).catch(function () { /* keep last good */ });
    }
    function applyStats(s) {
        if (!s) return;
        Object.keys(s).forEach(function (k) {
            if (k === "topEventTypes") { renderEventTypes(s[k] || []); return; }
            currentStats[k] = Number(s[k]) || 0;
            var el = document.querySelector('.metric-value[data-stat="' + k + '"]');
            if (el) countTo(el, currentStats[k]);
        });
        updateEmptyStates();
    }
    // A tile shows its empty-state CTA when every count it knows about is zero. Uses the last known
    // target values (not the animating DOM text) so there's no flash of "0 -> CTA" on first paint.
    function updateEmptyStates() {
        document.querySelectorAll(".metric-tile").forEach(function (tile) {
            var vals = tile.querySelectorAll(".metric-value[data-stat]");
            if (!vals.length) return;
            var known = 0, allZero = true;
            vals.forEach(function (v) {
                var k = v.getAttribute("data-stat");
                if (k in currentStats) { known++; if (currentStats[k] !== 0) allZero = false; }
            });
            if (!known) return;
            var row = tile.querySelector(".metric-row"), empty = tile.querySelector(".metric-empty");
            if (row) row.classList.toggle("is-hidden", allZero);
            if (empty) empty.classList.toggle("is-hidden", !allZero);
        });
    }
    function countTo(el, target) {
        var start = toNumber(el.textContent);
        if (start === target) { el.textContent = target.toLocaleString(); return; }
        var duration = 700, t0 = null;
        function tick(ts) {
            if (t0 === null) t0 = ts;
            var p = Math.min((ts - t0) / duration, 1);
            var eased = 1 - Math.pow(1 - p, 3);
            el.textContent = Math.round(start + (target - start) * eased).toLocaleString();
            if (p < 1) requestAnimationFrame(tick);
        }
        requestAnimationFrame(tick);
    }
    function toNumber(text) {
        var n = parseInt((text || "0").replace(/[^0-9-]/g, ""), 10);
        return isNaN(n) ? 0 : n;
    }

    function renderEventTypes(list) {
        var ul = document.getElementById("home-eventtypes");
        var empty = document.getElementById("home-eventtypes-empty");
        if (!ul) return;
        if (!list.length) { ul.innerHTML = ""; if (empty) empty.classList.remove("is-hidden"); return; }
        if (empty) empty.classList.add("is-hidden");
        var max = list.reduce(function (m, e) { return Math.max(m, Number(e.count) || 0); }, 0) || 1;
        ul.innerHTML = list.map(function (e) {
            var pct = barPct(Number(e.count) || 0, max);
            return '<li class="eventtype-item">' +
                '<span class="eventtype-name"></span>' +
                '<span class="eventtype-track"><span class="eventtype-fill" style="width:' + pct + '%"></span></span>' +
                '<span class="eventtype-count">' + (Number(e.count) || 0).toLocaleString() + '</span></li>';
        }).join("");
        // Names via textContent so a tenant's event-type strings can't inject markup.
        var items = ul.querySelectorAll(".eventtype-item");
        list.forEach(function (e, i) {
            var nameEl = items[i] && items[i].querySelector(".eventtype-name");
            if (nameEl) nameEl.textContent = e.type;
        });
    }
    function barPct(count, max) { return count <= 0 ? 0 : Math.max(6, Math.round(count / max * 100)); }

    // --- Spotlight: newest series' last-24h data, classified live/stale, drawn with D3 ---
    function loadSpotlight() {
        var cached = readCache(SPOTLIGHT.key, SPOTLIGHT.ttl);
        if (cached) { renderSpotlight(cached); return; }
        fetchSpotlight()
            .then(function (cards) { writeCache(SPOTLIGHT.key, cards); renderSpotlight(cards); })
            .catch(function () { renderSpotlight([]); });
    }
    function fetchSpotlight() {
        // The 6 most-recently-created series (the list is newest-first), shown in that order. Each is
        // classified live (has ≥2 points in the last 24h -> sparkline) or stale (muted + "No data" badge).
        return apiGet("/timeseries?limit=" + SPOTLIGHT.count).then(function (list) {
            var series = ((list && list.items) || []).slice(0, SPOTLIGHT.count);
            if (!series.length) return [];
            var now = new Date(), start = new Date(now.getTime() - SPOTLIGHT.hours * 3600000);
            var filters = series.map(function (ts) {
                return {
                    externalId: ts.externalId, start: start.toISOString(), end: now.toISOString(),
                    aggregates: ["avg"], granularity: "30 minute"
                };
            });
            return apiPost("/timeseries/data/list", { items: filters }).then(function (resp) {
                var byId = {};
                ((resp && resp.items) || []).forEach(function (dc) {
                    var vals = (dc.datapoints || []).map(dpValue).filter(function (v) { return v != null; });
                    if (vals.length) byId[dc.externalId] = vals;
                });
                return series.map(function (ts) {
                    var vals = byId[ts.externalId];
                    var isLive = vals && vals.length >= 2;    // has recent data in the 24h window
                    return { name: ts.name, unit: ts.unit || "", spark: isLive ? vals : [], status: isLive ? "ok" : "stale" };
                });
            });
        });
    }
    function dpValue(dp) {
        if (!dp || typeof dp !== "object") return null;
        var keys = ["value", "average", "avg", "max"];
        for (var i = 0; i < keys.length; i++) {
            if (dp[keys[i]] != null) { var n = parseFloat(dp[keys[i]]); return isNaN(n) ? null : n; }
        }
        return null;
    }
    function renderSpotlight(cards) {
        var list = document.getElementById("home-spotlight");
        var empty = document.getElementById("home-spotlight-empty");
        if (!list) return;
        list.innerHTML = "";
        if (!cards.length) { if (empty) empty.classList.remove("is-hidden"); return; }
        if (empty) empty.classList.add("is-hidden");
        cards.forEach(function (c) {
            var a = document.createElement("a");
            a.className = "spark-card is-ready" + (c.status === "stale" ? " is-stale" : "");
            a.href = "/timeseries/index";
            var head = document.createElement("div"); head.className = "spark-head";
            var name = document.createElement("span"); name.className = "spark-name"; name.textContent = c.name || "";
            var unit = document.createElement("span"); unit.className = "spark-unit"; unit.textContent = c.unit || "";
            head.appendChild(name); head.appendChild(unit);
            var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
            svg.setAttribute("class", "spark-svg");
            svg.setAttribute("viewBox", "0 0 120 32");
            svg.setAttribute("preserveAspectRatio", "none");
            var foot = document.createElement("div"); foot.className = "spark-foot";
            var latest = document.createElement("span"); latest.className = "spark-latest";
            foot.appendChild(latest);
            if (c.status === "stale") {
                var badge = document.createElement("span"); badge.className = "spark-stale";
                badge.textContent = i18n.spotlightStale || "No data · 24h";
                foot.appendChild(badge);
            } else {
                var trend = document.createElement("span"); trend.className = "spark-trend";
                foot.appendChild(trend);
            }
            a.appendChild(head); a.appendChild(svg); a.appendChild(foot);
            list.appendChild(a);
            if (c.status !== "stale") drawSparkline(a, c.spark, c.unit);
        });
    }
    function drawSparkline(card, values, unit) {
        if (typeof d3 === "undefined" || !Array.isArray(values) || values.length < 2) return;
        var series = values.map(function (v, i) { return { t: i, v: Number(v) }; })
            .filter(function (d) { return d.v != null && !isNaN(d.v); });
        if (series.length < 2) return;
        var w = 120, h = 32, pad = 2;
        var x = d3.scaleLinear().domain([0, series.length - 1]).range([pad, w - pad]);
        var ext = d3.extent(series, function (d) { return d.v; });
        if (ext[0] === ext[1]) { ext[0] -= 1; ext[1] += 1; }   // flat line: avoid zero range
        var y = d3.scaleLinear().domain(ext).range([h - pad, pad]);
        var line = d3.line().x(function (d, i) { return x(i); }).y(function (d) { return y(d.v); });
        var svg = d3.select(card).select("svg.spark-svg");
        svg.selectAll("*").remove();
        svg.append("path").attr("d", line(series)).attr("fill", "none").attr("stroke", NAVY).attr("stroke-width", 1.5);
        var first = series[0].v, latest = series[series.length - 1].v;
        card.querySelector(".spark-latest").textContent = humanizeValue(latest, unit);
        // Trend over the window. Navy/Muted only — brand forbids alarm (red/green) colours.
        var trendEl = card.querySelector(".spark-trend");
        if (!trendEl) return;
        var delta = latest - first, pctAbs = first !== 0 ? Math.abs(delta / first) * 100 : 0;
        if (pctAbs < 0.05) { trendEl.textContent = "–"; }
        else {
            var pctTxt = pctAbs < 10 ? pctAbs.toFixed(1) : String(Math.round(pctAbs));
            trendEl.textContent = (delta > 0 ? "▲ " : "▼ ") + pctTxt + "%";
        }
    }
    // Byte-unit series -> getByteSize; other big numbers -> abbreviateNumber (both from application.js).
    function humanizeValue(v, unit) {
        if (v == null || isNaN(v)) return "";
        var u = (unit || "").trim().toLowerCase();
        if ((u === "bytes" || u === "byte" || u === "b") && typeof getByteSize === "function") return getByteSize(v, 1);
        var abs = Math.abs(v);
        if (abs >= 1e4 && typeof abbreviateNumber === "function") return abbreviateNumber(v);
        if (abs >= 100) return Math.round(v).toLocaleString();
        return (Math.round(v * 100) / 100).toLocaleString();
    }

    // --- Recent activity ------------------------------------------------------------------
    function loadActivity() {
        var cached = readCache(ACTIVITY.key, ACTIVITY.ttl);
        if (cached) { renderActivity(cached); return; }
        apiPost("/events/filter", { limit: ACTIVITY.limit, sort: { property: ["eventTime"], order: "desc" } })
            .then(function (resp) {
                var events = ((resp && resp.items) || []).map(function (e) {
                    return { type: e.type, subType: e.subType, description: e.description, eventTime: e.eventTime };
                });
                writeCache(ACTIVITY.key, events);
                renderActivity(events);
            }).catch(function () { renderActivity([]); });
    }
    function renderActivity(events) {
        var ul = document.getElementById("home-activity-list");
        var empty = document.getElementById("home-activity-empty");
        if (!ul) return;
        ul.innerHTML = "";
        if (!events.length) { if (empty) empty.classList.remove("is-hidden"); return; }
        if (empty) empty.classList.add("is-hidden");
        events.forEach(function (ev) {
            var li = document.createElement("li"); li.className = "activity-item";
            var dot = document.createElement("span"); dot.className = "activity-dot";
            var body = document.createElement("div"); body.className = "activity-body";
            var type = document.createElement("span"); type.className = "activity-type"; type.textContent = ev.type || "";
            body.appendChild(type);
            if (ev.subType) { var sub = document.createElement("span"); sub.className = "activity-sub"; sub.textContent = ev.subType; body.appendChild(sub); }
            if (ev.description) { var desc = document.createElement("span"); desc.className = "activity-desc"; desc.textContent = ev.description; body.appendChild(desc); }
            var time = document.createElement("time"); time.className = "activity-time"; time.textContent = formatTime(ev.eventTime);
            li.appendChild(dot); li.appendChild(body); li.appendChild(time);
            ul.appendChild(li);
        });
    }
    // Match the old server format 'dd MMM HH:mm', locale-aware to the page locale.
    function formatTime(raw) {
        if (raw == null) return "";
        var d = new Date(raw);
        if (isNaN(d.getTime())) return "";
        try {
            return d.toLocaleDateString(locale, { day: "2-digit", month: "short" }) + " " +
                d.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit", hour12: false });
        } catch (e) {
            return d.toISOString().slice(0, 16).replace("T", " ");
        }
    }
})();
