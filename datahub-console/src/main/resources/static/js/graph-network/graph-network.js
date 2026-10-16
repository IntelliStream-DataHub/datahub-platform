// Graph palette, in one place — read by the drawing (graph-view.js) for the relation lines and the
// interaction colours. Two variants: the light one mirrors the intellistream.ai hero graph (thin
// blue-grey connectors on a white canvas); the dark one keeps the console's dark canvas. The active
// palette is picked from <html data-theme> and re-applied live on theme change (see applyTheme()).
const GRAPH_PALETTES = {
    light: {
        nodeSurface: '#ffffff',  // node fill — white pill in light theme
        labelText: '#17313d',    // node label — dark ink on the white pill
        edge: '#9fb9c8',         // soft blue-grey connector
        edgeText: '#64808d',     // italic relationship label
        textBg: '#ffffff',       // relationship-label chip background
        accent: '#f6783c',       // interaction (edge select / edge-handles) — brand orange
        selected: '#f6783c',     // selected node outline — brand orange, like the hero
        selectedHalo: '#ff9e63', // selected node halo (overlay)
        tutorial: '#105984',     // tutorial highlight (Navy brand accent)
    },
    dark: {
        nodeSurface: '#0c2233',  // node fill — dark chip in dark theme
        labelText: '#e8f1f6',    // node label — bright on the dark chip
        edge: '#4a6472',
        edgeText: '#c7d6e0',
        textBg: '#0e2233',
        accent: '#ffb155',
        selected: '#ffb155',
        selectedHalo: '#ffcf99',
        tutorial: '#63c5f4',
    },
};
function graphPalette() {
    return document.documentElement.getAttribute('data-theme') === 'light'
        ? GRAPH_PALETTES.light : GRAPH_PALETTES.dark;
}
// Reassigned by applyTheme() when the theme toggles; the drawing reads it live.
let GRAPH_COLORS = graphPalette();

// The view of a graph of resources: the drawing itself is graph-view.js (dots grouped by the
// BELONGS_TO tree, curved bundles, outlines, names); this class loads the graph from datahub-api
// and wires it to the console: the node's action menu, the relation and resource forms, drawing a
// new relation, the filter, and the hooks the guided tutorial uses.
// How many resources a network load asks for: all of them. The api stops at 5,000 unless told
// otherwise, which left anything larger cut off at its nearest 5,000. It streams the network now, and
// the view draws a large one group by group (graph-view.js, big mode), so the whole network is fetched.
const NETWORK_LIMIT = -1;

class GraphNetwork {

    constructor(obj) {
        this.rootId = null;
        this.loadedId = null;
        this.queryDepth = obj.queryDepth || 5;
        this.container = obj.container;
        // A page makes a new GraphNetwork in the same container whenever another root is picked. The
        // one before lets go of the container first, so canvases and listeners don't pile up.
        if (this.container.__graphNetwork) this.container.__graphNetwork.destroy();
        this.container.__graphNetwork = this;
        this.labels = [];
        GRAPH_COLORS = graphPalette();   // the theme may have changed while no graph was open
        this.view = createGraphView(this.container, {
            colors: () => GRAPH_COLORS,
            theme: () => document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark',
            // The arrangement is remembered per graph: the resource it was opened from, and how deep.
            saveKey: () => this.loadedId == null ? null : this.loadedId + '|' + this.queryDepth,
            onEdgePick: edge => this.openEdgeForm(edge),
            picking: () => !this.addEdgeModeEnabled,
            tr: (key, args) => args ? $L(key, null, args) : $L(key),
            // A big graph brings resources into Cytoscape as they come into view; a filter hides them too.
            // Resources brought into view arrive unfiltered, so the filter only has work while it is set.
            onWindow: () => { if (this.filter && this.filter.state && this.filter.isSet()) this.filter.apply(); },
        });

        // Re-theme the graph live when the user toggles the colour theme (the toggle sets
        // data-theme on <html>; see layout/main.html).
        this.themeObserver = new MutationObserver(() => this.applyTheme());
        this.themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });

        // Clicking away closes the node's action menu: on the canvas, an edge, or anywhere else
        // on the page. Capture phase, so a handler that stops propagation cannot keep it open.
        // A press on another node closes it too, and that node's tap opens its own.
        this.onDocumentPointerDown = (event) => {
            if (this.activeTooltip && !this.activeTooltip.contains(event.target)) {
                this.removeTooltip();
            }
            // The View menu closes the same way, unless the press is on the menu or its button.
            if (this.viewMenu && !this.viewMenu.hidden && !this.viewMenu.contains(event.target) && !this.viewBtn.contains(event.target)) {
                this.closeViewMenu();
            }
        };
        // Esc closes the View menu, and stops drawing a relation.
        this.onKeyDown = (event) => {
            if (event.key !== 'Escape') return;
            if (this.viewMenu && !this.viewMenu.hidden) this.closeViewMenu();
            else if (this.addEdgeModeEnabled) this.setDrawMode(false);
        };
        document.addEventListener('keydown', this.onKeyDown);
        document.addEventListener('pointerdown', this.onDocumentPointerDown, true);

        this.cy.on('grab', 'node', (e) => {
            this.removeTooltip();
        });

        this.cy.on('tap', 'node.dot', (event) => {
            this.removeTooltip();
            const node = event.target;
            this.setupTooltip(node);
        });
        this.cy.on("zoom", params => {
            this.resetTooltipAndSelection();
        });

        this.cy.on("pan", params => {
            this.resetTooltipAndSelection();
        });

        this.cy.on('mouseover', 'node.dot, node.agg', (event) => {
            this.container.style.cursor = 'pointer';
            this.showNodeTooltip(event.target);
        });
        // Restore cursor when not hovering
        this.cy.on('mouseout', 'node', (event) => {
            this.container.style.cursor = 'default';
            this.hideNodeTooltip();
        });
        // Selecting a resource dims everything but it and its neighbours, and colours its relations
        // by direction: one colour where they leave it, another where they arrive.
        this.cy.on('select', 'node.dot', (evt) => this.view.select(evt.target.id()));
        this.cy.on('unselect', 'node.dot', () => this.view.clearSel());

        this.setupEdgeHandles();
        this.createViewMenu();
        this.filter = new GraphFilter(this);
    }

    // The Cytoscape instance belongs to the drawing.
    get cy() {
        return this.view.cy;
    }

    // Let go of the container: the drawing, its canvases and listeners, and this class's buttons.
    destroy() {
        this.destroyed = true;
        this.removeTooltip();
        this.hideNodeTooltip();
        this.themeObserver.disconnect();
        document.removeEventListener('pointerdown', this.onDocumentPointerDown, true);
        document.removeEventListener('keydown', this.onKeyDown);
        if (this.stopIconsInMenu) this.stopIconsInMenu();
        if (this.eh) this.eh.destroy();
        [this.viewBtn, this.viewMenu, this.drawHint].forEach(el => el && el.remove());
        this.view.destroy();
        if (this.container.__graphNetwork === this) delete this.container.__graphNetwork;
    }

    // A relation was clicked (or chosen, where several meet): open its edit form.
    openEdgeForm(edge) {
        if (this.addEdgeModeEnabled) return;
        new ResourceEditEdgeForm({
            entityId: edge.id().replace("edge-", ""),
            network: this,
            afterSave: data => {
                for(let i = 0; i < data.relations.length; i++){
                    const relation = data.relations[i];
                    const edge = this.cy.getElementById("edge-" + relation.id);
                    edge.data('label', relation.type);
                    edge.data('metadata', relation.metadata || {});   // reflect the edited metadata
                    edge.move({
                        source: "node-" + relation.start,
                        target: "node-" + relation.end
                    });
                }
                // A reversed or re-pointed relation takes a new path, and its "data flow" label
                // may have started or stopped.
                this.relayout();
            },
            afterDelete: data => {
                this.cy.$('#edge-' + data.entityId).remove();
                this.relayout();
            }
        }).render();
    }

    // Draw again after resources or relations were added, removed or changed. Known resources stay
    // where they are and only new ones are placed. The forms add a node and its relations one call
    // at a time, so the redraw waits until they are all in.
    relayout() {
        if (this.refreshQueued) return;
        this.refreshQueued = true;
        queueMicrotask(() => {
            if (!this.refreshQueued || this.destroyed) return;
            this.refreshQueued = false;
            this.view.refresh();
            // Something added while a filter is on is hidden like the rest.
            if (this.filter) this.filter.apply();
        });
    }

    resetTooltipAndSelection(){
        this.removeTooltip();
        this.hideNodeTooltip();
        //this.network.unselectAll();
    }

    removeTooltip(){
        if(this.editEdgeTooltip){
            this.editEdgeTooltip.remove();
            this.editEdgeTooltip = null;
        }
        if(this.activeTooltip){
            this.activeTooltip.remove();
            this.activeTooltip = null;
        }
    }

    // Hover tooltip with a resource's full name and types. The canvas shows a shortened name (the part
    // its parent already says is dropped), and only where names don't overlap, so the full one is here.
    showNodeTooltip(node){
        const label = node.data('label') || '';
        this.hideNodeTooltip();
        if(!label) return;
        const types = (Array.isArray(node.data('labels')) ? node.data('labels') : []).join(', ');
        const rpos = node.renderedPosition();
        this.nodeTooltip = Object.assign(document.createElement('div'), {
            className: 'dh-node-tooltip',
            innerHTML: window.escapeHtml(label) + (types ? `<span class="dh-node-tooltip-types">${window.escapeHtml(types)}</span>` : '')
        });
        this.nodeTooltip.style.left = rpos.x + 'px';
        this.nodeTooltip.style.top = (rpos.y - node.renderedHeight() / 2 - 8) + 'px';
        this.container.append(this.nodeTooltip);
    }

    hideNodeTooltip(){
        if(this.nodeTooltip){
            this.nodeTooltip.remove();
            this.nodeTooltip = null;
        }
    }

    // --- Guided-tutorial targeting hook --------------------------------------
    // The graph renders to <canvas>, so a node has no DOM element to anchor a
    // tutorial spotlight to. These expose a node's live screen rect plus a redraw
    // subscription, so the tutorial can track a node as the canvas pans/zooms.

    // Find a node by its externalId (carried in node data); null if absent.
    nodeByExternalId(externalId){
        if(!externalId) return null;
        const match = this.cy.nodes().filter(n => n.data('externalId') === externalId);
        return match.nonempty() ? match[0] : null;
    }

    // Current viewport rect (fixed-position CSS px) of a node, or null. The node's
    // renderedBoundingBox is relative to the canvas; offset by the container's own
    // screen position to get viewport coordinates.
    nodeScreenRect(externalId){
        const node = this.nodeByExternalId(externalId);
        if(!node) return null;
        const bb = node.renderedBoundingBox();
        const c = this.container.getBoundingClientRect();
        return {
            left: c.left + bb.x1, top: c.top + bb.y1,
            right: c.left + bb.x2, bottom: c.top + bb.y2,
            width: bb.w, height: bb.h,
        };
    }

    // Center the node so the tutorial can reliably spotlight it; the spotlight
    // tracks the move because every animation frame fires 'render'.
    focusNodeIntoView(externalId, { duration = 250 } = {}){
        const node = this.nodeByExternalId(externalId);
        if(!node) return false;
        // cy may have been initialised while its container was mid-relayout (the
        // right-form closing as the tutorial advances to the select step), leaving
        // Cytoscape with a stale/zero canvas size that paints blank until something
        // resizes it. Re-measure before centering so the node actually shows.
        this.cy.resize();
        if(duration > 0){
            this.cy.animate({ center: { eles: node } }, { duration });
        } else {
            // Snap synchronously so the pan is final before we repaint the labels below.
            this.cy.center(node);
        }
        // The HTML-label overlay (cytoscape-node-html-label) only repaints on pan/zoom and
        // data events — a bare resize()/instant center fires neither, so a node focused right
        // after the graph (re)loaded (e.g. a freshly created resource in the tour, or one the
        // node already sat centred so the center was a no-op) can render blank until the user
        // pans/zooms. Re-fire both so its label paints at the node's spot now.
        this.cy.emit('pan');
        this.refreshLabels();
        return true;
    }

    // Subscribe to canvas redraws (pan/zoom/drag/layout animation). Fires only when
    // the canvas actually repaints — no idle cost. Returns an unsubscribe fn.
    onViewportChange(cb){
        this.cy.on('render', cb);
        return () => this.cy.off('render', cb);
    }

    // Resolve once a node with this externalId is in the graph. A just-created resource
    // may still be propagating from Postgres to Neo4j (applied from the outbox queue just
    // after commit), and the graph only fetches on select — so re-load the network while it
    // is still EMPTY (propagation pending). Once the graph has nodes we only poll: a
    // re-load then would just clear what is already shown without finding anything new.
    // Resolves with the node, or null on timeout. Promise-based (not async) to match
    // the rest of the file.
    waitForNode(externalId, { interval = 300, reloadInterval = 1500, timeout = 30000 } = {}){
        return new Promise(resolve => {
            const t0 = performance.now();
            let lastReload = -Infinity;
            const tick = () => {
                if(this.destroyed) return resolve(null);   // another root was opened meanwhile
                const node = this.nodeByExternalId(externalId);
                if(node) return resolve(node);
                const elapsed = performance.now() - t0;
                if(elapsed >= timeout) return resolve(null);
                // Poll cheaply for the node every `interval`, but only RELOAD the network on the
                // slower `reloadInterval` — a reload is a full fetch and would hammer the API if
                // done every poll. So the spotlight appears promptly once the node lands, without
                // flooding reloads while it is still propagating (Postgres -> Pulsar -> Neo4j).
                if(this.rootId != null && this.cy.nodes().empty() && (elapsed - lastReload >= reloadInterval)){
                    lastReload = elapsed;
                    this.loadNetwork(this.rootId, false);
                }
                setTimeout(tick, interval);
            };
            tick();
        });
    }

    // --- Tutorial multi-element highlight ----------------------------------------------
    // Glow a set of nodes + the relations between/among them and dim everything else, so
    // the tour can explain several connected nodes/relations at once — the single-node
    // spotlight can only isolate one node. Styling lives in the stylesheet (the
    // .tut-highlight / .tut-dim selectors, Navy accent) like the rest of the graph's
    // colors; these methods only toggle classes. clearHighlight() removes them.
    highlightElements(externalIds){
        this.clearHighlight();
        const ids = new Set((externalIds || []).filter(Boolean));
        const nodes = this.cy.nodes().filter(n => ids.has(n.data('externalId')));
        if(nodes.empty()) return false;
        const edges = nodes.connectedEdges().filter(e => nodes.contains(e.source()) && nodes.contains(e.target()));
        const focus = nodes.union(edges);
        this.cy.elements().difference(focus).addClass('tut-dim');
        focus.addClass('tut-highlight');
        return true;
    }

    // Glow the whole network (every node + relation) — the "this is your knowledge graph" beat.
    highlightAll(){
        this.clearHighlight();
        if(this.cy.nodes().empty()) return false;
        this.cy.elements().addClass('tut-highlight');
        return true;
    }

    // Glow one node, the nodes it connects to, and the relations between them — dimming
    // everything else. Used to spotlight "these two are now connected" after a relation
    // is created, without needing the freshly-created neighbour's external id.
    highlightNeighborhood(externalId){
        this.clearHighlight();
        if(!externalId) return false;
        const node = this.cy.nodes().filter(n => n.data('externalId') === externalId);
        if(node.empty()) return false;
        const focus = node.closedNeighborhood(); // node + its edges + adjacent nodes
        this.cy.elements().difference(focus).addClass('tut-dim');
        focus.addClass('tut-highlight');
        return true;
    }

    // Revert any tutorial highlight (toggled classes only; base stylesheet untouched).
    clearHighlight(){
        this.cy.elements().removeClass('tut-highlight tut-dim');
    }

    setupTooltip(node) {
        // Floating action toolbar, centered just above the selected node. Rebuilt as an HTML
        // Font Awesome button bar (was a hand-drawn SVG) so it aligns to the node and styles
        // cleanly; see the .dh-node-actions rules in all.css.
        this.hideNodeTooltip();   // don't stack the hover tooltip under the action menu
        const rpos = node.renderedPosition();
        const gap = (node.renderedHeight() / 2) + 10;
        // Show/hide relations is a single toggle: if the node already has visible outgoing
        // neighbours, the button hides them; otherwise it expands (fetches + shows) them.
        const relationsShown = node.outgoers('node').filter(':visible').nonempty();
        const toggleIcon = relationsShown ? 'fa-eye-slash' : 'fa-diagram-project';
        const toggleTitle = relationsShown ? 'Hide relations' : 'Expand relations';

        // A policy is one-per-dataset, so the "Add policy" action only appears on dataset nodes.
        const isDatasetNode = (node.data('labels') || []).map(l => String(l).toUpperCase()).includes('DATASET');

        this.activeTooltip = Object.assign(document.createElement('div'), {
            className: 'node-selector dh-node-actions',
            innerHTML: `
                <button type="button" class="dh-node-action" data-action="toggle-relations" title="${toggleTitle}"><i class="fa-solid ${toggleIcon}"></i></button>
                <button type="button" class="dh-node-action" data-action="add" title="Add a connected node"><i class="fa-solid fa-plus"></i></button>
                <button type="button" class="dh-node-action" data-action="relate" title="Create relation to"><i class="fa-solid fa-link"></i></button>
                <button type="button" class="dh-node-action" data-action="edit" title="Edit node"><i class="fa-solid fa-pen"></i></button>
                <button type="button" class="dh-node-action" data-action="clone" title="Clone node"><i class="fa-solid fa-clone"></i></button>`
        });
        // Full size at any zoom: a zoomed-out network has dots a few pixels wide, and a toolbar scaled
        // with them could not be used. The caret (bottom-centre) points at the node.
        this.activeTooltip.style.left = rpos.x + 'px';
        this.activeTooltip.style.top = (rpos.y - gap) + 'px';
        this.activeTooltip.style.transformOrigin = 'bottom center';
        this.activeTooltip.style.transform = 'translate(-50%, -100%)';
        this.container.append(this.activeTooltip);

        // Cytoscape uses mouseup for click events, so the button handlers listen on mouseup.
        this.activeTooltip.querySelector('[data-action="toggle-relations"]').addEventListener('mouseup', e => {
            this.removeTooltip();
            const n = this.cy.$('#' + node.id());
            if (n.outgoers('node').filter(':visible').nonempty()) {
                // relations are showing → hide them
                n.outgoers('node').hide();
            } else {
                // relations are hidden or not yet loaded → expand + show
                this.fetchAndAddRelatedNodes(node.id().replace("node-", ""));
                n.outgoers('node').show();
            }
            // Names, outlines and flows follow what is shown.
            this.refreshLabels();
        });

        const sourceNode = this.toNodeData(node);

        // Add a connected node. On a dataset the "+" first asks WHAT to add (Resource,
        // Timeseries, Dataset or Policy) via a small inline chooser — rather than a second
        // toolbar icon per type. On any other node it goes straight to adding a resource.
        const openResourceFlow = () => {
            const resourceRelForm = new ResourceRelationForm({
                rootId: this.rootId,
                relationFrom: sourceNode,
                network: this,
                afterSave: data => {
                    this.addNode(data);
                    this.addRelatedEdges(data);
                    this.relayout();
                }
            });
            resourceRelForm.render();
        };

        // Add a timeseries into this dataset: TimeseriesForm with the dataset preselected and a
        // BELONGS_TO from-relation prefilled, so the new series both lands in the dataset
        // (dataSetId) and arrives with a visible edge from it. Both are editable before saving.
        const openTimeseriesFlow = () => {
            const tsForm = new TimeseriesForm({
                network: this,
                afterSave: json => {
                    const ts = json.items[0];
                    this.addNode({ id: ts.id, name: ts.name, labels: ['TIMESERIES'], externalId: ts.externalId,
                                   dataSetId: ts.dataSetId });
                    this.addRelatedEdges(ts);
                    this.relayout();
                }
            });
            tsForm.render();
            tsForm.setDataSet({ id: sourceNode.id, name: sourceNode.label });
            tsForm.addFromResourceAndRelType([{ name: 'BELONGS_TO', nodes: [{ id: sourceNode.id, name: sourceNode.label }] }]);
        };

        // Add a sub-dataset: DataSetForm with this dataset preselected as the parent
        // (connectedDataSets). The server writes the BELONGS_TO edge parent → child; mirror it
        // in the view on save.
        const openDatasetFlow = () => {
            const dataSetForm = new DataSetForm({
                afterSave: obj => {
                    this.addNode({ id: obj.id, name: obj.name, labels: ['DATASET'], externalId: obj.externalId });
                    this.addEdge({
                        id: 'dataset-' + sourceNode.id + '-' + obj.id,
                        type: 'BELONGS_TO',
                        start: sourceNode.id,
                        end: obj.id
                    });
                    this.relayout();
                }
            });
            dataSetForm.render();
            dataSetForm.formElement.querySelector('[data-type="dataset-container"]')
                .append(dataSetForm.createDataSetLabel({ id: sourceNode.id, name: sourceNode.label }));
        };

        // Add (or re-open) the dataset's policy node. One policy per dataset: if a POLICY child is
        // already in view, open it; otherwise create one (PolicyForm create mode, dataSetId pre-set
        // → POST /policies/create builds the node + ENFORCED_ON edge and publishes it to the graph).
        const openPolicyFlow = () => {
            const existingPolicy = node.outgoers('node').filter(n =>
                (n.data('labels') || []).map(l => String(l).toUpperCase()).includes('POLICY'));
            if (existingPolicy.nonempty()) {
                new PolicyForm({
                    rootId: this.rootId,
                    entityId: String(existingPolicy.first().data('id')).replace('node-', ''),
                    network: this
                }).render();
                return;
            }
            new PolicyForm({
                rootId: this.rootId,
                dataSetId: sourceNode.id,
                network: this,
                afterSave: json => {
                    const p = json.items[0];
                    this.addNode({ id: p.id, name: p.name, labels: ['POLICY'], externalId: p.externalId });
                    this.addEdge({
                        id: 'policy-' + sourceNode.id + '-' + p.id,
                        type: 'ENFORCED_ON',
                        start: sourceNode.id,
                        end: p.id
                    });
                    this.relayout();
                }
            }).render();
        };

        this.activeTooltip.querySelector('[data-action="add"]').addEventListener('mouseup', e => {
            if (!isDatasetNode) {
                this.removeTooltip();
                openResourceFlow();
                return;
            }
            // Dataset → choose what to add. Reuse the floating action card, restyled as a stacked
            // labelled menu (.is-chooser) so it matches the toolbar and reads cleanly in both themes.
            this.activeTooltip.classList.add('is-chooser');
            this.activeTooltip.innerHTML = `
                <button type="button" class="dh-node-choice" data-action="add-resource"><i class="fa-solid fa-cube"></i> Resource</button>
                <button type="button" class="dh-node-choice" data-action="add-timeseries"><i class="fa-solid fa-chart-line"></i> Timeseries</button>
                <button type="button" class="dh-node-choice" data-action="add-dataset"><i class="fa-solid fa-database"></i> Dataset</button>
                <button type="button" class="dh-node-choice" data-action="add-policy"><i class="fa-solid fa-shield-halved"></i> Policy</button>`;
            this.activeTooltip.querySelector('[data-action="add-resource"]').addEventListener('mouseup', () => { this.removeTooltip(); openResourceFlow(); });
            this.activeTooltip.querySelector('[data-action="add-timeseries"]').addEventListener('mouseup', () => { this.removeTooltip(); openTimeseriesFlow(); });
            this.activeTooltip.querySelector('[data-action="add-dataset"]').addEventListener('mouseup', () => { this.removeTooltip(); openDatasetFlow(); });
            this.activeTooltip.querySelector('[data-action="add-policy"]').addEventListener('mouseup', () => { this.removeTooltip(); openPolicyFlow(); });
        });

        this.activeTooltip.querySelector('[data-action="edit"]').addEventListener('mouseup', e => {
            this.removeTooltip();

            const nodeData = node.data();
            const labels = (nodeData.labels || []).map(l => l.toUpperCase());

            const isPolicyNode = labels.includes("POLICY");
            const isDataSetNode = labels.includes("DATASET");
            const isTimeSeries = labels.includes("TIMESERIES");
            const formConfig = {
                rootId: this.rootId,
                entityId: nodeData.id.replace("node-", ""),
                network: this,
                afterSave: data => {
                    const cyNode = this.cy.$('#node-' + data.id);
                    cyNode.data({
                        name: data.name,
                        label: data.name,
                        labels: data.labels,
                        externalId: data.externalId,
                        color: this.getColor(data)
                    });
                    // A new name or type changes the drawn name and icon.
                    this.relayout();
                },
                afterDelete: data => {
                    this.cy.$('#node-' + data.entityId).remove();
                    this.relayout();
                }
            };

            if (isPolicyNode) {
                // The policy update endpoint returns a {items:[policy]} wrapper (unlike the
                // dataset/resource forms the shared afterSave was written for), so reading
                // data.name off the top level yields undefined and blanks the node. Unwrap
                // items[0] and refresh only the editable fields (label stays POLICY, so keep
                // the node's existing labels/color).
                new PolicyForm({
                    ...formConfig,
                    afterSave: resp => {
                        const p = (resp && resp.items) ? resp.items[0] : resp;
                        if (!p) return;
                        this.cy.$('#node-' + p.id).data({
                            name: p.name,
                            label: p.name,
                            externalId: p.externalId
                        });
                        this.relayout();
                    }
                }).render();
                return;
            }
            if(isTimeSeries){
                formConfig.afterSave = data => {
                    data.items.forEach( item => { // special case for timeseries: api returns Object{items: [Objects]}
                        const cyNode = this.cy.$('#node-' + item.id);
                        cyNode.data({
                            name: item.name,
                            label: item.name,
                            // labels: data.items[0].labels, Timeseries currently only have TIMESERIES label
                            externalId: item.externalId
                        });
                        const section = document.querySelector(`section[id^="section-root-"][id$="-list"]`);
                        const listElem = section && section.querySelector(`li[data-root-id = "${item.id}"]`);
                        // Rich rows (resources list) keep their dot/chips: update just the name span.
                        if(listElem){ const nameEl = listElem.querySelector('.dl-name'); (nameEl || listElem).textContent = item.name; }
                    })
                    this.relayout();
                }
                new TimeseriesEditForm(formConfig).render();
                return;
            }
            if (isDataSetNode) {
                formConfig.afterSave = data => {
                    const cyNode = this.cy.$('#node-' + data.id);
                    cyNode.data({
                        name: data.name,
                        label: data.name,
                        labels: data.labels,
                        externalId: data.externalId
                    });
                    this.relayout();
                    const section = document.querySelector(`section[id^="section-root-"][id$="-list"]`);
                    const listElem = section && section.querySelector(`li[data-id = "${data.id}"]`);
                    if(listElem){ const nameEl = listElem.querySelector('.dl-name'); (nameEl || listElem).textContent = data.name; }
                }
                new EditDataSetForm(formConfig).render();
                return;
            }
            formConfig.afterSave = data => {
                const cyNode = this.cy.$('#node-' + data.id);
                cyNode.data({
                    name: data.name,
                    label: data.name,
                    labels: data.labels,
                    externalId: data.externalId
                });
                this.relayout();
                const section = document.querySelector(`section[id^="section-root-"][id$="-list"]`);
                const listElem = section.querySelector(`li[data-root-id = "${data.id}"]`);
                listElem.textContent = data.name;
            }
            new ResourceEditForm(formConfig).render();
        });

        this.activeTooltip.querySelector('[data-action="clone"]').addEventListener('mouseup', e => {
            this.removeTooltip();
            const resourceCloneForm = new ResourceCloneForm({
                rootId: this.rootId,
                relationFrom: sourceNode,
                entityId: sourceNode.id,
                network: this,
                afterSave: data => {
                    this.addNode(data);
                    this.addRelatedEdges(data);
                    this.relayout();
                }
            });
            resourceCloneForm.render();
        });

        this.activeTooltip.querySelector('[data-action="relate"]').addEventListener('mouseup', e => {
            this.removeTooltip();
            const createRelationForm = new ResourceEdgeForm({
                rootId: this.rootId,
                title: $L('create.edge'),
                // labels ride along so the form can apply dataset rules (BELONGS_TO default +
                // endpoint validation) to the preselected from resource.
                fromNode: {id: sourceNode.id, name: sourceNode.label, color: sourceNode.color.background, labels: sourceNode.labels},
                network: this,
                // The saved edge goes in whole: its metadata drives the flow animation, and its
                // endpoints decide whether the other node still has to be fetched into the view.
                afterSave: data => this.addRelation(data)
            });
            createRelationForm.render();
        });

    }

    // Re-pick the palette from <html data-theme> and redraw with it.
    applyTheme() {
        GRAPH_COLORS = graphPalette();
        this.view.applyTheme();
        if (this.filter) this.filter.paintIconButtons();   // the label icons are drawn in the theme's ink
    }

    // Relations whose metadata carries an "animate::datatype" entry show that value travelling along
    // the line from source to target, like the travelling signals in the intellistream.ai hero graph.
    // The drawing reads the metadata again after an edit, so the label starts, changes or stops.
    refreshEdgeAnimations(){
        this.view.refreshFlows();
    }

    loadNetwork(id, isRootId){
        if(isRootId){
            this.rootId = id;
        }
        this.loadedId = id;
        // Returns the load promise so callers can act once the graph is rendered
        // (e.g. select/highlight the root node).
        return Api.post("/resources/fetch-related", { id: id, depth: this.queryDepth, limit: NETWORK_LIMIT }).then( response => {
            if(response.status === 200){
                return response.json();
            } else {
                throw Error();
            }
        }).then( json => {
            this.reload(json);
        })
            .catch(e => {
                console.error(e);
            });
    }

    reload(data){
        // A fetch that comes back after another root was picked has nothing left to draw into.
        if (this.destroyed) return;
        // Replace, not append. Without this, re-loading the network (clicking a
        // different root, changing depth, etc.) tries to re-add nodes/edges that
        // are already on the canvas and Cytoscape rejects duplicate ids.
        this.removeTooltip();
        this.view.reset();
        this.cy.elements().remove();

        this.labels = data.labels;
        this.standardLabels = [];
        this.labels = this.labels
            .filter(item => {
                if(item.name === "ASSET" || item.name === "FUNCTION" || item.name === "TIMESERIES" || item.name === "POLICY" || item.name === "DATASET"){
                    this.standardLabels.push(item);
                    return false;
                }
                return true;
            });
        this.labels = this.labels.concat(this.standardLabels);

        if (data.nodes.length > this.view.bigAbove) {
            // Too many for Cytoscape at once: the drawing keeps them and shows what is in view.
            this.view.loadBig(data.nodes.map(node => this.nodeData(node)), data.edges.map(edge => this.edgeData(edge)));
        } else {
            this.cy.batch(() => {
                data.nodes.forEach(node => this.addNode(node));
                data.edges.forEach(edge => this.addEdge(edge));
            });
        }
        // Drawn now rather than when the adds' queued redraw comes round, so the graph is on screen
        // when the load promise resolves.
        this.refreshQueued = false;
        this.view.refresh();
        // Ring the root so the graph mirrors the left-menu selection. It is not selected: selecting
        // dims everything but its neighbours, which would hide most of the graph on open.
        this.view.setRoot(this.rootId != null && this.findNodeById(this.rootId).nonempty() ? "node-" + this.rootId : null);

        // A freshly loaded root starts unfiltered; rebuild the option lists from the
        // new graph contents.
        this.filter.reset();
        this.filter.refreshOptions();
    }

    nodeData(node){
        return {
            id: "node-" + node.id,
            label: node.name,             // node name (also used by the filter + hover tooltip)
            labels: node.labels,
            externalId: node.externalId,
            dataSetId: node.dataSetId,    // the dataset this node lives in, so a node created
                                          // off it can default to the same one (see toNodeData)
            color: this.getColor(node),   // the node's label colour (forms and the filter use it)
        };
    }

    edgeData(edge){
        return {
            id: "edge-" + edge.id,      // Unique identifier for the edge
            source: "node-" + edge.start,  // ID of the source node
            target: "node-" + edge.end,   // ID of the target node
            label: edge.type,
            metadata: edge.metadata     // relationship properties; drives the flow animation
        };
    }

    addNode(node){
        const added = this.cy.add({
            group: 'nodes',  // specify that you are adding a node
            data: this.nodeData(node),
        });
        // Every path that adds a node redraws, placing it beside its neighbours.
        this.relayout();
        return added;
    }

    addEdge(edge){
        const added = this.cy.add({
            group: 'edges', // Define that you're adding an edge
            data: this.edgeData(edge)
        });
        // Redraw here rather than at the call sites: every path that draws an edge — initial load,
        // save response, fetch-related, and creating a relation by hand — is then routed, and a
        // second relation between the same pair gets its own lane.
        this.relayout();
        return added;
    }

    /**
     * Bring a newly saved relationship into the view. Takes the saved edge as the api returns it
     * (id, start, end, type, metadata).
     *
     * Adding the edge is not enough on its own, which is why creating a relationship used to need
     * a page reload before it looked right:
     *
     *  - an edge can only be added once both its endpoints are on the canvas, and the form lets you
     *    relate to any resource, not just one already in view. When an end is missing, expand the
     *    other end's neighbourhood instead: that pass brings the node in with its name and labels
     *    and adds the edge along with it;
     *  - a second relationship between the same pair of nodes would land exactly under the first,
     *    which addEdge() takes care of by re-fanning the pair as it draws;
     *  - the metadata carries the data-flow animation, and a new relationship type may need to join
     *    the filter's list.
     */
    addRelation(edge){
        if(!edge) return;
        const startInView = this.findNodeById(edge.start).nonempty();
        const endInView = this.findNodeById(edge.end).nonempty();
        if(!startInView && !endInView) return;   // neither end is on the canvas: nothing to attach to
        if(!startInView || !endInView){
            this.fetchAndAddRelatedNodes(startInView ? edge.start : edge.end);
            return;
        }
        if(this.findEdgeById(edge.id).nonempty()) return;   // already drawn

        this.addEdge(edge);
        if(this.filter){
            this.filter.refreshOptions();
            this.filter.apply();
        }
        this.refreshEdgeAnimations();
    }

    // Draw the edges a just-saved node came back with. The save response carries them in the
    // node-centric `relatedResources` encoding: each entry is the node at the other end, plus the
    // relationship type, the direction relative to `node`, and the id of the edge realizing it.
    //
    // Every relation is added, not just the first: the create/clone forms let you pick several
    // relationship types at once, and each one is a separate edge.
    addRelatedEdges(node){
        (node.relatedResources || []).forEach(relation => {
            const outbound = relation.direction !== 'INBOUND';
            this.addEdge({
                id: relation.edgeId,
                type: relation.relationshipType,
                start: outbound ? node.id : relation.id,
                end: outbound ? relation.id : node.id
            });
        });
    }

    fetchAndAddRelatedNodes(nodeId){
        Api.post("/resources/fetch-related", { id: nodeId, depth: 1 }).then( response => {
            if(response.status === 200){
                return response.json();
            } else {
                throw Error();
            }
        }).then( json => {
            if (this.destroyed) return;
            const targetNode = this.findNodeById(nodeId);

            let newNodesHasBeenAdded = false;
            for(let i = 0; i < json.nodes.length; i++){
                const n = json.nodes[i];
                const result = this.findNodeById(n.id);
                if(result.empty()){
                    const newNode = this.addNode(n);
                    newNode.position({x: targetNode.position().x, y: targetNode.position().y});
                    newNodesHasBeenAdded = true
                }
            }
            json.edges.forEach( e => {
                const result = this.findEdgeById(e.id);
                if(result.empty()){
                    this.addEdge(e);
                }
            });


            if(newNodesHasBeenAdded){
                this.relayout();
            }

            // New labels/relationship types may have arrived with the expanded nodes.
            this.filter.refreshOptions();
            this.filter.apply();
            this.refreshEdgeAnimations();

        }).catch(e => {
            console.error(e);
        });
    }

    getColor(n) {
        const match = this.labels.find(it => n.labels && n.labels.includes(it.name));
        // Falling through to a neutral grey keeps the node renderable even when its
        // label row is missing from GET /labels — better than throwing and silently
        // dropping the node out of the graph.
        return match ? match.color : "#999999";
    }

    // A big graph keeps what is out of view in the drawing rather than in Cytoscape; for those, a stand-in
    // answers whether it is in the graph and where.
    findNodeById(id){
        const el = this.cy.getElementById("node-" + id);
        return el.nonempty() || !this.view.isBig() ? el : this.view.standIn("node-" + id);
    }

    findEdgeById(id){
        const el = this.cy.getElementById("edge-" + id);
        return el.nonempty() || !this.view.isBig() ? el : this.view.standIn("edge-" + id);
    }

    toNodeData(node) {
        const ndata = node.data();
        return {
            id: String(ndata.id.replace("node-", "")),
            name: ndata.label,
            label: ndata.label,
            labels: ndata.labels,
            externalId: ndata.externalId,
            dataSetId: ndata.dataSetId,
            color: this.getColor(ndata)
        };
    }

    // Redraw the names, outlines and flows after resources were shown, hidden or highlighted.
    refreshLabels() {
        this.view.redraw();
    }

    // ---- the View menu -------------------------------------------------------------------------------
    // One button at the top right opens a menu of ways to show the graph, each a row of choices like the
    // network lab's, and the two things you do to the drawing itself: lay it out afresh, and draw a
    // relation by dragging. The choices are kept in this browser.
    createViewMenu() {
        this.viewBtn = Object.assign(document.createElement('button'), {
            type: 'button',
            className: 'dh-btn secondary network-btns view-btn',
            title: $L('graph.view'),
            innerHTML: '<i class="fa fa-fw fa-sliders"></i>'
        });
        this.viewBtn.setAttribute('aria-haspopup', 'true');
        this.viewBtn.setAttribute('aria-expanded', 'false');
        this.viewBtn.addEventListener('click', () => this.viewMenu.hidden ? this.openViewMenu() : this.closeViewMenu());
        this.viewMenu = Object.assign(document.createElement('div'), { className: 'dh-graph-view-menu', hidden: true });
        this.viewMenu.addEventListener('pointerdown', e => e.stopPropagation());
        this.container.append(this.viewBtn, this.viewMenu);
        // A choice made elsewhere (the icon style in another tab's graph) shows here too.
        this.stopIconsInMenu = GraphIcons.subscribe(() => { if (!this.viewMenu.hidden) this.renderViewMenu(); });
    }

    // The rows of the menu: what each shows, its choices, and how it reads and sets the current one.
    // A row marked small applies only to graphs drawn whole, not to a big graph drawn group by group.
    viewChoices() {
        const option = (key, label, choices, small) => ({ key, label, small, choices,
            get: () => this.view.options()[key], set: v => this.view.setOptions({ [key]: v }) });
        return [
            option('size', 'graph.view.size', [['importance', 'graph.view.size.importance'], ['same', 'graph.view.size.same']], true),
            option('lines', 'graph.view.lines', [['curved', 'graph.view.lines.curved'], ['straight', 'graph.view.lines.straight']], true),
            { key: 'icons', label: 'graph.icons', get: () => GraphIcons.style(), set: v => GraphIcons.setStyle(v),
              choices: [['symbols', 'graph.icons.symbols'], ['simple', 'graph.icons.simple'], ['none', 'graph.icons.none']] },
            option('flows', 'graph.view.flows', [['on', 'graph.view.flows.on'], ['off', 'graph.view.flows.off']]),
        ];
    }

    openViewMenu() {
        this.removeTooltip();
        this.renderViewMenu();
        this.viewMenu.hidden = false;
        this.viewBtn.setAttribute('aria-expanded', 'true');
        const first = this.viewMenu.querySelector('button[aria-pressed="true"]:not(:disabled)');
        if (first) first.focus();
    }

    closeViewMenu() {
        this.viewMenu.hidden = true;
        this.viewBtn.setAttribute('aria-expanded', 'false');
    }

    renderViewMenu() {
        const big = this.view.isBig(), menu = this.viewMenu, uid = 'gv' + Math.random().toString(36).slice(2, 8);
        menu.replaceChildren();
        let gated = false;
        this.viewChoices().forEach(row => {
            const off = !!row.small && big;
            gated = gated || off;
            const group = Object.assign(document.createElement('div'), { className: 'gv-row' });
            const label = Object.assign(document.createElement('span'), { className: 'gv-label', id: uid + '-' + row.key, textContent: $L(row.label) });
            const seg = Object.assign(document.createElement('div'), { className: 'gv-seg' });
            seg.setAttribute('role', 'group');
            seg.setAttribute('aria-labelledby', label.id);
            const now = row.get();
            row.choices.forEach(([value, text]) => {
                const b = Object.assign(document.createElement('button'), { type: 'button', textContent: $L(text), disabled: off });
                b.setAttribute('aria-pressed', String(value === now));
                b.addEventListener('click', () => { if (row.get() !== value) { row.set(value); this.renderViewMenu(); } });
                seg.append(b);
            });
            group.append(label, seg);
            menu.append(group);
        });
        if (gated) menu.append(Object.assign(document.createElement('p'), { className: 'gv-note', textContent: $L('graph.view.big', null, [this.view.bigAbove.toLocaleString()]) }));
        const actions = Object.assign(document.createElement('div'), { className: 'gv-actions' });
        const action = (icon, text, title, fn) => {
            const b = Object.assign(document.createElement('button'), { type: 'button', className: 'gv-action', title: title || '' });
            b.innerHTML = `<i class="fa fa-fw ${icon}"></i><span></span>`;
            b.querySelector('span').textContent = text;
            b.addEventListener('click', fn);
            actions.append(b);
        };
        action('fa-circle-nodes', $L('graph.view.rearrange'), $L('graph.rearrange'), () => { this.closeViewMenu(); this.removeTooltip(); this.view.rearrange(); });
        action('fa-diagram-project', this.addEdgeModeEnabled ? $L('graph.view.draw.stop') : $L('graph.view.draw'), $L('create.relation.between.2.nodes'),
            () => { this.closeViewMenu(); this.setDrawMode(!this.addEdgeModeEnabled); });
        menu.append(actions, Object.assign(document.createElement('p'), { className: 'gv-note', textContent: $L('graph.view.saved') }));
    }

    // Drawing a relation: drag from one resource to another, then pick the relation type. While it is on,
    // the View button is lit and a note on the graph says how to stop.
    setDrawMode(on) {
        this.addEdgeModeEnabled = on;
        this.container.style.cursor = on ? 'grab' : 'default';
        this.viewBtn.classList.toggle('active', on);
        if (on) { this.removeTooltip(); this.eh.enableDrawMode(); } else this.eh.disableDrawMode();
        if (on && !this.drawHint) {
            this.drawHint = Object.assign(document.createElement('div'), { className: 'dh-graph-mode' });
            this.drawHint.innerHTML = '<span></span><button type="button" class="gv-action"><i class="fa fa-fw fa-xmark"></i><span></span></button>';
            this.drawHint.querySelector(':scope > span').textContent = $L('graph.view.draw.hint');
            this.drawHint.querySelector('button span').textContent = $L('graph.view.draw.stop');
            this.drawHint.querySelector('button').addEventListener('click', () => this.setDrawMode(false));
            this.container.append(this.drawHint);
        }
        if (this.drawHint) this.drawHint.hidden = !on;
    }

    setupEdgeHandles() {
        this.addEdgeModeEnabled = false;
        this.eh = this.cy.edgehandles({
            snap: false, // snap to nearest node
            snapThreshold: 5,
            noEdgeEventsInDraw: true, // prevent firing of edge events while drawing
            disableBrowserGestures: true, // disable browser gestures while using the extension
            handleNodes: 'node.dot', // selector for which nodes to be handles
            canConnect: (source, target) => !source.same(target) && target.hasClass('dot'),   // not a big graph's group dot
            edgeParams: (sourceNode, targetNode) => {
                return {classes: 'new-edge-handle'};
            }
        });

        this.cy.on('ehcomplete', (event, sourceNode, targetNode, addedEdge) => {
            const picker = new RelationList({
                network: this,
                multiSelect: false
            });
            picker.render(); // Render so "picker.cancelButton" gets initialized
            picker.setConfirmCallback( selectedData => {
                Api.post("/resources/create", { relations: [{
                    fromId: sourceNode.id().replace("node-", ""),
                    toId: targetNode.id().replace("node-", ""),
                    relationshipTypeId: selectedData[0].id
                }] }).then( response => {
                    if(response.ok){
                        return response.json();
                    }
                    addedEdge.remove();
                    // A refused relation says why, e.g. a second BELONGS_TO into a dataset.
                    return DataHubProblem.read(response).then(problem => { problem.flash('error.problem.failed'); throw problem; });
                }).then( json => {
                    addedEdge.remove();
                    this.addRelation(json.relations[0]);
                })
                    .catch(e => {
                        console.error(e);
                        addedEdge.remove();
                    });
            });
            picker.cancelButton.addEventListener('pointerdown', e => {
                addedEdge.remove();
            });
            this.setDrawMode(false);
        });
    }
}
/*
    Filter UI for the graph network. A funnel toggle button in the sub-nav opens a panel in the
    shared right-form slide-out, listing the present node types and relation types as two grouped
    listings (reusing the .data-list base listing). Every type is shown by default; unchecking one
    hides its nodes/edges. Also filters nodes by name. Hidden elements get the `.filtered-out`
    class (display:none), defined in the GraphNetwork cytoscape stylesheet.
 */
class GraphFilter {

    constructor(network) {
        this.network = network;
        // null = no constraint (every type of that kind shown); otherwise a Set of the
        // label-names / relationship-types that remain enabled (checked). Empty Set = hide all.
        this.state = { name: '', activeLabels: null, activeRelTypes: null };
        this.present = { labels: [], relTypes: [] };
        this.panel = null;     // the right-form <section> while open, else null
        this.createButton();
    }

    get cy() {
        return this.network.cy;
    }

    createButton() {
        const navSlot = document.querySelector('[data-type="graph-filter-slot"]');
        this.btn = Object.assign(document.createElement('button'), {
            type: 'button',
            className: 'dh-btn secondary small graph-filter-btn',
            title: $L('filter'),
            innerHTML: `<i class="fa fa-fw fa-filter"></i><span>${$L('filter')}</span>`
        });
        this.btn.addEventListener('click', () => this.togglePanel());
        // Clear the slot first so re-selecting a root doesn't stack duplicate controls.
        navSlot.replaceChildren(this.btn);
    }

    get mainElement() {
        return document.querySelector('.right-form');
    }

    togglePanel() {
        if (this.panel) this.closePanel();
        else this.openPanel();
    }

    openPanel() {
        const mainElement = this.mainElement;
        this.panel = Object.assign(document.createElement('section'), {
            className: 'graph-filter-form',
            innerHTML: `
                <div class="heading flex-container left-right">
                    <h3>${$L('filter')}</h3>
                </div>
                <main class="datahub-form-fields">
                    <label>${$L('filter.by.name')}</label>
                    <input type="text" class="gf-name" placeholder="${$L('filter.by.name')}"/>
                    <div class="gf-group" data-group="labels">
                        <h3 class="gf-group-title">${$L('labels')}</h3>
                        <p class="gf-hint gf-labels-hint">${$L('graph.icons.hint')}</p>
                        <ul class="data-list slim gf-list"></ul>
                    </div>
                    <div class="gf-group" data-group="relTypes">
                        <h3 class="gf-group-title">${$L('relationships')}</h3>
                        <ul class="data-list slim gf-list"></ul>
                    </div>
                </main>
                <footer class="btns flex-end pad20">
                    <button type="button" class="dh-btn secondary" data-type="gf-clear"><span>${$L('clear.filters')}</span></button>
                    <button type="button" class="dh-btn primary" data-type="gf-close"><span>${$L('close')}</span></button>
                </footer>`
        });

        // Bring the panel to the front when clicked, matching the stacked edit forms.
        this.panel.setAttribute('data-type', 'move-to-front');
        this.panel.addEventListener('pointerdown', () => {
            if (this.panel && this.panel.parentElement.lastElementChild !== this.panel) {
                this.panel.parentElement.append(this.panel);
            }
        });

        mainElement.appendChild(this.panel);
        mainElement.classList.add('visible');
        setTimeout(() => this.panel && this.panel.classList.add('visible'), 10);
        this.btn.classList.add('active');

        this.nameInput = this.panel.querySelector('.gf-name');
        this.nameInput.value = this.state.name || '';
        this.nameInput.addEventListener('input', e => {
            this.state.name = e.target.value;
            this.apply();
        });
        // A label's icon button shows its image once that has loaded, and a warning if it could not be.
        this.stopIcons = GraphIcons.subscribe(() => this.paintIconButtons());
        this.panel.querySelector('[data-type="gf-close"]').addEventListener('click', () => this.closePanel());
        this.panel.querySelector('[data-type="gf-clear"]').addEventListener('click', () => {
            this.reset();
            this.refreshOptions();
            this.apply();
        });

        this.refreshOptions();
    }

    closePanel() {
        if (!this.panel) return;
        const mainElement = this.mainElement;
        const panel = this.panel;
        this.panel = null;
        this.nameInput = null;
        this.picker = null;
        if (this.stopIcons) { this.stopIcons(); this.stopIcons = null; }
        panel.classList.remove('visible');
        if (mainElement.childElementCount === 1) mainElement.classList.remove('visible');
        setTimeout(() => {
            panel.remove();
            if (mainElement.childElementCount === 0) mainElement.classList.remove('visible');
        }, 500);
        this.btn.classList.remove('active');
    }

    refreshOptions() {
        // A big graph's resources are mostly out of view, so the lists come from the drawing's store.
        const all = this.network.view.modelData();
        const presentLabels = new Set();
        (all ? all.nodes : this.cy.nodes().map(n => n.data())).forEach(d => (d.labels || []).forEach(l => presentLabels.add(l)));

        const presentRelTypes = new Set();
        (all ? all.edges : this.cy.edges().map(e => e.data())).forEach(d => {
            const t = d.label;
            if (t) presentRelTypes.add(t);
        });

        this.present.labels = [...presentLabels].sort();
        this.present.relTypes = [...presentRelTypes].sort();

        if (!this.panel) return;
        this.renderOptions('labels', this.present.labels);
        this.renderOptions('relTypes', this.present.relTypes);
    }

    renderOptions(group, values) {
        const list = this.panel.querySelector(`.gf-group[data-group="${group}"] .gf-list`);
        const activeSet = group === 'labels' ? this.state.activeLabels : this.state.activeRelTypes;
        // A type is checked (shown) when there's no constraint (null) or it's in the active Set.
        const isChecked = v => activeSet === null ? true : activeSet.has(v);

        // Preserve any live checkbox state across an in-place refresh (e.g. a graph reload).
        const prev = {};
        list.querySelectorAll('input[type="checkbox"]').forEach(cb => { prev[cb.value] = cb.checked; });

        list.innerHTML = '';
        if (group === 'labels') this.picker = null;
        values.forEach(value => {
            const checked = (value in prev) ? prev[value] : isChecked(value);
            const li = document.createElement('li');
            const row = Object.assign(document.createElement('label'), { className: 'gf-row' });
            const cb = Object.assign(document.createElement('input'), { type: 'checkbox', value: value, checked: checked });
            cb.addEventListener('change', () => this.onOptionChange(group));
            const name = Object.assign(document.createElement('span'), { className: 'gf-row-name', textContent: value });
            // Node types get a colour chip matching their graph colour; relation types have no
            // colour concept yet, so their rows are just a checkbox + name.
            if (group === 'labels') {
                const chip = Object.assign(document.createElement('span'), { className: 'gf-chip' });
                chip.style.backgroundColor = this.network.getColor({ labels: [value] });
                row.append(cb, chip, name);
                li.className = 'gf-label';
                li.append(row, this.iconButton(value));
            } else {
                row.append(cb, name);
                li.append(row);
            }
            list.append(li);
        });
        // Sync state from the (possibly preserved) checkbox states without re-applying yet.
        this.onOptionChange(group, false);
    }

    onOptionChange(group, apply = true) {
        const list = this.panel.querySelector(`.gf-group[data-group="${group}"] .gf-list`);
        const boxes = [...list.querySelectorAll('input[type="checkbox"]')];
        const checked = boxes.filter(b => b.checked);
        // Everything checked -> null (no constraint). Otherwise the Set of checked values;
        // an empty Set hides every type of that kind.
        const active = (boxes.length > 0 && checked.length === boxes.length)
            ? null
            : new Set(checked.map(b => b.value));
        if (group === 'labels') this.state.activeLabels = active;
        else this.state.activeRelTypes = active;
        if (apply) this.apply();
    }

    reset() {
        this.state = { name: '', activeLabels: null, activeRelTypes: null };
        if (this.nameInput) this.nameInput.value = '';
        if (this.panel) this.panel.querySelectorAll('.gf-list').forEach(l => { l.innerHTML = ''; });
    }

    isSet() { return !!(this.state.name || this.state.activeLabels || this.state.activeRelTypes); }

    // ---- a label's icon -----------------------------------------------------------------------------
    // The button beside a label shows the icon its resources get, and opens a picker for an image from
    // Files. The image is remembered in this browser only (graph-icons.js).
    iconButton(label) {
        const btn = Object.assign(document.createElement('button'), { type: 'button', className: 'gf-icon-btn' });
        btn.dataset.label = label;
        btn.append(Object.assign(document.createElement('img'), { alt: '' }));
        btn.addEventListener('click', () => this.togglePicker(btn.closest('li'), label));
        this.paintIconButton(btn);
        return btn;
    }

    paintIconButton(btn) {
        const label = btn.dataset.label, image = GraphIcons.imageOf(label);
        const failed = !!image && GraphIcons.imageFailed(image.id);
        btn.querySelector('img').src = GraphIcons.labelSrc(label, GRAPH_COLORS.labelText);
        btn.classList.toggle('has-image', !!image && !failed);
        btn.classList.toggle('failed', failed);
        btn.title = failed ? $L('graph.icon.failed', null, [image.name])
            : image ? $L('graph.icon.chosen', null, [image.name, label]) : $L('graph.icon.choose', null, [label]);
    }

    paintIconButtons() {
        if (this.panel) this.panel.querySelectorAll('.gf-icon-btn').forEach(b => this.paintIconButton(b));
    }

    togglePicker(li, label) {
        const open = this.picker;
        if (open) { open.el.remove(); this.picker = null; if (open.li === li) return; }
        const el = Object.assign(document.createElement('div'), {
            className: 'gf-picker',
            innerHTML: `
                <div class="gf-picker-head"></div>
                <p class="gf-hint">${$L('graph.icon.browser.only')}</p>
                <input type="search" class="gf-picker-search" placeholder="${$L('graph.icon.search')}" aria-label="${$L('graph.icon.search')}"/>
                <nav class="gf-picker-path"></nav>
                <div class="gf-picker-items"></div>
                <div class="gf-picker-actions">
                    <button type="button" class="dh-btn secondary small" data-type="gf-auto"><span>${$L('graph.icon.automatic')}</span></button>
                    <a class="gf-picker-files" href="/files" target="_blank" rel="noopener">${$L('graph.icon.upload')}</a>
                </div>`
        });
        el.querySelector('.gf-picker-head').textContent = $L('graph.icon.for', null, [label]);
        li.append(el);
        const picker = this.picker = { li, label, el, path: '/', seq: 0, timer: 0 };
        const search = el.querySelector('.gf-picker-search');
        search.addEventListener('input', () => {
            clearTimeout(picker.timer);
            picker.timer = setTimeout(() => this.showFiles(picker, search.value.trim()), 250);
        });
        el.querySelector('[data-type="gf-auto"]').addEventListener('click', () => this.chooseImage(picker, null));
        this.showFiles(picker, '');
        search.focus();
    }

    // A folder's images and sub-folders, or the images whose names begin with what was typed (Files
    // matches names from their beginning, so "pump" finds pump.svg and "svg" finds nothing).
    showFiles(picker, query) {
        const seq = ++picker.seq, items = picker.el.querySelector('.gf-picker-items'), nav = picker.el.querySelector('.gf-picker-path');
        const segments = picker.path.split('/').filter(Boolean);
        const path = query ? '/files/search?q=' + encodeURIComponent(query) + '&limit=100'
            : '/files/list' + segments.map(x => '/' + encodeURIComponent(x)).join('');
        nav.replaceChildren();
        if (!query) {
            const crumb = (text, to) => {
                const b = Object.assign(document.createElement('button'), { type: 'button', className: 'gf-crumb', textContent: text });
                b.addEventListener('click', () => { picker.path = to; this.showFiles(picker, ''); });
                nav.append(b);
            };
            crumb($L('graph.icon.files'), '/');
            segments.forEach((x, k) => crumb(x, '/' + segments.slice(0, k + 1).join('/')));
        }
        items.replaceChildren(this.pickerNote('graph.icon.loading'));
        Api.get(path)
            .then(r => r.status === 403 ? 'disabled' : r.ok ? r.json() : Promise.reject(r.status))
            .then(json => {
                if (seq !== picker.seq || this.picker !== picker) return;
                if (json === 'disabled') { items.replaceChildren(this.pickerNote('graph.icon.disabled')); return; }
                const all = (json && json.items) || [];
                const folders = query ? [] : all.filter(f => f.type === 'FOLDER').sort((a, b) => String(a.name).localeCompare(String(b.name)));
                const images = all.filter(GraphIcons.isImageFile).sort((a, b) => String(a.name).localeCompare(String(b.name)));
                items.replaceChildren();
                folders.forEach(f => {
                    const b = Object.assign(document.createElement('button'), { type: 'button', className: 'gf-folder' });
                    b.innerHTML = '<i class="fa fa-fw fa-folder"></i><span></span>';
                    b.querySelector('span').textContent = f.name;
                    b.addEventListener('click', () => { picker.path = f.path || (picker.path.replace(/\/$/, '') + '/' + f.name); this.showFiles(picker, ''); });
                    items.append(b);
                });
                images.forEach(f => {
                    const b = Object.assign(document.createElement('button'), { type: 'button', className: 'gf-tile', title: f.name });
                    const img = Object.assign(document.createElement('img'), { alt: '', loading: 'lazy', src: '/files/download/' + encodeURIComponent(f.externalId) });
                    b.append(img, Object.assign(document.createElement('span'), { textContent: f.name }));
                    b.addEventListener('click', () => this.chooseImage(picker, { id: f.externalId, name: f.name }));
                    items.append(b);
                });
                if (!folders.length && !images.length) items.replaceChildren(this.pickerNote(query ? 'graph.icon.none.found' : 'graph.icon.none.here'));
            })
            .catch(() => { if (seq === picker.seq && this.picker === picker) items.replaceChildren(this.pickerNote('graph.icon.error')); });
    }

    pickerNote(key) {
        return Object.assign(document.createElement('p'), { className: 'gf-hint gf-picker-note', textContent: $L(key) });
    }

    chooseImage(picker, file) {
        GraphIcons.setImage(picker.label, file);
        picker.el.remove();
        if (this.picker === picker) this.picker = null;
        this.paintIconButtons();
    }

    apply() {
        const q = (this.state.name || '').trim().toLowerCase();
        const labels = this.state.activeLabels;
        const types = this.state.activeRelTypes;

        let changed = false;
        this.cy.batch(() => {
            this.cy.nodes('.dot').forEach(n => {
                const wasHidden = n.hasClass('filtered-out');
                const name = (n.data('label') || '').toLowerCase();
                const nLabels = n.data('labels') || [];
                const nameOk = !q || name.includes(q);
                const labelOk = !labels || nLabels.length === 0 || nLabels.some(l => labels.has(l));
                const hide = !(nameOk && labelOk);
                n.toggleClass('filtered-out', hide);   // hides the canvas node (display:none)
                if (hide !== wasHidden) changed = true;
            });
            this.cy.edges().not('.agg').forEach(e => {
                const typeOk = !types || types.has(e.data('label') || '');
                const endpointsVisible = !e.source().hasClass('filtered-out')
                    && !e.target().hasClass('filtered-out');
                e.toggleClass('filtered-out', !(typeOk && endpointsVisible));
            });
        });
        // Keep the sub-nav button lit while any constraint is active, even with the panel closed.
        const filtered = this.isSet();
        if (this.btn) this.btn.classList.toggle('has-filter', filtered);
        // Names, outlines and flows are drawn on canvases of their own, which follow what is shown.
        if (changed) this.network.refreshLabels();
    }
}
