/*
    The drawing of the network view, for GraphNetwork (graph-network.js).

    Resources are dots sized by importance (betweenness) with an icon for what they are (a pump, a valve:
    see graph-icons.js), grouped by the data's containment tree (BELONGS_TO, PART_OF): a parent's
    sections are laid out together inside one more group, and each group is outlined. A resource with
    parents in several groups sits between them and every group reaches out to it. Lines between groups
    run as curved bundles through the groups; the names drawn are the ones that read at the current
    zoom. Without a usable tree, Louvain finds the groups instead.

    A context hub, linked to half the graph or more (the plant everything is part of), is grouped with
    the resources nothing else connects, and its lines are drawn only while one of their ends is
    selected (graph-algorithms.js).

    Cytoscape draws the dots and lines (WebGL where the browser has WebGL 2, canvas otherwise). The
    outlines, names and moving flow labels are drawn on canvases of their own, below and above it, so a
    pan or zoom never restyles the graph.

    The arrangement is remembered per graph in this browser: known resources go back where they were,
    and only new ones are placed, so adding or editing something never moves the rest.

    A graph of more than BIG_ABOVE resources is drawn group by group: zoomed out, each group is one dot,
    and a group opens into its resources as it is zoomed into (see "big graphs" below).
 */
(function () {
'use strict';
const A = window.GraphAlgorithms;

const MIN_LABEL_PX = 10, PAD = 30, FIT_MAX_ZOOM = 1.4;
// Names grow with the zoom only up to a comfortable size: a hub's stops at 18 px on screen, a leaf's at
// 13 px. Zooming in then shows more names rather than bigger ones.
const MAX_LABEL_PX = imp => 13 + 5 * (imp || 0), MAX_EDGE_LABEL_PX = 11, MAX_FLOW_LABEL_PX = 12, ICON_MIN_PX = 12;
const GROUP_LABEL_PX = imp => 12 + 3 * (imp || 0), GROUP_LABEL_GAP = 10;
// Pills, in units of their name's font size: the padding at each end, the room for the icon, the widest a
// pill gets (a longer name ends in "…"), and its height. Below PILL_MIN_PX on screen a pill shows no name.
const PILL_PAD = 0.7, PILL_ICON = 1.6, PILL_MAX = 17, PILL_H = 2.2, PILL_MIN_PX = 7;
// The diagram: up to this many resources, and the gaps between its columns and between resources in one.
const DIAGRAM_MAX = 5000, DIAGRAM_RANK_GAP = 60, DIAGRAM_GAP = 10, DIAGRAM_WRAP = 16;
// A shut group's name keeps one modest size however far out the view is: it is a signpost, not a heading.
const labelPx = (n, z) => (n.hasClass('agg') ? GROUP_LABEL_PX(n.data('imp')) : Math.min(n.data('fs') * z, MAX_LABEL_PX(n.data('imp'))));
// Above this many resources names are re-picked only once a pan or zoom settles, and above the second
// the lines inside a group are drawn straight, which keeps the WebGL renderer at 60 frames a second.
const SETTLE_ABOVE = 1000, STRAIGHT_ABOVE = 3000;

// Group colours chosen to stay apart on both themes and from the line colour.
const CLUSTER = ['#f2a65a', '#8fb9ff', '#e98bd0', '#9ad66f', '#ffd166', '#c3a6ff', '#ff8f8f', '#5fd4c4', '#d4b483', '#7fc8f8', '#f7a1c4', '#b8e986'];
const OTHER = '#9aa9b2';
// The icon inside a resource's dot comes from graph-icons.js, which also remembers the chosen style.
const iconOf = (g, ink) => window.GraphIcons.iconSrc(g, ink);

// a over b at alpha k, as '#rrggbb'.
function mixHex(a, b, k) {
    const h = c => { c = c.replace('#', ''); if (c.length === 3) c = c.split('').map(x => x + x).join(''); return [0, 2, 4].map(i => parseInt(c.slice(i, i + 2), 16)); };
    const P = h(a), Q = h(b);
    return '#' + P.map((v, i) => Math.round(v * k + Q[i] * (1 - k)).toString(16).padStart(2, '0')).join('');
}
const rgbToHex = s => { const m = String(s).match(/\d+(\.\d+)?/g); return m && m.length >= 3 ? '#' + m.slice(0, 3).map(v => Math.round(+v).toString(16).padStart(2, '0')).join('') : null; };

// Text widths are measured once per name and size (to the nearest half pixel) and remembered.
const ctx2d = document.createElement('canvas').getContext('2d');
const textWidths = new Map();
const textW = (t, px, w) => {
    const k = w + '|' + Math.round(px * 2) + '|' + t;
    let v = textWidths.get(k);
    if (v === undefined) { ctx2d.font = `${w} ${Math.round(px * 2) / 2}px Manrope, Arial, sans-serif`; v = ctx2d.measureText(t).width; if (textWidths.size > 20000) textWidths.clear(); textWidths.set(k, v); }
    return v;
};
// The text cut to fit a width, ending in "…" where it was cut.
function clipText(t, px, room) {
    if (textW(t, px, 600) <= room) return t;
    let lo = 0, hi = t.length;
    while (lo < hi) { const m = (lo + hi + 1) >> 1; if (textW(t.slice(0, m) + '…', px, 600) <= room) lo = m; else hi = m - 1; }
    return t.slice(0, lo).trimEnd() + '…';
}
const flowOf = e => { const m = e.metadata || {}; return m['metadata_animate::datatype'] || m['animate::datatype'] || ''; };
const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
let glOk = null;
const hasWebgl = () => { if (glOk === null) { try { glOk = !!document.createElement('canvas').getContext('webgl2'); } catch (e) { glOk = false; } } return glOk; };

// ---- shapes -----------------------------------------------------------------------------------------
function hull(points) {
    const p = points.slice().sort((a, b) => a.x - b.x || a.y - b.y);
    if (p.length < 3) return p;
    const cross = (o, a, b) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    const lo = [], up = [];
    p.forEach(q => { while (lo.length >= 2 && cross(lo[lo.length - 2], lo[lo.length - 1], q) <= 0) lo.pop(); lo.push(q); });
    p.slice().reverse().forEach(q => { while (up.length >= 2 && cross(up[up.length - 2], up[up.length - 1], q) <= 0) up.pop(); up.push(q); });
    return lo.slice(0, -1).concat(up.slice(0, -1));
}
// An outline through a hull's corners, rounded: from midpoint to midpoint, curving through each corner.
function roundedInto(path, hp) {
    hp.forEach((p, i) => { const q = hp[(i + 1) % hp.length], m = { x: (p.x + q.x) / 2, y: (p.y + q.y) / 2 }; if (i === 0) path.moveTo(m.x, m.y); else path.quadraticCurveTo(p.x, p.y, m.x, m.y); });
    const p0 = hp[0], q0 = hp[1 % hp.length]; path.quadraticCurveTo(p0.x, p0.y, (p0.x + q0.x) / 2, (p0.y + q0.y) / 2); path.closePath();
}
function inPoly(hp, p) {
    let inside = false;
    for (let i = 0, j = hp.length - 1; i < hp.length; j = i++) {
        const a = hp[i], b = hp[j];
        if ((a.y > p.y) !== (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
    }
    return inside;
}
function nearestOnPoly(hp, p) {
    let best = null, bd = Infinity;
    hp.forEach((a, i) => {
        const b = hp[(i + 1) % hp.length], dx = b.x - a.x, dy = b.y - a.y, l = dx * dx + dy * dy || 1;
        const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / l)), q = { x: a.x + t * dx, y: a.y + t * dy }, d = Math.hypot(p.x - q.x, p.y - q.y);
        if (d < bd) { bd = d; best = q; }
    });
    return best;
}
function closestPair(P, Q) {
    let best = { d: Infinity };
    P.forEach(a => { const b = nearestOnPoly(Q, a), d = Math.hypot(a.x - b.x, a.y - b.y); if (d < best.d) best = { a, b, d }; });
    Q.forEach(b => { const a = nearestOnPoly(P, b), d = Math.hypot(a.x - b.x, a.y - b.y); if (d < best.d) best = { a, b, d }; });
    return best;
}

// ---- how sharply a drawn line turns -----------------------------------------------------------------
// A line is drawn through its bend points only if it reads as one smooth stroke: nowhere may it turn more
// than SHARP_DEG within SHARP_LEN units, as Cytoscape draws it. The check is 5 degrees stricter, because
// this model of the drawing is close, not exact.
const SHARP_DEG = 60, SHARP_LEN = 16, SHARP_CHECK = SHARP_DEG - 5;
function lineEnd(c, r, toward) { const dx = toward.x - c.x, dy = toward.y - c.y, l = Math.hypot(dx, dy) || 1; return { x: c.x + dx / l * (r - 3), y: c.y + dy / l * (r - 3) }; }
// For a curve: quadratic pieces through the midpoints between control points, starting and ending where
// the line towards the nearest point leaves each resource's dot.
function sharpTurn(p, cps, q, rs, rt) {
    const on = [lineEnd(p, rs, cps[0])];
    for (let i = 0; i < cps.length - 1; i++) on.push({ x: (cps[i].x + cps[i + 1].x) / 2, y: (cps[i].y + cps[i + 1].y) / 2 });
    on.push(lineEnd(q, rt, cps[cps.length - 1]));
    const pts = [];
    cps.forEach((c, i) => { const a = on[i], b = on[i + 1]; for (let k = 0; k < 24; k++) { const u = k / 24, v = 1 - u; pts.push({ x: v * v * a.x + 2 * v * u * c.x + u * u * b.x, y: v * v * a.y + 2 * v * u * c.y + u * u * b.y }); } });
    pts.push(on[on.length - 1]);
    return windowTurn(pts);
}
function windowTurn(pts) {
    const seg = [];
    for (let i = 1; i < pts.length; i++) { const dx = pts[i].x - pts[i - 1].x, dy = pts[i].y - pts[i - 1].y, l = Math.hypot(dx, dy); if (l > 1e-6) seg.push({ a: Math.atan2(dy, dx), l }); }
    let worst = 0;
    for (let i = 0; i < seg.length; i++) {
        let turn = 0, len = 0;
        for (let j = i + 1; j < seg.length && len < SHARP_LEN; j++) { const d = seg[j].a - seg[j - 1].a; turn += Math.atan2(Math.sin(d), Math.cos(d)); len += seg[j].l; worst = Math.max(worst, Math.abs(turn)); }
    }
    return worst * 180 / Math.PI;
}

// ---- how the graph is shown -------------------------------------------------------------------------
// Chosen in the View menu (graph-network.js) and kept in this browser, for every graph. The first choice of
// each is the default.
const VIEW_KEY = 'dh.graph.view.v1';
const VIEW_CHOICES = { layout: ['network', 'diagram'], nodes: ['dots', 'pills'], size: ['importance', 'same'], lines: ['curved', 'straight'], flows: ['on', 'off'] };
// The diagram is laid out by dagre, which is loaded the first time it is needed. A page whose last choice was
// the diagram starts loading it at once, so it is there by the time the graph arrives.
let dagreLoading = null;
function loadDagre() {
    if (window.dagre) return Promise.resolve();
    if (!dagreLoading) dagreLoading = new Promise((resolve, reject) => {
        const el = Object.assign(document.createElement('script'), { src: '/static/js/graph-network/dagre.min.js', async: true });
        el.onload = () => resolve(); el.onerror = () => { dagreLoading = null; reject(new Error('dagre did not load')); };
        document.head.append(el);
    });
    return dagreLoading;
}
function readView() {
    let o = null;
    try { o = JSON.parse(localStorage.getItem(VIEW_KEY) || 'null'); } catch (e) { o = null; }
    const v = {};
    Object.entries(VIEW_CHOICES).forEach(([k, choices]) => { v[k] = o && choices.includes(o[k]) ? o[k] : choices[0]; });
    return v;
}

// ---- remembering the arrangement ----------------------------------------------------------------------
// Every resource's position and group is saved after each drawing and each drag, in this browser's
// localStorage. Browsers give a site about 5 MB, shared with the console's other settings, so arrangements
// get a budget of their own; when it is full the least recently opened one is dropped, and that graph is
// laid out afresh next time.
const STORE_BUDGET = 1000000, STORE_MAX_VIEWS = 200;   // characters; about a fifth of the site's share
const INDEX_KEY = 'dh.graph.arrangements.v1', KEY_PREFIX = 'dh.graph.arrangement.v1|';
const store = {
    raw(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    put(k, v) { try { localStorage.setItem(k, v); return true; } catch (e) { return false; } },
    del(k) { try { localStorage.removeItem(k); } catch (e) { /* storage blocked: nothing to remove */ } },
};
const bare = id => String(id).replace(/^node-/, '');
// One arrangement in as few characters as it takes: the ids in one list, positions in whole pixels, groups
// as a list and the resources placed by hand by their place in it.
function encodeArrangement(ids, pos, comm, moved) {
    const at = new Map(ids.map((id, k) => [id, k]));
    return JSON.stringify({ v: 1, i: ids.map(bare), p: ids.flatMap((id, k) => [Math.round(pos[k].x), Math.round(pos[k].y)]), c: comm, m: moved.filter(id => at.has(id)).map(id => at.get(id)) });
}
function decodeArrangement(text) {
    try {
        const o = JSON.parse(text);
        if (!o || o.v !== 1 || !Array.isArray(o.i) || !Array.isArray(o.p) || o.p.length !== 2 * o.i.length) return null;
        const ids = o.i.map(x => 'node-' + x);
        return { pos: new Map(ids.map((k, j) => [k, [o.p[2 * j], o.p[2 * j + 1]]])), comm: new Map(ids.map((k, j) => [k, (o.c || [])[j]])), moved: (o.m || []).map(j => ids[j]).filter(Boolean) };
    } catch (e) { return null; }
}
function readIndex() { try { const x = JSON.parse(store.raw(INDEX_KEY) || '[]'); return Array.isArray(x) ? x : []; } catch (e) { return []; } }
function writeIndex(idx) { store.put(INDEX_KEY, JSON.stringify(idx)); }
function putArrangement(key, text) {
    const need = key.length + text.length;
    let idx = readIndex().filter(e => e.k !== key).sort((a, b) => a.t - b.t);
    if (need > STORE_BUDGET) return false;
    const total = () => idx.reduce((s, e) => s + e.n, 0);
    const dropOldest = () => { const old = idx.shift(); store.del(old.k); };
    while (idx.length && (total() + need > STORE_BUDGET || idx.length + 1 > STORE_MAX_VIEWS)) dropOldest();
    let ok = store.put(key, text);
    while (!ok && idx.length) { dropOldest(); ok = store.put(key, text); }   // the site's share is full
    if (ok) idx.push({ k: key, t: Date.now(), n: need });
    writeIndex(idx);
    return ok;
}
function getArrangement(key) {
    const text = store.raw(key);
    if (!text) return null;
    const saved = decodeArrangement(text);
    const idx = readIndex(), e = idx.find(x => x.k === key);
    if (e) { e.t = Date.now(); writeIndex(idx); }
    return saved;
}
function dropArrangement(key) { store.del(key); writeIndex(readIndex().filter(e => e.k !== key)); }

// ---- flows ------------------------------------------------------------------------------------------
// Each relation's place in its cycle comes from its id, so the motion is the same on every load.
const phaseOf = id => { let h = 2166136261; for (let i = 0; i < id.length; i++) { h ^= id.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0) / 4294967296; };
const FLOW_DOT = ['#e0b354', '#9ec9ff', '#f29f9f', '#b9e38f', '#d7a8ff'];
const flowColour = t => t === 'oil' ? FLOW_DOT[0] : t === 'gas' ? FLOW_DOT[1] : FLOW_DOT[2 + Math.floor(phaseOf(t) * 3)];
// Limits that keep motion light on any graph. A flow shorter than MIN_MOVING_PX on screen does not move
// (motion that short reads as flicker); at most MAX_MOVING flows move at once; and if drawing ever averages
// more than FRAME_BUDGET_MS, the layer draws every other frame, then stops with the labels standing still.
const MIN_MOVING_PX = 24, MAX_MOVING = 600, FRAME_BUDGET_MS = 4;
function pointAt(p, s) {
    const { pts, cum } = p;
    if (s <= 0) return pts[0];
    if (s >= p.len) return pts[pts.length - 1];
    let lo = 0, hi = cum.length - 1;
    while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (cum[mid] < s) lo = mid; else hi = mid; }
    const u = (s - cum[lo]) / ((cum[hi] - cum[lo]) || 1);
    return { x: pts[lo].x + (pts[hi].x - pts[lo].x) * u, y: pts[lo].y + (pts[hi].y - pts[lo].y) * u };
}
function segDist(P, a, b) {
    const vx = b.x - a.x, vy = b.y - a.y, L2 = vx * vx + vy * vy;
    const u = L2 ? Math.max(0, Math.min(1, ((P.x - a.x) * vx + (P.y - a.y) * vy) / L2)) : 0;
    return Math.hypot(P.x - a.x - u * vx, P.y - a.y - u * vy);
}
const esc = s => window.escapeHtml ? window.escapeHtml(String(s)) : String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// Elements that are not part of the graph: the temporary group boxes of the layout, and the handles,
// ghosts and previews of edge-handles while a relation is being drawn.
const TEMP = '.grp, .eh-handle, .eh-ghost, .eh-ghost-node, .eh-ghost-edge, .eh-preview, .new-edge-handle';
// Whether a resource or relation is shown: not filtered out and not hidden by a collapse. Read from the
// class and the style directly; Cytoscape's visible() can still answer from before a class change made
// in the same moment.
const shown = n => !n.hasClass('filtered-out') && n.style('display') !== 'none';
const shownEdge = e => shown(e) && shown(e.source()) && shown(e.target());

// Grouped layout settings, tuned on the 3W field and a ten-field graph.
const GROUPED = { inter: 120, intra: 26, grav: 0.25, gr: 3.8, nf: 0.1, gc: 1.2, grc: 1.4, sep: 60 };
// Curved bundles: between two groups, a line runs out through a gate on its group, via the centres of
// the groups' own super-groups, and in through a gate on the other group (Holten 2006). At a resource,
// lines heading the same way share a trunk before splitting to their ends. Gap and span in radians.
const BETA = 0.92, FAN_GAP = 0.6, FAN_MIN = 3, FAN_TRUNK = 0.5, FAN_SPAN = 1.2;
// Two relationships between the same pair would take the same path and hide each other, so each gets its
// own lane, PAR_GAP apart.
const PAR_GAP = 10;
const FLOAT_PAD = 14;   // outline padding at zoom 1
const ARM_LONG = 160;   // drawing units; past this a group reaches its resource with a dashed line, not an arm
const HIT_PX = 6;       // how near a click must be to a line, on screen
// Wheel zoom: a mouse notch zooms 25 %, a touchpad's small steps zoom in proportion (a 75 px step
// doubles), and a touchpad pinch, which arrives as a wheel with Ctrl held, follows the fingers.
const NOTCH = 1.25, WHEEL_DOUBLE = 75;
// Big graphs: above BIG_ABOVE resources, a group opens once it is OPEN_PX across on screen, the ones
// nearest the middle first, while at most SHOWN_MAX resources are shown.
const BIG_ABOVE = 5000, OPEN_PX = 90, SHOWN_MAX = 3500;
// A shut group's dot is as large as the group will be at the moment it opens, and smaller the further out
// the view is (down to DOT_MIN of that), so a zoomed-out network has room between its groups and their
// names instead of dots packed edge to edge.
const DOT_MIN = 0.4, DOT_GAMMA = 0.5;
const dotScale = (r, z) => Math.max(DOT_MIN, Math.min(1, Math.pow(2 * r * z / OPEN_PX, DOT_GAMMA)));
// How long one piece of a big graph's work may hold the page before letting it draw and respond.
const SLICE_MS = 25, COMMIT_MS = 250, COMMIT_MAX = 1500;

/**
 * opts:
 *   colors()      the console palette in use (GRAPH_PALETTES entry)
 *   theme()       'light' or 'dark'
 *   saveKey()     the localStorage key of this graph's arrangement, or null to remember nothing
 *   onEdgePick(e) a relation was clicked (after choosing, when several meet there)
 *   tr(key, args) localized text
 *   onWindow()    a big graph brought resources into Cytoscape or took them out
 */
function createGraphView(container, opts) {
    let cy = null, cyWebgl = false;
    let G = null, info = null, current = null, fellBack = false, rootId = null, selectedId = null;
    let MULTI = [], MOVED = new Set(), SHORT = new Map(), NAMED = new Set();
    let frame = 0, posVersion = 0, hullCache = { key: '', pos: -1, groups: null }, hullTimer = 0;
    let scratch = null, viewMoving = false, viewTimer = 0, lodLast = 0, lodTimer = 0;
    let flowList = null, flowRAF = 0, flowEvery = 1, flowTick = 0, flowRecent = [], flowStopped = false;
    let pageScrolling = false, pageScrollTimer = 0, destroyed = false;
    let V = readView();
    // A graph drawn before dagre arrived is drawn as a network, and then again as the diagram.
    if (V.layout === 'diagram') loadDagre().then(() => { if (!destroyed && !big && current && !current.diagram && V.layout === 'diagram') { current = null; MOVED = new Set(); refresh(); } }).catch(() => { /* stays a network */ });
    let big = null, winTimer = 0, winGen = 0, winBusy = false;
    // A group's colour. Past the twelfth group they are grey, except in a big graph, where hundreds of group
    // dots in grey would say nothing: there the colours go round again.
    const colourOf = c => CLUSTER[c] || (big && c >= 0 ? CLUSTER[c % CLUSTER.length] : OTHER);
    const PATHS = new Map();

    // The canvases: outlines under the graph, names and flows over it, and the chooser for relations that
    // meet where a click lands. The graph gets a host of its own, so the container keeps its buttons.
    const make = (tag, cls) => Object.assign(document.createElement(tag), { className: cls });
    const hullCanvas = make('canvas', 'dh-graph-layer dh-graph-hulls');
    const host = make('div', 'dh-graph-cy');
    const labelCanvas = make('canvas', 'dh-graph-layer dh-graph-labels');
    const flowCanvas = make('canvas', 'dh-graph-layer dh-graph-flow');
    const pick = make('div', 'dh-graph-pick');
    pick.hidden = true;
    pick.innerHTML = '<p class="dh-graph-pick-h"></p><ul></ul>';
    container.prepend(hullCanvas, host, labelCanvas, flowCanvas, pick);

    // ---- colours ----------------------------------------------------------------------------------------
    // The page behind the graph, for colours mixed into it: the container's own background, or the first
    // ancestor's that has one.
    function canvasBg() {
        for (let el = container; el && el.nodeType === 1; el = el.parentElement) {
            const c = getComputedStyle(el).backgroundColor;
            if (c && !/rgba\(.*,\s*0\)$/.test(c) && c !== 'transparent') return rgbToHex(c) || '#002942';
        }
        return opts.theme() === 'light' ? '#ffffff' : '#002942';
    }
    // Lines are the console's relation colour (GRAPH_PALETTES.edge), drawn solid between groups and at
    // 60 % inside them. A selected resource's lines turn one colour where they leave it, another where they
    // arrive. Worked out once per theme: the flows read it on every frame.
    let palCache = null;
    const palette = () => palCache || (palCache = makePalette());
    function makePalette() {
        const c = opts.colors(), dark = opts.theme() !== 'light';
        const p = dark
            ? { label: '#e9f1f4', ink: '#0b1217', pick: '#ffffff', flowText: '#8a80a0', out: '#ffb85c', in: '#7fb2ff' }
            : { label: '#16303b', ink: '#16303b', pick: '#17313d', flowText: '#675d80', out: '#c46a00', in: '#2a5fd1' };
        p.bg = canvasBg(); p.outline = p.bg;
        p.line = c.edge; p.accent = c.accent; p.tutorial = c.tutorial;
        p.fadeFrom = mixHex(p.line, p.bg, 0.3); p.outFrom = mixHex(p.out, p.bg, 0.3); p.inFrom = mixHex(p.in, p.bg, 0.3);
        return p;
    }

    // ---- the graph as the algorithms see it --------------------------------------------------------------
    const graphNodes = () => cy.nodes().not(TEMP);
    const graphEdges = () => cy.edges().not(TEMP).filter(e => !e.source().is(TEMP) && !e.target().is(TEMP));
    const labelsOf = l => Array.isArray(l) ? l : l ? [l] : [];
    function readGraph() {
        return A.buildGraph({
            nodes: graphNodes().map(n => ({ id: n.id(), name: n.data('label') || '', labels: labelsOf(n.data('labels')) })),
            edges: graphEdges().map(e => ({ id: e.id(), source: e.data('source'), target: e.data('target'), type: e.data('label') || '', metadata: e.data('metadata') })),
        });
    }
    function analyse() { A.drain(analyseSteps()); }
    function* analyseSteps() {
        const bt = yield* A.betweennessSteps(G);
        const tree = yield* A.hierarchyClustersSteps(G);
        // A hub's relations are left out of betweenness, which would leave the plant a speck: it is drawn
        // as large as the most important resource.
        info = { bt, tree, importance: A.normalise(bt.node).map((v, i) => (G.hubs.has(i) ? 1 : v)) };
        info.edgeImp = A.normalise(G.edges.map(e => bt.edge.get(bt.key(G.index.get(e.source), G.index.get(e.target))) || 0));
    }
    // Containment sections when the graph has a tree that groups it clearly; Louvain otherwise, so a tenant
    // that models no containment still gets groups.
    const treeUsable = h => new Set(h.comm).size >= 2 && (h.q === undefined || h.q >= 0.3);
    function freshGroups() {
        fellBack = !treeUsable(info.tree);
        return fellBack ? A.withContext(G, A.louvain(G)) : info.tree.comm;
    }
    // Groups stay as they were saved, so an edit neither recolours nor regroups anything. A resource that
    // is new since then joins the group most of its neighbours are in, or with only a hub's relations, the
    // hub's group.
    function keptGroups(saved) {
        fellBack = !treeUsable(info.tree);
        const comm = G.ids.map(id => Number.isInteger(saved.get(id)) ? saved.get(id) : -1);
        const nb = G.ids.map(() => []);
        G.edges.forEach(e => { if (e.ctx) return; const a = G.index.get(e.source), b = G.index.get(e.target); nb[a].push(b); nb[b].push(a); });
        for (let pass = 0; pass < 4; pass++) comm.forEach((c, i) => {
            if (c >= 0) return;
            const count = new Map();
            nb[i].forEach(j => { if (comm[j] >= 0) count.set(comm[j], (count.get(comm[j]) || 0) + 1); });
            const best = [...count.entries()].sort((a, b) => b[1] - a[1] || a[0] - b[0])[0];
            if (best) comm[i] = best[0];
        });
        A.contextHomes(G).forEach((h, i) => { if (comm[i] < 0 && comm[h] >= 0) comm[i] = comm[h]; });
        let next = A.maxOf(comm, -1) + 1;
        comm.forEach((c, i) => { if (c < 0) comm[i] = next++; });
        return comm;
    }

    // ---- resources in several groups --------------------------------------------------------------------
    // The groups a resource belongs to besides the one it is drawn in: its other containers' groups.
    function otherGroups(i, comm) {
        if (fellBack || !info || !info.tree.parents) return [];
        const t = info.tree, home = comm[i];
        return [...new Set((t.parents[i] || []).filter(p => p !== t.parent[i]).map(p => comm[p]))].filter(c => c !== home && c !== undefined);
    }
    // A resource in several groups floats between them, unless it heads a section of its own (its children
    // are drawn in its group): then it stays with them, and the other groups reach into the section for it.
    function floats(i, comm) {
        if (!otherGroups(i, comm).length) return false;
        return !G.ids.some((_, j) => j !== i && info.tree.parent[j] === i && comm[j] === comm[i]);
    }
    function multiOf(comm) {
        const out = [];
        G.ids.forEach((id, i) => { const o = otherGroups(i, comm); if (o.length) out.push({ id, i, groups: [comm[i], ...o] }); });
        return out;
    }
    // Which resources are drawn inside their group (kept together, outlined): those with at least as many
    // links inside the group as out of it, in a group that is itself mostly inward-looking. Connectors, like
    // a site and its fields, float between the groups instead of stretching one across the canvas; so does
    // a resource shared evenly by several parents, every resource in several groups, and whatever hangs off
    // one of those alone.
    function groupMembers(comm) {
        const inside = new Map(), out = new Map(), size = new Map(), nIn = new Array(comm.length).fill(0), nOut = new Array(comm.length).fill(0);
        comm.forEach(c => size.set(c, (size.get(c) || 0) + 1));
        G.edges.forEach(e => {
            if (e.ctx) return;
            const i = G.index.get(e.source), j = G.index.get(e.target), a = comm[i], b = comm[j];
            if (a === b) { inside.set(a, (inside.get(a) || 0) + 1); nIn[i]++; nIn[j]++; }
            else { out.set(a, (out.get(a) || 0) + 1); out.set(b, (out.get(b) || 0) + 1); nOut[i]++; nOut[j]++; }
        });
        const tight = c => size.get(c) >= 2 && (inside.get(c) || 0) >= (out.get(c) || 0);
        const shared = i => !fellBack && info.tree.shared[i];
        const nb = G.ids.map(() => new Set());
        G.edges.forEach(e => { const i = G.index.get(e.source), j = G.index.get(e.target); if (!e.ctx && i !== j) { nb[i].add(j); nb[j].add(i); } });
        const fl = G.ids.map((_, i) => floats(i, comm)), lone = i => nb[i].size === 1 && fl[[...nb[i]][0]];
        return new Set(G.ids.filter((id, i) => tight(comm[i]) && nIn[i] >= nOut[i] && !shared(i) && !fl[i] && !lone(i)));
    }

    // ---- looks ------------------------------------------------------------------------------------------
    // Names drop the part an ancestor already says ("Well 00001 PMW" reads "PMW"), but keep more of
    // themselves while they would read the same as another resource's. The full name is in the tooltip.
    function shortNames() { return A.drain(shortNamesSteps()); }
    function* shortNamesSteps() {
        const parent = new Map();
        G.edges.forEach(e => { if (e.parent && !parent.has(e.child)) parent.set(e.child, e.parent); });
        const chain = id => { const out = []; for (let x = parent.get(id), k = 0; x && k < 32; x = parent.get(x), k++) out.push(G.nodes.get(x).name); return out; };
        const nb = new Map(G.ids.map(id => [id, []]));
        G.edges.forEach(e => { nb.get(e.source).push(e.target); nb.get(e.target).push(e.source); });
        const options = new Map();
        let done = 0;
        for (const id of G.ids) {
            const name = G.nodes.get(id).name;
            let anc = chain(id);
            if (!anc.length) nb.get(id).forEach(o => { if (parent.has(o)) anc = anc.concat([G.nodes.get(o).name], chain(o)); });
            const cuts = [...new Set(anc.filter(a => a && name.startsWith(a + ' ') && a.length < name.length))].sort((a, b) => b.length - a.length);
            options.set(id, [...cuts.map(a => name.slice(a.length + 1)), name]);
            if (++done % 5000 === 0) yield;
        }
        const choice = new Map(G.ids.map(id => [id, 0]));
        for (let round = 0; round < 6; round++) {
            const count = new Map();
            G.ids.forEach(id => { const t = options.get(id)[choice.get(id)]; count.set(t, (count.get(t) || 0) + 1); });
            let changed = false;
            G.ids.forEach(id => { const o = options.get(id), k = choice.get(id); if (count.get(o[k]) > 1 && k < o.length - 1) { choice.set(id, k + 1); changed = true; } });
            if (!changed) break;
            yield;
        }
        return new Map(G.ids.map(id => [id, options.get(id)[choice.get(id)]]));
    }
    // Dots sized by importance, or all one size, coloured by group, with an icon for what the resource is. A
    // big graph's dots are always sized by importance: there the size is what tells its hubs apart.
    function lookOf(i, comm, pal) {
        const imp = info.importance[i], same = V.size === 'same' && !big, d = same ? 16 : 8 + 30 * imp, id = G.ids[i];
        const look = { w: d, h: d, fs: same ? 10.5 : 8 + 11 * imp, imp, dot: colourOf(comm[i]), icon: iconOf(G.nodes.get(id), pal.ink), c: comm[i], short: SHORT.get(id) };
        if (pills()) {
            // A pill as wide as its name, which is written inside it after the icon.
            const fs = same ? 10 : 9 + 5 * imp, name = look.short || '', room = fs * (PILL_MAX - PILL_ICON - 2 * PILL_PAD);
            const text = clipText(name, fs, room);
            Object.assign(look, { fs, w: fs * (PILL_ICON + 2 * PILL_PAD) + textW(text, fs, 600), h: fs * PILL_H, pt: text });
        }
        return look;
    }
    // Pills are for graphs drawn whole; a big graph is drawn group by group with dots.
    const pills = () => V.nodes === 'pills' && !big;
    // A node's size for the layout: a pill counts as the mean of its width and height.
    const span = n => (n.hasClass('pill') ? (n.data('w') + n.data('h')) / 2 : n.data('w')) || 20;
    const edgeLookOf = (e, k, comm) => ({ imp: e.ctx ? 0 : info.edgeImp[k], flow: flowOf(e), same: !e.ctx && comm[G.index.get(e.source)] === comm[G.index.get(e.target)] ? 1 : 0 });
    function applyLook(comm) {
        const pal = palette();
        cy.batch(() => {
            G.ids.forEach((id, i) => {
                const n = cy.getElementById(id);
                n.data(lookOf(i, comm, pal));
                if (!n.hasClass('dot')) n.addClass('dot');
                if (pills() !== n.hasClass('pill')) n.toggleClass('pill', pills());
            });
            G.edges.forEach((e, k) => {
                const el = cy.getElementById(e.id);
                el.data(edgeLookOf(e, k, comm));
                if (e.ctx !== el.hasClass('ctx')) el.toggleClass('ctx', e.ctx);
            });
        });
    }

    // ---- layout -----------------------------------------------------------------------------------------
    function forceOptions() {
        const d = id => span(cy.getElementById(id));
        return { name: 'fcose', quality: 'proof', randomize: true, animate: false, fit: false, nodeDimensionsIncludeLabels: false, packComponents: true,
            numIter: 2500, tile: true, nodeSeparation: GROUPED.sep, gravity: GROUPED.grav, gravityRange: GROUPED.gr,
            nestingFactor: GROUPED.nf, gravityCompound: GROUPED.gc, gravityRangeCompound: GROUPED.grc,
            nodeRepulsion: n => 4500 + 260 * span(n),   // the group boxes have no size of their own
            idealEdgeLength: e => (e.data('same') ? GROUPED.intra : GROUPED.inter) + (d(e.data('source')) + d(e.data('target'))) / 2,
            edgeElasticity: e => e.data('same') ? 0.55 : 0.12 };
    }
    // Nested containment groups: the sections of one parent (a well) sit inside one more group with that
    // parent, so a well's sections stay together.
    function sectionParents(comm) {
        if (fellBack) return null;   // Louvain's groups have no parents
        const h = info.tree, root = new Map(), hasKids = new Set(h.parent.filter(p => p >= 0));
        G.ids.forEach((id, i) => { const c = comm[i], r = root.get(c); if (h.parent[i] >= 0 || hasKids.has(i)) { if (r === undefined || h.treeDepth[i] < h.treeDepth[r]) root.set(c, i); } });
        const outer = new Map();   // group -> index of its root's parent
        root.forEach((i, c) => { if (h.parent[i] >= 0) outer.set(c, h.parent[i]); });
        return outer;
    }
    // The groups are temporary compound nodes while fcose runs, so it keeps each one together; they are
    // removed again afterwards and drawn as outlines instead of boxes. While the layout runs, lines are
    // haystack: fcose's result depends on the edge style. A hub's lines pull on nothing.
    function groupedLayout(comm) {
        const member = groupMembers(comm), groups = [...new Set(G.ids.filter(id => member.has(id)).map(id => comm[G.index.get(id)]))];
        // Moving a node into a compound re-adds it, which would show a node hidden by a collapse.
        const hidden = graphNodes().filter(n => n.style('display') === 'none' && !n.hasClass('filtered-out'));
        graphEdges().addClass('lay');
        const outer = groups.length > 1 ? sectionParents(comm) : null;
        if (outer) {
            // An outer group for every parent holding two sections or more, with the parent itself inside.
            const count = new Map(); groups.forEach(c => { if (outer.has(c)) count.set(outer.get(c), (count.get(outer.get(c)) || 0) + 1); });
            let keep = new Set([...count.entries()].filter(([, n]) => n >= 2).map(([k]) => k));
            if (keep.size < 2) keep = new Set();   // one parent holding everything (a single well) adds nothing
            cy.add([...keep].map(k => ({ group: 'nodes', data: { id: 'out:' + k }, classes: 'grp' })));
            cy.add(groups.map(c => ({ group: 'nodes', data: { id: 'grp:' + c, parent: keep.has(outer.get(c)) ? 'out:' + outer.get(c) : undefined }, classes: 'grp' })));
            keep.forEach(k => { const id = G.ids[k]; if (!member.has(id)) cy.getElementById(id).move({ parent: 'out:' + k }); });
        } else if (groups.length > 1) cy.add(groups.map(c => ({ group: 'nodes', data: { id: 'grp:' + c }, classes: 'grp' })));
        if (groups.length > 1) G.ids.forEach((id, i) => { if (member.has(id)) cy.getElementById(id).move({ parent: 'grp:' + comm[i] }); });
        A.withSeed(7, () => cy.layout(Object.assign(forceOptions(), { eles: cy.elements().not('edge.ctx') })).run());
        if (groups.length > 1) { cy.nodes(':child').not('.grp').move({ parent: null }); cy.nodes('.grp').move({ parent: null }); cy.remove('.grp'); }
        hidden.forEach(n => { const m = cy.getElementById(n.id()); if (m.length) m.style('display', 'none'); });
    }
    // ---- the diagram ----------------------------------------------------------------------------------
    // Left to right: each resource in the column after the one it belongs to (BELONGS_TO, PART_OF). One that
    // belongs to nothing hangs off a neighbour that is placed, a wave at a time, and off the most particular
    // one: the neighbour with the fewest relations. So a time series sits beside its sensor rather than
    // beside the drawing sheet both are shown on, and a chain of functions follows on from it. A piece of
    // the graph with no tree at all grows out from its most important resource. A plant that holds
    // everything is left as context, as in the network. dagre then orders each column so that as few lines
    // cross as it can.
    const diagram = () => V.layout === 'diagram' && !big && !!window.dagre && !!G && G.ids.length <= DIAGRAM_MAX;
    function diagramTree() {
        const n = G.ids.length, parent = info.tree.parent.slice(), placed = new Array(n).fill(false);
        parent.forEach((p, i) => { if (p >= 0) { placed[i] = true; placed[p] = true; } });
        const grow = () => {
            for (;;) {
                const wave = [];
                for (let j = 0; j < n; j++) {
                    if (placed[j]) continue;
                    let best = -1;
                    G.adj[j].forEach((_, i) => { if (placed[i] && (best < 0 || G.adj[i].size < G.adj[best].size || (G.adj[i].size === G.adj[best].size && i < best))) best = i; });
                    if (best >= 0) wave.push([j, best]);
                }
                if (!wave.length) return;
                wave.forEach(([j, i]) => { placed[j] = true; parent[j] = i; });
            }
        };
        grow();
        for (;;) {
            let start = -1;
            for (let i = 0; i < n; i++) if (!placed[i] && (start < 0 || info.importance[i] > info.importance[start])) start = i;
            if (start < 0) break;
            placed[start] = true;
            grow();
        }
        return parent;
    }
    function diagramLayout() {
        const parent = diagramTree(), g = new window.dagre.graphlib.Graph();
        g.setGraph({ rankdir: 'LR', ranksep: DIAGRAM_RANK_GAP, nodesep: DIAGRAM_GAP, edgesep: DIAGRAM_GAP, marginx: 0, marginy: 0 });
        g.setDefaultEdgeLabel(() => ({}));
        // A dot's name is written under it, so its box takes in the name.
        const box = new Map();
        G.ids.forEach(id => {
            const n = cy.getElementById(id), w = n.data('w'), h = n.data('h'), fs = n.data('fs') || 10;
            const b = n.hasClass('pill') ? { width: w, height: h, below: 0 } : { width: Math.max(w, textW(n.data('short') || '', fs, 600)), height: h + fs * 1.3 + 4, below: fs * 1.3 + 4 };
            box.set(id, b);
            g.setNode(id, { width: b.width, height: b.height });
        });
        // A long run of resources with nothing after them would make one very tall column: a parent's leaves,
        // or the resources linked to nothing but the context. They are wrapped into columns of DIAGRAM_WRAP,
        // each column held after the one before by links only dagre sees.
        const kids = new Map();
        parent.forEach((p, i) => { if (p >= 0) { if (!kids.has(p)) kids.set(p, []); kids.get(p).push(i); } });
        const link = (a, b) => g.setEdge(G.ids[a], G.ids[b]);
        const wrap = (list, from) => list.forEach((c, k) => { if (k >= DIAGRAM_WRAP) link(list[k - DIAGRAM_WRAP], c); else if (from >= 0) link(from, c); });
        kids.forEach((cs, p) => { cs.filter(c => kids.has(c)).forEach(c => link(p, c)); wrap(cs.filter(c => !kids.has(c)), p); });
        wrap(G.ids.map((_, i) => i).filter(i => parent[i] < 0 && !kids.has(i)), -1);
        window.dagre.layout(g);
        cy.batch(() => G.ids.forEach(id => { const d = g.node(id), b = box.get(id); cy.getElementById(id).position({ x: d.x, y: d.y - b.below / 2 }); }));
    }
    // A line in the diagram leaves its resource heading right and arrives heading right, unless straight
    // lines are chosen or its ends are in one column.
    function drawDiagramLines() {
        // Past STRAIGHT_ABOVE resources every line is straight, as in the network, to keep the drawing quick.
        const edges = graphEdges(), par = parallelOffsets(), large = G.ids.length > STRAIGHT_ABOVE, plain = V.lines === 'straight' || large;
        edges.removeClass('lay faint straight curved cross').removeData('cpw cpd _first bend');
        cy.batch(() => edges.not('.ctx').forEach(e => {
            const P = e.source().position(), Q = e.target().position(), mx = (P.x + Q.x) / 2;
            if (plain || Math.abs(Q.x - P.x) < DIAGRAM_RANK_GAP / 2 || !toCurve(e, [{ x: mx, y: P.y }, { x: mx, y: Q.y }], par.get(e.id()))) { e.addClass(large ? 'faint straight' : 'faint cross'); return; }
            e.addClass('curved');
        }));
        fixSign('.curved');
        PATHS.clear();
        flowReset();
    }

    // Known resources go where they were. New ones start beside their placed neighbours and are settled by
    // fcose with every known resource fixed (fixedNodeConstraint), so nothing else moves. This pass runs
    // without the groups: fcose holds the fixed nodes either way, but with groups it pushes newcomers two to
    // seven times further from their neighbours. Ids are hashes, so a stale arrangement is unlikely to share
    // ids with this graph; still, unless at least half the resources are known (guard), it starts over.
    function restore(pos, guard) {
        const nodes = graphNodes(), fresh = [];
        const known = nodes.filter(n => pos.has(n.id())).length;
        if (guard && known < nodes.length / 2) return null;
        nodes.forEach(n => { const p = pos.get(n.id()); if (p) n.position({ x: p[0], y: p[1] }); else fresh.push(n); });
        if (fresh.length && known) {
            const freshIds = new Set(fresh.map(n => n.id())), placed = new Set(), d = span;
            const bb = nodes.filter(o => !freshIds.has(o.id())).boundingBox();
            const ready = o => !freshIds.has(o.id()) || placed.has(o.id());
            fresh.sort((a, b) => (a.id() < b.id() ? -1 : 1)).forEach((n, k) => {
                // Beside its neighbours, leaving out hubs while it has others; with none at all, beside its group.
                const by = es => es.connectedNodes().not(TEMP).filter(o => o.id() !== n.id() && ready(o));
                let near = by(n.connectedEdges().not('.ctx'));
                if (!near.length) near = by(n.connectedEdges());
                if (!near.length) near = nodes.filter(o => o.id() !== n.id() && o.data('c') === n.data('c') && ready(o));
                const c = near.length ? near.reduce((s, o) => ({ x: s.x + o.position().x / near.length, y: s.y + o.position().y / near.length }), { x: 0, y: 0 }) : { x: bb.x2 + 60, y: bb.y1 + 40 * k };
                n.position({ x: c.x + 40 * Math.cos(k * 2.4), y: c.y + 40 * Math.sin(k * 2.4) });
                placed.add(n.id());
            });
            const fixed = nodes.filter(n => !freshIds.has(n.id())).map(n => ({ nodeId: n.id(), position: { ...n.position() } }));
            graphEdges().addClass('lay');
            A.withSeed(11, () => cy.layout({ name: 'fcose', eles: graphNodes().union(graphEdges().not('.ctx')), quality: 'proof', randomize: false, animate: false, fit: false, packComponents: false, nodeDimensionsIncludeLabels: false,
                numIter: 2500, nodeSeparation: 60, nodeRepulsion: n => 4500 + 260 * d(n), idealEdgeLength: e => 30 + (d(e.source()) + d(e.target())) / 2, fixedNodeConstraint: fixed }).run());
        }
        return { fresh: fresh.map(n => n.id()) };
    }

    // ---- lines ------------------------------------------------------------------------------------------
    function parallelOffsets() {
        const groups = new Map(), off = new Map();
        graphEdges().forEach(e => { const a = e.source().id(), b = e.target().id(), k = a < b ? a + '|' + b : b + '|' + a; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(e); });
        groups.forEach(es => {
            if (es.length < 2) return;
            es.sort((x, y) => (x.id() < y.id() ? -1 : 1)).forEach((e, k) => {
                const forward = e.source().id() < e.target().id();
                off.set(e.id(), (forward ? 1 : -1) * (k - (es.length - 1) / 2) * PAR_GAP);
            });
        });
        return off;
    }
    // A line is drawn through its bend points only if they come in order along it (one behind the last made
    // the line double back) and it nowhere turns too sharply; otherwise the caller straightens it further.
    function toCurve(e, pts, off) {
        const p = e.source().position(), q = e.target().position(), vx = q.x - p.x, vy = q.y - p.y, L2 = vx * vx + vy * vy || 1, L = Math.sqrt(L2);
        const r = n => (n.hasClass('pill') ? n.data('h') : n.data('w')) / 2 + 3, rs = r(e.source()), rt = r(e.target());
        let last = 0.02;
        const inner = pts.filter(P => {
            if (Math.hypot(P.x - p.x, P.y - p.y) <= rs || Math.hypot(P.x - q.x, P.y - q.y) <= rt) return false;
            const w = ((P.x - p.x) * vx + (P.y - p.y) * vy) / L2;
            if (w <= last || w >= 0.98) return false;
            last = w; return true;
        });
        if (!inner.length || L < 1) return false;
        // A lane's offset moves every point sideways. A curve with one bend point bows half as far as the
        // point, so there the offset is doubled.
        const o = (off || 0) * (inner.length === 1 ? 2 : 1), lane = s => inner.map(P => ({ x: P.x - vy / L * s, y: P.y + vx / L * s }));
        if (Math.max(...(o ? [o, -o] : [0]).map(s => sharpTurn(p, lane(s), q, rs, rt))) > SHARP_CHECK) return false;
        const raw = inner.map(P => ((P.y - p.y) * vx - (P.x - p.x) * vy) / L);
        e.data({ cpw: inner.map(P => (((P.x - p.x) * vx + (P.y - p.y) * vy) / L2).toFixed(4)).join(' '), cpd: raw.map(v => (v + o).toFixed(2)).join(' '), bend: Math.max(...raw.map(Math.abs)) });
        if (!off) e.data('_first', inner[0]);   // a lane's offset moves its points, so it cannot check the sign
        return true;
    }
    // Cytoscape's sign for a control-point distance is checked once against where it puts the point.
    function fixSign(sel) {
        const probe = cy.edges(sel).filter(e => e.data('_first') && Math.abs(parseFloat(String(e.data('cpd')).split(' ')[0])) > 3)[0];
        if (!probe) return;
        const cp = (probe.controlPoints() || [])[0], want = probe.data('_first');
        if (cp && Math.hypot(cp.x - want.x, cp.y - want.y) > 2) cy.batch(() => cy.edges(sel).forEach(e => e.data('cpd', String(e.data('cpd')).split(' ').map(v => (-parseFloat(v)).toFixed(2)).join(' '))));
    }
    function drawLines(comm, member) {
        if (current && current.diagram) { drawDiagramLines(); return; }
        const edges = graphEdges();
        edges.removeClass('lay faint straight curved cross').removeData('cpw cpd _first bend');
        // Straight lines, if chosen: every line straight, and those between groups drawn as strongly as the
        // curved bundles would be, so the structure still shows.
        const plain = V.lines === 'straight';
        const sup = A.superClusters(G, comm);
        // Group centres and spreads, for the trunks between groups.
        const cen = new Map(), clustersIn = new Map(), scen = new Map();
        const add = (m, k, p) => { if (!m.has(k)) m.set(k, { x: 0, y: 0, n: 0 }); const a = m.get(k); a.x += p.x; a.y += p.y; a.n++; };
        graphNodes().forEach(n => { if (!member.has(n.id())) return; const c = n.data('c'), p = n.position(); add(cen, c, p); add(scen, sup[c], p); if (!clustersIn.has(sup[c])) clustersIn.set(sup[c], new Set()); clustersIn.get(sup[c]).add(c); });
        [cen, scen].forEach(m => m.forEach(a => { a.x /= a.n; a.y /= a.n; }));
        const rad = new Map();
        graphNodes().forEach(n => { if (!member.has(n.id())) return; const c = n.data('c'), p = n.position(), C = cen.get(c); rad.set(c, (rad.get(c) || 0) + Math.hypot(p.x - C.x, p.y - C.y) / C.n); });
        const gate = (c, toward) => { const C = cen.get(c), d = Math.hypot(toward.x - C.x, toward.y - C.y) || 1, k = Math.min(0.45, 0.55 * (rad.get(c) || 0) / d); return { x: C.x + (toward.x - C.x) * k, y: C.y + (toward.y - C.y) * k }; };
        // Fans: at every resource, its lines grouped by direction. A direction holding FAN_MIN lines or more
        // leaves the resource as one trunk and splits towards each end, like a palm frond.
        const fanOf = new Map();
        if (!plain) graphNodes().forEach(h => {
            const es = h.connectedEdges().not(TEMP).not('.ctx').filter(e => e.source().id() !== e.target().id());
            if (es.length < FAN_MIN) return;
            const hp = h.position();
            const items = es.map(e => { const o = e.source().same(h) ? e.target() : e.source(), p = o.position(); return { e, ang: Math.atan2(p.y - hp.y, p.x - hp.x), len: Math.hypot(p.x - hp.x, p.y - hp.y) }; }).sort((a, b) => a.ang - b.ang);
            const n = items.length, gaps = items.map((it, k) => { const nx = items[(k + 1) % n]; let g = nx.ang - it.ang; if (k === n - 1) g += 2 * Math.PI; return g; });
            const start = gaps.findIndex(g => g > FAN_GAP);
            if (start < 0) return;                       // lines all round: no direction to bundle
            let group = [];
            const fan = g => {
                if (g.length < FAN_MIN) return;
                let sx = 0, sy = 0; g.forEach(it => { sx += Math.cos(it.ang); sy += Math.sin(it.ang); });
                const ang = Math.atan2(sy, sx), d = FAN_TRUNK * Math.min(...g.map(it => it.len));
                g.forEach(it => { const prev = fanOf.get(it.e.id()); if (!prev || prev.weight < g.length) fanOf.set(it.e.id(), { hub: h.id(), weight: g.length, a: { x: hp.x + Math.cos(ang) * d * 0.45, y: hp.y + Math.sin(ang) * d * 0.45 }, b: { x: hp.x + Math.cos(ang) * d, y: hp.y + Math.sin(ang) * d } }); });
            };
            // A run of lines with small gaps between them can still spread wide, so a run is cut into fans no
            // wider than FAN_SPAN; a trunk down the middle of a wide spread sends the outer lines back.
            const flush = () => {
                const rel = group.map(it => (it.ang - group[0].ang + 2 * Math.PI) % (2 * Math.PI));
                for (let i = 0; i < group.length;) { let j = i; while (j + 1 < group.length && rel[j + 1] - rel[i] <= FAN_SPAN) j++; fan(group.slice(i, j + 1)); i = j + 1; }
                group = [];
            };
            for (let k = 1; k <= n; k++) { const idx = (start + k) % n; group.push(items[idx]); if (gaps[idx] > FAN_GAP) flush(); }
            flush();
        });
        const par = parallelOffsets(), straight = G.ids.length > STRAIGHT_ABOVE;
        cy.batch(() => edges.not('.ctx').forEach(e => {
            const sN = e.source(), tN = e.target(), cs = sN.data('c'), ct = tN.data('c');
            const P = sN.position(), Q = tN.position();
            const ms = member.has(sN.id()) && cen.has(cs), mt = member.has(tN.id()) && cen.has(ct);
            if (plain) { e.addClass(straight ? 'faint straight' : 'faint'); if (ms && mt && cs !== ct) e.addClass('cross'); return; }
            let path = null;
            if (ms && mt && cs !== ct) {
                const ss = sup[cs], st = sup[ct], viaSup = ss !== st && clustersIn.get(ss).size > 1 && clustersIn.get(st).size > 1;
                path = [P, gate(cs, viaSup ? scen.get(ss) : cen.get(ct))];
                if (viaSup) path.push(scen.get(ss), scen.get(st));
                path.push(gate(ct, viaSup ? scen.get(st) : cen.get(cs)), Q);
            } else if (fanOf.has(e.id())) {
                const f = fanOf.get(e.id());
                path = f.hub === sN.id() ? [P, f.a, f.b, Q] : [P, f.b, f.a, Q];
            }
            // Straightened further if it turns too sharply, then drawn straight.
            if (!path || ![BETA, 0.6, 0.3].some(b => toCurve(e, A.straighten(path, b).slice(1, -1), par.get(e.id())))) { e.addClass(straight ? 'faint straight' : 'faint'); return; }
            e.addClass('curved');
        }));
        fixSign('.curved');
        PATHS.clear();
        flowReset();
    }

    // ---- where a shared resource goes -----------------------------------------------------------------------
    // A resource that floats between its groups is placed by rule rather than left where the layout's springs
    // put it: between two groups, in the middle of the gap between their outlines; among three or more, at the
    // point as near the farthest of them as it can be. It is then moved clear of other resources and of groups
    // it is not in, and anything hanging off it alone moves with it. One placed by hand stays where it was
    // left, until Rearrange. tol: a move shorter than this is not made; a saved arrangement was stored in whole
    // pixels, so its outlines come back a fraction off, and putting it back must not shift anything.
    function privateLeaves(id, member, float) {
        return cy.getElementById(id).neighborhood('node').not(TEMP).filter(o => !member.has(o.id()) && !float.has(o.id()) && o.neighborhood('node').not(TEMP).every(q => q.id() === id));
    }
    function placeFloaters(comm, member, tol = 0.5) {
        if (!MULTI.length || (current && current.diagram)) return;   // the diagram places a shared resource itself
        const float = new Set(MULTI.filter(m => floats(m.i, comm)).map(m => m.id));
        const list = MULTI.filter(m => float.has(m.id) && !MOVED.has(m.id));
        const pts = new Map();
        cy.nodes('.dot').forEach(n => { if (!member.has(n.id())) return; const k = comm[G.index.get(n.id())], p = n.position(), r = n.data('w') / 2 + FLOAT_PAD;
            if (!pts.has(k)) pts.set(k, []); for (let a = 0; a < 8; a++) pts.get(k).push({ x: p.x + r * Math.cos(a * Math.PI / 4), y: p.y + r * Math.sin(a * Math.PI / 4) }); });
        const hulls = new Map([...pts].map(([k, p]) => [k, hull(p)]));
        const leaves = id => privateLeaves(id, member, float);
        const away = new Set(list.map(m => m.id));
        list.forEach(m => leaves(m.id).forEach(o => away.add(o.id())));
        const others = cy.nodes('.dot').filter(n => !away.has(n.id()));
        list.slice().sort((a, b) => (a.id < b.id ? -1 : 1)).forEach(m => {
            const n = cy.getElementById(m.id), w = n.data('w'), clear = w / 2 + 6;
            const hs = m.groups.map(k => hulls.get(k)).filter(h => h && h.length >= 3);
            if (!hs.length) return;
            let x, axis = null;
            if (hs.length === 1) { const q = nearestOnPoly(hs[0], n.position()), d = Math.hypot(n.position().x - q.x, n.position().y - q.y) || 1; x = inPoly(hs[0], n.position()) ? q : { x: q.x + (n.position().x - q.x) / d * clear, y: q.y + (n.position().y - q.y) / d * clear }; }
            else if (hs.length === 2) {
                const c = closestPair(hs[0], hs[1]);
                x = { x: (c.a.x + c.b.x) / 2, y: (c.a.y + c.b.y) / 2 }; axis = { x: c.b.x - c.a.x, y: c.b.y - c.a.y };
            } else {
                // Badoiu and Clarkson's step towards whichever outline is farthest, so the arms come out even.
                x = hs.reduce((s, h) => { const c = h.reduce((t, q) => ({ x: t.x + q.x / h.length, y: t.y + q.y / h.length }), { x: 0, y: 0 }); return { x: s.x + c.x / hs.length, y: s.y + c.y / hs.length }; }, { x: 0, y: 0 });
                for (let it = 0; it < 300; it++) {
                    let far = null, fd = -1;
                    hs.forEach(h => { const q = inPoly(h, x) ? x : nearestOnPoly(h, x), d = Math.hypot(x.x - q.x, x.y - q.y); if (d > fd) { fd = d; far = q; } });
                    x = { x: x.x + (far.x - x.x) / (it + 2), y: x.y + (far.y - x.y) / (it + 2) };
                }
                for (let pass = 0; pass < 6; pass++) {
                    const h = hs.find(hh => inPoly(hh, x));
                    if (!h) break;
                    const q = nearestOnPoly(h, x), d = Math.hypot(q.x - x.x, q.y - x.y) || 1;
                    x = { x: q.x + (q.x - x.x) / d * clear, y: q.y + (q.y - x.y) / d * clear };
                }
            }
            // Clear of other resources and their names, and outside groups it is not in: the first free spot
            // stepping sideways across the gap, then round in a widening circle.
            const mine = new Set(m.groups), foreign = [...hulls].filter(([k, h]) => !mine.has(k) && h.length >= 3).map(([, h]) => h);
            const foot = (o, p) => { const ow = o.data('w'), fs = o.data('fs') || 10, tw = textW(o.data('short') || '', fs, 600) / 2;
                return { x1: p.x - Math.max(ow / 2, tw) - 4, x2: p.x + Math.max(ow / 2, tw) + 4, y1: p.y - ow / 2 - 4, y2: p.y + ow / 2 + 4 + fs * 1.3 }; };
            const hit = (a, b) => a.x1 < b.x2 && a.x2 > b.x1 && a.y1 < b.y2 && a.y2 > b.y1;
            const free = p => { const f = foot(n, p);
                return !foreign.some(h => inPoly(h, p) || (() => { const q = nearestOnPoly(h, p); return Math.hypot(p.x - q.x, p.y - q.y) < clear; })())
                    && !others.some(o => hit(f, foot(o, o.position()))); };
            const step = w + 10, al = axis ? Math.hypot(axis.x, axis.y) || 1 : 1, side = axis ? { x: -axis.y / al, y: axis.x / al } : { x: 1, y: 0 };
            const tries = [x];
            for (let k = 1; k <= 6; k++) tries.push({ x: x.x + side.x * step * k, y: x.y + side.y * step * k }, { x: x.x - side.x * step * k, y: x.y - side.y * step * k });
            for (let k = 0; k < 24; k++) { const a = k * 2.4, r = step * (1 + k / 6); tries.push({ x: x.x + r * Math.cos(a), y: x.y + r * Math.sin(a) }); }
            const at = tries.find(free) || x, p0 = n.position(), dx = at.x - p0.x, dy = at.y - p0.y;
            if (Math.hypot(dx, dy) < tol) return;
            n.position(at); leaves(m.id).forEach(o => o.position({ x: o.position().x + dx, y: o.position().y + dy }));
            others.merge(n);
        });
        // A resource that heads a section of its own stays in it, at the corner of its section's outline
        // nearest its other groups, so it is still where its groups meet.
        MULTI.filter(m => !float.has(m.id) && !MOVED.has(m.id) && member.has(m.id)).forEach(m => {
            const n = cy.getElementById(m.id), k0 = comm[m.i], own = [];
            cy.nodes('.dot').forEach(o => { if (o.id() === m.id || !member.has(o.id()) || comm[G.index.get(o.id())] !== k0) return; const p = o.position(), r = o.data('w') / 2 + FLOAT_PAD; for (let a = 0; a < 8; a++) own.push({ x: p.x + r * Math.cos(a * Math.PI / 4), y: p.y + r * Math.sin(a * Math.PI / 4) }); });
            const oh = own.length >= 24 ? hull(own) : null, hs = m.groups.slice(1).map(k => hulls.get(k)).filter(h => h && h.length >= 3);
            if (!oh || !hs.length) return;
            const far = p => Math.max(...hs.map(h => { const q = nearestOnPoly(h, p); return Math.hypot(p.x - q.x, p.y - q.y); }));
            const v = oh.reduce((a, b) => (far(b) < far(a) ? b : a)), mem = others.filter(o => o.id() !== m.id && member.has(o.id()) && comm[G.index.get(o.id())] === k0).toArray();
            if (!mem.length) return;
            const o = mem.reduce((a, b) => (Math.hypot(b.position().x - v.x, b.position().y - v.y) < Math.hypot(a.position().x - v.x, a.position().y - v.y) ? b : a)), op = o.position();
            const d = Math.hypot(v.x - op.x, v.y - op.y) || 1, gap = (n.data('w') + o.data('w')) / 2 + 10, at = { x: op.x + (v.x - op.x) / d * gap, y: op.y + (v.y - op.y) / d * gap };
            if (others.some(q => q.id() !== m.id && Math.hypot(q.position().x - at.x, q.position().y - at.y) < (n.data('w') + q.data('w')) / 2 + 8)) return;
            const p0 = n.position(), dx = at.x - p0.x, dy = at.y - p0.y;
            if (Math.hypot(dx, dy) < tol) return;
            n.position(at); leaves(m.id).forEach(q => q.position({ x: q.position().x + dx, y: q.position().y + dy }));
        });
    }

    // ---- outlines ---------------------------------------------------------------------------------------------
    // Outline shapes depend only on the positions and, through their padding, a little on the zoom. They are
    // worked out once per arrangement and zoom step and redrawn from memory; while a zoom is under way the
    // shapes from the last step are reused, and worked out again once it settles.
    function hullShapes(z) {
        const step = Math.round(Math.log2(Math.max(z, 0.35)) * 4), key = posVersion + '|' + step;
        if (hullCache.key === key) return hullCache.groups;
        if (hullCache.pos === posVersion && hullCache.groups) {
            clearTimeout(hullTimer);
            hullTimer = setTimeout(() => { hullTimer = 0; hullCache.pos = -1; drawHulls(); }, 180);
            return hullCache.groups;
        }
        const zz = Math.max(Math.pow(2, step / 4), 0.35), groups = new Map(), member = current ? current.member : null;
        cy.nodes('.dot').forEach(n => { if (!member || !member.has(n.id()) || !shown(n)) return; const k = n.data('c'); if (!groups.has(k)) groups.set(k, { ns: [] }); groups.get(k).ns.push(n); });
        groups.forEach(g => {
            if (g.ns.length < 3) return;
            const pts = [];
            let x1 = Infinity, x2 = -Infinity, y1 = Infinity;
            g.ns.forEach(n => { const p = n.position(), r = n.data('w') / 2 + 14 / zz; x1 = Math.min(x1, p.x); x2 = Math.max(x2, p.x); y1 = Math.min(y1, p.y - n.data('h') / 2);
                if (n.hasClass('pill')) { const ry = n.data('h') / 2 + 14 / zz; [[-1, -1], [0, -1], [1, -1], [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0]].forEach(([a, b]) => pts.push({ x: p.x + a * r, y: p.y + b * ry })); return; }
                for (let a = 0; a < 8; a++) pts.push({ x: p.x + r * Math.cos(a * Math.PI / 4), y: p.y + r * Math.sin(a * Math.PI / 4) }); });
            const hp = hull(pts), path = new Path2D();
            roundedInto(path, hp);
            g.path = path; g.hp = hp; g.box = { x1, x2, y1 };
            g.hub = g.ns.reduce((a, b) => ((b.data('imp') || 0) > (a.data('imp') || 0) ? b : a));
        });
        // Bridge: each group a resource belongs to reaches out to it with an arm, wide at the resource and a
        // little narrower where it joins the outline. Hull and arms are one path, filled once, so the overlap
        // is not drawn twice as dark. Past ARM_LONG there is no arm, only a dashed line in the group's colour.
        MULTI.forEach(m => {
            const n = cy.getElementById(m.id);
            if (!n.length || !shown(n)) return;
            const home = member && member.has(m.id);
            const p = n.position(), r = n.data('w') / 2 + (home ? 5 : 14) / zz, rr = r * 0.8;
            m.groups.forEach(k => {
                const g = groups.get(k);
                if (!g || !g.hp || inPoly(g.hp, p)) return;
                const q = nearestOnPoly(g.hp, p), cx = g.hp.reduce((s, a) => s + a.x, 0) / g.hp.length, cyy = g.hp.reduce((s, a) => s + a.y, 0) / g.hp.length;
                const d = Math.hypot(cx - q.x, cyy - q.y) || 1, b = { x: q.x + (cx - q.x) / d * rr, y: q.y + (cyy - q.y) / d * rr };
                const len = Math.hypot(p.x - q.x, p.y - q.y), w2 = home ? Math.max(3, r * 0.22) : rr;
                if (len + 14 / zz - FLOAT_PAD > ARM_LONG) { (g.links = g.links || []).push({ len, p, q, r }); return; }
                const circle = (c, rad) => Array.from({ length: 12 }, (_, a) => ({ x: c.x + rad * Math.cos(a * Math.PI / 6), y: c.y + rad * Math.sin(a * Math.PI / 6) }));
                const parts = home ? [hull(circle(p, r)), hull(circle(p, w2).concat(circle(b, w2)))] : [hull(circle(p, r).concat(circle(b, rr)))];
                (g.arms = g.arms || []).push(parts);
            });
        });
        groups.forEach(g => { if (!g.arms || !g.path) return; const u = new Path2D(g.path); g.arms.forEach(parts => parts.forEach(hp => roundedInto(u, hp))); g.union = u; });
        hullCache = { key, pos: posVersion, groups };
        return groups;
    }
    // The groups to light up: everything the selected resource belongs to.
    function selectedGroups() {
        if (!selectedId || !current || !G.index.has(selectedId)) return null;
        const i = G.index.get(selectedId);
        return new Set([current.comm[i], ...otherGroups(i, current.comm)]);
    }
    function sizeCanvas(c) {
        const dpr = window.devicePixelRatio || 1, w = cy.width(), h = cy.height();
        if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); c.style.width = w + 'px'; c.style.height = h + 'px'; }
        const ctx = c.getContext('2d');
        ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.clearRect(0, 0, c.width, c.height);
        return { ctx, dpr, w, h };
    }
    function drawHulls() {
        if (!cy || destroyed) return;
        const { ctx, dpr, w, h } = sizeCanvas(hullCanvas);
        if (!current || current.diagram) return;   // the diagram's columns show its structure, not outlines
        const z = cy.zoom(), pan = cy.pan(), pal = palette();
        ctx.setTransform(dpr * z, 0, 0, dpr * z, dpr * pan.x, dpr * pan.y);
        const shapes = hullShapes(z);
        const fillA = opts.theme() === 'light' ? 0.12 : 0.09, lit = selectedGroups();
        shapes.forEach((g, k) => {
            if (!g.path) return;
            const col = colourOf(k), on = lit && lit.has(k), lw = (on ? 2.4 : 1.2) / z, sa = on ? 0.9 : 0.35;
            ctx.globalAlpha = on ? fillA * 1.6 : fillA; ctx.fillStyle = col; ctx.fill(g.union || g.path);
            if (g.links) {
                ctx.globalAlpha = on ? 0.95 : 0.6; ctx.strokeStyle = col; ctx.lineWidth = (on ? 2.4 : 1.6) / z; ctx.setLineDash([7 / z, 5 / z]);
                ctx.beginPath();
                g.links.forEach(l => { const d = l.len || 1, e = { x: l.p.x + (l.q.x - l.p.x) / d * l.r, y: l.p.y + (l.q.y - l.p.y) / d * l.r }; ctx.moveTo(l.q.x, l.q.y); ctx.lineTo(e.x, e.y); });
                ctx.stroke(); ctx.setLineDash([]);
            }
            // While the view moves, a group with arms is stroked round its hull only; the joined outline takes a
            // full-canvas pass per group, so it is drawn once the view settles.
            if (!g.union || viewMoving) { ctx.lineWidth = lw; ctx.globalAlpha = sa; ctx.strokeStyle = col; ctx.stroke(g.path); return; }
            // Stroked as one outline: on a scratch canvas, stroke hull and arms, then cut away their inside, so
            // the hull's edge does not run across the root of an arm.
            if (!scratch) scratch = document.createElement('canvas');
            if (scratch.width !== hullCanvas.width || scratch.height !== hullCanvas.height) { scratch.width = hullCanvas.width; scratch.height = hullCanvas.height; }
            const s2 = scratch.getContext('2d');
            s2.setTransform(1, 0, 0, 1, 0, 0); s2.clearRect(0, 0, scratch.width, scratch.height);
            s2.setTransform(dpr * z, 0, 0, dpr * z, dpr * pan.x, dpr * pan.y);
            s2.lineWidth = 2 * lw; s2.strokeStyle = col; s2.lineJoin = 'round'; s2.stroke(g.union);
            s2.globalCompositeOperation = 'destination-out'; s2.fill(g.union); s2.globalCompositeOperation = 'source-over';
            ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = sa; ctx.drawImage(scratch, 0, 0); ctx.restore();
        });
        ctx.globalAlpha = 1;
        // Group names, as Cytoscape's AutoAnnotate labels clusters: the group's most important resource,
        // written on its outline while that resource's own name is too small to draw, clear of other names.
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.font = '700 12px Manrope, Arial, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'bottom'; ctx.lineJoin = 'round';
        const placed = [], named = [];
        NAMED.forEach(id => {
            const n = cy.getElementById(id);
            if (!n.length) return;
            const p = n.renderedPosition(), px = labelPx(n, z), tw = textW(n.data('short') || '', px, 600), top = p.y + n.renderedHeight() / 2 + Math.min(3 * z, 4);
            named.push({ x1: p.x - tw / 2 - 2, x2: p.x + tw / 2 + 2, y1: top, y2: top + px * 1.25 });
        });
        [...shapes.entries()].filter(([, g]) => g.path).sort((a, b) => b[1].ns.length - a[1].ns.length).forEach(([k, g]) => {
            const hub = g.hub;
            if (NAMED.has(hub.id())) return;
            const x1 = g.box.x1 * z + pan.x, x2 = g.box.x2 * z + pan.x, y1 = g.box.y1 * z + pan.y;
            if (x2 < -100 || x1 > w + 100 || y1 < -20 || y1 > h + 20) return;
            const text = hub.data('short') || '', tw = textW(text, 12, 700), cx = (x1 + x2) / 2, top = y1 - 8;
            const r = { x1: cx - tw / 2 - 3, x2: cx + tw / 2 + 3, y1: top - 15, y2: top };
            if (r.x2 < 0 || r.x1 > w || r.y2 < 0 || r.y1 > h || placed.concat(named).some(o => r.x1 < o.x2 && r.x2 > o.x1 && r.y1 < o.y2 && r.y2 > o.y1)) return;
            placed.push(r);
            ctx.lineWidth = 3; ctx.strokeStyle = pal.outline; ctx.strokeText(text, cx, top);
            ctx.fillStyle = opts.theme() === 'light' ? pal.label : colourOf(k); ctx.fillText(text, cx, top);
        });
    }

    // ---- names --------------------------------------------------------------------------------------------
    // Which names to draw: every name that renders at 10 px or more, most important first, skipping any that
    // would overlap a name already placed.
    const hiddenName = n => n.hasClass('dim') || n.hasClass('tut-dim') || !shown(n);
    function shownLabels(z, pan, w, h) {
        const cell = 48, grid = new Map(), names = new Set();
        const nodes = cy.nodes('.dot, .agg').toArray().sort((a, b) => (b.data('imp') || 0) - (a.data('imp') || 0) || (a.id() < b.id() ? -1 : 1));
        // A shut group's name goes where it covers no other group's dot and stands a little apart from the
        // other names; where groups are packed, most go unnamed until the view comes closer, like towns on
        // a map. Names under every dot of a packed overview read as one mass of text.
        const dots = new Map();
        cy.nodes('.agg').forEach(n => {
            const p = n.position(), x = p.x * z + pan.x, y = p.y * z + pan.y, r = n.data('w') * z / 2;
            if (x < -r - 50 || y < -r - 50 || x > w + r + 50 || y > h + r + 50) return;
            const c = { n, x, y, r };
            for (let gx = Math.floor((x - r) / cell); gx <= Math.floor((x + r) / cell); gx++) for (let gy = Math.floor((y - r) / cell); gy <= Math.floor((y + r) / cell); gy++) { const k = gx + ',' + gy; if (!dots.has(k)) dots.set(k, []); dots.get(k).push(c); }
        });
        const coversDot = (rect, own) => {
            for (let gx = Math.floor(rect.x1 / cell); gx <= Math.floor(rect.x2 / cell); gx++) for (let gy = Math.floor(rect.y1 / cell); gy <= Math.floor(rect.y2 / cell); gy++) {
                for (const c of dots.get(gx + ',' + gy) || []) {
                    if (c.n === own) continue;
                    const dx = c.x - Math.max(rect.x1, Math.min(c.x, rect.x2)), dy = c.y - Math.max(rect.y1, Math.min(c.y, rect.y2));
                    if (dx * dx + dy * dy < c.r * c.r) return true;
                }
            }
            return false;
        };
        const hit = r => {
            for (let gx = Math.floor(r.x1 / cell); gx <= Math.floor(r.x2 / cell); gx++) for (let gy = Math.floor(r.y1 / cell); gy <= Math.floor(r.y2 / cell); gy++) {
                for (const o of grid.get(gx + ',' + gy) || []) if (r.x1 < o.x2 && r.x2 > o.x1 && r.y1 < o.y2 && r.y2 > o.y1) return true;
            }
            return false;
        };
        const put = r => { for (let gx = Math.floor(r.x1 / cell); gx <= Math.floor(r.x2 / cell); gx++) for (let gy = Math.floor(r.y1 / cell); gy <= Math.floor(r.y2 / cell); gy++) { const k = gx + ',' + gy; if (!grid.has(k)) grid.set(k, []); grid.get(k).push(r); } };
        for (const n of nodes) {
            if (n.hasClass('pill') || n.data('fs') * z < MIN_LABEL_PX || !shown(n)) continue;
            const px = labelPx(n, z), p = n.position(), x = p.x * z + pan.x, y = p.y * z + pan.y;
            if (x < -50 || y < -50 || x > w + 50 || y > h + 50) continue;
            const tw = textW(n.data('short') || '', px, 600), top = y + n.data('h') * z / 2 + Math.min(3 * z, 4);
            const r = { x1: x - tw / 2 - 2, x2: x + tw / 2 + 2, y1: top, y2: top + px * 1.25 };
            const group = n.hasClass('agg');
            if (hit(r) || (group && coversDot(r, n))) continue;
            put(group ? { x1: r.x1 - GROUP_LABEL_GAP, x2: r.x2 + GROUP_LABEL_GAP, y1: r.y1 - GROUP_LABEL_GAP / 2, y2: r.y2 + GROUP_LABEL_GAP / 2 } : r);
            names.add(n.id());
        }
        return names;
    }
    // Like towns on a map: the most important resources get names big enough to read when the whole network
    // is in view (at most three times their usual size); everything else names itself on zoom.
    function boostHubLabels() {
        if (pills()) return;   // a pill's name is inside it, at the pill's own size
        const bb = graphNodes().boundingBox({ includeLabels: false });
        const z = Math.min(FIT_MAX_ZOOM, (cy.width() - 2 * PAD) / Math.max(1, bb.w), (cy.height() - 2 * PAD) / Math.max(1, bb.h));
        const nodes = cy.nodes('.dot').toArray().sort((a, b) => b.data('imp') - a.data('imp'));
        const k = Math.min(nodes.length, 6 + Math.round(nodes.length / 40));
        cy.batch(() => nodes.slice(0, k).forEach(n => { const fs = n.data('fs'); n.data('fs', Math.min(fs * 3, Math.max(fs, MIN_LABEL_PX * 1.1 / Math.max(z, 1e-3)))); }));
    }
    function applyLod() {
        if (!cy || destroyed) return;
        const z = cy.zoom();
        NAMED = shownLabels(z, cy.pan(), cy.width(), cy.height());
        // A type icon on a dot under ICON_MIN_PX cannot be made out, so small dots go without. Only nodes whose
        // state changes are touched: every class change makes Cytoscape restyle the node.
        cy.batch(() => cy.nodes('.dot').forEach(n => { const small = (n.hasClass('pill') ? n.data('h') : n.data('w')) * z < ICON_MIN_PX; if (small !== n.hasClass('noicon')) n.toggleClass('noicon', small); }));
        cy.edges('.hl, .hov, :selected').updateStyle();   // relation labels are sized from the zoom
        drawLabels();
    }
    // Names on their own canvas, in screen pixels, under the dot like Cytoscape would place them. A resource
    // in several groups wears a ring split into its groups' colours.
    // The icons inside the dots. Cytoscape's WebGL renderer paints a node into a texture once and stretches it as
    // the view zooms in, which blurs an icon, so the dots are plain circles and their icons are drawn here instead.
    // Each icon is drawn once for each size it appears at on screen, in device pixels, into a small canvas that every
    // dot showing it then copies, so it is sharp at any zoom and costs one copy a dot.
    const iconImages = new Map(), iconBitmaps = new Map();
    function iconBitmap(src, devPx) {
        let img = iconImages.get(src);
        if (!img) {
            if (iconImages.size > 500) { iconImages.clear(); iconBitmaps.clear(); }
            img = new Image(); img.onload = redrawSoon; img.src = src; iconImages.set(src, img);
        }
        if (!img.complete || !img.naturalWidth) return null;
        const size = Math.max(8, Math.min(256, Math.ceil(devPx / 4) * 4)), k = size + '|' + src;
        let c = iconBitmaps.get(k);
        if (!c) {
            if (iconBitmaps.size > 2000) iconBitmaps.clear();
            c = document.createElement('canvas'); c.width = c.height = size;
            c.getContext('2d').drawImage(img, 0, 0, size, size);
            iconBitmaps.set(k, c);
        }
        return c;
    }
    function drawDotIcons(ctx, z, pan, w, h, dpr) {
        cy.nodes('.dot').forEach(n => {
            if (n.hasClass('noicon') || !shown(n)) return;
            const src = n.data('icon');
            if (!src || src === 'none') return;
            const p = n.position(), x = p.x * z + pan.x, y = p.y * z + pan.y, s = n.data('w') * z * 0.58;
            if (x + s < 0 || x - s > w || y + s < 0 || y - s > h) return;
            const bmp = iconBitmap(src, s * dpr);
            if (!bmp) return;
            // As faint as the dot when a selection or the tutorial dims it.
            ctx.globalAlpha = 0.85 * (n.hasClass('dim') || n.hasClass('tut-dim') ? 0.18 : 1);
            ctx.drawImage(bmp, x - s / 2, y - s / 2, s, s);
        });
        ctx.globalAlpha = 1;
    }
    function drawLabels() {
        if (!cy || destroyed) return;
        const { ctx, dpr, w, h } = sizeCanvas(labelCanvas);
        if (!current) return;
        const z = cy.zoom(), pan = cy.pan(), pal = palette();
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        drawDotIcons(ctx, z, pan, w, h, dpr);
        MULTI.forEach(m => {
            const n = cy.getElementById(m.id);
            if (!n.length || hiddenName(n)) return;
            const p = n.position(), x = p.x * z + pan.x, y = p.y * z + pan.y, r = Math.max(4, n.data('w') * z / 2) + 3.5;
            if (x < -20 - r || x > w + 20 + r || y < -20 || y > h + 20) return;
            const k = m.groups.length, gap = k > 1 ? 0.18 : 0;
            ctx.lineWidth = 3; ctx.lineCap = 'round';
            if (n.hasClass('pill')) {
                // Round a pill the ring is its outline, in bands of its groups' colours from left to right.
                const hh = n.data('h') * z / 2 + 3.5, grad = ctx.createLinearGradient(x - r, 0, x + r, 0);
                m.groups.forEach((g, j) => { grad.addColorStop(j / k, colourOf(g)); grad.addColorStop((j + 1) / k, colourOf(g)); });
                ctx.strokeStyle = grad; ctx.beginPath(); ctx.roundRect(x - r, y - hh, 2 * r, 2 * hh, hh); ctx.stroke();
                ctx.lineCap = 'butt';
                return;
            }
            m.groups.forEach((g, j) => {
                const a0 = -Math.PI / 2 + j * 2 * Math.PI / k + gap / 2, a1 = a0 + 2 * Math.PI / k - gap;
                ctx.strokeStyle = colourOf(g); ctx.beginPath(); ctx.arc(x, y, r, a0, a1); ctx.stroke();
            });
            ctx.lineCap = 'butt';
        });
        if (pills()) drawPillNames(ctx, z, pan, w, h, pal);
        ctx.textAlign = 'center'; ctx.textBaseline = 'top'; ctx.lineJoin = 'round'; ctx.lineWidth = 3; ctx.strokeStyle = pal.outline; ctx.fillStyle = pal.label;
        NAMED.forEach(id => {
            const n = cy.getElementById(id);
            if (!n.length || hiddenName(n)) return;
            const p = n.position(), px = labelPx(n, z), x = p.x * z + pan.x, y = p.y * z + pan.y + n.data('h') * z / 2 + Math.min(3 * z, 4);
            if (x < -200 || x > w + 200 || y < -40 || y > h + 40) return;
            ctx.font = `600 ${px}px Manrope, Arial, sans-serif`;
            const t = n.data('short') || '';
            ctx.globalAlpha = 0.85; ctx.strokeText(t, x, y);
            ctx.globalAlpha = 1; ctx.fillText(t, x, y);
        });
    }
    // A pill's name, after its icon, in the dark ink the icons use; centred when the pill shows no icon.
    function drawPillNames(ctx, z, pan, w, h, pal) {
        ctx.textBaseline = 'middle'; ctx.fillStyle = pal.ink;
        cy.nodes('.pill').forEach(n => {
            const fs = n.data('fs'), px = fs * z;
            if (px < PILL_MIN_PX || hiddenName(n)) return;
            const p = n.position(), x = p.x * z + pan.x, y = p.y * z + pan.y, hw = n.data('w') * z / 2;
            if (x + hw < 0 || x - hw > w || y < -px || y > h + px) return;
            ctx.font = `600 ${px}px Manrope, Arial, sans-serif`;
            const icon = n.data('icon') !== 'none' && !n.hasClass('noicon');
            ctx.textAlign = icon ? 'left' : 'center';
            ctx.fillText(n.data('pt') || '', icon ? x - hw + fs * (PILL_PAD + PILL_ICON) * z : x, y);
        });
    }
    // While the view moves, names and outlines are brought up to date at most LOD_MS apart, and once more when
    // it stops. On a large graph only once it stops: there the pass takes a few milliseconds.
    const LOD_MS = 120;
    function scheduleLod() {
        const now = performance.now(), run = () => { lodLast = performance.now(); lodTimer = 0; applyLod(); drawHulls(); };
        clearTimeout(lodTimer);
        if (G && G.ids.length > SETTLE_ABOVE) { lodTimer = setTimeout(run, LOD_MS); return; }
        if (now - lodLast >= LOD_MS) run(); else lodTimer = setTimeout(run, LOD_MS - (now - lodLast));
    }
    const redrawSoon = () => { if (!frame) frame = requestAnimationFrame(() => { frame = 0; drawHulls(); drawLabels(); }); };

    // ---- flows ----------------------------------------------------------------------------------------------
    // Only relations marked as flows move: the console's "animate::datatype" set in the relation's edit form,
    // whose text travels from start to end, along the line as drawn. Zoomed out, where the text would be too
    // small to read, it is a dot in the flow's colour. Under reduced motion the labels stand still mid-line.
    function edgePath(e) {
        const s = e.sourceEndpoint(), t = e.targetEndpoint();
        const cps = e.controlPoints();
        if (!cps || !cps.length) return [s, t];
        const on = [s];
        for (let i = 0; i < cps.length - 1; i++) on.push({ x: (cps[i].x + cps[i + 1].x) / 2, y: (cps[i].y + cps[i + 1].y) / 2 });
        on.push(t);
        const pts = [s];
        cps.forEach((c, i) => { const a = on[i], b = on[i + 1]; for (let k = 1; k <= 8; k++) { const u = k / 8, v = 1 - u; pts.push({ x: v * v * a.x + 2 * v * u * c.x + u * u * b.x, y: v * v * a.y + 2 * v * u * c.y + u * u * b.y }); } });
        return pts;
    }
    function pathOf(e) {
        let p = PATHS.get(e.id());
        if (p) return p;
        const pts = edgePath(e), cum = [0];
        for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y));
        let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity;
        pts.forEach(q => { x1 = Math.min(x1, q.x); y1 = Math.min(y1, q.y); x2 = Math.max(x2, q.x); y2 = Math.max(y2, q.y); });
        p = { pts, cum, len: cum[cum.length - 1], bb: { x1, y1, x2, y2 } };
        PATHS.set(e.id(), p);
        return p;
    }
    function animatedEdges() {
        if (flowList) return flowList;
        if (V.flows === 'off') return (flowList = []);
        const first = e => (e.selected() || e.hasClass('hov') ? 2 : e.hasClass('hl') ? 1 : 0);
        flowList = cy.edges().filter(e => !!e.data('flow')).toArray().sort((a, b) => first(b) - first(a) || (b.data('imp') || 0) - (a.data('imp') || 0));
        return flowList;
    }
    function flowDirty() { flowList = null; startFlow(); }
    function flowReset() { flowEvery = 1; flowRecent = []; flowStopped = false; flowDirty(); }
    function startFlow() {
        if (!cy || destroyed) return;
        if (reducedMotion() || flowStopped) { drawFlow(0, true); return; }
        if (!flowRAF) flowRAF = requestAnimationFrame(stepFlow);
    }
    function stepFlow(ts) {
        flowRAF = 0;
        if (destroyed) return;
        if (flowStopped) { drawFlow(0, true); return; }
        const list = animatedEdges();
        if (++flowTick % flowEvery === 0) {
            const t0 = performance.now();
            drawFlow(ts, false);
            flowRecent.push(performance.now() - t0);
            if (flowRecent.length >= 30) {
                const avg = flowRecent.reduce((a, b) => a + b, 0) / flowRecent.length;
                flowRecent = [];
                if (avg > FRAME_BUDGET_MS && flowEvery === 1) flowEvery = 2;
                else if (avg > FRAME_BUDGET_MS) { flowStopped = true; flowList = null; drawFlow(0, true); return; }
            }
        }
        if (list.length && !document.hidden) flowRAF = requestAnimationFrame(stepFlow);
    }
    function drawFlow(ts, still) {
        const { ctx, dpr, w, h } = sizeCanvas(flowCanvas);
        const list = animatedEdges();
        if (!list.length) return;
        const z = cy.zoom(), pan = cy.pan(), pal = palette(), sec = ts / 1000;
        const view = { x1: -pan.x / z - 40, y1: -pan.y / z - 40, x2: (w - pan.x) / z + 40, y2: (h - pan.y) / z + 40 };
        const inView = p => p.bb.x2 >= view.x1 && p.bb.x1 <= view.x2 && p.bb.y2 >= view.y1 && p.bb.y1 <= view.y2;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        let moving = 0;
        const minLen = MIN_MOVING_PX / z, asText = 9 * z >= 7;
        ctx.font = `600 ${Math.min(9 * z, MAX_FLOW_LABEL_PX)}px Manrope, Arial, sans-serif`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        list.forEach(e => {
            if (e.removed() || !shownEdge(e)) return;
            const p = pathOf(e);
            if (p.len < minLen || !inView(p)) return;
            if (!still && ++moving > MAX_MOVING) return;
            const dur = Math.min(10, Math.max(3.2, p.len * 0.024));
            const u = still ? 0.5 : ((sec / dur) + phaseOf(e.id())) % 1;
            const q = pointAt(p, u * p.len), x = q.x * z + pan.x, y = q.y * z + pan.y;
            ctx.globalAlpha = (still ? 1 : Math.min(1, u / 0.12, (1 - u) / 0.12)) * (e.hasClass('dim') || e.hasClass('tut-dim') ? 0.3 : 1);
            if (asText) { ctx.fillStyle = pal.flowText; ctx.fillText(e.data('flow'), x, y); }
            else { ctx.fillStyle = flowColour(e.data('flow')); ctx.beginPath(); ctx.arc(x, y, 2, 0, 2 * Math.PI); ctx.fill(); }
        });
        ctx.globalAlpha = 1;
    }

    // ---- style ---------------------------------------------------------------------------------------------
    function style() {
        const pal = palette(), fade = !cyWebgl;
        // Cytoscape's WebGL renderer leaves a node's opacity out, so there a dimmed resource is drawn in its
        // colour mixed into the background instead, which looks the same.
        const dimNode = k => cyWebgl ? { 'background-color': n => mixHex(n.data('dot') || OTHER, pal.bg, k), 'background-image-opacity': k, 'border-opacity': k } : { 'opacity': k };
        return [
            { selector: 'node', style: { 'label': '', 'overlay-opacity': 0, 'width': 10, 'height': 10, 'shape': 'ellipse', 'background-color': OTHER, 'border-width': 0 } },
            { selector: 'node[w]', style: { 'width': 'data(w)', 'height': 'data(h)' } },
            // Dots draw no names of their own: names go on a layer above the graph, so zooming never restyles a node.
            // A dot is a plain circle; its icon is drawn on the name layer (drawDotIcons), where it stays sharp.
            { selector: 'node.dot', style: { 'background-color': 'data(dot)' } },
            { selector: 'node.pill', style: { 'shape': 'round-rectangle', 'background-position-x': n => (n.data('fs') * PILL_PAD) + 'px', 'background-position-y': '50%',
                'background-width': n => (n.data('fs') * 1.35) + 'px', 'background-height': n => (n.data('fs') * 1.35) + 'px', 'background-image-opacity': 0.9 } },
            { selector: 'node.noicon', style: { 'background-image': 'none' } },
            { selector: 'node.grp', style: { 'background-opacity': 0, 'border-width': 0 } },
            // Plain lines are straight; bezier only so that two relationships between one pair fan apart (a
            // quadratic curve bows half its control-point step, so twice the gap gives PAR_GAP lanes). A line
            // inside a group is drawn at 60 %, so the lines between groups, drawn solid, stand out from them.
            { selector: 'edge', style: { 'width': 1, 'line-color': pal.line, 'curve-style': 'bezier', 'control-point-step-size': 2 * PAR_GAP, 'opacity': 0.6, 'overlay-opacity': 0 } },
            { selector: 'edge.lay, edge.straight', style: { 'curve-style': 'haystack', 'haystack-radius': 0 } },
            { selector: 'edge.curved', style: { 'curve-style': 'unbundled-bezier', 'control-point-weights': 'data(cpw)', 'control-point-distances': 'data(cpd)', 'edge-distances': 'node-position',
                'width': e => 1.1 + 1.2 * (e.data('imp') || 0), 'opacity': 1,
                // A wide, faint band under each line: where strands run together the bands stack into a trunk.
                'underlay-color': pal.line, 'underlay-padding': 4, 'underlay-opacity': 0.12 } },
            { selector: 'edge.cross', style: { 'width': e => 1.1 + 1.2 * (e.data('imp') || 0), 'opacity': 1 } },
            // Direction without arrowheads: every line fades from faint at its start to full at its end. The
            // WebGL renderer draws no gradients, so there lines are solid until something is selected.
            ...(fade ? [{ selector: 'edge', style: { 'line-fill': 'linear-gradient', 'line-gradient-stop-colors': [pal.fadeFrom, pal.line], 'line-gradient-stop-positions': [0, 100] } }] : []),
            { selector: '.dim', style: { 'opacity': 0.08 } },
            { selector: 'node.dim', style: dimNode(0.18) },
            { selector: 'node.root', style: { 'border-width': 2, 'border-color': pal.label, 'border-opacity': 0.9 } },
            { selector: 'node.sel', style: { 'border-width': 3, 'border-color': pal.accent, 'border-opacity': 1 } },
            { selector: 'edge.hl', style: { 'opacity': 0.95, 'z-index': 9 } },
            // A relation shows its type when you point at it, pick it, or select one of its ends.
            { selector: 'edge.hl, edge.hov, edge:selected', style: { 'label': 'data(label)', 'font-family': 'Manrope, Arial, sans-serif', 'font-size': () => Math.min(8 * cy.zoom(), MAX_EDGE_LABEL_PX) / cy.zoom(), 'font-weight': 600, 'color': pal.label,
                'text-rotation': 'autorotate', 'text-background-color': pal.bg, 'text-background-opacity': 0.85, 'text-background-padding': 1.5, 'min-zoomed-font-size': 6 } },
            // A selected hub's lines go unlabelled, all saying the same thing, until one is pointed at or picked.
            { selector: 'edge.quiet', style: { 'label': '' } },
            { selector: 'edge.quiet.hov, edge.quiet:selected', style: { 'label': 'data(label)' } },
            // The selected resource's lines: one colour where they leave it, another where they arrive.
            { selector: 'edge.hl.out', style: cyWebgl ? { 'line-color': pal.out, 'width': 1.8 } : { 'line-fill': 'linear-gradient', 'line-gradient-stop-colors': [pal.outFrom, pal.out], 'line-gradient-stop-positions': [0, 100], 'underlay-color': pal.out, 'width': 1.8 } },
            { selector: 'edge.hl.in', style: cyWebgl ? { 'line-color': pal.in, 'width': 1.8 } : { 'line-fill': 'linear-gradient', 'line-gradient-stop-colors': [pal.inFrom, pal.in], 'line-gradient-stop-positions': [0, 100], 'underlay-color': pal.in, 'width': 1.8 } },
            { selector: 'edge.hov', style: { 'opacity': 1, 'width': 2.2, 'z-index': 10, 'underlay-opacity': 0.16 } },
            { selector: 'edge:selected', style: { 'opacity': 1, 'width': 2.6, 'z-index': 11, 'line-fill': 'solid', 'line-color': pal.pick, 'underlay-color': pal.pick, 'underlay-padding': 4, 'underlay-opacity': 0.2 } },
            // A big graph's shut groups: one dot each, filled with the group's colour faded into the page, and a
            // line between linked groups, wider the more relations it stands for.
            // Shut groups in muted colours: hundreds of them in full colour read as confetti. A group shows its
            // own colour once it opens.
            { selector: 'node.agg', style: { 'width': 'data(w)', 'height': 'data(w)', 'background-color': 'data(fill)', 'border-width': 1.5, 'border-color': 'data(dot)', 'border-opacity': 1 } },
            { selector: 'node.agg.root', style: { 'border-width': 4, 'border-color': pal.label } },
            // The lines between shut groups are what makes the zoomed-out view read as a network, so they are
            // drawn a little stronger than a single relation.
            { selector: 'edge.agg', style: { 'curve-style': 'haystack', 'haystack-radius': 0, 'width': e => Math.min(6, 1.3 + Math.log2(e.data('n') || 1)), 'opacity': 0.8, 'line-fill': 'solid', 'line-color': pal.line } },
            // Drawing a new relation (edge-handles).
            { selector: '.eh-source, .eh-target', style: { 'border-width': 3, 'border-color': pal.accent, 'border-opacity': 1 } },
            { selector: '.eh-handle', style: { 'width': 10, 'height': 10, 'background-color': pal.accent, 'background-image': 'none' } },
            { selector: '.eh-ghost-edge.eh-preview-active', style: { 'opacity': 0 } },
            { selector: '.eh-preview, .eh-ghost-edge', style: { 'curve-style': 'straight', 'line-fill': 'solid', 'line-color': pal.accent, 'target-arrow-shape': 'triangle', 'target-arrow-color': pal.accent, 'opacity': 1, 'width': 1.5 } },
            // A hub's lines are drawn only while one of their ends is selected, or the line itself.
            { selector: 'edge.ctx', style: { 'display': 'none' } },
            { selector: 'edge.ctx.hl, edge.ctx:selected', style: { 'display': 'element' } },
            { selector: '.filtered-out', style: { 'display': 'none' } },
            // The guided tutorial's highlight and dimming.
            { selector: 'node.tut-highlight', style: { 'border-width': 4, 'border-color': pal.tutorial, 'border-opacity': 1 } },
            { selector: 'edge.tut-highlight', style: { 'line-fill': 'solid', 'line-color': pal.tutorial, 'width': 4, 'opacity': 1 } },
            { selector: '.tut-dim', style: { 'opacity': 0.18 } },
            { selector: 'node.tut-dim', style: dimNode(0.18) },
        ];
    }

    // ---- selection ------------------------------------------------------------------------------------------
    function select(id) {
        const n = cy.getElementById(id);
        if (!n.length) return;
        selectedId = id;
        const near = n.closedNeighborhood();
        const hub = !!(G && G.index.has(id) && G.hubs.has(G.index.get(id)));
        cy.batch(() => {
            cy.elements().removeClass('dim hl sel in out quiet');
            cy.elements().not(near).not(TEMP).addClass('dim');
            n.addClass('sel');
            n.connectedEdges().forEach(e => e.addClass('hl ' + (e.data('source') === id ? 'out' : 'in') + (hub ? ' quiet' : '')));
        });
        drawHulls();
    }
    function clearSel() {
        selectedId = null;
        if (!cy) return;
        cy.elements().removeClass('dim hl sel in out quiet');
        drawHulls();
    }

    // ---- relations where a click lands ------------------------------------------------------------------------
    // Lines bundled into a trunk run close together, so a click collects every line within HIT_PX of it,
    // measured on the curve Cytoscape draws. One opens straight away; several get a short list to choose from.
    function edgesNear(P, tol) {
        const out = [];
        cy.edges().not(TEMP).not('.agg').forEach(e => {
            if (!shownEdge(e)) return;
            const pts = edgePath(e);
            let d = Infinity;
            for (let i = 0; i < pts.length - 1; i++) d = Math.min(d, segDist(P, pts[i], pts[i + 1]));
            if (d <= tol) out.push({ e, d });
        });
        return out.sort((a, b) => a.d - b.d || (a.e.id() < b.e.id() ? -1 : 1)).map(o => o.e);
    }
    const nameOf = n => n.data('short') || n.data('label') || '';
    function showPick(edges, rp) {
        const list = pick.querySelector('ul');
        pick.querySelector('.dh-graph-pick-h').textContent = opts.tr('graph.relations.meet', [edges.length]);
        list.innerHTML = edges.slice(0, 8).map(e => `<li><button type="button" data-id="${esc(e.id())}"><b>${esc(nameOf(e.source()))}</b> <i>${esc(e.data('label') || '')} →</i> <b>${esc(nameOf(e.target()))}</b></button></li>`).join('') +
            (edges.length > 8 ? `<li class="dh-graph-pick-more">${esc(opts.tr('graph.relations.more', [edges.length - 8]))}</li>` : '');
        list.querySelectorAll('button').forEach(b => {
            const e = cy.getElementById(b.dataset.id);
            b.addEventListener('mouseenter', () => e.addClass('hov'));
            b.addEventListener('mouseleave', () => e.removeClass('hov'));
            b.addEventListener('focus', () => e.addClass('hov'));
            b.addEventListener('blur', () => e.removeClass('hov'));
            b.addEventListener('click', () => { hidePick(); pickEdge(e); });
        });
        pick.hidden = false;
        const W = cy.width(), H = cy.height(), bw = pick.offsetWidth, bh = pick.offsetHeight;
        pick.style.left = Math.max(8, Math.min(W - bw - 8, rp.x + 10)) + 'px';
        pick.style.top = Math.max(8, Math.min(H - bh - 8, rp.y + 10)) + 'px';
        const first = list.querySelector('button'); if (first) first.focus({ preventScroll: true });
    }
    function hidePick() { if (!pick.hidden) { pick.hidden = true; if (cy) cy.edges('.hov').removeClass('hov'); } }
    function pickEdge(e) {
        cy.elements(':selected').unselect();
        e.select();
        if (opts.onEdgePick) opts.onEdgePick(e);
    }
    function onTap(evt) {
        const t = evt.target;
        if (t !== cy && t.isNode()) return;
        hidePick();
        if (!evt.position || (opts.picking && !opts.picking())) return;
        const near = edgesNear(evt.position, HIT_PX / cy.zoom());
        if (!near.length) return;
        if (near.length === 1) pickEdge(near[0]);
        else showPick(near, evt.renderedPosition);
    }
    const onKey = e => { if (e.key === 'Escape') hidePick(); };

    // ---- wheel zoom -------------------------------------------------------------------------------------------
    // Cytoscape recognises a mouse wheel and then zooms 2.8 % a notch, so going from a large network to
    // readable names took dozens of notches. While the page itself is scrolling past the graph, the wheel goes
    // on scrolling it.
    function onWheel(e) {
        if (!cy || !host.contains(e.target)) return;
        e.stopPropagation();
        if (pageScrolling) return;
        e.preventDefault();
        if (cy.nodes(':grabbed').length) return;
        const d = e.deltaY * (e.deltaMode === 1 ? 33 : e.deltaMode === 2 ? 800 : 1), cap = WHEEL_DOUBLE * Math.log2(NOTCH);
        const f = e.ctrlKey ? Math.exp(-Math.max(-60, Math.min(60, d)) / 100) : Math.pow(2, -Math.sign(d) * Math.min(Math.abs(d), cap) / WHEEL_DOUBLE);
        const r = host.getBoundingClientRect();
        cy.zoom({ level: cy.zoom() * f, renderedPosition: { x: e.clientX - r.left, y: e.clientY - r.top } });
    }
    const onScroll = () => { pageScrolling = true; clearTimeout(pageScrollTimer); pageScrollTimer = setTimeout(() => { pageScrolling = false; }, 250); };
    const onVisible = () => { if (!document.hidden) startFlow(); };

    // ---- the graph view ---------------------------------------------------------------------------------------
    // WebGL where the browser has WebGL 2: Cytoscape's WebGL renderer holds 60 frames a second at every size
    // measured (to 5,000 resources); it draws long curves as short straight pieces and leaves out glow and
    // gradients. ?renderer=canvas in the address forces the canvas renderer.
    function makeCy() {
        cyWebgl = hasWebgl() && !/[?&]renderer=canvas\b/.test(location.search);
        cy = cytoscape(Object.assign({ container: host, elements: [], style: [], minZoom: 0.02, maxZoom: 4, boxSelectionEnabled: false, selectionType: 'single' },
            cyWebgl ? { webgl: true } : {}));
        cy.style().fromJson(style()).update();
        cy.on('viewport', () => {
            hidePick();
            viewMoving = true; clearTimeout(viewTimer); viewTimer = setTimeout(() => { viewMoving = false; drawHulls(); }, 160);
            scheduleLod(); redrawSoon();
            if (reducedMotion() || flowStopped) drawFlow(0, true);
            if (big) { sizeDots(); clearTimeout(winTimer); winTimer = setTimeout(windowNow, 160); }
        });
        cy.on('tap', 'node.agg', e => openGroup(e.target.data('c')));
        // A big graph's store follows what GraphNetwork adds to Cytoscape and removes from it (a new resource,
        // a deleted relation), apart from what the view itself brings in and takes out.
        const tracked = el => big && !big.windowing && !el.is(TEMP) && !el.hasClass('agg');
        cy.on('add', e => { const el = e.target; if (!tracked(el)) return; if (el.isNode()) { big.nodes.set(el.id(), Object.assign({}, el.data())); big.pos.set(el.id(), { ...el.position() }); } else big.edges.set(el.id(), Object.assign({}, el.data())); });
        cy.on('remove', e => { const el = e.target; if (!tracked(el)) return; if (el.isNode()) { big.nodes.delete(el.id()); big.pos.delete(el.id()); } else big.edges.delete(el.id()); });
        cy.on('position', 'node', e => { posVersion++; e.target.connectedEdges().forEach(x => PATHS.delete(x.id())); redrawSoon(); });
        // Dimming and the tutorial's highlight hide names, so a class change redraws them.
        cy.on('class', 'node', redrawSoon);
        // Which flows move first depends on the lines' classes (selected, pointed at), so a change rebuilds the list.
        cy.on('class', 'edge', () => { if (flowList) flowDirty(); });
        cy.on('select', 'edge', () => { if (flowList) flowDirty(); });
        cy.on('tap', onTap);
        cy.on('mouseover', 'edge', e => { if (!e.target.is(TEMP)) { e.target.addClass('hov'); host.style.cursor = 'pointer'; } });
        cy.on('mouseout', 'edge', e => { e.target.removeClass('hov'); host.style.cursor = ''; });
        cy.on('dragfree', 'node', e => { if (!e.target.is(TEMP)) afterMove(e.target.id()); });
        // A shared resource takes what hangs off it alone along while it is dragged, as its placement does.
        let carry = null;
        cy.on('grab', 'node', e => { const id = e.target.id(); carry = current && MULTI.some(m => m.id === id) ? { at: { ...e.target.position() }, ns: privateLeaves(id, current.member, new Set(MULTI.map(m => m.id))) } : null; });
        cy.on('drag', 'node', e => {
            if (!carry || !carry.ns.length) return;
            const p = e.target.position(), dx = p.x - carry.at.x, dy = p.y - carry.at.y;
            carry.at = { ...p };
            cy.batch(() => carry.ns.forEach(o => o.position({ x: o.position().x + dx, y: o.position().y + dy })));
        });
    }

    // ---- big graphs ---------------------------------------------------------------------------------------
    // Above BIG_ABOVE resources the graph is kept here as plain data, and Cytoscape holds only what is on
    // screen. Zoomed out, each group is one dot, about as large as the group will be, with one line for each
    // pair of linked groups. A group that grows to OPEN_PX across opens into its resources and the relations
    // among them; a relation to a group still shut is drawn to that group's dot. Cytoscape draws about 5,000
    // elements at 60 frames a second and 100,000 at 3, and a removed element keeps most of its memory, so
    // elements are made as they come into view and dropped as they leave. A group is laid out the first time
    // it opens: the overview needs only where each group sits.
    function loadBig(nodes, edges) {
        const shown = new Set();
        big = { nodes: new Map(nodes.map(d => [d.id, d])), edges: new Map(edges.map(d => [d.id, d])), pos: new Map(), groups: null, comm: null, shown, open: shown, inner: null, edgesOf: null,
            aggN: new Map(), aggEnds: new Map(), synced: false, busy: false, job: null, windowing: false };
        cy.minZoom(0.002);
    }
    // A group's room: its resources at the grouped layout's spacing fill a circle about this wide.
    const groupRadius = n => 24 * Math.sqrt(n) + 20;
    const own = d => { const { id, source, target, parent, ...rest } = d; return rest; };   // data Cytoscape may update in place
    // Worked through a slice at a time (runBig), so the page keeps responding while a large graph is
    // analysed and placed. reuse: the graph is unchanged (Rearrange), so its analysis still stands.
    function bigRefresh(reuse) { runBig(bigRefreshSteps(reuse)); }
    function* bigRefreshSteps(reuse) {
        winGen++;
        const before = current ? new Map(current.ids.map((id, i) => [id, current.comm[i]])) : null;
        if (!reuse || !G || !info) {
            G = A.buildGraph({
                nodes: [...big.nodes.values()].map(d => ({ id: d.id, name: d.label || '', labels: labelsOf(d.labels) })),
                edges: [...big.edges.values()].map(d => ({ id: d.id, source: d.source, target: d.target, type: d.label || '', metadata: d.metadata })),
            });
            yield;
            SHORT = yield* shortNamesSteps();
            yield;
            yield* analyseSteps();
            yield;
        }
        const comm = before ? keptGroups(before) : freshGroups(), pal = palette();
        yield;
        G.ids.forEach((id, i) => Object.assign(big.nodes.get(id), lookOf(i, comm, pal)));
        yield;
        G.edges.forEach((e, k) => Object.assign(big.edges.get(e.id), edgeLookOf(e, k, comm), { ctx: e.ctx }));
        yield;
        const member = groupMembers(comm);
        MULTI = multiOf(comm);
        current = { ids: G.ids, comm, member };
        big.comm = new Map(G.ids.map((id, i) => [id, comm[i]]));
        const members = new Map();
        G.ids.forEach((id, i) => { if (!members.has(comm[i])) members.set(comm[i], []); members.get(comm[i]).push(id); });
        big.inner = new Map();
        G.edges.forEach(e => { const c = big.comm.get(e.source); if (!e.ctx && c === big.comm.get(e.target)) { if (!big.inner.has(c)) big.inner.set(c, []); big.inner.get(c).push(e); } });
        // Every relation under each group it touches, so opening or shutting a group revisits only those.
        big.edgesOf = new Map();
        const under = (c, e) => { let l = big.edgesOf.get(c); if (!l) big.edgesOf.set(c, l = []); l.push(e); };
        G.edges.forEach(e => { const a = big.comm.get(e.source), b = big.comm.get(e.target); under(a, e); if (b !== a) under(b, e); });
        yield;
        const first = !big.groups;
        if (first) yield* bigGroups(members); else yield* regroup(members);
        // What is on screen now takes the new looks; a resource added since the last drawing gets its place.
        cy.batch(() => {
            cy.nodes().not(TEMP).not('.agg').forEach(n => {
                const d = big.nodes.get(n.id());
                if (!d) return;
                if (!big.pos.has(n.id()) || !n.hasClass('dot')) { const g = big.groups.get(big.comm.get(n.id())); if (g && g.laid) placeInGroup(g, [n.id()]); if (big.pos.has(n.id())) n.position({ ...big.pos.get(n.id()) }); }
                n.data(own(d)); n.addClass('dot');
            });
            cy.edges().not(TEMP).not('.agg').forEach(e => { const d = big.edges.get(e.id()); if (!d) return; e.data(own(d)); e.addClass('straight'); e.toggleClass('ctx', !!d.ctx); });
        });
        if (first) fitBig();
        big.synced = false;
        big.busy = false;
        windowNow();
    }
    // Runs a big graph's steps a slice at a time. A newer run, a reset or a new graph ends an older one.
    function runBig(steps) {
        const b = big, job = {};
        b.job = job; b.busy = true;
        const slice = () => {
            if (destroyed || big !== b || b.job !== job) return;
            const t0 = performance.now();
            let r;
            try {
                do { r = steps.next(); } while (!r.done && performance.now() - t0 < SLICE_MS);
            } catch (err) { b.busy = false; throw err; }
            if (!r.done) setTimeout(slice, 0);
        };
        // Not straight away: whatever asked for this (a load, an edit) finishes its own task first.
        setTimeout(slice, 0);
    }
    // Where each group sits: fcose on the graph of groups, each as wide as its room, linked as strongly as
    // the relations between them; then any two rooms that still overlap are pushed apart.
    function* bigGroups(members) {
        big.groups = new Map();
        // A group is named after its head: the resource highest in the tree, else the most important one.
        const t = info.tree, kids = new Set(t.parent.filter(p => p >= 0)), rank = i => (fellBack || (t.parent[i] < 0 && !kids.has(i)) ? 1e9 : t.treeDepth[i]);
        const head = ids => ids.reduce((a, b) => {
            const ia = G.index.get(a), ib = G.index.get(b), da = rank(ia), db = rank(ib);
            return db < da || (db === da && info.importance[ib] > info.importance[ia]) ? b : a;
        });
        members.forEach((ids, c) => big.groups.set(c, { c, ids, r: groupRadius(ids.length), x: 0, y: 0, laid: false, name: G.nodes.get(head(ids)).name }));
        const w = new Map();
        G.edges.forEach(e => { if (e.ctx) return; const a = big.comm.get(e.source), b = big.comm.get(e.target); if (a === b) return; const k = a < b ? a + ',' + b : b + ',' + a; w.set(k, (w.get(k) || 0) + 1); });
        const els = [...big.groups.values()].map(g => ({ group: 'nodes', data: { id: 'g' + g.c, d: 2 * g.r } }))
            .concat([...w.keys()].map((k, j) => { const [a, b] = k.split(','); return { group: 'edges', data: { id: 'l' + j, source: 'g' + a, target: 'g' + b } }; }));
        const hc = cytoscape({ headless: true, styleEnabled: true, elements: els, style: [{ selector: 'node', style: { width: 'data(d)', height: 'data(d)' } }] });
        A.withSeed(13, () => hc.layout({ name: 'fcose', quality: 'default', randomize: true, animate: false, fit: false, nodeDimensionsIncludeLabels: false, packComponents: false,
            numIter: 2500, nodeSeparation: 40, gravity: 0.4, gravityRange: 3.8, nodeRepulsion: n => 4500 + 30 * n.data('d'),
            idealEdgeLength: e => (e.source().data('d') + e.target().data('d')) / 2 + 30, edgeElasticity: 0.3 }).run());
        hc.nodes().forEach(n => { const g = big.groups.get(+n.id().slice(1)); g.x = n.position().x; g.y = n.position().y; });
        hc.destroy();
        yield;
        yield* separate([...big.groups.values()]);
    }
    // Push overlapping rooms apart until none overlap, or a few hundred rounds; with only some moving
    // (moving), the rest stay put. Where the layout crowds groups (hundreds linked to one site want to sit in
    // a ring round it), they spread out from the crowd and end up packed close together.
    function* separate(gs, moving) {
        const GAP = 20, cell = 2 * A.maxOf(gs.map(g => g.r), 1) + GAP, may = g => !moving || moving.has(g.c);
        for (let round = 0; round < 400; round++) {
            const grid = new Map();
            gs.forEach(g => { const k = Math.floor(g.x / cell) + ',' + Math.floor(g.y / cell); if (!grid.has(k)) grid.set(k, []); grid.get(k).push(g); });
            let moved = false;
            gs.forEach(a => {
                const gx = Math.floor(a.x / cell), gy = Math.floor(a.y / cell);
                for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) (grid.get((gx + dx) + ',' + (gy + dy)) || []).forEach(b => {
                    if (b.c <= a.c || (!may(a) && !may(b))) return;
                    const vx = b.x - a.x, vy = b.y - a.y, d = Math.hypot(vx, vy) || 1e-3, need = a.r + b.r + GAP - d;
                    if (need <= 0) return;
                    const ux = d > 1e-3 ? vx / d : 1, uy = d > 1e-3 ? vy / d : 0, sa = may(a) ? (may(b) ? 0.5 : 1) : 0, sb = 1 - sa;
                    a.x -= ux * need * sa; a.y -= uy * need * sa; b.x += ux * need * sb; b.y += uy * need * sb; moved = true;
                });
            });
            if (!moved) break;
            if (round % 10 === 9) yield;
        }
    }
    // After an edit: the groups keep their places and inner layouts. A resource new to a laid-out group goes
    // beside its neighbours there; a new group goes beside the groups it is linked to.
    function* regroup(members) {
        const fresh = new Set();
        big.groups.forEach((g, c) => { if (!members.has(c)) big.groups.delete(c); });
        members.forEach((ids, c) => {
            const g = big.groups.get(c);
            if (g) { const known = new Set(g.ids); g.ids = ids; if (g.laid) placeInGroup(g, ids.filter(id => !known.has(id) || !big.pos.has(id))); return; }
            const near = [];
            ids.forEach(id => G.adj[G.index.get(id)].forEach((_, j) => { const o = big.groups.get(big.comm.get(G.ids[j])); if (o && o.c !== c) near.push(o); }));
            const at = near.length ? near.reduce((s, o) => ({ x: s.x + o.x / near.length, y: s.y + o.y / near.length }), { x: 0, y: 0 }) : { x: 0, y: 0 };
            big.groups.set(c, { c, ids, r: groupRadius(ids.length), x: at.x + 1, y: at.y + 1, laid: false, name: G.nodes.get(ids[0]).name });
            fresh.add(c);
        });
        if (fresh.size) yield* separate([...big.groups.values()], fresh);
    }
    function placeInGroup(g, ids) {
        ids.forEach((id, k) => {
            const near = [];
            G.adj[G.index.get(id)].forEach((_, j) => { const p = big.pos.get(G.ids[j]); if (p && big.comm.get(G.ids[j]) === g.c) near.push(p); });
            const c = near.length ? near.reduce((s, p) => ({ x: s.x + p.x / near.length, y: s.y + p.y / near.length }), { x: 0, y: 0 }) : { x: g.x, y: g.y };
            big.pos.set(id, { x: c.x + 30 * Math.cos(k * 2.4 + 1), y: c.y + 30 * Math.sin(k * 2.4 + 1) });
        });
    }
    // A group's resources, laid out as the grouped layout lays out a group, then fitted into its room.
    function layoutGroup(g) {
        g.laid = true;
        if (g.ids.length === 1) { big.pos.set(g.ids[0], { x: g.x, y: g.y }); return; }
        const d = id => big.nodes.get(id).w || 20;
        const els = g.ids.map(id => ({ group: 'nodes', data: { id, w: d(id) } })).concat((big.inner.get(g.c) || []).map(e => ({ group: 'edges', data: { id: e.id, source: e.source, target: e.target } })));
        const hc = cytoscape({ headless: true, styleEnabled: true, elements: els, style: [{ selector: 'node', style: { width: 'data(w)', height: 'data(w)' } }] });
        A.withSeed(7, () => hc.layout({ name: 'fcose', quality: 'proof', randomize: true, animate: false, fit: false, nodeDimensionsIncludeLabels: false, packComponents: false,
            numIter: 2500, tile: true, nodeSeparation: GROUPED.sep, gravity: GROUPED.grav, gravityRange: GROUPED.gr, nodeRepulsion: n => 4500 + 260 * n.data('w'),
            idealEdgeLength: e => GROUPED.intra + (e.source().data('w') + e.target().data('w')) / 2, edgeElasticity: 0.55 }).run());
        const ps = g.ids.map(id => ({ ...hc.getElementById(id).position() }));
        hc.destroy();
        const cx = ps.reduce((s, p) => s + p.x, 0) / ps.length, cyy = ps.reduce((s, p) => s + p.y, 0) / ps.length;
        const r0 = Math.max(1, ...ps.map((p, k) => Math.hypot(p.x - cx, p.y - cyy) + d(g.ids[k]) / 2));
        const s = Math.min(1, (g.r - FLOAT_PAD) / r0);
        g.ids.forEach((id, k) => big.pos.set(id, { x: g.x + (ps[k].x - cx) * s, y: g.y + (ps[k].y - cyy) * s }));
    }
    function aggNode(g) {
        const pal = palette(), col = colourOf(g.c), full = Math.max(12, 1.2 * g.r), d = full * dotScale(g.r, cy.zoom()), most = big.most || 1;
        return { group: 'nodes', classes: 'agg' + (rootId && big.comm.get(rootId) === g.c ? ' root' : ''), grabbable: false, selectable: false, position: { x: g.x, y: g.y },
            data: { id: 'agg:' + g.c, c: g.c, n: g.ids.length, w: d, h: d, full, r: g.r, fill: mixHex(col, pal.bg, 0.32), dot: mixHex(col, pal.bg, 0.7), fs: 1e6, imp: Math.sqrt(g.ids.length / most), short: g.name, label: g.name, labels: [opts.tr('graph.group.size', [g.ids.length])] } };
    }
    // Bring Cytoscape in line with the view: the open groups' resources and the relations among them, a dot
    // for every shut group, and one line from a shown resource to each shut group it is linked to, and
    // between two shut groups. A hub's relations to a shut group are left out, as they are hidden anyway.
    // Groups open and shut one at a time, nearest first, a slice at a time, so the page keeps responding
    // while a view fills in: laying out a group of 160 resources takes 20 to 30 ms. A newer view picks up
    // from wherever this one has got to.
    function windowNow() {
        clearTimeout(winTimer); winTimer = 0;
        if (!big || !big.groups || !cy || destroyed || big.busy) return;
        const gen = ++winGen;
        winBusy = true;
        big.most = A.maxOf([...big.groups.values()].map(o => o.ids.length), 1);
        if (!big.synced) syncShown();
        const z = cy.zoom(), ext = cy.extent(), m = 0.15 * Math.max(ext.w, ext.h), mx = (ext.x1 + ext.x2) / 2, my = (ext.y1 + ext.y2) / 2;
        const near = [...big.groups.values()].filter(g => 2 * g.r * z >= OPEN_PX && g.x + g.r > ext.x1 - m && g.x - g.r < ext.x2 + m && g.y + g.r > ext.y1 - m && g.y - g.r < ext.y2 + m)
            .sort((a, b) => Math.hypot(a.x - mx, a.y - my) - Math.hypot(b.x - mx, b.y - my) || a.c - b.c);
        const open = [];
        let total = 0;
        for (const g of near) { if (total && total + g.ids.length > SHOWN_MAX) break; open.push(g.c); total += g.ids.length; }
        const want = new Set(open);
        // Shutting is cheap and makes room, so it happens at once. Opening lays groups out a slice at a time,
        // and hands what is ready to Cytoscape every COMMIT_MS, up to COMMIT_MAX resources at a time: each
        // change to the elements costs Cytoscape a pass over all of them when it next draws, so a few large
        // changes are much cheaper than many small, and a very large one holds the page.
        const shut = [...big.shown].filter(c => !want.has(c)), pending = open.filter(c => !big.shown.has(c)), ready = [];
        // Cytoscape works out an element's style the first time it is asked, so it is asked here, in the
        // commit's own slice, rather than all at once by the names and outlines at the end.
        const commit = (cs, on) => {
            if (!cs.length) return;
            let added = cy.collection();
            big.windowing = true;
            cy.batch(() => cs.forEach(c => { if (big.groups.has(c)) added = added.union(toggleGroup(c, on)); }));
            big.windowing = false;
            added.forEach(el => { if (!el.removed()) el.pstyle('display'); });
        };
        let committedAt = 0, shownAt = performance.now();
        const next = () => setTimeout(slice, 0);
        // The names, outlines and flows are worked out once Cytoscape has drawn what was added, so the
        // styles they read are already there.
        const finish = () => requestAnimationFrame(() => setTimeout(() => { if (gen !== winGen || !big || destroyed) return; winBusy = false; windowShown(); }, 0));
        function slice() {
            if (gen !== winGen || !big || destroyed || big.busy) return;
            commit(shut.splice(0), false);
            // A commit is a slice of its own: Cytoscape's next drawing pays for it.
            if (ready.length && (!pending.length || performance.now() - committedAt > COMMIT_MS)) {
                let n = 0, k = 0;
                while (k < ready.length && (!k || n + big.groups.get(ready[k]).ids.length <= COMMIT_MAX)) n += big.groups.get(ready[k++]).ids.length;
                commit(ready.splice(0, k), true); committedAt = performance.now();
                return pending.length || ready.length ? next() : finish();
            }
            if (!pending.length && !ready.length) return finish();
            // Groups already laid out are ready at once; at most one is laid out per slice.
            let laid = false;
            while (pending.length) { const g = big.groups.get(pending[0]); if (g && !g.laid) { if (laid) break; layoutGroup(g); laid = true; } ready.push(pending.shift()); }
            // Names, outlines and flows follow now and then while a view fills in, and at the end.
            if (performance.now() - shownAt > 1000) { windowShown(); shownAt = performance.now(); }
            next();
        }
        slice();
    }
    // What the canvases of their own (names, outlines, flows) and the filter make of the elements now shown.
    function windowShown() {
        if (selectedId && cy.getElementById(selectedId).empty()) clearSel();
        else if (selectedId) select(selectedId);
        posVersion++; PATHS.clear(); flowDirty();
        if (opts.onWindow) opts.onWindow();
        applyLod(); drawHulls();
    }
    // The classes a resource comes into view with, including the icon applyLod would give it at this zoom:
    // a class changed after the fact makes Cytoscape work out its style a second time.
    const arrivingClasses = (id, d, z) => 'dot' + (id === rootId ? ' root' : '') + ((d.w || 20) * z < ICON_MIN_PX ? ' noicon' : '');
    // How a relation is drawn with the groups now shown: itself (both ends shown), as a line to a shut
    // group's dot or between two dots (counted, so one line stands for many), or not at all.
    const REAL = 1;
    const aggLine = (s, t) => ({ k: 'agge:' + s + '|' + t, s, t });
    function lineOf(e) {
        const ga = big.comm.get(e.source), gb = big.comm.get(e.target), a = big.shown.has(ga), b = big.shown.has(gb);
        if (a && b) return REAL;
        if (e.ctx) return null;
        if (a) return aggLine(e.source, 'agg:' + gb);
        if (b) return aggLine('agg:' + ga, e.target);
        if (ga !== gb) return aggLine('agg:' + Math.min(ga, gb), 'agg:' + Math.max(ga, gb));
        return null;
    }
    // Opens a group into its resources, or shuts it back into a dot, and redraws only the relations that
    // touch it. Runs inside a batch with big.windowing set; returns what it added.
    function toggleGroup(c, on) {
        const g = big.groups.get(c), es = big.edgesOf.get(c) || [], before = es.map(lineOf);
        let added;
        if (on) {
            if (!g.laid) layoutGroup(g);
            big.shown.add(c);
            cy.remove(cy.getElementById('agg:' + c));
            const z = cy.zoom();
            added = cy.add(g.ids.map(id => { const d = big.nodes.get(id); return { group: 'nodes', data: { ...d }, position: { ...(big.pos.get(id) || { x: g.x, y: g.y }) }, classes: arrivingClasses(id, d, z) }; }));
        } else {
            // Leaving: what a resource or relation says now is kept for when it comes back.
            const out = cy.collection(g.ids.map(id => cy.getElementById(id)).filter(n => n.nonempty()));
            out.connectedEdges().not('.agg').forEach(e => { big.edges.set(e.id(), Object.assign(big.edges.get(e.id()) || {}, e.data())); });
            out.forEach(n => { big.nodes.set(n.id(), Object.assign(big.nodes.get(n.id()) || {}, n.data())); big.pos.set(n.id(), { ...n.position() }); });
            cy.remove(out);
            big.shown.delete(c);
            added = cy.add(aggNode(g));
        }
        const touched = new Set(), real = [];
        es.forEach((e, k) => {
            const was = before[k], now = lineOf(e);
            if (was === REAL && now !== REAL) { const x = cy.getElementById(e.id); if (x.nonempty()) { big.edges.set(e.id, Object.assign(big.edges.get(e.id) || {}, x.data())); cy.remove(x); } }
            if (now === REAL && was !== REAL && cy.getElementById(e.id).empty()) { const d = big.edges.get(e.id); real.push({ group: 'edges', data: { ...d }, classes: 'straight' + (d.ctx ? ' ctx' : '') }); }
            if (was && was !== REAL) { big.aggN.set(was.k, (big.aggN.get(was.k) || 0) - 1); touched.add(was.k); }
            if (now && now !== REAL) { big.aggN.set(now.k, (big.aggN.get(now.k) || 0) + 1); big.aggEnds.set(now.k, now); touched.add(now.k); }
        });
        const lines = [];
        touched.forEach(k => {
            const n = big.aggN.get(k) || 0, x = cy.getElementById(k);
            if (n <= 0) { big.aggN.delete(k); big.aggEnds.delete(k); if (x.nonempty()) cy.remove(x); }
            else if (x.empty()) { const w = big.aggEnds.get(k); lines.push({ group: 'edges', data: { id: k, source: w.s, target: w.t, n }, classes: 'agg' }); }
            else if (x.data('n') !== n) x.data('n', n);
        });
        return added.union(cy.add(real)).union(cy.add(lines));
    }
    // Makes Cytoscape match the groups shown from scratch: after a load, an edit, a Rearrange or a theme
    // change. The groups shown stay shown; windowNow then opens and shuts groups from there.
    function syncShown() {
        const S = big.shown = big.open = new Set([...(big.shown || [])].filter(c => big.groups.has(c)));
        S.forEach(c => { const g = big.groups.get(c); if (!g.laid) layoutGroup(g); });
        const wantNodes = new Set(), wantEdges = new Set(), agg = new Map();
        S.forEach(c => big.groups.get(c).ids.forEach(id => wantNodes.add(id)));
        G.edges.forEach(e => { const v = lineOf(e); if (v === REAL) wantEdges.add(e.id); else if (v) { const o = agg.get(v.k); if (o) o.n++; else agg.set(v.k, { ...v, n: 1 }); } });
        big.aggN = new Map([...agg].map(([k, w]) => [k, w.n]));
        big.aggEnds = new Map(agg);
        big.windowing = true;
        cy.batch(() => {
            const out = cy.nodes('.dot').filter(n => !wantNodes.has(n.id()));
            out.connectedEdges().not('.agg').forEach(e => { big.edges.set(e.id(), Object.assign(big.edges.get(e.id()) || {}, e.data())); });
            out.forEach(n => { big.nodes.set(n.id(), Object.assign(big.nodes.get(n.id()) || {}, n.data())); big.pos.set(n.id(), { ...n.position() }); });
            cy.remove(out);
            cy.remove(cy.nodes('.agg').filter(n => S.has(n.data('c')) || !big.groups.has(n.data('c'))));
            cy.edges('.agg').forEach(e => { const w = agg.get(e.id()); if (!w) cy.remove(e); else if (w.n !== e.data('n')) e.data('n', w.n); });
            const add = [];
            big.groups.forEach(g => { if (!S.has(g.c) && cy.getElementById('agg:' + g.c).empty()) add.push(aggNode(g)); });
            const z = cy.zoom();
            wantNodes.forEach(id => { if (cy.getElementById(id).empty()) { const d = big.nodes.get(id); add.push({ group: 'nodes', data: { ...d }, position: { ...(big.pos.get(id) || { x: 0, y: 0 }) }, classes: arrivingClasses(id, d, z) }); } });
            cy.add(add);
            const addE = [];
            wantEdges.forEach(id => { if (cy.getElementById(id).empty()) { const d = big.edges.get(id); addE.push({ group: 'edges', data: { ...d }, classes: 'straight' + (d.ctx ? ' ctx' : '') }); } });
            agg.forEach((w, k) => { if (cy.getElementById(k).empty()) addE.push({ group: 'edges', data: { id: k, source: w.s, target: w.t, n: w.n }, classes: 'agg' }); });
            cy.add(addE);
        });
        big.windowing = false;
        big.synced = true;
    }
    // The shut groups' dots follow the zoom (dotScale); only once it has moved by a few per cent.
    function sizeDots() {
        if (!big || !big.groups || !cy) return;
        const z = cy.zoom();
        if (big.dotZ && Math.abs(Math.log(z / big.dotZ)) < 0.03) return;
        big.dotZ = z;
        cy.batch(() => cy.nodes('.agg').forEach(n => { const d = n.data('full') * dotScale(n.data('r'), z); if (Math.abs(d - n.data('w')) > 0.5) n.data({ w: d, h: d }); }));
    }
    function bigBox() {
        let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity;
        big.groups.forEach(g => { x1 = Math.min(x1, g.x - g.r); y1 = Math.min(y1, g.y - g.r); x2 = Math.max(x2, g.x + g.r); y2 = Math.max(y2, g.y + g.r); });
        return { x1, y1, x2, y2 };
    }
    function fitBig() {
        const b = bigBox(), W = cy.width(), H = cy.height();
        if (!isFinite(b.x1)) return;
        const z = Math.min(FIT_MAX_ZOOM, (W - 2 * PAD) / Math.max(1, b.x2 - b.x1), (H - 2 * PAD) / Math.max(1, b.y2 - b.y1));
        cy.viewport({ zoom: z, pan: { x: W / 2 - z * (b.x1 + b.x2) / 2, y: H / 2 - z * (b.y1 + b.y2) / 2 } });
    }
    // A shut group's dot was clicked: zoom until the group fills most of the view, which opens it.
    function openGroup(c) {
        const g = big && big.groups.get(c);
        if (!g) return;
        const W = cy.width(), H = cy.height(), z = Math.min(cy.maxZoom(), 0.8 * Math.min(W, H) / (2 * g.r));
        cy.animate({ zoom: z, pan: { x: W / 2 - z * g.x, y: H / 2 - z * g.y } }, { duration: reducedMotion() ? 0 : 300 });
    }
    // For GraphNetwork: whether a resource or relation is in the graph, and a stand-in for one out of view.
    function standIn(id) {
        const d = big && (big.nodes.get(id) || big.edges.get(id)), has = !!d;
        return { length: has ? 1 : 0, nonempty: () => has, empty: () => !has, id: () => id, data: k => (d ? (k ? d[k] : d) : undefined),
            position: () => { const p = (big && big.pos.get(id)) || (d && big.groups && big.groups.get(big.comm.get(id))) || { x: 0, y: 0 }; return { x: p.x, y: p.y }; } };
    }

    // ---- drawing --------------------------------------------------------------------------------------------
    function fit() {
        if (big) { if (big.groups) fitBig(); return; }
        const nodes = graphNodes().filter(shown);
        if (!nodes.length) return;
        cy.fit(nodes, PAD);
        if (cy.zoom() > FIT_MAX_ZOOM) cy.zoom({ level: FIT_MAX_ZOOM, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
    }
    // Each way of drawing keeps a picture of its own, so switching back finds the one left there.
    function arrangementKey() {
        const key = opts.saveKey();
        // Pills change size with the size choice, so each size of pill has a picture of its own too.
        return key ? KEY_PREFIX + key + (diagram() ? '|diagram' : '') + (pills() ? (V.size === 'same' ? '|pills-same' : '|pills') : '') : null;
    }
    function saveArrangement() {
        const key = opts.saveKey();
        if (!key || !current) return;
        cy.batch(() => graphNodes().forEach(n => { const p = n.position(), x = Math.round(p.x), y = Math.round(p.y); if (x !== p.x || y !== p.y) n.position({ x, y }); }));
        putArrangement(arrangementKey(), encodeArrangement(G.ids, G.ids.map(id => cy.getElementById(id).position()), current.comm, [...MOVED]));
    }
    // Draws the graph as it now is in Cytoscape. The first time (after a load or Rearrange) it puts back the
    // saved arrangement, or lays the graph out; after that it keeps every known resource where it is, places
    // only new ones and keeps the groups as they were.
    function refresh() {
        if (!cy || destroyed) return;
        if (big) { bigRefresh(); return; }
        const before = current ? new Map(current.ids.map((id, i) => [id, current.comm[i]])) : null;
        G = readGraph();
        if (!G.ids.length) { current = null; MULTI = []; NAMED = new Set(); drawHulls(); drawLabels(); drawFlow(0, true); return; }
        SHORT = shortNames();
        analyse();
        let comm, first = !before, restored = false;
        if (first) {
            const key = arrangementKey(), saved = key ? getArrangement(key) : null;
            let placed = null;
            if (saved) { comm = keptGroups(saved.comm); applyLook(comm); placed = restore(saved.pos, true); restored = !!placed; }
            if (!placed) { comm = freshGroups(); applyLook(comm); if (diagram()) diagramLayout(); else groupedLayout(comm); }
            MOVED = new Set(placed && saved.moved ? saved.moved.filter(id => G.index.has(id)) : []);
        } else if (diagram()) {
            // The diagram is laid out again: dagre gives the same picture for the same graph, and a change
            // shifts only what it touches. A resource moved by hand stays where it was left.
            comm = keptGroups(before);
            applyLook(comm);
            MOVED = new Set([...MOVED].filter(id => G.index.has(id)));
            const kept = new Map([...MOVED].map(id => [id, { ...cy.getElementById(id).position() }]));
            diagramLayout();
            kept.forEach((p, id) => cy.getElementById(id).position(p));
        } else {
            comm = keptGroups(before);
            applyLook(comm);
            const pos = new Map();
            G.ids.forEach(id => { if (before.has(id)) { const p = cy.getElementById(id).position(); pos.set(id, [p.x, p.y]); } });
            restore(pos, false);
            MOVED = new Set([...MOVED].filter(id => G.index.has(id)));
        }
        const member = groupMembers(comm);
        MULTI = multiOf(comm);
        current = { ids: G.ids, comm, member, diagram: diagram() };
        boostHubLabels();
        placeFloaters(comm, member, first && !restored ? 0.5 : 3);   // after the names are sized, so it can keep clear of them
        drawLines(comm, member);
        if (first) fit();
        posVersion++;
        if (selectedId && G.index.has(selectedId)) select(selectedId); else selectedId = null;
        applyLod();
        saveArrangement();
        drawHulls();
    }
    // After a drag: the lines are routed again from the new positions and the arrangement is saved. A shared
    // resource dragged by hand stays where it was dropped; the others follow their groups.
    function afterMove(id) {
        if (!current) return;
        if (big) { const n = cy.getElementById(id); if (n.nonempty() && big.nodes.has(id)) big.pos.set(id, { ...n.position() }); posVersion++; applyLod(); drawHulls(); return; }
        if (id && G.index.has(id)) MOVED.add(id);
        placeFloaters(current.comm, current.member);
        drawLines(current.comm, current.member);
        applyLod();
        drawHulls();
        saveArrangement();
    }
    // A choice from the View menu: kept for every graph, and applied to this one with as little redrawn as it
    // takes. Size and lines keep every resource where it is.
    function setOptions(o) {
        const was = V, next = { ...V };
        Object.entries(o || {}).forEach(([k, val]) => { if (VIEW_CHOICES[k] && VIEW_CHOICES[k].includes(val)) next[k] = val; });
        V = next;
        try { localStorage.setItem(VIEW_KEY, JSON.stringify(V)); } catch (e) { /* storage blocked: the choice lasts until the page closes */ }
        if (!cy || destroyed) return;
        if (was.flows !== V.flows) flowReset();
        if (big || !current) return;
        // A new way of drawing is laid out afresh, or put back as it was last left. So is a new size of pill:
        // the pills would overlap where they stand.
        if (V.layout === 'diagram' && !window.dagre) {
            loadDagre().then(() => { if (!destroyed && !big && current && V.layout === 'diagram') { current = null; MOVED = new Set(); refresh(); } }).catch(() => { /* stays a network */ });
            return;
        }
        if (was.nodes !== V.nodes || was.layout !== V.layout || (pills() && was.size !== V.size)) { current = null; MOVED = new Set(); refresh(); return; }
        if (was.size !== V.size) { applyLook(current.comm); boostHubLabels(); }
        if (was.size !== V.size || was.lines !== V.lines) {
            drawLines(current.comm, current.member);
            posVersion++;
            applyLod();
            drawHulls();
        }
    }
    function rearrange() {
        if (big) {
            big.groups = null; big.pos.clear(); current = null; MOVED = new Set();
            big.windowing = true; cy.remove(cy.elements().not(TEMP)); big.windowing = false;
            big.shown = big.open = new Set();
            bigRefresh(true);
            return;
        }
        const key = arrangementKey();
        if (key) dropArrangement(key);
        MOVED = new Set();
        current = null;
        refresh();
    }
    // After resources are shown or hidden, the theme changed, or the tutorial highlighted something.
    function redraw() {
        if (!cy || destroyed) return;
        posVersion++;
        PATHS.clear();
        flowDirty();
        applyLod();
        drawHulls();
    }
    // Every resource's icon again, after a theme switch or a change of icon style; in a big graph also the
    // resources out of view, which take theirs when they are drawn.
    function applyIcons() {
        if (!cy || destroyed || !G) return;
        const ink = palette().ink;
        cy.batch(() => cy.nodes('.dot').forEach(n => { const g = G.nodes.get(n.id()); if (g) n.data('icon', iconOf(g, ink)); }));
        if (big) big.nodes.forEach((d, id) => { const g = G.nodes.get(id); if (g) d.icon = iconOf(g, ink); });
        redrawSoon();   // the icons are drawn on the name layer
    }
    const stopIcons = window.GraphIcons.subscribe(applyIcons);
    function applyTheme() {
        if (!cy || destroyed) return;
        palCache = null;
        applyIcons();
        cy.style().fromJson(style()).update();
        // A big graph's group dots are made again in the new colours.
        if (big && G) {
            big.windowing = true; cy.remove(cy.nodes('.agg')); big.windowing = false;
            big.synced = false;
            windowNow();
        }
        redraw();
    }
    function setRoot(id) {
        cy.nodes('.root').removeClass('root');
        rootId = id;
        if (id) cy.getElementById(id).addClass('root');
        if (big && id && big.comm) cy.getElementById('agg:' + big.comm.get(id)).addClass('root');   // shut in its group
    }
    // Forget what was drawn, so the next refresh restores or lays out the whole graph.
    function reset() { current = null; selectedId = null; MULTI = []; NAMED = new Set(); MOVED = new Set(); PATHS.clear(); hidePick(); if (big && cy) cy.minZoom(0.02); big = null; clearTimeout(winTimer); winGen++; }
    function resize() { if (!cy || destroyed) return; cy.resize(); applyLod(); drawHulls(); drawFlow(0, true); startFlow(); }
    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(() => resize()) : null;
    function destroy() {
        destroyed = true;
        stopIcons();
        if (ro) ro.disconnect();
        cancelAnimationFrame(frame); cancelAnimationFrame(flowRAF);
        clearTimeout(viewTimer); clearTimeout(lodTimer); clearTimeout(hullTimer); clearTimeout(pageScrollTimer); clearTimeout(winTimer);
        container.removeEventListener('wheel', onWheel, { capture: true });
        window.removeEventListener('scroll', onScroll);
        document.removeEventListener('visibilitychange', onVisible);
        document.removeEventListener('keydown', onKey);
        if (cy) cy.destroy();
        [hullCanvas, host, labelCanvas, flowCanvas, pick].forEach(el => el.remove());
    }

    makeCy();
    if (ro) ro.observe(container);
    container.addEventListener('wheel', onWheel, { capture: true, passive: false });
    window.addEventListener('scroll', onScroll, { passive: true });
    document.addEventListener('visibilitychange', onVisible);
    document.addEventListener('keydown', onKey);

    return {
        get cy() { return cy; },
        get webgl() { return cyWebgl; },
        refresh, rearrange, redraw, applyTheme, select, clearSel, setRoot, reset, fit, destroy, hidePick,
        options: () => ({ ...V }), setOptions, diagramMax: DIAGRAM_MAX,
        // Whether the graph is too large for the diagram (it is then drawn as a network).
        tooLargeForDiagram: () => !!G && !big && G.ids.length > DIAGRAM_MAX,
        // Big graphs: GraphNetwork hands them over instead of adding them to Cytoscape.
        bigAbove: BIG_ABOVE, isBig: () => !!big, loadBig, standIn,
        modelData: () => (big ? { nodes: [...big.nodes.values()], edges: [...big.edges.values()] } : null),
        refreshFlows: () => { cy.edges().forEach(e => { const m = { metadata: e.data('metadata') }; e.data('flow', flowOf(m)); }); flowReset(); },
        // For tests and the tutorial: what is drawn and how.
        state: () => ({ groups: current ? current.comm.slice() : [], ids: current ? current.ids.slice() : [], multi: MULTI.map(m => ({ id: m.id, groups: m.groups.slice() })), hubs: G ? [...G.hubs].map(i => G.ids[i]) : [], moved: [...MOVED], named: [...NAMED], fellBack, member: current ? [...current.member] : [], selected: selectedId, root: rootId, big: !!big, open: big ? [...big.open] : [], groupCount: big && big.groups ? big.groups.size : 0, busy: !!(big && (big.busy || winBusy)) }),
        edgesNear: (p, tol) => edgesNear(p, tol === undefined ? HIT_PX / cy.zoom() : tol),
    };
}

window.createGraphView = createGraphView;
})();
