// /static/js/tutorials/datasets.js
(function () {
    // Defines the "datasets" tutorial and registers it into the engine.
    // Industry selection is read from localStorage and mapped to scenario i18n keys.

    if (window.__datasetsTutorialDefined) return;
    window.__datasetsTutorialDefined = true;

    const LS = Object.freeze({
        industry: "tutorial:datasets:industry"
    });

    // Use stable ids so both JS and message bundles can share the same values.
    const Industry = Object.freeze({
        OIL_GAS: "oil_gas",
        TECH: "tech",
        MANUFACTURING: "manufacturing",
        ENERGY: "energy"
    });

    function getIndustry() {
        return localStorage.getItem(LS.industry) || Industry.OIL_GAS;
    }

    // Bubble width for the side-by-side narration steps: the figures are 320px wide, so the
    // default 620px centered bubble would leave the copy column too narrow to read comfortably.
    const SPLIT_WIDTH = "760px";

    function fmt1(template, value0) {
        return String(template || "").replace("{0}", String(value0 ?? ""));
    }

    function scenarioKey(industry, suffix) {
        return `tutorial.datasets.scenario.${industry}.${suffix}`;
    }

    function getScenario(industry) {
        // All scenario text comes from messages.properties / messages_nb.properties
        return {
            name: $L(scenarioKey(industry, "name")),
            externalId: $L(scenarioKey(industry, "externalId")),
            description: $L(scenarioKey(industry, "description")),
            intro: $L(scenarioKey(industry, "intro")),
            // Resources chapter — industry-flavored node names.
            resourceName: $L(scenarioKey(industry, "resourceName")),
            resourceExternalId: $L(scenarioKey(industry, "resourceExternalId")),
            resource2Name: $L(scenarioKey(industry, "resource2Name")),
            // Timeseries chapter — an example measurement name.
            tsName: $L(scenarioKey(industry, "tsName"))
        };
    }

    // --- Illustration helpers (inline SVG for the problem / correlation / agent beats) ---
    // Injected straight into a step's content (the tooltip renders content as innerHTML, no CSP).
    // Series lines reuse the live chart's --line-path-* vars (via .tut-series-* classes) so the
    // SVG intros match the real InsightsChart payoff; structural parts use the .tut-svg-* classes.
    // All theme-aware. viewBox 0 0 320 150, no external refs.

    // Catmull-Rom -> cubic bezier: a smooth line through a set of [x,y] points.
    function smoothPath(pts) {
        if (!pts.length) return "";
        let d = `M${pts[0][0]} ${pts[0][1]}`;
        for (let i = 0; i < pts.length - 1; i++) {
            const p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
            const c1x = p1[0] + (p2[0] - p0[0]) / 6, c1y = p1[1] + (p2[1] - p0[1]) / 6;
            const c2x = p2[0] - (p3[0] - p1[0]) / 6, c2y = p2[1] - (p3[1] - p1[1]) / 6;
            d += ` C${c1x.toFixed(1)} ${c1y.toFixed(1)} ${c2x.toFixed(1)} ${c2y.toFixed(1)} ${p2[0]} ${p2[1]}`;
        }
        return d;
    }

    // Sparkline shapes as normalized heights (0 = bottom, 1 = top). One spikes (the anomaly).
    const WAVE = Object.freeze({
        a: [.28, .44, .38, .55, .66, .80], b: [.5, .36, .58, .42, .62, .72],
        c: [.4, .56, .46, .6, .5, .64], alert: [.36, .42, .4, .5, .95, .5]
    });
    // A dashboard-style monitor panel: a title strip (colour dot + label), a smooth area-filled
    // sparkline below, and an end dot. Three of these sit wired under the industry asset.
    function monitor(x, seriesCls, areaCls, fillCls, vals, label) {
        const y = 58, w = 96, h = 74, pad = 11, dotY = y + 16, top = y + 26, sh = 28, iw = w - 2 * pad;
        const pts = vals.map((v, i) => [+(x + pad + iw * (i / (vals.length - 1))).toFixed(1), +((top + sh) - v * sh).toFixed(1)]);
        const line = smoothPath(pts), area = line + ` L${pts[pts.length - 1][0]} ${top + sh} L${pts[0][0]} ${top + sh} Z`, last = pts[pts.length - 1];
        return `<rect class="tut-svg-panel" x="${x}" y="${y}" width="${w}" height="${h}" rx="9"/>`
            + `<circle class="${fillCls}" cx="${x + pad + 2}" cy="${dotY}" r="2.6"/>`
            + `<text class="tut-svg-label" x="${x + pad + 9}" y="${dotY + 3.5}">${label}</text>`
            + `<line class="tut-svg-grid" x1="${x + pad}" y1="${y + 22}" x2="${x + w - pad}" y2="${y + 22}" opacity="0.6"/>`
            + `<path class="${areaCls}" d="${area}"/><path class="${seriesCls}" d="${line}" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`
            + `<circle class="${fillCls}" cx="${last[0]}" cy="${last[1]}" r="2.4"/>`;
    }

    // Per-industry asset scene glyph + the three signal labels + which signal spikes (anomaly).
    function problemScene(industry) {
        if (industry === Industry.TECH) return { // server rack
            labels: ["CPU", "Inlet temp", "Fan"], alert: 1,
            icon: `<g class="tut-svg-glyph" stroke-width="1.6"><rect x="140" y="8" width="40" height="40" rx="4"/>`
                + `<line x1="140" y1="21.3" x2="180" y2="21.3"/><line x1="140" y1="34.6" x2="180" y2="34.6"/>`
                + `<line x1="152" y1="14.7" x2="164" y2="14.7"/><line x1="152" y1="28" x2="160" y2="28"/><line x1="152" y1="41.3" x2="162" y2="41.3"/></g>`
                + `<circle class="tut-fill-accent" cx="146.5" cy="14.7" r="1.8"/><circle class="tut-fill-accent" cx="146.5" cy="28" r="1.8"/><circle class="tut-fill-glyph" cx="146.5" cy="41.3" r="1.8"/>`
        };
        if (industry === Industry.MANUFACTURING) return { // conveyor + box + robot arm
            labels: ["Throughput", "Vibration", "Defects"], alert: 2,
            icon: `<g class="tut-svg-glyph" stroke-width="1.6"><path d="M118 44 L192 44"/>`
                + `<circle cx="124" cy="48" r="3.4"/><circle cx="141" cy="48" r="3.4"/><circle cx="158" cy="48" r="3.4"/><circle cx="175" cy="48" r="3.4"/><circle cx="192" cy="48" r="3.4"/>`
                + `<rect x="146" y="31" width="16" height="12" rx="1.5"/>`
                + `<path d="M200 46 L200 20 L186 20"/><path d="M186 20 L186 12 L177 17"/><circle cx="200" cy="46" r="3"/><circle cx="200" cy="20" r="2.6"/><circle cx="186" cy="20" r="2.4"/></g>`
        };
        if (industry === Industry.ENERGY) return { // transformer (fins + bushings) + pylon
            labels: ["Load", "Winding", "Oil temp"], alert: 0,
            icon: `<g class="tut-svg-glyph" stroke-width="1.6"><rect x="122" y="16" width="30" height="28" rx="2"/>`
                + `<line x1="129" y1="16" x2="129" y2="44"/><line x1="137" y1="16" x2="137" y2="44"/><line x1="145" y1="16" x2="145" y2="44"/>`
                + `<path d="M130 16 L130 10 M144 16 L144 10"/><path d="M180 46 L189 10 L198 46 M182 35 L196 35 M184 25 L194 25 M186 16 L192 16"/>`
                + `<path d="M152 22 L180 18 M152 30 L180 26 M152 38 L180 34"/></g>`
                + `<circle class="tut-fill-glyph" cx="130" cy="9" r="1.7"/><circle class="tut-fill-glyph" cx="144" cy="9" r="1.7"/>`
        };
        return { // oil_gas (default) — a pump jack
            labels: ["Pressure", "Flow", "Temp"], alert: 0,
            icon: `<g class="tut-svg-glyph" stroke-width="1.6"><path class="tut-svg-axis" d="M132 44 L208 44"/>`
                + `<path d="M150 44 L159 24 L175 24 L184 44"/><path d="M151 20 L204 30"/><path d="M202 26 L214 28 L211 38 L200 35 Z"/>`
                + `<path d="M148 20 L154 20 L154 30 L148 30 Z"/><path d="M208 35 L208 44"/></g>`
                + `<circle class="tut-fill-glyph" cx="167" cy="24" r="2.1"/>`
        };
    }

    // Narration beside its illustration: copy on the left, figure on the right. The figure helpers
    // return a .tut-figure wrapper, so this just puts the two in a flex row (see .tut-split in
    // tutorial.css); too narrow a bubble wraps it back to stacked, text first.
    function withFigure(text, fig) {
        return `<div class="tut-split"><div class="tut-split-text">${text}</div>${fig}</div>`;
    }

    // The problem beat: the asset up top, its three signals wired DOWN into separate monitors that
    // are siloed from each other (the "?" gaps), so the shared pattern stays invisible.
    function svgProblem() {
        const p = problemScene(getIndustry());
        const sC = i => i === p.alert ? "tut-series-alert" : (i === 0 ? "tut-series-1" : i === 1 ? "tut-series-2" : "tut-series-3");
        const aC = i => i === p.alert ? "tut-area-alert" : (i === 0 ? "tut-area-1" : i === 1 ? "tut-area-2" : "tut-area-3");
        const fC = i => i === p.alert ? "tut-fill-alert" : (i === 0 ? "tut-fill-1" : i === 1 ? "tut-fill-2" : "tut-fill-3");
        const wave = i => i === p.alert ? WAVE.alert : (i === 0 ? WAVE.a : i === 1 ? WAVE.b : WAVE.c);
        const xs = [8, 112, 216];
        const wires = xs.map(x => `<path class="tut-svg-axis" opacity="0.4" d="M160 46 Q${x + 48} 50 ${x + 48} 58"/>`).join("");
        const cards = xs.map((x, i) => monitor(x, sC(i), aC(i), fC(i), wave(i), p.labels[i])).join("");
        const q = x => `<text class="tut-svg-label" x="${x}" y="98" text-anchor="middle" font-size="12" opacity="0.85">?</text><path class="tut-svg-axis" stroke-dasharray="1.5 3" opacity="0.5" d="M${x - 6} 102 L${x + 6} 102"/>`;
        return `<div class="tut-figure"><svg viewBox="0 0 320 150" width="320" role="img" aria-hidden="true">`
            + p.icon + wires + cards + q(106) + q(210)
            + `</svg></div>`;
    }

    // Welcome hero: scattered, disconnected data sources on the left resolving into one connected
    // knowledge graph on the right (with a discovered accent link) — the DataHub value in one image.
    function svgWelcome() {
        const tile = (x, y, inner) => `<g transform="translate(${x},${y})"><rect class="tut-svg-panel" x="0" y="0" width="34" height="28" rx="5"/>${inner}</g>`;
        const grid = `<g class="tut-svg-glyph" opacity="0.8"><line x1="8" y1="9" x2="26" y2="9"/><line x1="8" y1="15" x2="26" y2="15"/><line x1="8" y1="21" x2="26" y2="21"/><line x1="14" y1="6" x2="14" y2="24"/><line x1="20" y1="6" x2="20" y2="24"/></g>`;
        const gauge = `<g class="tut-svg-glyph" opacity="0.85"><path d="M9 20 A8 8 0 0 1 25 20"/><path d="M17 20 L22 12"/></g><circle class="tut-fill-glyph" cx="17" cy="20" r="1.4"/>`;
        const doc = `<g class="tut-svg-glyph" opacity="0.8"><path d="M11 6 L20 6 L24 10 L24 22 L11 22 Z"/><line x1="14" y1="12" x2="21" y2="12"/><line x1="14" y1="16" x2="21" y2="16"/></g>`;
        const spark = `<path class="tut-series-2" d="M8 20 C12 14 16 21 20 12 C22 9 24 14 26 11" fill="none" stroke-width="1.6" stroke-linecap="round"/>`;
        const n = (x, y) => `<circle class="tut-svg-node" cx="${x}" cy="${y}" r="7"/>`;
        return `<div class="tut-figure"><svg viewBox="0 0 340 130" width="340" role="img" aria-hidden="true">`
            + tile(8, 10, grid) + tile(52, 44, gauge) + tile(20, 74, doc) + tile(60, 96, spark)
            + `<g class="tut-svg-axis" opacity="0.55" fill="none"><path d="M100 24 C140 30 150 55 176 58"/><path d="M92 58 C130 62 150 62 176 66"/><path d="M60 90 C120 92 150 78 178 74"/><path d="M100 108 C140 100 150 90 180 82"/></g>`
            + `<g class="tut-svg-glyph" opacity="0.5"><path d="M150 60 l10 0 m-4 -4 l4 4 l-4 4"/></g>`
            + `<g class="tut-svg-axis" fill="none"><path d="M244 40 L292 54"/><path d="M244 40 L262 92"/><path d="M292 54 L318 88"/><path d="M262 92 L308 100"/></g>`
            + `<path class="tut-svg-glow" stroke-width="6" d="M292 54 L318 88"/><path class="tut-stroke-accent" stroke-width="2.5" d="M292 54 L318 88"/>`
            + n(244, 40) + n(292, 54) + n(262, 92) + n(318, 88) + n(308, 100)
            + `</svg></div>`;
    }

    // Correlation intro: the SAME rise/fall in three SEPARATE stacked mini-charts — apart, you
    // can't see they co-move. Faint vertical guides at the shared peaks hint the alignment.
    function svgCorrelationProblem() {
        const xs = [6, 56, 106, 156, 206, 256, 306];
        const shape = [.35, .7, .45, .9, .5, .72, .6];
        const box = (y, sCls, aCls, fCls, amp, off) => {
            const top = y + 7, h = 26;
            const pts = xs.map((x, i) => [x, +((top + h) - (shape[i] * amp + off) * h).toFixed(1)]);
            const line = smoothPath(pts), area = line + ` L${pts[pts.length - 1][0]} ${top + h} L${pts[0][0]} ${top + h} Z`;
            const last = pts[pts.length - 1];
            return `<rect class="tut-svg-panel" x="6" y="${y}" width="308" height="40" rx="8"/>`
                + `<path class="${aCls}" d="${area}"/><path class="${sCls}" d="${line}" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`
                + `<circle class="${fCls}" cx="${last[0]}" cy="${last[1]}" r="2.6"/>`;
        };
        const guide = i => `<line class="tut-svg-grid" stroke-dasharray="2 3" x1="${xs[i]}" y1="6" x2="${xs[i]}" y2="144" opacity="0.5"/>`;
        return `<div class="tut-figure"><svg viewBox="0 0 320 150" width="320" role="img" aria-hidden="true">`
            + guide(1) + guide(3)
            + box(6, "tut-series-1", "tut-area-1", "tut-fill-1", 0.8, 0.12)
            + box(52, "tut-series-2", "tut-area-2", "tut-fill-2", 0.7, 0.16)
            + box(98, "tut-series-3", "tut-area-3", "tut-fill-3", 0.75, 0.1)
            + `</svg></div>`;
    }

    // Future-analysis teaser: a small knowledge graph with a highlighted accent link and a
    // magnifying glass over it — richer analysis tooling surfacing connections across the network.
    function svgAgentTeaser() {
        const node = (x, y, r = 9) => `<circle class="tut-svg-node" cx="${x}" cy="${y}" r="${r}"/>`;
        return `<div class="tut-figure"><svg viewBox="0 0 320 150" width="320" role="img" aria-hidden="true">`
            + `<g class="tut-svg-axis" fill="none"><path d="M74 52 Q110 30 152 38"/><path d="M74 52 Q92 84 120 104"/><path d="M152 38 Q200 40 236 66"/><path d="M120 104 Q170 118 212 112"/></g>`
            + `<path class="tut-svg-glow" stroke-width="6" d="M236 66 Q228 92 212 112"/>`
            + `<path class="tut-stroke-accent" stroke-width="2.5" d="M236 66 Q228 92 212 112"/>`
            + node(74, 52) + node(152, 38) + node(120, 104) + node(236, 66) + node(212, 112)
            + `<g transform="translate(250,84)"><circle class="tut-fill-accent" r="15" opacity="0.14"/>`
            + `<circle class="tut-stroke-accent" cx="0" cy="0" r="8" stroke-width="2.5"/>`
            + `<path class="tut-stroke-accent" stroke-width="2.5" stroke-linecap="round" d="M5.7 5.7 L12 12"/></g>`
            + `</svg></div>`;
    }

    // Produces the step array for a specific scenario.
    // Steps intentionally use selectors from existing right-form markup.
    function stepsForScenario(s) {
        // The active industry (for scenario-keyed copy that isn't pre-resolved into `s`).
        const industry = getIndustry();
        // The root dataset list rows, keyed by the stable data-id on each <li>.
        const rootListRows = () => Array.from(
            document.querySelectorAll('ul[data-type="root-dataset-list"] li[data-id]')
        );
        const datasetRowIds = () => new Set(rootListRows().map(li => li.getAttribute("data-id")));
        // Rows present immediately before the create-dataset save is submitted,
        // used to detect a genuinely new row rather than a same-named leftover.
        let datasetIdsBeforeSave = null;
        // Form values captured at save so Back can refill the reopened form after
        // deleting the dataset it created (text fields only; pickers are re-picked).
        let datasetSnapshot = null;
        const setFieldValue = (sel, val) => {
            const el = document.querySelector(sel);
            if (el) { el.value = val || ""; el.dispatchEvent(new Event("input", { bubbles: true })); }
        };

        // Per-run unique external id. The tour no longer wipes previous runs, so this run's
        // created assets get a "_<runToken>" suffix (stamped at tour start) to avoid colliding
        // with networks the user chose to keep. Applied in each save step's onEnter, which runs
        // before the save guard reads the externalId field — so the saved id and the
        // remembered id match.
        const runSuffix = () => {
            const t = window.DataHubTutorialContext?.recall("runToken");
            return t ? "_" + t : "";
        };
        const applyUniqueExternalId = () => {
            const f = document.querySelector('.right-form form input[name="externalId"]');
            const suf = runSuffix();
            if (f && f.value && suf && !f.value.endsWith(suf)) f.value = f.value + suf;
        };

        // --- Resources chapter helpers (resources page + graph) ---
        const RESOURCES_PAGE = "/resources/index";
        const rootAssetRows = () => Array.from(
            document.querySelectorAll('[data-type="root-asset-list"] li[data-root-id]')
        );
        const rootAssetIds = () => new Set(rootAssetRows().map(li => li.getAttribute("data-root-id")));
        let rootAssetIdsBefore = null;
        // Form values + created id captured at the resource save, so Back can delete the
        // created resource and refill the reopened create form (label is re-picked).
        let resourceSnapshot = null;
        let createdResourceId = null;
        // The root-resource row created during this run (not a pre-existing one).
        const freshRootAssetRow = () => {
            const before = rootAssetIdsBefore || new Set();
            return rootAssetRows().find(li => !before.has(li.getAttribute("data-root-id")))
                || rootAssetRows()[0] || null;
        };
        // The external id the user actually created. The resource form auto-derives the
        // external id from the name, so it can differ from the scenario suggestion —
        // spotlight the real node, falling back to the suggestion if none was remembered.
        const targetResourceExternalId = () =>
            window.DataHubTutorialContext?.recall("lastResourceExternalId") || s.resourceExternalId;

        // --- Timeseries chapter helpers (timeseries page) ---
        const TIMESERIES_PAGE = "/timeseries/index";
        const INSIGHTS_PAGE = "/timeseries/insights";
        const EVENTS_PAGE = "/events/index";
        const tsRows = () => Array.from(document.querySelectorAll('[data-type="timeseries-list"] li'));
        const tsRowIds = () => new Set(tsRows().map(li => li.getAttribute("data-root-id")));
        // The timeseries row created this run — spotlighted for the "see your data" chart step.
        // Falls back to the id persisted in context: after a cross-page Back the page reloads
        // and re-runs this factory, resetting the createdTimeseriesId closure to null, so the
        // chart step could no longer find its row. The remembered id survives that reload.
        const freshTimeseriesRow = () => {
            const id = createdTimeseriesId || window.DataHubTutorialContext?.recall("tutorialTimeseriesId");
            return (id && document.querySelector(`[data-type="timeseries-list"] li[data-root-id="${id}"]`)) || null;
        };
        let tsIdsBefore = null;
        // Snapshot + created id so Back can delete the timeseries and refill its form.
        let timeseriesSnapshot = null;
        let createdTimeseriesId = null;
        // A picker opens as a second stacked <section> in the right-form, so the
        // "open the picker" steps advance when the section count grows.
        const rfSectionCount = () => document.querySelectorAll('.right-form > section').length;
        let rfSectionsBefore = null;
        // The resource the user built the network around, remembered when selected, so the
        // final step can re-select it on the resources tab and show the finished diagram.
        const rememberedResourceRow = () => {
            const id = window.DataHubTutorialContext?.recall("lastResourceRootId");
            return (id && document.querySelector(`[data-type="root-asset-list"] li[data-root-id="${id}"]`)) || null;
        };
        // Run a graph highlight as soon as it can take. A just-created node/edge may still
        // be settling into the canvas when the step shows, so retry briefly (best effort).
        const highlightWhenReady = (fn) => {
            let tries = 0;
            const tick = () => {
                const g = window.resourceNetwork;
                if (g && fn(g)) return;
                if (++tries < 20) setTimeout(tick, 150);
            };
            tick();
        };

        // --- Buildout chapter: auto-build a small network, then highlight + explain it ---
        // Building 5-10 throwaway nodes by hand teaches nothing past the first one and is
        // tedious, so the tour builds a believable cluster for the user and explains it.
        // Promise-based (no async, per reviewer rule). buildoutDone flips when the cluster
        // is created, shown in the graph, and highlighted — the build step's appear guard
        // advances on it.
        let buildoutDone = false;
        const RESOURCE_API = "/api/resources/save";
        const csrfHeaders = () => {
            const h = document.querySelector('meta[name="_csrf_header"]')?.content;
            const t = document.querySelector('meta[name="_csrf"]')?.content;
            const base = { "Accept": "application/json", "Content-Type": "application/json" };
            if (h) base[h] = t;
            return base;
        };
        // The cluster: a small asset network. Label names must exist in the tenant; we
        // resolve them from /api/label/list and fall back to the first available label
        // (resources require >=1 label).
        // Root + 9 children = a 10-node starter network (user can add more). Reuses the
        // existing label set (PUMP/VALVE/TANK/SENSOR/MOTOR) so every create has a valid label.
        // `parent` = index into this list (null = the root) so the network BRANCHES into a
        // tree instead of every node hanging off the root. A parent must appear before its
        // children here (they're created in order).
        const BUILDOUT_PLAN = [
            { name: "Pump P-1", label: "PUMP", parent: null },
            { name: "Valve V-1", label: "VALVE", parent: null },
            { name: "Tank T-1", label: "TANK", parent: null },
            { name: "Vibration sensor S-1", label: "SENSOR", parent: 0 },
            { name: "Motor M-1", label: "MOTOR", parent: 0 },
            { name: "Pump P-2", label: "PUMP", parent: null },
            { name: "Valve V-2", label: "VALVE", parent: 5 },
            { name: "Flow sensor S-2", label: "SENSOR", parent: 2 },
            { name: "Tank T-2", label: "TANK", parent: 5 },
        ];
        const autoBuildNetwork = () => {
            const headers = csrfHeaders();
            if (!headers["X-CSRF-TOKEN"] && !Object.keys(headers).some(k => k.toLowerCase().includes("csrf"))) {
                // No CSRF meta (unexpected) — don't hang the tour.
                buildoutDone = true; return;
            }
            const post = (url, body) => fetch(url, { method: "POST", headers, body: JSON.stringify(body) })
                .then(r => (r.ok ? r.json() : null)).catch(() => null);
            const ts = Date.now();
            const createdIds = [];

            // Fetch labels + relationship types, then build. (No prior-cluster cleanup —
            // the tour no longer auto-deletes; the user keeps or removes at the end.)
            Promise.all([
                fetch("/api/label/list", { headers: { Accept: "application/json" } }).then(r => r.json()).catch(() => ({})),
                fetch("/api/relationship/list", { headers: { Accept: "application/json" } }).then(r => r.json()).catch(() => ({})),
            ]).then(([labelsResp, relsResp]) => {
                const labels = labelsResp.items || labelsResp || [];
                const rels = relsResp.items || relsResp || [];
                const labelNames = new Set(labels.map(l => l.name));
                const fallbackLabel = labels[0] && labels[0].name;
                const relType = rels.find(r => r.name === "CONTAINS") || rels[0] || {};
                const relId = relType.id != null ? String(relType.id) : null;
                const pick = (name) => (labelNames.has(name) ? name : fallbackLabel);
                if (!fallbackLabel || !relId) { buildoutDone = true; return; }

                // Build ON the resource the user already created this run, if any, so the
                // diagram extends their own network. Skipped straight to the diagram? Create a
                // fresh root named "Tutorial resource N" (N = next after existing ones).
                const ctx = window.DataHubTutorialContext;
                const existingRootId = ctx?.recall("lastResourceRootId");
                const existingRootExt = ctx?.recall("lastResourceExternalId");
                const resolveRoot = existingRootId
                    ? Promise.resolve({ rootId: existingRootId, rootExt: existingRootExt, created: false })
                    : post("/api/resources/search", { query: "Tutorial resource" }).then(s => {
                          const n = ((s && s.items) ? s.items.length : 0) + 1;
                          const rootExt = `tutorial_resource_${ts}`;
                          return post(RESOURCE_API, {
                              name: `Tutorial resource ${n}`, externalId: rootExt, isRoot: "true",
                              labels: [pick("STATION")], metadata: {},
                          }).then(root => (root && root.id != null) ? { rootId: root.id, rootExt, created: true } : null);
                      });

                return resolveRoot.then(root => {
                    if (!root || root.rootId == null) { buildoutDone = true; return; }
                    // Track only what THIS run created for cleanup; a reused user resource is
                    // tracked separately (lastResourceRootId). highlightExt always includes the
                    // root so the trace beat can glow root + a couple of children.
                    if (root.created) createdIds.push(root.rootId);
                    const highlightExt = [root.rootExt].filter(Boolean);
                    const childIds = [];
                    return BUILDOUT_PLAN.reduce((chain, p, i) => chain.then(() => {
                        const ext = `tour_${p.label.toLowerCase()}_${ts}_${i}`;
                        const parentId = (p.parent == null) ? root.rootId : (childIds[p.parent] || root.rootId);
                        return post(RESOURCE_API, {
                            name: p.name, externalId: ext, isRoot: "false",
                            labels: [pick(p.label)], metadata: {},
                            relationFrom: parentId, relationTypes: [relId],
                        }).then(child => { if (child && child.id != null) { createdIds.push(child.id); highlightExt.push(ext); childIds[i] = child.id; } });
                    }), Promise.resolve()).then(() => ({ rootId: root.rootId, rootExt: root.rootExt, ids: createdIds, ext: highlightExt }));
                });
            }).then(result => {
                if (!result) { buildoutDone = true; return; }
                // Track created resources so the next tour's sandbox reset removes them
                // (they aren't in the dataset, so the dataset wipe alone wouldn't catch them).
                window.DataHubTutorialContext?.remember("buildoutResourceIds", result.ids);
                window.DataHubTutorialContext?.remember("buildoutExternalIds", result.ext);
                // Show the cluster in the graph, then highlight the whole network. When the
                // chapter runs standalone no root is selected, so the page hasn't created the
                // graph yet — instantiate it the same way the resources page does.
                let graph = window.resourceNetwork;
                if (!graph && typeof GraphNetwork !== "undefined") {
                    const container = document.querySelector('[data-type="resource-network"]');
                    if (container) { graph = new GraphNetwork({ container }); window.resourceNetwork = graph; }
                }
                if (graph && typeof graph.loadNetwork === "function") {
                    graph.loadNetwork(result.rootId, true);
                    graph.waitForNode(result.rootExt).then(() => { graph.highlightAll(); buildoutDone = true; });
                } else {
                    buildoutDone = true;
                }
            }).catch(() => { buildoutDone = true; });
        };

        // --- Events chapter: seed a couple of events onto the resource the user built ---
        // Events are discrete happenings (an alarm, a maintenance job) attached to resources via
        // relatedResources. Seed two onto the tour's resource, straight to datahub-api's
        // POST /events/create with a fresh /token bearer — the same browser-direct pattern as the
        // timeseries seed. Best-effort + fire-and-forget: events flow through Pulsar -> consumer ->
        // ClickHouse, so they may still be settling when the user reaches the log (the events page
        // auto-searches on load, so they surface as they land). The externalIds are remembered so
        // the sandbox reset can delete them (events don't cascade from the resource delete).
        const seedEvents = () => {
            const ctx = window.DataHubTutorialContext;
            const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]')?.content;
            if (!apiBaseUrl) return;
            const doSeed = (resExt) => {
                if (!resExt) return;
                const suffix = (ctx?.recall("runToken")) ? "_" + ctx.recall("runToken") : "_" + Date.now().toString(36);
                const now = Date.now();
                // Narrate what the user just did, so the log reads back the tour's own actions on
                // their resource: the asset joined the network, its measurement came online, and a
                // reading tripped a threshold (the spike from the problem beat).
                const items = [
                    {
                        externalId: "tutorial_commissioned" + suffix,
                        eventTime: now - 90 * 60000,
                        type: "asset",
                        subType: "commissioned",
                        description: `${s.resourceName} added to the network`,
                        relatedResources: [{ externalId: resExt }],
                        metadata: { source: "tutorial" },
                    },
                    {
                        externalId: "tutorial_measurement" + suffix,
                        eventTime: now - 40 * 60000,
                        type: "asset",
                        subType: "measurement",
                        description: `Measurement "${s.tsName}" started reporting on ${s.resourceName}`,
                        relatedResources: [{ externalId: resExt }],
                        metadata: { source: "tutorial" },
                    },
                    {
                        externalId: "tutorial_alarm" + suffix,
                        eventTime: now - 12 * 60000,
                        type: "alarm",
                        subType: "threshold",
                        description: `${s.tsName} crossed its warning threshold on ${s.resourceName}`,
                        relatedResources: [{ externalId: resExt }],
                        metadata: { severity: "high", source: "tutorial" },
                    },
                ];
                ctx?.remember?.("tutorialEventExternalIds", items.map(it => it.externalId));
                fetch("/token", { headers: { Accept: "text/plain" }, credentials: "same-origin" })
                    .then(r => (r.ok ? r.text() : null))
                    .then(token => {
                        if (!token) return;
                        return fetch(apiBaseUrl + "/events/create", {
                            method: "POST",
                            headers: {
                                "Authorization": "Bearer " + token,
                                "Content-Type": "application/json",
                                "Accept": "application/json"
                            },
                            body: JSON.stringify({ items })
                        });
                    })
                    .catch(() => { /* best effort */ });
            };
            // Attach to the resource the tour built (full run), or the tenant's first resource
            // (standalone chapter — EnsureResource has seeded one) so the chapter works either way.
            const known = ctx?.recall("lastResourceExternalId");
            if (known) { doSeed(known); return; }
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            const csrf = document.querySelector('meta[name="_csrf"]')?.content;
            fetch("/api/resources/search", {
                method: "POST",
                headers: { Accept: "application/json", "Content-Type": "application/json", ...(header ? { [header]: csrf } : {}) },
                body: JSON.stringify({ query: "" })
            })
                .then(r => (r.ok ? r.json() : null))
                .then(j => { const items = j && (j.items || (Array.isArray(j) ? j : [])); doSeed(items && items[0] && items[0].externalId); })
                .catch(() => { /* best effort */ });
        };

        // --- Correlation chapter: two sibling series that co-move with the user's series ---
        // Create them, seed all three off one shared industry rhythm, and arm the Insights overlay.
        // Best-effort: create/seed can lag or fail; the payoff copy holds and the overlay
        // degrades to whatever series exist (at least the user's own). Reuses csrfHeaders() above.
        //
        // A shared, industry-flavoured hourly rhythm (unitless). All three series derive from it so
        // they visibly move together; each then lags/leads it and adds a small distinct wiggle, so
        // the chart (which auto-scales every series to its own y-axis) shows three separate-but-
        // related sensors — staggered peaks — rather than one line drawn three times.
        const scenarioRhythm = (t) => {
            const h = ((t % 24) + 24) % 24, drift = Math.sin(t / 17);
            if (industry === Industry.TECH) {                 // daytime compute load
                const work = 1 / (1 + Math.exp(-(h - 8) * 1.2)) - 1 / (1 + Math.exp(-(h - 18) * 1.2));
                return 0.9 * work + 0.25 * drift;
            }
            if (industry === Industry.MANUFACTURING) {        // two production shifts, quiet overnight
                const shift = (h >= 6 && h < 22) ? 1 : 0.3;
                return 0.8 * shift + 0.18 * Math.sin(2 * Math.PI * h / 8) + 0.2 * drift;
            }
            if (industry === Industry.ENERGY) {               // morning + evening demand peaks
                const g = (c, w) => Math.exp(-Math.pow((h - c) / w, 2));
                return 0.6 * g(8, 2.4) + g(19, 3) + 0.2 * drift;
            }
            return 0.6 * Math.sin(2 * Math.PI * h / 24 - 1) + 0.28 * Math.sin(t / 2) + 0.25 * drift; // oil_gas
        };
        // series: "a" = the user's measurement (phase 0), "b"/"c" = the siblings (lag/lead).
        const corrValue = (i, series) => {
            if (series === "b") return 55 + 20 * scenarioRhythm(i - 3) + 3 * Math.sin(i / 3.3);
            if (series === "c") return 6 + 12 * scenarioRhythm(i + 2) + 2 * Math.cos(i / 5);
            return 22 + 16 * scenarioRhythm(i) + 2 * Math.sin(i / 2.1);
        };
        const corrPoints = (now, hr, hours, series) => {
            const dps = [];
            for (let i = hours; i >= 0; i--) dps.push({ timestamp: String(now - i * hr), value: corrValue(i, series).toFixed(2) });
            return dps;
        };
        const seedCorrelatedSeries = () => {
            const ctx = window.DataHubTutorialContext;
            const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]')?.content;
            if (!apiBaseUrl) return;
            const dsId = ctx?.getSelectedDatasetId?.();
            const now = Date.now(), hr = 3600000, hours = 48;
            const runToken = ctx?.recall("runToken") || "tut";
            // Arm the Insights overlay (consumed on that page's load) with ONLY the user's own
            // series — the reveal step later adds the two siblings, so the correlation lands as a
            // discovery ("watch what happens when we overlay the others") rather than a fait accompli.
            const armOverlay = (exts) => {
                try {
                    sessionStorage.setItem("dh-pending-view", JSON.stringify({
                        page: "insights",
                        filter: { externalIds: exts, start: new Date(now - hours * hr).toISOString(), end: new Date(now).toISOString() }
                    }));
                    ctx?.remember?.("correlationOverlayExternalIds", exts);
                } catch (e) { /* private mode */ }
            };
            // Seed once per run. On re-entry (stepping back to this beat) just re-arm the overlay so
            // a fresh Insights load still opens on A — don't re-create the series or re-POST
            // datapoints, which would duplicate points and thicken the lines.
            if (ctx?.recall("correlationSeeded")) {
                const base = ctx?.recall("correlationOverlayExternalIds");
                if (base && base.length) armOverlay(base);
                return;
            }
            const bearer = () => fetch("/token", { headers: { Accept: "text/plain" }, credentials: "same-origin" }).then(r => (r.ok ? r.text() : null));
            // A real unit keeps /timeseries/save valid; reuse one unit for all three.
            fetch("/api/units", { headers: { Accept: "application/json" } })
                .then(r => (r.ok ? r.json() : null)).catch(() => null)
                .then(j => {
                    const units = (j && (j.items || (Array.isArray(j) ? j : []))) || [];
                    const u = units[0] || {};
                    const unit = u.name || u.unit || "unit";
                    const unitExternalId = u.externalId || null;
                    const create = (externalId, name) => {
                        const body = { name, externalId, unit, valueType: "float" };
                        if (unitExternalId) body.unitExternalId = unitExternalId;
                        if (dsId) body.dataSetId = dsId;
                        return fetch("/api/timeseries/save", { method: "POST", headers: csrfHeaders(), body: JSON.stringify(body) })
                            .then(r => (r.ok ? r.json() : null)).catch(() => null)
                            .then(res => { const item = res && res.items && res.items[0]; return item ? { externalId, id: item.id } : null; });
                    };
                    // Base series A: the user's built series, or create one so this chapter also
                    // works when jumped to standalone (a.seed = whether A still needs seeding).
                    const existingA = ctx?.recall("lastTimeseriesExternalId");
                    const ensureA = existingA
                        ? Promise.resolve({ externalId: existingA, seed: false })
                        : create(`${runToken}_corr_a`, $L(scenarioKey(industry, "tsName")) || "Measurement")
                            .then(r => {
                                if (r) { ctx?.remember?.("lastTimeseriesExternalId", r.externalId); if (r.id) ctx?.remember?.("tutorialTimeseriesId", r.id); }
                                return r ? { externalId: r.externalId, seed: true } : null;
                            });
                    return ensureA.then(a => {
                        if (!a) return;
                        armOverlay([a.externalId]);
                        // Deterministic sibling ids + reveal set, remembered up front so a partial
                        // create failure (e.g. a 409 on re-run) can't shrink what the reconciler
                        // reveals. Seed both regardless — a datapoints POST for a series that failed
                        // to create is simply dropped by the api (best effort).
                        const corr2Ext = `${a.externalId}_corr2`, corr3Ext = `${a.externalId}_corr3`;
                        ctx?.remember?.("correlationRevealExternalIds", [corr2Ext, corr3Ext]);
                        ctx?.remember?.("correlationSeeded", true);
                        return Promise.all([
                            create(corr2Ext, $L(scenarioKey(industry, "corr2Name")) || "corr2"),
                            create(corr3Ext, $L(scenarioKey(industry, "corr3Name")) || "corr3")
                        ]).then(sibs => {
                            ctx?.remember?.("correlationTimeseriesIds", sibs.filter(Boolean).map(x => x.id).filter(Boolean));
                            const items = [
                                { externalId: corr2Ext, datapoints: corrPoints(now, hr, hours, "b") },
                                { externalId: corr3Ext, datapoints: corrPoints(now, hr, hours, "c") }
                            ];
                            if (a.seed) items.push({ externalId: a.externalId, datapoints: corrPoints(now, hr, hours, "a") });
                            return bearer().then(token => {
                                if (!token) return;
                                return fetch(apiBaseUrl + "/timeseries/data", {
                                    method: "POST",
                                    headers: { "Authorization": "Bearer " + token, "Content-Type": "application/json", "Accept": "application/json" },
                                    body: JSON.stringify({ items })
                                });
                            });
                        });
                    });
                })
                .catch(() => { /* best effort */ });
        };

        // --- Deterministic correlation overlay ---------------------------------------------
        // The payoff overlays the user's series (A) plus two siblings (B, C) on the Insights
        // chart. A row's `.selected` class is set synchronously, but the LINE is drawn by an
        // async fetch that only paints if datapoints have propagated — so `.selected` is a poor
        // proxy for "line on screen": a lagged series reads selected yet draws nothing. The old
        // code trusted `.selected` and ran two uncancelled retry loops, so moving back and forth
        // left overlapping timers toggling the same rows and the chart flickered between 1 and 2
        // lines. This reconciler instead drives the chart to an EXACT set, checks real
        // data-presence via InsightsChart.getColor, and keeps a single cancellable timer.
        let corrReconcileTimer = null;
        const cancelCorrReconcile = () => { if (corrReconcileTimer) { clearTimeout(corrReconcileTimer); corrReconcileTimer = null; } };
        const corrRow = (ext) => document.querySelector(`[data-type="timeseries-list"] li[data-external-id="${ext}"]`);
        // A just-created sibling may be absent from the server-rendered list (it can be long); mint
        // a row so it's selectable, mirroring the Insights pending-view's own fallback.
        const corrEnsureRow = (ext) => {
            let li = corrRow(ext);
            if (li) return li;
            const ul = document.querySelector('[data-type="timeseries-list"]');
            if (!ul) return null;
            li = document.createElement("li");
            li.setAttribute("data-external-id", ext);
            li.textContent = ext;
            li.addEventListener("pointerdown", () => { if (typeof window.leftMenuTsClickEvent === "function") window.leftMenuTsClickEvent(li); });
            ul.appendChild(li);
            return li;
        };
        const corrDrawn = (ext) => { const c = window.InsightsChart; try { return !!(c && c.getColor && c.getColor(ext)); } catch (e) { return false; } };
        // One clean toggle: prefer the page's own handler (exposed on window) over synthesizing
        // DOM events, which double-fire on rows that listen to both pointerdown and click.
        const corrToggle = (li) => {
            if (typeof window.leftMenuTsClickEvent === "function") window.leftMenuTsClickEvent(li);
            else { li.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true })); li.click(); }
        };
        // Reconcile the chart to show EXACTLY `wanted` out of the correlation set `all`. Idempotent
        // once every wanted series is drawn (no flicker); retries through lag; self-terminates when
        // the Insights chart leaves the DOM (navigating away) so no timer outlives the page.
        const reconcileCorrelation = (wanted, all) => {
            cancelCorrReconcile();
            let tries = 0;
            const pass = () => {
                corrReconcileTimer = null;
                if (!document.getElementById("datapoints-chart")) return;   // left Insights — stop
                // 1. Turn OFF any correlation series that shouldn't be shown right now.
                (all || []).forEach(ext => {
                    if (wanted.indexOf(ext) !== -1) return;
                    const li = corrRow(ext);
                    if (li && li.classList.contains("selected")) corrToggle(li);   // deselect → removeDataSet
                });
                // 2. Ensure each wanted series is actually DRAWN (not merely row-selected).
                let allDrawn = true;
                wanted.forEach(ext => {
                    if (corrDrawn(ext)) return;
                    allDrawn = false;
                    const li = corrEnsureRow(ext);
                    if (!li) return;                                            // list not in the DOM yet
                    if (!li.classList.contains("selected")) {
                        corrToggle(li);                                        // not selected → select → fetch
                    } else if (tries >= 1) {
                        // Still selected-but-empty after a grace pass: the fetch came back with no
                        // data (propagation lag). Clear the stale selection and reselect to refetch.
                        corrToggle(li);
                        corrToggle(li);
                    }
                    // tries === 0 && selected: an in-flight fetch may still paint it — leave it be.
                });
                if (!allDrawn && ++tries < 12) corrReconcileTimer = setTimeout(pass, 1200);
            };
            pass();
        };
        const correlationSets = () => {
            const ctx = window.DataHubTutorialContext;
            const base = (ctx && ctx.recall && ctx.recall("correlationOverlayExternalIds")) || [];
            const sibs = (ctx && ctx.recall && ctx.recall("correlationRevealExternalIds")) || [];
            return { base: base.slice(), all: base.concat(sibs) };
        };
        // Solo beat: exactly the user's series. Reveal beat: the user's plus the two siblings.
        const showSoloSeries = () => { const { base, all } = correlationSets(); reconcileCorrelation(base, all); };
        const revealCorrelatedSeries = () => { const { all } = correlationSets(); reconcileCorrelation(all, all); };

        const steps = [
            {
                // Value proposition + orientation before any clicking. Centered narration.
                placement: "center",
                spotlight: false,
                interactive: false,
                title: $L("tutorial.intro.welcome.title"),
                maxWidth: SPLIT_WIDTH,
                content: withFigure($L("tutorial.intro.welcome.content"), svgWelcome())
            },
            {
                // The problem, made concrete + industry-specific, with an illustration: the
                // signals exist but live apart, so the pattern (and the fix) is invisible.
                // Untagged like welcome, so it shares the intro progress segment.
                placement: "center",
                spotlight: false,
                interactive: false,
                title: $L("tutorial.problem.title"),
                maxWidth: SPLIT_WIDTH,
                content: withFigure($L(scenarioKey(industry, "problemContent")), svgProblem())
            },
            {
                // Why datasets exist, before the first click — every chapter opens on the "why".
                // Centered narration (no target); the create beat below does the anchored action.
                chapter: "datasets",
                placement: "center",
                spotlight: false,
                interactive: false,
                title: $L("tutorial.datasets.intro.title"),
                content: $L("tutorial.datasets.intro.content")
            },
            {
                target: '[data-type="create-dataset-btn"]',
                entersRightForm: true,
                title: $L("tutorial.datasets.create.title"),
                // Keep the "+" reference since it's UI, not text. No unicode arrows used.
                content: `${s.intro}<br><br>${$L("tutorial.datasets.create.content")}`,
                position: "auto",
                guard: { type: "click", selector: '[data-type="create-dataset-btn"]' }
            },

            {
                target: '.right-form form input[name="name"]',
                inRightForm: true,
                fallbackOpen: '[data-type="create-dataset-btn"]',
                timeout: 12000,
                title: $L("tutorial.datasets.name.title"),
                content: fmt1($L("tutorial.datasets.name.content"), s.name),
                position: "auto",
                guard: {
                    type: "inputMin",
                    selector: ".right-form form input[name='name']",
                    min: 3,
                    mode: "enableNext"
                }
            },
            {
                target: '.right-form form input[name="externalId"]',
                inRightForm: true,
                fallbackOpen: '[data-type="create-dataset-btn"]',
                timeout: 12000,
                title: $L("tutorial.datasets.externalId.title"),
                content: fmt1($L("tutorial.datasets.externalId.content"), s.externalId),
                position: "auto",
                guard: { type: "inputFilled", selector: '.right-form form input[name="externalId"]' }
            },
            {
                target: '.right-form form textarea[name="description"]',
                inRightForm: true,
                fallbackOpen: '[data-type="create-dataset-btn"]',
                timeout: 12000,
                title: $L("tutorial.datasets.description.title"),
                // The key can be expanded later to include {0} if you want the scenario description inline.
                content: `${$L("tutorial.datasets.description.content")}<br><i>${s.description}</i>`,
                position: "auto",
                guard: { type: "inputFilled", selector: '.right-form form textarea[name="description"]' }
            },
            {
                target: '.right-form form footer button[type="submit"]',
                inRightForm: true,
                fallbackOpen: '[data-type="create-dataset-btn"]',
                timeout: 12000,
                // Allow the whole form so the user can correct a rejected save
                // (e.g. duplicate external id) and retry without leaving the step.
                allow: '.right-form',
                onEnter: applyUniqueExternalId,
                title: $L("tutorial.datasets.save.title"),
                content: $L("tutorial.datasets.save.content"),
                position: "auto",
                guard: {
                    type: "click",
                    selector: '.right-form form footer button[type="submit"]',
                    eventType: "pointerdown",
                    hideNext: true,

                    // Captures dataset name before submitting so later steps can find it.
                    rememberKey: "lastDatasetName",
                    rememberSelector: ".right-form form input[name='name']",

                    // Snapshot the rows that already exist before saving, so the
                    // success check requires a genuinely NEW row instead of matching
                    // a same-named dataset left over from an earlier run (which would
                    // glitch the tour past a save the server actually rejected).
                    onAction: () => {
                        datasetIdsBeforeSave = datasetRowIds();
                        // Remember the external id so the post-select step can spotlight
                        // the dataset's graph node by id (mirrors the resource flow).
                        const ext = document.querySelector(".right-form form input[name='externalId']");
                        const v = (ext && ext.value) ? ext.value.trim() : "";
                        if (v) window.DataHubTutorialContext?.remember("lastDatasetExternalId", v);
                        // Snapshot the form so Back can refill it after deleting the dataset.
                        datasetSnapshot = {
                            name: document.querySelector(".right-form form input[name='name']")?.value || "",
                            externalId: v,
                            description: document.querySelector(".right-form form textarea[name='description']")?.value || "",
                        };
                    },

                    // Success: a row appears whose data-id wasn't present before the
                    // save (i.e. this save created it). A full list refresh keeps
                    // existing datasets' stable data-ids, so only the new one matches.
                    afterWaitFor: () => {
                        const before = datasetIdsBeforeSave || new Set();
                        const li = rootListRows().find(li => !before.has(li.getAttribute("data-id"))) || null;
                        // Record the freshly created dataset as the tour's sandbox so a
                        // later run can wipe it (and resume can re-select it), even if the
                        // user quits before the explicit "select" step captures it.
                        if (li) window.DataHubTutorialContext?.setSelectedDatasetId(li.getAttribute("data-id"));
                        return li;
                    },
                    // Failure: the form re-renders a validation error (e.g. duplicate
                    // external id). Ends the wait at once so the user can correct and
                    // retry instead of staring at a frozen tooltip until the timeout.
                    afterFailFor: ".right-form .flash.error",
                    afterWaitTimeout: 8000,

                    // Closes right-form after save so next steps can interact with list/graph.
                    closeRightFormAfter: true
                },
                // Back-undo: delete the dataset this step created and refill the reopened
                // form with what was entered, so Back consistently undoes the creation.
                // Self-guards: if nothing was created (no selected dataset) it is a no-op.
                onBack: () => {
                    const ctx = window.DataHubTutorialContext;
                    const id = ctx?.getSelectedDatasetId();
                    if (!id) return Promise.resolve();
                    ctx.clearSelectedDatasetId();
                    // Apply the visible undo immediately so Back feels responsive — the
                    // app doesn't refresh the list on delete, so remove the row ourselves
                    // and refill the create form (reopened by the save step's fallbackOpen).
                    const row = document.querySelector(`ul[data-type="root-dataset-list"] li[data-id="${id}"]`);
                    if (row) row.remove();
                    const snap = datasetSnapshot || {};
                    setFieldValue(".right-form form input[name='name']", snap.name);
                    setFieldValue(".right-form form input[name='externalId']", snap.externalId);
                    setFieldValue(".right-form form textarea[name='description']", snap.description);
                    // Delete on the backend in the background (best-effort).
                    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
                    const token = document.querySelector('meta[name="_csrf"]')?.content;
                    return fetch(`/api/datasets/delete/${encodeURIComponent(id)}`, {
                        method: "DELETE",
                        headers: header ? { [header]: token } : {},
                    }).catch(() => { /* best effort */ });
                }
            },

            {
                target: () => {
                    const name = window.DataHubTutorialContext?.recall("lastDatasetName");
                    if (!name) return document.querySelector('ul[data-type="root-dataset-list"] li[data-id]');
                    const spans = Array.from(document.querySelectorAll('ul[data-type="root-dataset-list"] li[data-id] span'));
                    const span = spans.find(x => (x.textContent || "").trim() === name);
                    return span ? span.closest("li[data-id]") : null;
                },
                title: $L("tutorial.datasets.select.title"),
                content: $L("tutorial.datasets.select.content"),
                position: "auto",
                guard: {
                    type: "click",
                    // Advance on click so the row's loadGraphNetwork runs before the
                    // next step tries to spotlight the dataset's node (see the resource
                    // select step for the same pointerdown-vs-click reasoning).
                    eventType: "click",
                    selector: () => {
                        const name = window.DataHubTutorialContext?.recall("lastDatasetName");
                        if (!name) return document.querySelector('ul[data-type="root-dataset-list"] li[data-id]');
                        const spans = Array.from(
                            document.querySelectorAll('ul[data-type="root-dataset-list"] li[data-id] span')
                        );
                        const span = spans.find(x => (x.textContent || "").trim() === name);
                        return span ? span.closest("li[data-id]") : null;
                    }
                }
            },
            {
                // Selecting the dataset loads it into the graph as the root node.
                // Spotlight it so the user recognises their new dataset as the start
                // of the network before they head to resources. waitForNode + the
                // cy.resize() in focusNodeIntoView handle the load/propagation delay.
                graphNode: () => window.DataHubTutorialContext?.recall("lastDatasetExternalId"),
                interactive: false,
                title: $L("tutorial.datasets.node.title"),
                content: $L("tutorial.datasets.node.content")
            },

            // === Resources chapter: build a small network ===
            // The datasets graph starts empty, so the network is built on the resources
            // page where resources render as nodes. The user navigates there by clicking
            // the Resources tab (not an automatic jump); the canvas-based "tap -> add-node"
            // flow is then driven softly via "appear" guards.
            {
                chapter: "resources",
                // Let the user navigate by clicking the Resources tab so they learn where
                // resources live. eventType "click" advances on the same click that
                // follows the link; the next step's page triggers the resume on arrival.
                target: 'nav a[href$="/resources/index"]',
                title: $L("tutorial.resources.nav.title"),
                content: $L("tutorial.resources.nav.content"),
                position: "auto",
                guard: { type: "click", selector: 'nav a[href$="/resources/index"]', eventType: "click" }
            },
            {
                page: RESOURCES_PAGE,
                placement: "center",
                spotlight: false,
                interactive: false,
                title: $L("tutorial.resources.intro.title"),
                content: $L("tutorial.resources.intro.content")
            },
            {
                page: RESOURCES_PAGE,
                target: '[data-type="create-root-node-btn"]',
                entersRightForm: true,
                title: $L("tutorial.resources.create.title"),
                content: $L("tutorial.resources.create.content"),
                position: "auto",
                guard: { type: "click", selector: '[data-type="create-root-node-btn"]' }
            },
            {
                page: RESOURCES_PAGE,
                target: '.right-form form input[name="name"]',
                inRightForm: true,
                fallbackOpen: '[data-type="create-root-node-btn"]',
                timeout: 12000,
                lockSubmit: true,
                title: $L("tutorial.resources.name.title"),
                content: fmt1($L("tutorial.resources.name.content"), s.resourceName),
                position: "auto",
                guard: { type: "inputMin", selector: '.right-form form input[name="name"]', min: 2, mode: "enableNext" }
            },
            {
                page: RESOURCES_PAGE,
                target: '.right-form form input[name="externalId"]',
                inRightForm: true,
                timeout: 12000,
                lockSubmit: true,
                title: $L("tutorial.resources.externalId.title"),
                content: fmt1($L("tutorial.resources.externalId.content"), s.resourceExternalId),
                position: "auto",
                guard: { type: "inputFilled", selector: '.right-form form input[name="externalId"]' }
            },
            {
                // A resource needs at least one label. The "+" opens a "Choose label"
                // picker that mounts as a SECTION inside .right-form. Advance when it opens.
                page: RESOURCES_PAGE,
                target: '.right-form [data-type="label-list-btn"]',
                inRightForm: true,
                allow: '.right-form',
                timeout: 12000,
                lockSubmit: true,
                title: $L("tutorial.resources.label.title"),
                content: $L("tutorial.resources.label.content"),
                // Same right-edge "+" picker pattern — bottom-center keeps the bubble off the form.
                placement: "bottomCenter",
                // The button is clickable via the allowed .right-form, so it doesn't need its
                // own elevation. Without this it gets a stacking context that paints it on top
                // of the picker section that opens, blocking the picker's search content.
                interactive: false,
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => {
                        const rf = document.querySelector('.right-form');
                        const picker = rf && Array.from(rf.children).find(c => c.tagName === "SECTION");
                        return picker || null;
                    }
                }
            },
            {
                // Pick (or create) a label in the picker, then Confirm. The tooltip sits at
                // the bottom so it doesn't cover the picker; advance once a label lands in
                // the resource's labels-container (the picker closes on Confirm).
                page: RESOURCES_PAGE,
                placement: "bottomCenter",
                spotlight: false,
                allow: '.right-form',
                lockSubmit: true,
                title: $L("tutorial.resources.labelPick.title"),
                content: $L("tutorial.resources.labelPick.content"),
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => {
                        const c = document.querySelector('.right-form [data-type="labels-container"]');
                        return (c && c.children.length > 0) ? c : null;
                    }
                }
            },
            {
                // Explain metadata briefly. Optional field, so no guard — just Next.
                page: RESOURCES_PAGE,
                target: '.right-form .metadata-section',
                allow: '.right-form',
                lockSubmit: true,
                title: $L("tutorial.resources.metadata.title"),
                content: $L("tutorial.resources.metadata.content"),
                position: "auto"
            },
            {
                page: RESOURCES_PAGE,
                target: '.right-form form footer button[type="submit"]',
                inRightForm: true,
                // Reopen the create form if Back lands here with it closed (mirrors the
                // dataset save step) so the undo can refill it.
                fallbackOpen: '[data-type="create-root-node-btn"]',
                timeout: 12000,
                // Allow the whole form so a rejected save can be corrected + retried.
                allow: '.right-form',
                onEnter: applyUniqueExternalId,
                title: $L("tutorial.resources.save.title"),
                content: $L("tutorial.resources.save.content"),
                position: "auto",
                guard: {
                    type: "click",
                    selector: '.right-form form footer button[type="submit"]',
                    eventType: "pointerdown",
                    hideNext: true,
                    // Remember the real external id (auto-derived from the name) so the
                    // graph spotlight targets the node that was actually created.
                    rememberKey: "lastResourceExternalId",
                    rememberSelector: ".right-form form input[name='externalId']",
                    // Snapshot existing root resources so we detect the genuinely new one,
                    // and the form values so Back can refill the reopened form.
                    onAction: () => {
                        rootAssetIdsBefore = rootAssetIds();
                        resourceSnapshot = {
                            name: document.querySelector('.right-form form input[name="name"]')?.value || "",
                            externalId: document.querySelector('.right-form form input[name="externalId"]')?.value || "",
                        };
                    },
                    afterWaitFor: () => {
                        const before = rootAssetIdsBefore || new Set();
                        const li = rootAssetRows().find(li => !before.has(li.getAttribute("data-root-id"))) || null;
                        if (li) {
                            createdResourceId = li.getAttribute("data-root-id");
                            // Persist the genuinely-new row's id as the network root, so the
                            // finale re-selects the resource actually created this run (not a
                            // re-derived guess). Survives the navigation to/from Timeseries.
                            window.DataHubTutorialContext?.remember("lastResourceRootId", createdResourceId);
                        }
                        return li;
                    },
                    afterFailFor: ".right-form .flash.error",
                    afterWaitTimeout: 8000,
                    closeRightFormAfter: true
                },
                // Back-undo: delete the resource this step created and refill the reopened
                // create form (the label is re-picked). No-op if nothing was created.
                onBack: () => {
                    if (!createdResourceId) return Promise.resolve();
                    const id = createdResourceId;
                    createdResourceId = null;
                    // Apply the visible undo immediately (the app doesn't refresh the
                    // list on delete), then delete on the backend in the background.
                    const row = document.querySelector(`[data-type="root-asset-list"] li[data-root-id="${id}"]`);
                    if (row) row.remove();
                    const snap = resourceSnapshot || {};
                    setFieldValue('.right-form form input[name="name"]', snap.name);
                    setFieldValue('.right-form form input[name="externalId"]', snap.externalId);
                    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
                    const token = document.querySelector('meta[name="_csrf"]')?.content;
                    return fetch(`/api/resources/delete/${encodeURIComponent(id)}`, {
                        method: "DELETE",
                        headers: header ? { [header]: token } : {},
                    }).catch(() => { /* best effort */ });
                }
            },
            {
                page: RESOURCES_PAGE,
                target: () => freshRootAssetRow(),
                timeout: 12000,
                title: $L("tutorial.resources.select.title"),
                content: $L("tutorial.resources.select.content"),
                position: "auto",
                // Advance on "click", not the default pointerdown: the row's click handler
                // loads the graph, and advancing on pointerdown would swap in the next
                // step's blocker before the click fires — blocking the graph from loading.
                guard: {
                    type: "click",
                    selector: () => freshRootAssetRow(),
                    eventType: "click",
                    // Remember this resource's row id so the final step can re-select it
                    // on the resources tab and show the finished network.
                    onAction: () => {
                        const row = freshRootAssetRow();
                        if (row) window.DataHubTutorialContext?.remember("lastResourceRootId", row.getAttribute("data-root-id"));
                    }
                }
            },
            {
                page: RESOURCES_PAGE,
                graphNode: () => targetResourceExternalId(),
                interactive: false,
                title: $L("tutorial.resources.node.title"),
                content: $L("tutorial.resources.node.content")
            },
            {
                // Spotlight the node and let the user open the graph's own "add
                // connected resource" flow. Allow both the canvas (to tap the node +
                // its "+") and the right-form the "+" opens. Advance as soon as that
                // form opens, so pressing "+" gives immediate feedback rather than
                // leaving the user staring at an unchanged step.
                page: RESOURCES_PAGE,
                graphNode: () => targetResourceExternalId(),
                allow: ['[data-type="resource-network"]', '.right-form'],
                title: $L("tutorial.resources.connect.title"),
                content: fmt1($L("tutorial.resources.connect.content"), s.resource2Name),
                guard: {
                    type: "appear",
                    hideNext: true,
                    // The "Create resource and relation" form's From-node button is
                    // unique to it — its presence means the connect form has opened.
                    selector: () => document.querySelector('.right-form [data-type="find-from-node-btn"]')
                }
            },
            {
                // The connect form is open: let the user fill it in (relationship,
                // label, name) and save. Advance when the form closes — a successful
                // save (or cancel) removes it — without depending on the new node
                // round-tripping through Pulsar + the graph reload.
                page: RESOURCES_PAGE,
                target: '.right-form',
                allow: '.right-form',
                title: $L("tutorial.resources.connectForm.title"),
                content: fmt1($L("tutorial.resources.connectForm.content"), s.resource2Name),
                position: "auto",
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => document.querySelector('.right-form [data-type="find-from-node-btn"]') ? null : document.body
                }
            },
            {
                // Show the resulting network before leaving for Timeseries — parallels
                // the dataset-node beat. The connect step's afterSave already added the
                // second node + edge to the canvas, so the network is on screen now.
                page: RESOURCES_PAGE,
                target: '[data-type="resource-network"]',
                interactive: false,
                // No spotlight: the graph's own glow (highlightNeighborhood below) is the visual
                // focus. Spotlighting the container would draw a cutout over the WHOLE canvas,
                // which reads as "everything is highlighted" and hides the two-node glow.
                spotlight: false,
                // Glow just the two connected nodes (the resource + the one we just linked
                // to it) and the relation between them, rather than the whole canvas.
                onEnter: () => highlightWhenReady(g => g.highlightNeighborhood(targetResourceExternalId())),
                title: $L("tutorial.resources.networkBeat.title"),
                content: fmt1($L("tutorial.resources.networkBeat.content"), s.resource2Name)
            },
            // === Timeseries chapter: add a measurement ===
            // Timeseries can't be created from a graph node (the node "+" only adds
            // resources), so the user goes to the Timeseries page. The new mechanic is
            // the required unit; we link the series to a resource so it becomes a node in
            // the network, then end on a view of that network.
            {
                chapter: "timeseries",
                // Stay on Resources — this step is shown standing on the resource network the
                // previous beat just built. Omitting page would default it back to DATASETS_PAGE
                // (see the spread at the bottom of this file), which navigated the tour to
                // Datasets and then asked the user to click Timeseries.
                page: RESOURCES_PAGE,
                // Navigate by clicking the Timeseries tab (same pattern as Resources).
                target: 'nav a[href$="/timeseries/index"]',
                title: $L("tutorial.timeseries.nav.title"),
                content: $L("tutorial.timeseries.nav.content"),
                position: "auto",
                guard: { type: "click", selector: 'nav a[href$="/timeseries/index"]', eventType: "click" }
            },
            {
                // Why timeseries — the "why" beat for this chapter, shown once we're on the page.
                chapter: "timeseries",
                page: TIMESERIES_PAGE,
                placement: "center",
                spotlight: false,
                interactive: false,
                title: $L("tutorial.timeseries.intro.title"),
                content: $L("tutorial.timeseries.intro.content")
            },
            {
                // The create button opens a submenu (timeseries vs function); advance
                // once the submenu is showing.
                page: TIMESERIES_PAGE,
                target: '[data-type="create-timeseries-btn"]',
                title: $L("tutorial.timeseries.create.title"),
                content: $L("tutorial.timeseries.create.content"),
                position: "auto",
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => {
                        const m = document.querySelector('[data-type="create-submenu"]');
                        return (m && !m.classList.contains("hidden")) ? m : null;
                    }
                }
            },
            {
                // Pick "Create Time Series" from the submenu; advance when the form opens.
                page: TIMESERIES_PAGE,
                target: '[data-type="create-ts"]',
                allow: '[data-type="create-submenu"]',
                title: $L("tutorial.timeseries.createMenu.title"),
                content: $L("tutorial.timeseries.createMenu.content"),
                position: "auto",
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => document.querySelector('.right-form form input[name="name"]')
                }
            },
            {
                page: TIMESERIES_PAGE,
                target: '.right-form form input[name="name"]',
                inRightForm: true,
                lockSubmit: true,
                title: $L("tutorial.timeseries.name.title"),
                content: fmt1($L("tutorial.timeseries.name.content"), s.tsName),
                position: "auto",
                guard: { type: "inputMin", selector: '.right-form form input[name="name"]', min: 2, mode: "enableNext" }
            },
            {
                // Open the unit picker (the required-unit "+"). Advance when the picker
                // opens as a second section; a separate pick step then drops the spotlight
                // so the highlighted button isn't left sitting under the picker.
                page: TIMESERIES_PAGE,
                target: '.right-form [data-type="unit-list-btn"]',
                allow: '.right-form',
                lockSubmit: true,
                title: $L("tutorial.timeseries.unit.title"),
                content: $L("tutorial.timeseries.unit.content"),
                // The "+" sits at the form's right edge; an anchored tooltip would cover the
                // fields beside it. Spotlight the button but drop the bubble to bottom-center.
                placement: "bottomCenter",
                // Don't elevate the button itself (see label step) — it would paint over the
                // unit picker that opens. It stays clickable via the allowed .right-form.
                interactive: false,
                guard: {
                    type: "appear",
                    hideNext: true,
                    onAction: () => { rfSectionsBefore = rfSectionCount(); },
                    selector: () => rfSectionCount() > (rfSectionsBefore ?? 0) ? document.querySelector('.right-form') : null
                }
            },
            {
                // Pick a unit + confirm. No spotlight (the picker fills the form); tooltip
                // at the bottom. Advance once a unit lands back on the form.
                page: TIMESERIES_PAGE,
                placement: "bottomCenter",
                spotlight: false,
                allow: '.right-form',
                lockSubmit: true,
                title: $L("tutorial.timeseries.unitPick.title"),
                content: $L("tutorial.timeseries.unitPick.content"),
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => {
                        const u = document.querySelector('.right-form [data-type="unit-name"]');
                        return (u && u.textContent.trim()) ? u : null;
                    }
                }
            },
            {
                // Value type: what kind of data the series holds. The form defaults to
                // BIGINT (whole numbers); a measurement wants decimals, so set it to Float
                // for the user (so seeded + real readings can be fractional).
                page: TIMESERIES_PAGE,
                target: '.right-form [data-type="value-type"]',
                allow: '.right-form',
                onEnter: () => {
                    const sel = document.querySelector('.right-form [data-type="value-type"] select, .right-form select[name="valueType"]');
                    if (sel) { sel.value = "float"; sel.dispatchEvent(new Event("change", { bubbles: true })); }
                },
                lockSubmit: true,
                title: $L("tutorial.timeseries.valueType.title"),
                content: $L("tutorial.timeseries.valueType.content"),
                position: "auto"
            },
            {
                // Open the relation picker. Connecting the series to a resource makes it a
                // node in the network (a dataset link alone wouldn't appear in the graph).
                page: TIMESERIES_PAGE,
                target: '.right-form [data-type="relation-list-btn"]',
                allow: '.right-form',
                lockSubmit: true,
                title: $L("tutorial.timeseries.relation.title"),
                content: fmt1($L("tutorial.timeseries.relation.content"), s.resourceName),
                // Same right-edge "+" as the unit step — bottom-center avoids squeezing the form.
                placement: "bottomCenter",
                // Not interactive (see label/unit) so it doesn't paint over the resource picker.
                interactive: false,
                guard: {
                    type: "appear",
                    hideNext: true,
                    onAction: () => { rfSectionsBefore = rfSectionCount(); },
                    selector: () => rfSectionCount() > (rfSectionsBefore ?? 0) ? document.querySelector('.right-form') : null
                }
            },
            {
                // Pick a resource + relationship, then confirm. No spotlight; advance once
                // a relation row lands in the form's relations list.
                page: TIMESERIES_PAGE,
                placement: "bottomCenter",
                spotlight: false,
                allow: '.right-form',
                lockSubmit: true,
                title: $L("tutorial.timeseries.relationPick.title"),
                content: fmt1($L("tutorial.timeseries.relationPick.content"), s.resourceName),
                guard: {
                    type: "appear",
                    hideNext: true,
                    selector: () => {
                        const c = document.querySelector('.right-form [data-type="relations"] .node-container');
                        return (c && c.children.length) ? c : null;
                    }
                }
            },
            {
                // Put the series in the dataset created earlier — now the dataset actually
                // holds data (the piece the old tour was missing). Auto-fill the dataset field
                // from the remembered sandbox dataset; this is a passive beat (no spotlight on
                // the dataset picker button — spotlighting it invited a click that opened a
                // redundant picker, which felt buggy). The user just reads it and continues.
                page: TIMESERIES_PAGE,
                allow: '.right-form',
                placement: "bottomCenter",
                spotlight: false,
                onEnter: () => {
                    const dsId = window.DataHubTutorialContext?.getSelectedDatasetId?.();
                    const dsName = window.DataHubTutorialContext?.recall("lastDatasetName");
                    const field = document.querySelector('.right-form [name="dataSetId"]');
                    const span = document.querySelector('.right-form [data-type="data-set"]');
                    if (field && dsId) { field.value = dsId; field.dispatchEvent(new Event("change", { bubbles: true })); }
                    if (span && dsName) span.textContent = dsName;
                },
                lockSubmit: true,
                title: $L("tutorial.timeseries.dataset.title"),
                content: fmt1($L("tutorial.timeseries.dataset.content"), s.name)
            },
            {
                page: TIMESERIES_PAGE,
                target: '.right-form form footer button[type="submit"]',
                inRightForm: true,
                // Reopen the timeseries form if Back lands here with it closed. The
                // submenu's "Create Time Series" entry opens it directly on pointerdown
                // (it doesn't need the submenu visible), so it works as a single opener.
                fallbackOpen: '[data-type="create-ts"]',
                timeout: 12000,
                allow: '.right-form',
                onEnter: applyUniqueExternalId,
                title: $L("tutorial.timeseries.save.title"),
                content: $L("tutorial.timeseries.save.content"),
                position: "auto",
                guard: {
                    type: "click",
                    selector: '.right-form form footer button[type="submit"]',
                    eventType: "pointerdown",
                    hideNext: true,
                    // Remember the auto-derived external id so the next step can seed demo
                    // datapoints onto this exact series.
                    rememberKey: "lastTimeseriesExternalId",
                    rememberSelector: '.right-form form input[name="externalId"]',
                    // Snapshot existing rows + the name so Back can detect the new series,
                    // delete it, and refill the reopened form (unit/relation re-picked).
                    onAction: () => {
                        tsIdsBefore = tsRowIds();
                        timeseriesSnapshot = {
                            name: document.querySelector('.right-form form input[name="name"]')?.value || "",
                        };
                    },
                    // Success: a row appeared whose id wasn't present before the save.
                    afterWaitFor: () => {
                        const before = tsIdsBefore || new Set();
                        const li = tsRows().find(li => !before.has(li.getAttribute("data-root-id"))) || null;
                        if (li) {
                            createdTimeseriesId = li.getAttribute("data-root-id");
                            // Persist for end-of-tour cleanup (a re-run wipes it).
                            window.DataHubTutorialContext?.remember("tutorialTimeseriesId", createdTimeseriesId);
                        }
                        return li;
                    },
                    afterFailFor: ".right-form .flash.error",
                    afterWaitTimeout: 8000,
                    closeRightFormAfter: true
                },
                // Back-undo: delete the timeseries this step created and refill the
                // reopened form (unit + relation are re-picked). No-op if none created.
                onBack: () => {
                    if (!createdTimeseriesId) return Promise.resolve();
                    const id = createdTimeseriesId;
                    createdTimeseriesId = null;
                    const row = document.querySelector(`[data-type="timeseries-list"] li[data-root-id="${id}"]`);
                    if (row) row.remove();
                    const snap = timeseriesSnapshot || {};
                    setFieldValue('.right-form form input[name="name"]', snap.name);
                    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
                    const token = document.querySelector('meta[name="_csrf"]')?.content;
                    return fetch(`/api/timeseries/delete/${encodeURIComponent(id)}`, {
                        method: "DELETE",
                        headers: header ? { [header]: token } : {},
                    }).catch(() => { /* best effort */ });
                }
            },
            {
                // Seed a couple of days of sample readings onto the new series so the chart
                // has something real to show. Generated in the browser and sent straight to
                // datahub-api's real ingest endpoint (POST /timeseries/data) with a token from
                // /token — the same path the file upload uses, and the same one a real
                // integrator would. Best-effort, fire-and-forget: points flow through
                // Pulsar -> consumer -> ClickHouse, so they may still be propagating when the
                // user clicks — the copy holds whether or not they're in yet.
                page: TIMESERIES_PAGE,
                placement: "center",
                spotlight: false,
                onEnter: () => {
                    const ext = window.DataHubTutorialContext?.recall("lastTimeseriesExternalId");
                    if (!ext) return;
                    const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]')?.content;
                    if (!apiBaseUrl) return;
                    // 48 hourly readings following this scenario's rhythm (series "a"). Using the
                    // same rhythm the correlation chapter uses means the sibling sensors it adds
                    // later genuinely co-move with this one. Decimals fit the Float value type.
                    const now = Date.now();
                    const hr = 3600000;
                    const hours = 48;
                    const datapoints = [];
                    for (let i = hours; i >= 0; i--) {
                        datapoints.push({ timestamp: String(now - i * hr), value: corrValue(i, "a").toFixed(2) });
                    }
                    // Fetch a fresh bearer token from the console session, then POST the points
                    // directly to the API (CORS-enabled), exactly like files/form.js does.
                    fetch("/token", { headers: { Accept: "text/plain" }, credentials: "same-origin" })
                        .then(r => (r.ok ? r.text() : null))
                        .then(token => {
                            if (!token) return;
                            return fetch(apiBaseUrl + "/timeseries/data", {
                                method: "POST",
                                headers: {
                                    "Authorization": "Bearer " + token,
                                    "Content-Type": "application/json",
                                    "Accept": "application/json"
                                },
                                body: JSON.stringify({ items: [{ externalId: ext, datapoints }] })
                            });
                        })
                        .catch(() => { /* best effort */ });
                },
                title: $L("tutorial.timeseries.seed.title"),
                content: $L("tutorial.timeseries.seed.content")
            },
            {
                // Click the series to chart it (row pointerdown -> by-hours-ago -> chart).
                // Advance on click so pointerdown draws the chart before the blocker swaps.
                page: TIMESERIES_PAGE,
                target: () => freshTimeseriesRow(),
                timeout: 12000,
                title: $L("tutorial.timeseries.chart.title"),
                content: $L("tutorial.timeseries.chart.content"),
                guard: { type: "click", selector: () => freshTimeseriesRow(), eventType: "click" }
            },
            {
                // Explain the chart + how real data gets in (the API + token).
                page: TIMESERIES_PAGE,
                // Spotlight the chart itself, not its container. #datapoints-chart has a
                // min-height of ~100vh (it fills the panel background), so the drawn chart
                // is only its top ~400px — spotlighting the div covered the chart plus a big
                // empty strip that bled off the bottom of the screen. The rendered <svg> is
                // sized to the chart, so it frames just the timeseries. By this step the
                // chart is already drawn (prior step), so the svg resolves immediately; the
                // div fallback keeps today's behaviour if the draw is still pending.
                target: () => document.querySelector("#datapoints-chart svg") || document.querySelector("#datapoints-chart"),
                interactive: false,
                // Re-draw the chart on entry, then retry a few times. This does double duty:
                // (1) after a cross-page Back the timeseries page reloaded, so the chart the
                // user drew is gone — re-select the series to bring it back; (2) the seeded
                // points flow through Pulsar -> consumer -> ClickHouse, so a query right after
                // the seed can return nothing and the console silently draws no chart. Clicking
                // the row re-queries; window.insightsChart is only set once data actually lands,
                // so we stop retrying as soon as the chart has data.
                onEnter: () => {
                    let tries = 0;
                    const tick = () => {
                        if (window.insightsChart) return;   // chart drawn (data landed)
                        const row = freshTimeseriesRow();
                        if (row) row.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
                        if (++tries < 6) setTimeout(tick, 2000);
                    };
                    tick();
                },
                title: $L("tutorial.timeseries.chartExplain.title"),
                content: $L("tutorial.timeseries.chartExplain.content")
            },
            {
                // End on the resources tab, where the canonical network graph lives.
                // Navigate there by clicking the Resources tab.
                // Stay on Timeseries until that click — the user is looking at the chart from
                // the previous step. Without page this defaulted to DATASETS_PAGE and bounced
                // the tour through Datasets before asking for the Resources tab.
                page: TIMESERIES_PAGE,
                target: 'nav a[href$="/resources/index"]',
                title: $L("tutorial.timeseries.viewNav.title"),
                content: $L("tutorial.timeseries.viewNav.content"),
                position: "auto",
                guard: { type: "click", selector: 'nav a[href$="/resources/index"]', eventType: "click" }
            },
            {
                // Auto-load the network for the resource we built around, then highlight it.
                // Replaces the old "find your resource in the list and click it" step, which
                // was unreliable when the tenant has many similarly-named rows (it spotlighted
                // the wrong one). loadNetwork goes depth-5 and follows relationships, so the
                // connected resource AND the timeseries show together.
                page: RESOURCES_PAGE,
                target: '[data-type="resource-network"]',
                interactive: false,
                // No container spotlight — the graph's own highlightAll glow is the focus
                // (see the resources networkBeat step for the same reasoning).
                spotlight: false,
                onEnter: () => {
                    const ctx = window.DataHubTutorialContext;
                    const rootId = ctx?.recall("lastResourceRootId");
                    if (!rootId) return;
                    let g = window.resourceNetwork;
                    if (!g) {
                        const c = document.querySelector('[data-type="resource-network"]');
                        if (!c) return;
                        g = new GraphNetwork({ container: c });
                        window.resourceNetwork = g;
                    }
                    g.loadNetwork(rootId, true);
                    highlightWhenReady(gg => gg.highlightAll());
                },
                title: $L("tutorial.timeseries.network.title"),
                content: $L("tutorial.timeseries.network.content")
            },

            // === Correlation chapter: one signal isn't the story — how signals move together is ===
            {
                // The insight the problem set up: related signals rise and fall together, but you
                // can only see it when they share one view. Centered SVG of three siloed signals.
                chapter: "correlation",
                page: RESOURCES_PAGE,
                placement: "center",
                spotlight: false,
                interactive: false,
                title: $L("tutorial.correlation.intro.title"),
                maxWidth: SPLIT_WIDTH,
                content: withFigure($L("tutorial.correlation.intro.content"), svgCorrelationProblem())
            },
            {
                // Passive dwell beat: create two sibling series that co-move with the user's, seed
                // all three off one shared wave, and arm the Insights overlay. Runs while the user
                // reads (gives the datapoints time to propagate to ClickHouse before the payoff).
                chapter: "correlation",
                page: RESOURCES_PAGE,
                placement: "center",
                spotlight: false,
                interactive: false,
                onEnter: () => seedCorrelatedSeries(),
                title: $L("tutorial.correlation.seed.title"),
                content: $L("tutorial.correlation.seed.content")
            },
            {
                // Reveal, beat 1 — cross to Insights (the overlay page). showSoloSeries reconciles
                // the chart to EXACTLY the user's series: just one line (and deselects the siblings
                // if the user stepped back from the reveal), retrying through any propagation lag.
                chapter: "correlation",
                page: INSIGHTS_PAGE,
                interactive: false,
                target: () => document.querySelector("#datapoints-chart svg") || document.querySelector("#datapoints-chart"),
                // Let the user zoom/brush the chart while the tour is up (the blocker otherwise
                // eats drags). The chart is the spotlight target, so allowing it is safe.
                allow: "#datapoints-chart",
                onEnter: () => showSoloSeries(),
                title: $L("tutorial.correlation.solo.title"),
                content: fmt1($L("tutorial.correlation.solo.content"), s.tsName)
            },
            {
                // Reveal, beat 2 — the discovery: overlay the two sibling sensors on the same asset;
                // they track the user's series in lockstep. revealCorrelatedSeries reconciles the
                // chart to all three (drawn, not just row-selected). A correlation you FIND, not fake.
                chapter: "correlation",
                page: INSIGHTS_PAGE,
                interactive: false,
                target: () => document.querySelector("#datapoints-chart svg") || document.querySelector("#datapoints-chart"),
                allow: "#datapoints-chart",   // interactive zoom/brush on the chart (see solo step)
                onEnter: () => revealCorrelatedSeries(),
                title: $L("tutorial.correlation.chart.title"),
                content: fmt1($L("tutorial.correlation.chart.content"), s.resourceName)
            },
            {
                // Future-analysis teaser (centered SVG): connected data makes patterns findable,
                // and richer analysis tooling is on the way. Deliberately generic — no specific
                // feature or AI claims.
                chapter: "correlation",
                page: INSIGHTS_PAGE,   // stay here — omitting page would default back to DATASETS_PAGE
                placement: "center",
                spotlight: false,
                interactive: false,
                // Keep the three series behind the card. Stepping BACK here from the events chapter
                // reloads Insights with the one-shot pending-view already consumed, so without this
                // the chart underneath would be empty.
                onEnter: () => revealCorrelatedSeries(),
                title: $L("tutorial.future.teaser.title"),
                maxWidth: SPLIT_WIDTH,
                content: withFigure($L("tutorial.future.teaser.content"), svgAgentTeaser())
            },

            // === Events chapter: log what happens to the assets, tie it back to the network ===
            {
                // Orientation: events are the discrete happenings (an alarm, a maintenance job),
                // the counterpart to the continuous timeseries signal.
                chapter: "events",
                page: RESOURCES_PAGE,
                interactive: false,
                spotlight: false,
                placement: "center",
                title: $L("tutorial.events.intro.title"),
                content: $L("tutorial.events.intro.content")
            },
            {
                // Seed a couple of events onto the resource the user built (see seedEvents). Passive
                // beat — the copy holds whether or not the events have finished propagating.
                chapter: "events",
                page: RESOURCES_PAGE,
                spotlight: false,
                placement: "center",
                onEnter: () => seedEvents(),
                title: $L("tutorial.events.seed.title"),
                content: fmt1($L("tutorial.events.seed.content"), s.resourceName)
            },
            {
                // Navigate to the Events tab (same click-to-nav pattern as resources/timeseries).
                chapter: "events",
                page: RESOURCES_PAGE,
                target: 'nav a[href$="/events/index"]',
                title: $L("tutorial.events.nav.title"),
                content: $L("tutorial.events.nav.content"),
                position: "auto",
                guard: { type: "click", selector: 'nav a[href$="/events/index"]', eventType: "click" }
            },
            {
                // The event log. The page auto-searches on load, so the seeded events surface here
                // as they finish processing (tolerant of the Pulsar -> consumer -> ClickHouse lag).
                // Centered narration (no target/spotlight): a fixed, centered tooltip stays put at
                // any list length — anchoring to the (viewport-tall) list would skew it on scroll.
                chapter: "events",
                page: EVENTS_PAGE,
                placement: "center",
                spotlight: false,
                title: $L("tutorial.events.list.title"),
                content: $L("tutorial.events.list.content")
            },
            {
                // Tie it back to the network: clicking any event lists the resources it concerns.
                // The list stays clickable (allow) so the user can open one; a fixed bottom-center
                // tooltip (no target/spotlight) avoids the tall-list tooltip/spotlight skew and keeps
                // the whole list visible while they click.
                chapter: "events",
                page: EVENTS_PAGE,
                allow: '.events-result',
                placement: "bottomCenter",
                title: $L("tutorial.events.details.title"),
                content: fmt1($L("tutorial.events.details.content"), s.resourceName)
            },

            // === Buildout chapter: grow the network for the user, then highlight + explain ===
            {
                chapter: "buildout",
                page: RESOURCES_PAGE,
                interactive: false,
                spotlight: false,
                placement: "center",
                title: $L("tutorial.buildout.intro.title"),
                content: $L("tutorial.buildout.intro.content")
            },
            {
                // Auto-build a small connected cluster. onAction fires the build once; the
                // appear guard advances when buildoutDone flips (cluster created, shown in
                // the graph, and highlighted). hideNext so the user waits for the build.
                chapter: "buildout",
                page: RESOURCES_PAGE,
                interactive: false,
                target: '[data-type="resource-network"]',
                title: $L("tutorial.buildout.building.title"),
                content: $L("tutorial.buildout.building.content"),
                guard: {
                    type: "appear",
                    hideNext: true,
                    onAction: () => autoBuildNetwork(),
                    selector: () => (buildoutDone ? document.querySelector('[data-type="resource-network"]') : null),
                    timeout: 60000
                }
            },
            {
                // Explain the network by glowing the whole graph (nodes + relations).
                chapter: "buildout",
                page: RESOURCES_PAGE,
                interactive: false,
                // No container spotlight — the graph's own glow is the focus.
                spotlight: false,
                target: '[data-type="resource-network"]',
                onEnter: () => window.resourceNetwork && window.resourceNetwork.highlightAll(),
                title: $L("tutorial.buildout.explain.title"),
                content: $L("tutorial.buildout.explain.content")
            },
            {
                // Multi-node highlight: dim the rest and glow a sub-path (root + a couple of
                // its assets + the relations between them) — shows how you trace dependencies.
                chapter: "buildout",
                page: RESOURCES_PAGE,
                interactive: false,
                // No container spotlight — the sub-path glow (highlightElements) is the focus;
                // a container cutout would cover it and read as "everything highlighted".
                spotlight: false,
                target: '[data-type="resource-network"]',
                onEnter: () => {
                    const ext = window.DataHubTutorialContext?.recall("buildoutExternalIds") || [];
                    if (window.resourceNetwork && ext.length) window.resourceNetwork.highlightElements(ext.slice(0, 3));
                },
                title: $L("tutorial.buildout.trace.title"),
                content: $L("tutorial.buildout.trace.content")
            },
            {
                // Soft suggestion to keep going themselves — no quota, clears the highlight
                // so the user can work the graph. The canvas + right-form are allowed through
                // the blocker so this isn't a dead end: the user can actually tap a node, hit
                // its + and add a resource here, then click Next when done. Bottom-center keeps
                // the tooltip out of the way while they build.
                chapter: "buildout",
                page: RESOURCES_PAGE,
                interactive: false,
                spotlight: false,
                allow: ['[data-type="resource-network"]', '.right-form'],
                placement: "bottomCenter",
                onEnter: () => window.resourceNetwork && window.resourceNetwork.clearHighlight(),
                title: $L("tutorial.buildout.yourturn.title"),
                content: $L("tutorial.buildout.yourturn.content")
            },
            {
                chapter: "buildout",
                // Stay on the resources page so the built diagram remains in view through the
                // recap — without this it defaults to DATASETS_PAGE and navigates away.
                page: RESOURCES_PAGE,
                placement: "center",
                spotlight: false,
                onEnter: () => window.resourceNetwork && window.resourceNetwork.clearHighlight(),
                title: $L("tutorial.resources.recap.title"),
                content: $L("tutorial.resources.recap.content"),
                // Keep (Next) leaves the network in place; "Remove tutorial data" deletes
                // everything this run created (dataset, resources, timeseries, cluster).
                nextLabel: $L("tutorial.end.keep"),
                altAction: { label: $L("tutorial.end.delete") },
                onAlt: () => (window.DataHubTutorialResetSandbox ? window.DataHubTutorialResetSandbox() : undefined)
            }
        ];

        // Tag every step with its chapter by carrying the boundary tags forward, so a
        // single chapter can be replayed on its own (the welcome step stays untagged).
        let chapterTag = null;
        steps.forEach(st => {
            if (st.chapter) chapterTag = st.chapter;
            else if (chapterTag) st.chapter = chapterTag;
        });
        return steps;
    }

    // Tutorial registration is exposed as a function so the industry modal can
    // re-register steps after the user selects an industry.
    // Every datasets step lives on the datasets page. Stamping step.page lets the
    // engine navigate here first when the tour is started from another page, and
    // lets the destination resume land on the right page (see tutorial-core.js).
    const DATASETS_PAGE = "/datasets/index";

    window.DataHubTutorialRegisterDatasets = function () {
        if (!window.DataHubTutorialEngine) return;

        const industry = getIndustry();
        const scenario = getScenario(industry);

        const steps = stepsForScenario(scenario).map(s => ({ page: DATASETS_PAGE, ...s }));
        window.DataHubTutorialEngine.define("datasets", steps);
    };

    // Sandbox lifecycle: the tour builds into one throwaway dataset. Starting a new
    // tour wipes the previous sandbox (cascade-deleting whatever was built in it) so
    // the run begins clean — this is what lets the user re-run without colliding on
    // the scenario's external id. Best-effort: a missing/already-deleted dataset is
    // fine; local context is always cleared afterwards. Called by tutorial-page-ui.js
    // before a fresh start (never on resume).
    window.DataHubTutorialResetSandbox = function () {
        const ctx = window.DataHubTutorialContext;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const headers = header ? { [header]: token } : {};
        // Issue a real HTTP DELETE to the entity's /delete/{id} endpoint (best-effort).
        const del = (url) => fetch(url, { method: "DELETE", headers }).catch(() => { /* best effort */ });

        // Resources the buildout chapter created aren't in the sandbox dataset, so the dataset
        // wipe alone wouldn't remove them — delete them by id too. They form a TREE, and the
        // delete rule (#133) refuses to strand children, so a parent can't be removed while a
        // child still exists. buildoutResourceIds is in creation order (parents before
        // children); reverse it (children first) and delete SEQUENTIALLY so each parent is
        // childless by the time we reach it, with the tree root deleted last. Parallel deletes
        // would 400 on every parent and leak them.
        const buildoutIds = (ctx && ctx.recall && ctx.recall("buildoutResourceIds")) || [];
        const rootResourceId = ctx && ctx.recall && ctx.recall("lastResourceRootId");
        const orderedIds = (Array.isArray(buildoutIds) ? buildoutIds.slice() : []).reverse();
        if (rootResourceId && !orderedIds.map(String).includes(String(rootResourceId))) orderedIds.push(rootResourceId);
        const resourceDeletes = orderedIds.reduce(
            (chain, rid) => chain.then(() => del(`/api/resources/delete/${encodeURIComponent(rid)}`)),
            Promise.resolve()
        );

        // The timeseries belongs to the dataset, but delete it explicitly too (node +
        // datapoints) so nothing dangles if the dataset delete doesn't cascade to it.
        const tsId = ctx && ctx.recall && ctx.recall("tutorialTimeseriesId");
        const tsDelete = tsId ? del(`/api/timeseries/delete/${encodeURIComponent(tsId)}`) : Promise.resolve();

        // The two correlation sibling series (created directly via the API in the correlation
        // chapter). Delete explicitly like tsDelete so they don't linger.
        const corrIds = (ctx && ctx.recall && ctx.recall("correlationTimeseriesIds")) || [];
        const corrDeletes = (Array.isArray(corrIds) && corrIds.length)
            ? Promise.all(corrIds.map(id => del(`/api/timeseries/delete/${encodeURIComponent(id)}`)))
            : Promise.resolve();

        const datasetId = ctx && ctx.getSelectedDatasetId && ctx.getSelectedDatasetId();
        const datasetDelete = datasetId
            ? del(`/api/datasets/delete/${encodeURIComponent(datasetId)}`)
            : Promise.resolve();

        // Seeded events aren't nodes and don't cascade from the resource delete, so remove them by
        // externalId via datahub-api's /events/delete (browser-direct with a fresh token, the same
        // path the seed used). Best-effort.
        const eventExtIds = (ctx && ctx.recall && ctx.recall("tutorialEventExternalIds")) || [];
        const eventsDelete = (Array.isArray(eventExtIds) && eventExtIds.length)
            ? fetch("/token", { headers: { Accept: "text/plain" }, credentials: "same-origin" })
                .then(r => (r.ok ? r.text() : null))
                .then(tok => {
                    const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]')?.content;
                    if (!tok || !apiBaseUrl) return;
                    return fetch(apiBaseUrl + "/events/delete", {
                        method: "POST",
                        headers: { "Authorization": "Bearer " + tok, "Content-Type": "application/json", "Accept": "application/json" },
                        body: JSON.stringify({ items: eventExtIds.map(id => ({ externalId: id })) })
                    });
                })
                .catch(() => { /* best effort */ })
            : Promise.resolve();

        return Promise.all([resourceDeletes, tsDelete, corrDeletes, datasetDelete, eventsDelete])
            .catch(() => { /* best effort — start the tour even if a wipe failed */ })
            .finally(() => {
                if (ctx) {
                    ctx.clearSelectedDatasetId();
                    if (ctx.remember) {
                        ctx.remember("buildoutResourceIds", []);
                        ctx.remember("lastResourceRootId", null);
                        ctx.remember("tutorialTimeseriesId", null);
                        ctx.remember("tutorialEventExternalIds", []);
                        ctx.remember("correlationTimeseriesIds", []);
                        ctx.remember("correlationOverlayExternalIds", []);
                        ctx.remember("correlationRevealExternalIds", []);
                        ctx.remember("correlationSeeded", false);
                    }
                }
            });
    };

    // Timeseries-chapter prerequisite: its "link to a resource" step needs at least
    // one resource to exist. When the chapter is launched on its own the user may have
    // none, so best-effort seed a throwaway one (resources require a label). Promise-
    // based (not async) to match the rest of the file; the chapter starts regardless.
    window.DataHubTutorialEnsureResource = function () {
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        if (!header) return Promise.resolve();
        const csrf = { [header]: token };
        return fetch("/api/resources/search", {
            method: "POST",
            headers: { Accept: "application/json", "Content-Type": "application/json", ...csrf },
            body: JSON.stringify({ query: "" }),
        })
            .then(r => (r.ok ? r.json() : null))
            .then(json => {
                const items = json && (json.items || (Array.isArray(json) ? json : []));
                if (items && items.length) return null;          // already have a resource
                return fetch("/api/label/list", { headers: { Accept: "application/json" } })
                    .then(r => r.json())
                    .then(labels => {
                        const arr = Array.isArray(labels) ? labels : (labels.items || []);
                        const lv = arr[0] && (arr[0].name || arr[0].externalId || arr[0].id);
                        return fetch("/api/resources/save", {
                            method: "POST",
                            headers: { Accept: "application/json", "Content-Type": "application/json", ...csrf },
                            body: JSON.stringify({
                                name: "Tutorial resource", externalId: "tutorial_resource_" + Date.now(),
                                description: "", source: "", isRoot: true,
                                labels: lv ? [String(lv)] : [], metadata: {},
                            }),
                        });
                    });
            })
            .catch(err => {
                // Best-effort: the timeseries chapter still starts without a seeded
                // resource (the user just won't have one to link). Log the failure so
                // it's diagnosable instead of silently swallowed.
                console.warn("[tutorial] Could not seed a resource for the timeseries chapter:", err);
            });
    };

    // Register once the i18n bundle is loaded + parsed: every step's text comes
    // from the bundle, so registering before it would just warn on each key.
    document.addEventListener("DOMContentLoaded", () => {
        window.i18nReady?.then(() => window.DataHubTutorialRegisterDatasets?.());
    });
})();
