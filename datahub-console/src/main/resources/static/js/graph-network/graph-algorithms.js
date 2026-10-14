/*
    Graph algorithms for the network view (graph-network.js): groups from the containment tree
    (BELONGS_TO, PART_OF), with Louvain as the fallback, and importance from betweenness. Pure functions
    over a plain graph built from what /resources/fetch-related returns, so nothing here knows about the
    canvas.
 */
(function () {
'use strict';

// Relations that put one resource inside another, and which end is the container: BELONGS_TO points
// from the container to what is in it, PART_OF from the part to the whole.
const CONTAINMENT = { BELONGS_TO: 'source', PART_OF: 'target' };
// A context hub is linked to at least half the graph, and to HUB_MIN resources or more: the plant
// everything is part of, the drawing sheet everything is shown on. Its relations say where a resource
// is, not how it connects, so grouping, importance and layout leave them out, and the view draws them
// only when one of their ends is selected. Not when that would leave under HUB_KEEP of the relations to
// go by, as when only a resource and its neighbours are loaded.
const HUB_MIN = 20, HUB_SHARE = 0.5, HUB_KEEP = 0.2;

// ---- seeded randomness, so every layout is the same picture for the same graph --------------------
function mulberry32(a) {
    return function () { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
}
function withSeed(seed, fn) {
    const saved = Math.random;
    Math.random = mulberry32(seed);
    try { return fn(); } finally { Math.random = saved; }
}

// ---- the graph ------------------------------------------------------------------------------------
// g = { nodes: [{ id, name, labels }], edges: [{ id, source, target, type }] }. Ids stay strings: node
// ids are hashes beyond 2^53. Each edge gets parent and child (the ids, for a containment relation, else
// null) and ctx (it touches a context hub); hubs holds the hubs' indexes. adj leaves ctx edges out.
function buildGraph(g) {
    const nodes = new Map();
    g.nodes.forEach(n => nodes.set(String(n.id), { ...n, id: String(n.id), name: String(n.name || ''), labels: (n.labels || []).map(l => String(l).toUpperCase()) }));
    const edges = g.edges.map((e, i) => ({ ...e, id: String(e.id != null ? e.id : 'e' + i), source: String(e.source), target: String(e.target) }))
        .filter(e => nodes.has(e.source) && nodes.has(e.target) && e.source !== e.target);
    const ids = [...nodes.keys()].sort((a, b) => nodes.get(a).name.localeCompare(nodes.get(b).name) || (a < b ? -1 : a > b ? 1 : 0));
    const index = new Map(ids.map((id, i) => [id, i]));
    edges.forEach(e => {
        const end = CONTAINMENT[e.type];
        e.parent = end ? (end === 'source' ? e.source : e.target) : null;
        e.child = end ? (end === 'source' ? e.target : e.source) : null;
    });
    const near = ids.map(() => new Set());
    edges.forEach(e => { const a = index.get(e.source), b = index.get(e.target); near[a].add(b); near[b].add(a); });
    let hubs = new Set(ids.map((_, i) => i).filter(i => near[i].size >= HUB_MIN && near[i].size >= HUB_SHARE * (ids.length - 1)));
    const touches = e => hubs.has(index.get(e.source)) || hubs.has(index.get(e.target));
    if (hubs.size && edges.filter(e => !touches(e)).length < HUB_KEEP * edges.length) hubs = new Set();
    edges.forEach(e => { e.ctx = touches(e); });
    // Undirected, weighted: two relationships between the same pair count twice.
    const adj = ids.map(() => new Map());
    edges.forEach(e => {
        if (e.ctx) return;
        const a = index.get(e.source), b = index.get(e.target);
        adj[a].set(b, (adj[a].get(b) || 0) + 1);
        adj[b].set(a, (adj[b].get(a) || 0) + 1);
    });
    const typeOf = n => { const t = ['DATASET', 'POLICY', 'TIMESERIES', 'FUNCTION'].find(x => n.labels.includes(x)); return t || (n.labels.includes('ASSET') ? 'ASSET' : n.labels.includes('RESOURCE') ? 'RESOURCE' : n.labels[0] || 'RESOURCE'); };
    nodes.forEach(n => { n.type = typeOf(n); });
    return { nodes, edges, ids, index, adj, hubs };
}

// ---- clustering -----------------------------------------------------------------------------------
// Each returns an array of community numbers, one per graph.ids entry, renumbered so community 0 is
// the largest; ties keep the order of the first member's name.
function renumber(comm) {
    const size = new Map(), first = new Map();
    comm.forEach((c, i) => { size.set(c, (size.get(c) || 0) + 1); if (!first.has(c)) first.set(c, i); });
    const order = [...size.keys()].sort((a, b) => size.get(b) - size.get(a) || first.get(a) - first.get(b));
    const map = new Map(order.map((c, i) => [c, i]));
    return comm.map(c => map.get(c));
}

// Modularity of a partition: the share of links inside communities minus what random wiring with the
// same degrees would put there. 0 is no structure; above 0.3 is usually called clear structure.
function modularity(G, comm, resolution = 1) {
    let m2 = 0;
    const tot = new Map(), inside = new Map();
    G.adj.forEach((row, i) => row.forEach((w, j) => {
        m2 += w;
        tot.set(comm[i], (tot.get(comm[i]) || 0) + w);
        if (comm[i] === comm[j]) inside.set(comm[i], (inside.get(comm[i]) || 0) + w);
    }));
    if (!m2) return 0;
    let q = 0;
    tot.forEach((t, c) => { q += (inside.get(c) || 0) / m2 - resolution * (t / m2) * (t / m2); });
    return q;
}

// Louvain (Blondel et al. 2008): move each node to the neighbouring community that raises modularity
// most, then merge communities into single nodes and repeat. Nodes are visited in name order, so the
// result is the same every time.
function louvain(G, resolution = 1) {
    let n = G.ids.length;
    let adj = G.adj.map(row => new Map(row));
    let member = G.ids.map((_, i) => i);
    const m2 = adj.reduce((s, row) => s + [...row.values()].reduce((a, b) => a + b, 0), 0);
    if (!m2) return renumber(member);
    for (let level = 0; level < 12; level++) {
        const k = adj.map(row => [...row.values()].reduce((a, b) => a + b, 0));
        const comm = adj.map((_, i) => i), tot = k.slice();
        let moved = true, passes = 0, any = false;
        while (moved && passes++ < 30) {
            moved = false;
            for (let i = 0; i < n; i++) {
                const ci = comm[i], wTo = new Map();
                adj[i].forEach((w, j) => { if (j !== i) wTo.set(comm[j], (wTo.get(comm[j]) || 0) + w); });
                tot[ci] -= k[i];
                let best = ci, bestGain = (wTo.get(ci) || 0) - resolution * tot[ci] * k[i] / m2;
                [...wTo.keys()].sort((a, b) => a - b).forEach(c => {
                    const gain = wTo.get(c) - resolution * tot[c] * k[i] / m2;
                    if (gain > bestGain + 1e-12) { best = c; bestGain = gain; }
                });
                tot[best] += k[i];
                if (best !== ci) { comm[i] = best; moved = true; any = true; }
            }
        }
        const ids = [...new Set(comm)].sort((a, b) => a - b), map = new Map(ids.map((c, i) => [c, i]));
        member = member.map(m => map.get(comm[m]));
        if (!any || ids.length === n) break;
        const next = ids.map(() => new Map());
        adj.forEach((row, i) => row.forEach((w, j) => { const a = map.get(comm[i]), b = map.get(comm[j]); next[a].set(b, (next[a].get(b) || 0) + w); }));
        adj = next; n = ids.length;
    }
    return renumber(member);
}

// Clusters of clusters: Louvain again on the graph of clusters, where two clusters are linked as
// strongly as the relationships between them. Gives the bundles a second level to branch at.
function superClusters(G, comm) {
    const k = Math.max(-1, ...comm) + 1;
    const adj = Array.from({ length: k }, () => new Map());
    G.adj.forEach((row, i) => row.forEach((w, j) => { const a = comm[i], b = comm[j]; adj[a].set(b, (adj[a].get(b) || 0) + w); }));
    const ids = Array.from({ length: k }, (_, i) => String(i));
    return louvain({ ids, adj });
}

// A resource whose only relations are to context hubs is drawn in a hub's group: the hub it is part of,
// or else the busiest hub it is linked to. A hub joins only a hub it is part of (a drawing sheet, the
// plant). contextHomes maps each such resource to the hub heading its group.
function contextHomes(G) {
    const home = new Map();
    if (!G.hubs || !G.hubs.size) return home;
    const deg = new Map();
    G.edges.forEach(e => { if (e.ctx) [e.source, e.target].forEach(id => { const i = G.index.get(id); if (G.hubs.has(i)) deg.set(i, (deg.get(i) || 0) + 1); }); });
    const rank = new Map();
    G.edges.forEach(e => {
        if (!e.ctx) return;
        const a = G.index.get(e.source), b = G.index.get(e.target);
        [[a, b], [b, a]].forEach(([i, h]) => {
            const part = e.child === G.ids[i] ? 1 : 0;
            if (!G.hubs.has(h) || G.adj[i].size || (G.hubs.has(i) && !part)) return;
            const r = [part, deg.get(h), -h], cur = rank.get(i);
            if (!cur || r[0] > cur[0] || (r[0] === cur[0] && (r[1] > cur[1] || (r[1] === cur[1] && r[2] > cur[2])))) { rank.set(i, r); home.set(i, h); }
        });
    });
    // Through a chain of hubs to the last, which heads the group; a cycle stops where it closes.
    const top = new Map();
    home.forEach((h, i) => { const seen = new Set([i]); while (home.has(h) && !seen.has(h)) { seen.add(h); h = home.get(h); } top.set(i, h); });
    top.forEach((h, i) => { if (h === i) top.delete(i); });
    return top;
}
// Each hub starts in a group of its own, so the hub and the resources nothing else connects end up
// together.
function withContext(G, comm) {
    const home = contextHomes(G);
    return home.size ? renumber(comm.map((c, i) => home.has(i) ? comm[home.get(i)] : c)) : comm;
}

// Hierarchical edge bundling (Holten 2006): a line follows the path through the cluster tree from
// its source to its target, and is drawn as a smooth curve through that path's points. beta < 1
// straightens each curve a little towards its own straight line, so the lines sharing a trunk stay
// separate strands of one band instead of a single stroke.
function straighten(points, beta) {
    const n = points.length, a = points[0], b = points[n - 1];
    return points.map((p, i) => { const t = i / (n - 1); return { x: beta * p.x + (1 - beta) * (a.x + t * (b.x - a.x)), y: beta * p.y + (1 - beta) * (a.y + t * (b.y - a.y)) }; });
}

// The data's own hierarchy as a clustering: cut the containment tree at one depth, so each cluster is
// a subtree. Resources without a parent join the cluster most of their neighbours are in. The depth
// is the one whose clusters explain the links best (highest modularity). A context hub's containment
// is not part of the tree: a plant that holds everything says nothing about how to group it.
function hierarchyClusters(G) {
    const isDs = i => G.nodes.get(G.ids[i]).type === 'DATASET';
    const parent = new Array(G.ids.length).fill(-1);
    const hier = G.edges.filter(e => e.parent && !e.ctx).sort((a, b) => String(a.id).localeCompare(String(b.id), undefined, { numeric: true }));
    const anc = (a, b) => { for (let x = b, k = 0; x >= 0 && k < 64; x = parent[x], k++) if (x === a) return true; return false; };
    // A resource can belong to several parents. It is drawn once, under the parent whose part of the plant
    // it has the most other relations with (that parent, or its other children); a tie goes to the oldest
    // containment, so a second parent added later does not move it. When the best two tie, it is shared.
    // Datasets are membership, not nesting: a BELONGS_TO between a dataset and a resource is left out.
    const parents = G.ids.map(() => []);
    hier.forEach(e => { const p = G.index.get(e.parent), c = G.index.get(e.child); if (isDs(p) === isDs(c) && p !== c && !parents[c].includes(p)) parents[c].push(p); });
    // A parent that is also above another parent says nothing new: a valve that is part of a line and of the
    // plant the line is in sits in the line, inside the plant. Only parents in different branches make a
    // resource belong to two groups.
    const above = q => { const seen = new Set(), st = [q]; while (st.length) { const x = st.pop(); for (const y of parents[x]) if (!seen.has(y)) { seen.add(y); st.push(y); } } return seen; };
    parents.forEach((ps, c) => { if (ps.length > 1) parents[c] = ps.filter(p => !ps.some(q => q !== p && above(q).has(p))); });
    const kids = G.ids.map(() => []);
    parents.forEach((ps, c) => ps.forEach(p => kids[p].push(c)));
    const hierPairs = new Set(hier.map(e => G.index.get(e.parent) + ':' + G.index.get(e.child)));
    const score = (c, p) => { let n = 0; G.adj[c].forEach((w, j) => { if (hierPairs.has(p + ':' + c) && j === p) return; if (j === p || kids[p].includes(j)) n += w; }); return n; };
    const shared = G.ids.map(() => false);
    parents.forEach((ps, c) => {
        if (!ps.length) return;
        const ranked = ps.map((p, k) => ({ p, k, s: score(c, p) })).sort((a, b) => b.s - a.s || a.k - b.k);
        if (ranked.length > 1 && ranked[0].s === ranked[1].s) shared[c] = true;
        for (const r of ranked) if (!anc(c, r.p)) { parent[c] = r.p; break; }
    });
    const depth = parent.map((_, i) => { let d = 0; for (let x = parent[i]; x >= 0 && d < 64; x = parent[x]) d++; return d; });
    const hasTree = parent.some(p => p >= 0);
    if (!hasTree) return { comm: G.ids.map(() => 0), depth: 0, parent, treeDepth: depth, parents, shared };
    let best = null;
    // From cut 0, where each tree is one cluster: with the plant set aside as context, its lines are the
    // roots, and a line's valves belong with it.
    for (let cut = 0; cut <= 4; cut++) {
        const comm = G.ids.map((_, i) => {
            if (parent[i] < 0 && !G.ids.some((__, j) => parent[j] === i)) return -1;   // not in the tree
            let x = i; while (depth[x] > cut) x = parent[x];
            return x;
        });
        // Fill in the unparented from their neighbours, a wave at a time.
        for (let wave = 0; wave < 30 && comm.includes(-1); wave++) {
            const next = comm.slice();
            comm.forEach((c, i) => {
                if (c !== -1) return;
                const w = new Map();
                G.adj[i].forEach((x, j) => { if (comm[j] !== -1) w.set(comm[j], (w.get(comm[j]) || 0) + x); });
                if (w.size) { const max = Math.max(...w.values()); next[i] = Math.min(...[...w.keys()].filter(k => w.get(k) === max)); }
            });
            if (next.every((c, i) => c === comm[i])) break;
            next.forEach((c, i) => { comm[i] = c; });
        }
        // What no wave reached: a connected piece is grouped on its own (Louvain on the piece), and a
        // resource with no relations here stays alone.
        const rest = G.ids.map((_, i) => i).filter(i => comm[i] === -1 && G.adj[i].size);
        if (rest.length) {
            const at = new Map(rest.map((i, k) => [i, k]));
            louvain({ ids: rest.map(i => G.ids[i]), adj: rest.map(i => new Map([...G.adj[i]].filter(([j]) => at.has(j)).map(([j, w]) => [at.get(j), w]))) })
                .forEach((c, k) => { comm[rest[k]] = 'piece' + c; });
        }
        const filled = comm.map((c, i) => c === -1 ? 'solo' + i : c);
        const r = renumber(filled), q = modularity(G, r);
        if (!best || q > best.q + 1e-9) best = { comm: r, depth: cut, q };
    }
    best.comm = withContext(G, best.comm);
    best.parent = parent; best.treeDepth = depth; best.parents = parents; best.shared = shared;
    return best;
}

// ---- importance -----------------------------------------------------------------------------------
// Brandes (2001) betweenness on the undirected graph: how many shortest paths run through each node
// and each relationship. A bridge between two parts of the plant scores high; a leaf scores zero.
function betweenness(G) {
    const n = G.ids.length, nb = G.adj.map(row => [...row.keys()]);
    const cb = new Float64Array(n), eb = new Map();
    const key = (a, b) => a < b ? a + ',' + b : b + ',' + a;
    const sigma = new Float64Array(n), dist = new Int32Array(n), delta = new Float64Array(n);
    for (let s = 0; s < n; s++) {
        const S = [], P = Array.from({ length: n }, () => []);
        sigma.fill(0); dist.fill(-1); delta.fill(0);
        sigma[s] = 1; dist[s] = 0;
        const Q = [s];
        for (let qi = 0; qi < Q.length; qi++) {
            const v = Q[qi]; S.push(v);
            for (const w of nb[v]) {
                if (dist[w] < 0) { dist[w] = dist[v] + 1; Q.push(w); }
                if (dist[w] === dist[v] + 1) { sigma[w] += sigma[v]; P[w].push(v); }
            }
        }
        while (S.length) {
            const w = S.pop();
            for (const v of P[w]) {
                const c = sigma[v] / sigma[w] * (1 + delta[w]);
                const k = key(v, w); eb.set(k, (eb.get(k) || 0) + c);
                delta[v] += c;
            }
            if (w !== s) cb[w] += delta[w];
        }
    }
    const node = Array.from(cb, v => v / 2), edge = new Map();
    eb.forEach((v, k) => edge.set(k, v / 2));
    return { node, edge, key };
}
// 0..1, square-root scaled so a few hubs don't flatten everyone else.
function normalise(v) { const max = Math.max(...v, 1e-12); return v.map(x => Math.sqrt(Math.max(0, x) / max)); }

window.GraphAlgorithms = { CONTAINMENT, withSeed, buildGraph, renumber, modularity, louvain, superClusters, contextHomes, withContext, straighten, hierarchyClusters, betweenness, normalise };
})();
