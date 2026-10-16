/*
    The icons drawn inside the network view's dots, and which one each resource gets (graph-view.js).

    Three styles, chosen in the graph's filter panel and remembered in this browser:
      symbols  process equipment in the style of a P&ID: a circle for a pump, a bow-tie for a valve, a
               capsule for a vessel, a bubble with the instrument's letters (PT, ZS). The default.
      simple   one icon per kind of resource (asset, time series, function, dataset, policy).
      none     plain dots.

    The symbols are our own drawings, on the same 16 px grid and line weight as the simple icons. They
    borrow only the basic shapes every P&ID standard shares, not NORSOK Z-004's drawings, which are
    copyrighted.

    A user can also give a label an image from Files, which then replaces the icon of every resource with
    that label. The choice is kept in this browser only, so colleagues still see the automatic icons.
 */
(function () {
'use strict';

// ---- the drawings ------------------------------------------------------------------------------------
// SVG bodies on a 16 x 16 grid, with INK standing in for the theme's ink colour.
const S = "fill='none' stroke='INK' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'";
const valve = y => `M2 ${y} 14 ${y + 6.4}V${y}L2 ${y + 6.4}z`;
const SYMBOLS = {
    pump: `<circle cx='8' cy='9' r='4.8' ${S}/><path d='M8 4.2h5.8M5 12.8l-1.6 2.3M11 12.8l1.6 2.3' ${S}/>`,
    compressor: `<circle cx='8' cy='8' r='5.6' ${S}/><path d='M3.6 4.6 13.3 6.3M3.6 11.4l9.7-1.7' ${S}/>`,
    fan: `<circle cx='8' cy='8' r='5.8' ${S}/><path d='M8 8C7.6 5.6 8.4 3.6 9.9 3.9s1.1 2.7-1.9 4.1zM8 8c-1.9 1.4-4 1.6-4.4.2S5.3 5.8 8 8zM8 8c2.3.6 3.6 2.2 2.6 3.3S7.7 11 8 8z' fill='INK'/>`,
    valve: `<path d='${valve(4.8)}' ${S}/>`,
    'control-valve': `<path d='${valve(7.6)}M8 10.8V5M5 5a3 3 0 0 1 6 0z' ${S}/>`,
    'check-valve': `<path d='${valve(4.8)}' ${S}/><path d='M2 4.8 8 8 2 11.2z' fill='INK'/>`,
    'shutdown-valve': `<path d='${valve(7.6)}M8 10.8V5.6' ${S}/><rect x='5' y='1.8' width='6' height='3.8' rx='.6' ${S}/>`,
    choke: `<path d='${valve(4.8)}M3.5 14.2 12.6 2.6M9.4 2.4h3.4v3.4' ${S}/>`,
    xtree: `<path d='M8 1.8v12.6M4 14.4h8M3.6 5.6h8.8M4.6 9.6h6.8' ${S}/><rect x='6.6' y='1.6' width='2.8' height='2.2' fill='INK'/>`,
    well: `<path d='M2.4 7.2h11.2M8 7.2v7.4M5.2 7.2 8 1.8l2.8 5.4M6.2 5h3.6' ${S}/>`,
    separator: `<rect x='1.6' y='4.4' width='12.8' height='7.2' rx='3.6' ${S}/><path d='M10.4 8.2v3.4M3.6 9.2h5' ${S}/>`,
    vessel: `<rect x='4.6' y='2.4' width='6.8' height='11.2' rx='3.4' ${S}/><path d='M8 .6v1.8M8 13.6v1.8' ${S}/>`,
    tank: `<path d='M3 6.4Q8 2.2 13 6.4V14H3z' ${S}/>`,
    'heat-exchanger': `<circle cx='8' cy='8' r='5.6' ${S}/><path d='M1.4 8h3.2l1.4-2.8 2.8 5.6L10.2 8h4.4' ${S}/>`,
    column: `<rect x='5' y='1.2' width='6' height='13.6' rx='3' ${S}/><path d='M5 5.6h6M5 8h6M5 10.4h6' ${S}/>`,
    motor: `<circle cx='8' cy='8' r='5.6' ${S}/><path d='M5.4 10.6V5.4L8 8.6l2.6-3.2v5.2' ${S}/>`,
    filter: `<path d='M8 1.8 14.2 8 8 14.2 1.8 8z' ${S}/><path d='M8 2.6v10.8' fill='none' stroke='INK' stroke-width='1.3' stroke-dasharray='1.6 1.4'/>`,
    manifold: `<path d='M2 4.6h12M4 4.6v7.8M8 4.6v7.8M12 4.6v7.8' ${S}/>`,
    reservoir: `<path d='M1.6 7.2c2-1.6 4.4-1.6 6.4 0s4.4 1.6 6.4 0M1.6 11.2c2-1.6 4.4-1.6 6.4 0s4.4 1.6 6.4 0' ${S}/><path d='M8 1.4v4' ${S}/>`,
    line: `<path d='M1.6 8h10.6M9.4 4.8 13.2 8l-3.8 3.2' ${S}/>`,
    transformer: `<circle cx='6' cy='8' r='3.8' ${S}/><circle cx='10' cy='8' r='3.8' ${S}/>`,
    site: `<path d='M3 14.6 5 8.2h6l2 6.4M4 11.4h8M5.4 8.2V5.6h5.2v2.6M9 5.6V1.8' ${S}/>`,
    system: `<rect x='2' y='2' width='5' height='5' rx='1' ${S}/><rect x='9' y='2' width='5' height='5' rx='1' ${S}/><rect x='2' y='9' width='5' height='5' rx='1' ${S}/><rect x='9' y='9' width='5' height='5' rx='1' ${S}/>`,
    agent: `<path d='M8 1.8l1.5 4.7L14.2 8l-4.7 1.5L8 14.2l-1.5-4.7L1.8 8l4.7-1.5z' ${S}/>`,
    instrument: `<circle cx='8' cy='8' r='6' ${S}/><path d='M8 8.6 10.9 5.3M4.8 10.6h1M10.2 10.6h1' ${S}/><circle cx='8' cy='8.6' r='1.1' fill='INK'/>`,
    drawing: `<path d='M3.6 1.8h6l2.8 2.8v9.6H3.6zM9.6 1.8v2.8h2.8M5.6 8.4h4.8M5.6 11h4.8' ${S}/>`,
};
// One icon per kind of resource: the "simple" style, and what the symbols fall back to.
const SIMPLE = {
    ASSET: "<path d='M8 1.8 13.6 4.9v6.2L8 14.2 2.4 11.1V4.9z M2.4 4.9 8 8l5.6-3.1 M8 8v6.2' fill='none' stroke='INK' stroke-width='1.6' stroke-linejoin='round'/>",
    TIMESERIES: "<path d='M2 11.5 5.5 7.5l2.8 2.4L14 4' fill='none' stroke='INK' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'/>",
    FUNCTION: "<path d='M10.5 2.5c-2 0-2.6 1.2-3 3.2L6.2 12c-.3 1.4-1 2-2.2 2 M4.5 6.5h6' fill='none' stroke='INK' stroke-width='1.6' stroke-linecap='round'/>",
    DATASET: "<ellipse cx='8' cy='4' rx='5' ry='2' fill='none' stroke='INK' stroke-width='1.5'/><path d='M3 4v8c0 1.1 2.2 2 5 2s5-.9 5-2V4' fill='none' stroke='INK' stroke-width='1.5'/>",
    POLICY: "<path d='M8 1.8 13 3.6v3.9c0 3-2.1 5.4-5 6.4-2.9-1-5-3.4-5-6.4V3.6z' fill='none' stroke='INK' stroke-width='1.6' stroke-linejoin='round'/>",
    DEFAULT: "<circle cx='8' cy='8' r='2.6' fill='INK'/>",
};
const simpleKey = type => SIMPLE[type] ? type : (type === 'RESOURCE' ? 'ASSET' : 'DEFAULT');
// An instrument shows its letters in a bubble, as on a P&ID. The image cannot use the page's fonts, so
// the letters are in a font every system has.
function bubble(letters) {
    const fs = letters.length <= 2 ? 5.8 : letters.length === 3 ? 4.6 : 3.7;
    return `<circle cx='8' cy='8' r='6.6' ${S}/><text x='8' y='${8 + fs * 0.36}' text-anchor='middle' font-family='Arial,Helvetica,sans-serif' font-weight='700' font-size='${fs}' fill='INK'>${letters}</text>`;
}

// ---- which symbol a resource gets -------------------------------------------------------------------
// The first rule that finds something wins:
//   1. a label that names the thing (PUMP, CENTRIFUGAL_PUMP, "User Valve"), the most specific first,
//   2. an instrument code among the labels (PT, ZS), written in the bubble,
//   3. a general instrument label (INSTRUMENT, GAUGE): a gauge dial,
//   4. the kind of resource: time series, function, dataset, policy,
//   5. a tag in the name: NORSOK equipment (27PA001A), ISA instruments (PT-1001), shutdown valves (XV-2001),
//   6. a word in the name, only when the labels say nothing beyond ASSET (pump, pumpe, ventil),
//   7. otherwise the simple icon.

// Earlier entries win: a choke is also a valve, but the choke symbol says more.
const LABEL_SYMBOL = [
    ['XTREE', 'xtree'], ['CHOKE', 'choke'], ['PCK', 'choke'], ['GLCK', 'choke'],
    ['CHECK_VALVE', 'check-valve'], ['SAFETY_VALVE', 'shutdown-valve'], ['DHSV', 'shutdown-valve'], ['PSDV', 'shutdown-valve'], ['SDV', 'shutdown-valve'], ['ESV', 'shutdown-valve'], ['XV', 'shutdown-valve'],
    ['CONTROL_VALVE', 'control-valve'], ['VALVE', 'valve'], ['PWV', 'valve'], ['AWV', 'valve'], ['PMW', 'valve'], ['AMW', 'valve'], ['PXO', 'valve'],
    ['COMPRESSOR', 'compressor'], ['PUMP', 'pump'], ['IMPELLER', 'pump'], ['FAN', 'fan'], ['BLOWER', 'fan'],
    ['SEPARATOR', 'separator'], ['COLUMN', 'column'], ['VESSEL', 'vessel'], ['DRUM', 'vessel'], ['SCRUBBER', 'vessel'], ['TANK', 'tank'],
    ['HEAT_EXCHANGER', 'heat-exchanger'], ['TUBE_BUNDLE', 'heat-exchanger'], ['COOLER', 'heat-exchanger'], ['MOTOR', 'motor'], ['FILTER', 'filter'],
    ['WELL', 'well'], ['MANIFOLD', 'manifold'], ['RESERVOIR', 'reservoir'], ['LINE', 'line'], ['PIPELINE', 'line'], ['PIPELINESTEP', 'line'],
    ['TRANSFORMER', 'transformer'], ['FIELD', 'site'], ['SITE', 'site'], ['PLATFORM', 'site'], ['STATION', 'site'],
    ['DRAWING', 'drawing'], ['DOCUMENT', 'drawing'], ['SYSTEM', 'system'], ['SUBSYSTEM', 'system'], ['PROCESSING', 'system'], ['AI_AGENT', 'agent'],
    // A pressure and temperature transmitter, signals and analytics: these keep a code or their kind's icon.
    ['TPT', 'isa:TPT'], ['SIGNAL', 'TIMESERIES'], ['PREPROCESSOR', 'FUNCTION'], ['ANOMALY', 'FUNCTION'],
];
const RANK = new Map(LABEL_SYMBOL.map(([label], i) => [label, i]));
const INSTRUMENT_WORDS = new Set(['INSTRUMENT', 'GAUGE', 'SENSOR', 'TRANSMITTER', 'INDICATOR', 'CONTROLLER', 'SWITCH', 'RECORDER', 'DISPLACER', 'GLASS']);
// ISA 5.1: the measured variable, an optional modifier, then what the instrument does.
const ISA = /^[AEFHIJKLPQRSTUVWYZ][DFQS]?[ACEGHIKLMRSTVYZ]{1,3}$/;
// NORSOK Z-DP-002 equipment codes confirmed in use in Equinor project documents; the code list itself
// is not public. Other codes are guessed from their first letter, and only after the instrument tags.
const NORSOK_CODE = { PA: 'pump', PH: 'pump', KA: 'compressor', HA: 'heat-exchanger', VD: 'vessel', TB: 'tank', ET: 'transformer' };
const NORSOK_FAMILY = { P: 'pump', K: 'compressor', H: 'heat-exchanger', V: 'vessel', T: 'tank', E: 'transformer' };
const PLAIN = new Set(['ASSET', 'RESOURCE', 'EQUIPMENT']);
// Words that look like an instrument code when a name is in capitals ("WELL 01").
const NOT_TAGS = new Set(['WELL', 'SITE', 'HEAT', 'RATE', 'SEAL', 'TEST', 'FLAT', 'LIFT', 'LEAK', 'PASS', 'SALT', 'TILE', 'WEST', 'EAST', 'AREA', 'ZONE', 'UNIT']);
const SHUTDOWN_TAG = /^(XV|EV|ESV|SDV)$/;
const NAME_WORDS = [[/\bpumpe?\b/i, 'pump'], [/\bk?compressor\b|\bkompressor\b/i, 'compressor'], [/\bfan\b|\bvifte\b|\bblower\b/i, 'fan'], [/\bvalve\b|\bventil\b/i, 'valve']];
// Imports write a label as "User Valve" or USER_VALVE; they are all compared in the second form.
const canon = l => String(l).trim().toUpperCase().replace(/[\s-]+/g, '_');

// Rules 1 to 3 read only the labels, and many resources share the same ones, so the answer is kept per set
// of labels. undefined means the labels decide nothing.
const byLabels = new Map();
function fromLabels(labels) {
    const k = labels.join('\u0001');
    if (byLabels.has(k)) return byLabels.get(k);
    let best = Infinity, instrument = false, code;
    for (const l of labels) {
        // A label names a thing when it is the thing, or when its words begin or end with it:
        // PLATE_HEAT_EXCHANGER is a heat exchanger, CHECK_VALVE_SWING a check valve.
        const w = l.split('_');
        for (let a = 0; a < w.length; a++) for (let b = a + 1; b <= w.length; b++) {
            if (a > 0 && b < w.length && b - a > 1) continue;
            const r = RANK.get(w.slice(a, b).join('_'));
            if (r !== undefined && r < best) best = r;
        }
        if (w.some(x => INSTRUMENT_WORDS.has(x))) instrument = true;
        // Instrument codes are two to four letters: a longer label (ASSET) is a word, not a code.
        if (code === undefined && l.length <= 4 && ISA.test(l)) code = l;
    }
    const found = best < Infinity ? LABEL_SYMBOL[best][1]
        : code ? (code.endsWith('V') ? 'valve' : 'isa:' + code)
        : instrument ? 'instrument' : undefined;
    byLabels.set(k, found);
    return found;
}

function fromName(name, labels) {
    // Tags are written in capitals, so a capitalised word in a name ("Heat 100") is not one.
    const n = name.match(/(?:^|[^A-Z0-9])\d{2}[-\s]?([A-Z]{2})[-\s]?\d{3,4}[A-Z]?(?:$|[^A-Z0-9])/);
    if (n && NORSOK_CODE[n[1]]) return NORSOK_CODE[n[1]];
    const t = name.match(/(?:^|[^A-Z])([A-Z]{2,4})[-\s]?\d{2,6}[A-Z]?(?:$|[^A-Z0-9])/);
    if (t) {
        const c = t[1];
        // In Norway X marks a safety on/off function, so XV is a shutdown valve though ISA leaves X open.
        if (SHUTDOWN_TAG.test(c)) return 'shutdown-valve';
        if (!NOT_TAGS.has(c) && ISA.test(c)) return /CV$/.test(c) ? 'control-valve' : c.endsWith('V') ? 'valve' : 'isa:' + c;
    }
    if (n && NORSOK_FAMILY[n[1][0]]) return NORSOK_FAMILY[n[1][0]];
    if (labels.every(l => PLAIN.has(l))) for (const [re, symbol] of NAME_WORDS) if (re.test(name)) return symbol;
    return undefined;
}

/** The symbol for a resource ({labels, name, type}), whatever style is chosen. */
function symbolFor(node) {
    const labels = (node.labels || []).map(canon);
    const found = fromLabels(labels);
    if (found) return found;
    if (node.type === 'DATASET' || node.type === 'POLICY' || node.type === 'FUNCTION' || node.type === 'TIMESERIES') return node.type;
    return fromName(String(node.name || ''), labels) || simpleKey(node.type);
}

// ---- what this browser remembers --------------------------------------------------------------------
// { style, images: { LABEL: { id, name } } }, where id is the file's external id in Files.
const SETTINGS_KEY = 'dh.graph.icons.v1', STYLES = ['symbols', 'simple', 'none'];
function readSettings() {
    let o = null;
    try { o = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null'); } catch (e) { o = null; }
    const style = o && STYLES.includes(o.style) ? o.style : 'symbols';
    const images = {};
    if (o && o.images && typeof o.images === 'object') {
        Object.entries(o.images).forEach(([label, f]) => { if (f && typeof f.id === 'string' && f.id) images[canon(label)] = { id: f.id, name: String(f.name || f.id) }; });
    }
    return { style, images };
}
let settings = readSettings();
// Raised whenever the style or a label's image changes, so every resource's icon is worked out again.
let version = 0;
const listeners = new Set();
function changed() { listeners.forEach(fn => { try { fn(); } catch (e) { /* a view that failed must not stop the others */ } }); }
function save() {
    try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); } catch (e) { /* storage blocked: the choice lasts until the page closes */ }
    version++;
    changed();
}

// ---- images from Files -------------------------------------------------------------------------------
// An image is drawn once onto a small canvas, so a big picture costs no more than a symbol, and every
// resource with the label shares it. It is loaded through the console's own download route, as the Files
// page's preview is, and only ever as an image: an SVG's scripts do not run that way.
const IMAGE_PX = 64;
const images = new Map();   // file id -> { state: 'loading' | 'ready' | 'failed', src }
function loadImage(id) {
    const entry = { state: 'loading', src: null };
    images.set(id, entry);
    const img = new Image();
    img.onload = () => {
        try {
            // An SVG with only a viewBox reports no size; it is drawn to fill the square.
            const w = img.naturalWidth || IMAGE_PX, h = img.naturalHeight || IMAGE_PX, k = IMAGE_PX / Math.max(w, h);
            const canvas = document.createElement('canvas');
            canvas.width = canvas.height = IMAGE_PX;
            canvas.getContext('2d').drawImage(img, (IMAGE_PX - w * k) / 2, (IMAGE_PX - h * k) / 2, w * k, h * k);
            entry.src = canvas.toDataURL('image/png');
            entry.state = 'ready';
        } catch (e) {
            entry.state = 'failed';
        }
        changed();
    };
    img.onerror = () => { entry.state = 'failed'; changed(); };
    img.src = '/files/download/' + encodeURIComponent(id);
    return entry;
}
const isImageFile = f => !!f && f.type !== 'FOLDER'
    && ((f.mimeType && String(f.mimeType).indexOf('image/') === 0) || /\.(gif|png|jpe?g|webp|svg|bmp|avif|ico)$/i.test(f.name || ''));

// ---- what the view asks -----------------------------------------------------------------------------
const cache = new Map();
function svgSrc(key, ink) {
    const k = key + ink;
    let src = cache.get(k);
    if (!src) {
        const body = key.startsWith('isa:') ? bubble(key.slice(4)) : (SYMBOLS[key] || SIMPLE[key] || SIMPLE.DEFAULT);
        src = 'data:image/svg+xml;utf8,' + encodeURIComponent(`<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 16 16'>${body.replace(/INK/g, ink)}</svg>`);
        cache.set(k, src);
    }
    return src;
}
// The label whose image a resource shows: its first label with one, a specific label before ASSET.
function imageFor(labels) {
    let plain = null;
    for (const l of labels) {
        const f = settings.images[canon(l)];
        if (!f) continue;
        if (!PLAIN.has(canon(l))) return f;
        plain = plain || f;
    }
    return plain;
}

/**
 * The icon for a resource of the view's graph ({labels, name, type}) in the given ink, as an image source,
 * or 'none' for a plain dot. What was worked out is kept on the resource until the settings change.
 */
function iconSrc(node, ink) {
    if (settings.style === 'none') return 'none';
    let c = node.__icon;
    if (!c || c.v !== version) {
        const auto = settings.style === 'simple' ? simpleKey(node.type) : symbolFor(node);
        const f = imageFor(node.labels || []);
        c = node.__icon = { v: version, auto, image: f ? f.id : null };
    }
    if (c.image) {
        const entry = images.get(c.image) || loadImage(c.image);
        if (entry.state === 'ready') return entry.src;
    }
    return svgSrc(c.auto, ink);
}

window.GraphIcons = {
    STYLES,
    iconSrc,
    symbolFor,
    isImageFile,
    style: () => settings.style,
    setStyle(style) { if (STYLES.includes(style) && style !== settings.style) { settings.style = style; save(); } },
    /** The image a label shows ({ id, name }), or null for the automatic icon. */
    imageOf: label => settings.images[canon(label)] || null,
    setImage(label, file) {
        const k = canon(label);
        if (file && file.id) settings.images[k] = { id: String(file.id), name: String(file.name || file.id) };
        else delete settings.images[k];
        save();
    },
    /** Whether an image a label was given could not be loaded (deleted, or no access to it). */
    imageFailed: id => (images.get(id) || {}).state === 'failed',
    /** The icon a label shows in the filter panel, in the given ink. */
    labelSrc(label, ink) {
        const f = settings.images[canon(label)];
        if (f) {
            const entry = images.get(f.id) || loadImage(f.id);
            if (entry.state === 'ready') return entry.src;
        }
        return svgSrc(symbolFor({ labels: [label], name: '', type: canon(label) }), ink);
    },
    /** fn is called when icons should be drawn again; returns the function that stops it. */
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
};
})();
