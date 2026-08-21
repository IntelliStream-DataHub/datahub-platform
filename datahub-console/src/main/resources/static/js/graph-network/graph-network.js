// Graph palette, in one place — referenced by the Cytoscape stylesheet (buildStyle) and the
// select handlers below. Two variants: the light one mirrors the intellistream.ai hero graph
// (solid colour pills — border matches the fill — with thin blue-grey connectors on a white
// canvas); the dark one keeps the console's dark canvas. The active palette is picked from
// <html data-theme> and re-applied live on theme change (see applyTheme()).
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
// Reassigned by applyTheme() when the theme toggles; the select handlers read it live.
let GRAPH_COLORS = graphPalette();

// The built-in resource types. In a node's label, these are shown first (in this order) and
// coloured with their label colour; any other (custom) label names are sorted after and dimmed.
const STANDARD_TYPES = ['ASSET', 'FUNCTION', 'TIMESERIES', 'RESOURCE', 'DATASET', 'POLICY'];

// Spacing between successive arcs joining the same pair of nodes, in px — see fanParallelEdges().
const ARC_STEP = 30;
// The sign of a control-point distance that puts an arc to the RIGHT of the edge's own direction
// of travel. Cytoscape measures the distance perpendicular to source → target, so "right of
// travel" is one fixed sign in the edge's own frame no matter which way it points. Flip this to +1
// if the handedness reads backwards on screen; it mirrors the whole scheme and nothing else.
const RIGHT_OF_TRAVEL = -1;
// How far the two directions of travel may differ in count before direction-based sides are given
// up in favour of plain left/right balancing.
const MAX_DIRECTION_IMBALANCE = 1;

class GraphNetwork {

    constructor(obj) {
        this.rootId = null;
        this.queryDepth = obj.queryDepth || 5;
        this.container = obj.container;
        this.layouts = {
            dagre: {
                name: 'dagre',
                rankDir: 'TB',
                nodeSep: 40,
                edgeSep: 20,
                rankSep: 135,   // rank gap = edge length in a TB layout
                animate: true,
                animationDuration: 300,
                fit: true,
                padding: 30,
            },
            // Force-directed layout (the "force" button). Tuned to spread nodes apart and keep
            // edges from bunching/overlapping: strong repulsion, long ideal edges, generous node
            // separation, label-aware node sizing, and a high-quality multi-iteration solve.
            fcose: {
                name: 'fcose',
                quality: 'proof',
                randomize: false,
                animate: true,
                animationDuration: 300,
                fit: true,
                padding: 40,
                nodeDimensionsIncludeLabels: true,
                packComponents: true,
                numIter: 2500,
                nodeRepulsion: 450000,
                idealEdgeLength: 85,
                edgeElasticity: 0.45,
                nodeSeparation: 150,
                gravity: 0.25,
                gravityRange: 3.8,
            },
        };
        this.activeLayout = 'dagre';
        // fcose options used when expanding a node's relations (fit:false so the view doesn't
        // zoom out). Same overlap-avoiding tuning as the force button, plus the longer edges.
        this.layoutOptions = {
            name: 'fcose',
            quality: 'proof',
            randomize: false,
            animate: true,
            animationDuration: 300,
            fit: false, // prevents zooming out when expanding nodes
            nodeDimensionsIncludeLabels: true,
            packComponents: true,
            numIter: 2500,
            nodeRepulsion: 8192 * 64,
            idealEdgeLength: 169,
            edgeElasticity: 0.45,
            nodeSeparation: 120,
            gravity: 0.25,
        };
        this.labels = [];
        this.cy = this.setup();

        // Node labels are HTML overlays (cytoscape-node-html-label) so each label can style its
        // type tokens individually (colour + weight) and truncate the type line and the name
        // line independently — see renderNodeLabel.
        this.nodeHtmlLabelTPL = data => this.renderNodeLabel(data);
        // Query 'node' (every node keeps a label element), NOT 'node:visible'. Visibility is driven
        // through the label content (data.gfHidden -> empty), because cytoscape recomputes :visible
        // lazily and a batched show leaves the plugin unable to re-create a removed label. Registered
        // exactly once here; all later updates flow through data/style events (never re-registered).
        this.cy.nodeHtmlLabel([{ query: 'node', tpl: this.nodeHtmlLabelTPL }]);

        // Overlay layer + state for the edge "data flow" travelers (see refreshEdgeAnimations).
        this.travelerLayer = Object.assign(document.createElement('div'), { className: 'dh-edge-traveler-layer' });
        this.container.append(this.travelerLayer);
        this.edgeTravelers = [];
        this.travelerRAF = null;

        // Re-theme the graph live when the user toggles the colour theme (the toggle sets
        // data-theme on <html>; see layout/main.html). Per-node fills/widths are bypass
        // styles and survive the stylesheet swap, so only the chrome (borders, edges,
        // canvas-facing colours) flips.
        this.themeObserver = new MutationObserver(() => this.applyTheme());
        this.themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });

        this.cy.on('click', (event) => {
            if(event.originalEvent.target.closest(".node-selector") != null){
                event.stopPropagation();
            }
        });

        this.cy.on('grab', 'node', (e) => {
            this.removeTooltip();
        });

        this.cy.on('tap', 'node', (event) => {
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

        this.cy.on('mouseover', 'node', (event) => {
            this.container.style.cursor = 'pointer';
            this.showNodeTooltip(event.target);
        });
        // Restore cursor when not hovering
        this.cy.on('mouseout', 'node', (event) => {
            this.container.style.cursor = 'default';
            this.hideNodeTooltip();
        });
        this.cy.on('select', 'node', (evt) => {
            const node = evt.target;
            // Get and style all connected edges
            node.connectedEdges().style({
                'line-color': GRAPH_COLORS.accent,
                'line-style': 'dashed',
                'line-dash-pattern': [6, 3],
                'target-arrow-color': GRAPH_COLORS.accent,
                'source-arrow-color': GRAPH_COLORS.accent
            });
            // The node fills with its colour on select; flag it so its label re-renders white.
            // The plugin re-renders a node's label on a data change, so we just set data here —
            // adding a second label spec would stack an overlapping (fuzzy) label.
            node.data('sel', true);
        });
        this.cy.on('unselect', 'node', (evt) => {
            const node = evt.target;
            // Reset style of previously connected edges
            node.connectedEdges()
                .removeStyle('line-color')
                .removeStyle('line-style')
                .removeStyle('target-arrow-color')
                .removeStyle('source-arrow-color')
                .removeStyle('line-dash-pattern');
            node.data('sel', false);
        });


        this.cy.on('click', 'edge', event => {
            if(event.isPropagationStopped()) return;

            const edge = event.target;
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
                    // Start/refresh (or stop) the "data flow" animation for the edited edge.
                    this.refreshEdgeAnimations();
                },
                afterDelete: data => {
                    this.cy.$('#edge-' + data.entityId).remove();
                }
            }).render();
        });

        this.createAddRelationsBtn();
        console.log("Graph network created");
        this.createLayoutButtons();
        this.filter = new GraphFilter(this);
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

    // Hover tooltip showing a node's full name — only when the canvas label is truncated
    // (the node's name is wider than the label's max width), since the canvas text has no
    // native title tooltip.
    showNodeTooltip(node){
        if(!this.isLabelTruncated(node)) return;
        const label = node.data('label') || '';
        this.hideNodeTooltip();
        const zoom = this.cy.zoom();
        const rpos = node.renderedPosition();
        const gap = (node.renderedHeight() / 2) + 8 * zoom;
        this.nodeTooltip = Object.assign(document.createElement('div'), {
            className: 'dh-node-tooltip',
            textContent: label
        });
        this.nodeTooltip.style.left = rpos.x + 'px';
        this.nodeTooltip.style.top = (rpos.y - gap) + 'px';
        // Scale with the zoom so the tooltip stays proportional to the nodes.
        this.nodeTooltip.style.transformOrigin = 'bottom center';
        this.nodeTooltip.style.transform = `translate(-50%, -100%) scale(${zoom})`;
        this.container.append(this.nodeTooltip);
    }

    hideNodeTooltip(){
        if(this.nodeTooltip){
            this.nodeTooltip.remove();
            this.nodeTooltip = null;
        }
    }

    // True when the node's name is wider than the pill's inner width (data(w) minus the label
    // padding), i.e. the name is actually ellipsised — so the hover tooltip only shows then.
    isLabelTruncated(node){
        const label = node.data('label') || '';
        if(!label) return false;
        const maxW = (node.data('w') || 120) - 12;
        const ctx = this._labelMeasureCtx
            || (this._labelMeasureCtx = document.createElement('canvas').getContext('2d'));
        ctx.font = "700 11px 'Manrope', Arial, sans-serif";
        return ctx.measureText(label).width > maxW;
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
    // may still be propagating from Postgres to Neo4j (async via Pulsar + the stateful
    // consumer), and the graph only fetches on select — so re-load the network while it
    // is still EMPTY (propagation pending). Once the graph has nodes we only poll: a
    // re-load then would just clear what is already shown without finding anything new.
    // Resolves with the node, or null on timeout. Promise-based (not async) to match
    // the rest of the file.
    waitForNode(externalId, { interval = 300, reloadInterval = 1500, timeout = 30000 } = {}){
        return new Promise(resolve => {
            const t0 = performance.now();
            let lastReload = -Infinity;
            const tick = () => {
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
        const zoom = this.cy.zoom();
        const rpos = node.renderedPosition();
        const gap = (node.renderedHeight() / 2) + 12 * zoom;
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
        this.activeTooltip.style.left = rpos.x + 'px';
        this.activeTooltip.style.top = (rpos.y - gap) + 'px';
        // Scale the toolbar with the zoom so it stays proportional to the node it hangs off.
        // The caret (bottom-centre) is the anchor, so it keeps pointing at the node as it scales.
        // (The tooltip is torn down on zoom/pan, so setting this once at open time is enough.)
        this.activeTooltip.style.transformOrigin = 'bottom center';
        this.activeTooltip.style.transform = `translate(-50%, -100%) scale(${zoom})`;
        this.container.append(this.activeTooltip);

        // Cytoscape uses mouseup for click events, so the button handlers listen on mouseup.
        this.activeTooltip.querySelector('[data-action="toggle-relations"]').addEventListener('mouseup', e => {
            this.removeTooltip();
            const n = this.cy.$('#' + node.id());
            if (n.outgoers('node').filter(':visible').nonempty()) {
                // relations are showing → hide them (flag empties their HTML labels too)
                const kids = n.outgoers('node');
                kids.hide();
                kids.data('gfHidden', true);
                kids.emit('data');
            } else {
                // relations are hidden or not yet loaded → expand + show
                this.fetchAndAddRelatedNodes(node.id().replace("node-", ""));
                const kids = n.outgoers('node');
                kids.show();
                kids.data('gfHidden', false);
                kids.emit('data');
            }
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
                    const layout = this.cy.elements().layout(this.layoutOptions);
                    layout.run();
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
                    const layout = this.cy.elements().layout(this.layoutOptions);
                    layout.run();
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
                    const layout = this.cy.elements().layout(this.layoutOptions);
                    layout.run();
                }
            });
            dataSetForm.render();
            dataSetForm.formElement.querySelector('[data-type="dataset-container"]')
                .append(dataSetForm.createDataSetLabel({ id: sourceNode.id, name: sourceNode.label }));
        };

        // Add (or re-open) the dataset's policy node. One policy per dataset: if a POLICY child is
        // already in view, open it; otherwise create one (PolicyForm create mode, dataSetId pre-set
        // → /api/policies/create builds the node + ENFORCED_ON edge and publishes it to the graph).
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
                    const layout = this.cy.elements().layout(this.layoutOptions);
                    layout.run();
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
                        color: this.getColor(data),
                        w: this.nodeWidth(data.name)
                    });
                    // The data() change above triggers the plugin to re-render this node's
                    // label with the updated name/types; no explicit re-register needed.
                },
                afterDelete: data => {
                    this.cy.$('#node-' + data.entityId).remove();
                    this.refreshLabels();
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
                            externalId: p.externalId,
                            w: this.nodeWidth(p.name)
                        });
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
                    const layout = this.cy.elements().layout(this.layoutOptions);
                    layout.run();
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

    setup() {
        return cytoscape({
            container: this.container, // container to render in
            elements: [],
            style: this.buildStyle(GRAPH_COLORS),
            wheelSensitivity: 2,
            // Cap zoom-in so a layout `fit` on a tiny graph (one or two nodes — e.g. the tour's
            // "your resource" beat) doesn't blow a single node up to fill the whole canvas. fit
            // respects maxZoom, so small graphs settle at a readable size with breathing room
            // instead of one giant pill; larger graphs are unaffected.
            maxZoom: 1.4,
        });
    }

    // The Cytoscape stylesheet, parameterised by a palette so it can be rebuilt on theme
    // change. Nodes are white content-width pills with a fat border in the node's label
    // colour (data(color)); edges are thin, arrow-less curved connectors with an italic
    // relationship label.
    buildStyle(c) {
        return [
            {
                selector: 'node',
                style: {
                    'shape': 'round-rectangle',
                    'corner-radius': '16px',
                    'background-color': c.nodeSurface,   // white pill (light) / dark chip (dark theme)
                    'border-style': 'solid',
                    'border-color': 'data(color)',   // thin border in the node's label colour
                    'border-width': 2,
                    // No native (canvas) label — the HTML overlay (renderNodeLabel) draws the
                    // text, so we don't render canvas text on top of it. The node is a fixed-height
                    // pill sized to a per-node width (data(w), computed from the name in addNode).
                    'width': 'data(w)',
                    'height': '34px',
                    'transition-property': 'outline-color outline-width overlay-opacity overlay-padding',
                    'transition-duration': '0.2s'
                }
            },
            {
                selector: 'edge',
                style: {
                    'width': 1,
                    'line-color': c.edge,
                    'curve-style': 'unbundled-bezier',
                    // Per-edge so parallel relationships fan onto distinct arcs instead of
                    // stacking on the same curve. Defaults to BASE_CURVE (see addEdge) and is
                    // widened by fanParallelEdges(); data survives an applyTheme restyle.
                    'control-point-distances': 'data(cpd)',
                    'control-point-weights': [0.5],
                    // Relations are directed, so show which way. Scaled up because the arrow is
                    // sized off the 1px line width and would otherwise be too small to read; the
                    // selected/hover/tutorial rules below already recolour it.
                    'target-arrow-shape': 'triangle',
                    'target-arrow-color': c.edge,
                    'arrow-scale': 1.6,
                    'text-rotation': 'autorotate',
                    'text-margin-y': 0,
                    'font-size': '9px',
                    'font-style': 'italic',
                    'text-background-padding': '2px',
                    'text-background-color': c.textBg,
                    'text-background-opacity': 0.85,
                    'color': c.edgeText,
                    'label': 'data(label)'
                }
            },
            {
                selector: 'node:selected',
                style: {
                    'background-color': 'data(color)',   // fill with the label colour when selected
                    'outline-color': c.selected,
                    'outline-width': 3,
                    'outline-style': 'solid',
                    'outline-opacity': 1
                }
            },
            {
                selector: 'edge:selected, .eh-hover',
                style: {
                    'width': 2,
                    'line-color': c.accent,
                    'target-arrow-color': c.accent,
                    'source-arrow-color': c.accent
                }
            },
            {
                selector: '.eh-source',
                style: { 'border-width': 4, 'border-color': c.accent }
            },
            {
                selector: '.eh-target',
                style: { 'border-width': 4, 'border-color': c.accent }
            },
            {
                selector: '.eh-ghost-edge.eh-preview-active',
                style: { 'opacity': 0 }
            },
            {
                selector: '.eh-preview, .eh-ghost-edge',
                style: {
                    'line-color': c.accent,
                    'target-arrow-color': c.accent,
                    'source-arrow-color': c.accent
                }
            },
            {
                selector: '.filtered-out',
                style: { 'display': 'none' }
            },
            {
                // Tutorial highlight: glow a focused node in the Navy brand accent.
                selector: 'node.tut-highlight',
                style: { 'border-width': 4, 'border-color': c.tutorial, 'border-opacity': 1 }
            },
            {
                selector: 'edge.tut-highlight',
                style: { 'line-color': c.tutorial, 'width': 4, 'target-arrow-color': c.tutorial }
            },
            {
                // Tutorial: dim everything outside the highlighted focus.
                selector: '.tut-dim',
                style: { 'opacity': 0.18 }
            },
        ];
    }

    // Re-pick the palette from <html data-theme> and swap the stylesheet. Per-node bypass styles
    // (fill) are preserved by Cytoscape across the swap, and the native label colour comes from
    // the stylesheet, so labels re-theme automatically.
    applyTheme() {
        GRAPH_COLORS = graphPalette();
        // Use fromJson().update() (not cy.style(sheet)) — the latter mis-renders existing
        // edges on a live restyle (they balloon in width and lose their curve).
        this.cy.style().fromJson(this.buildStyle(GRAPH_COLORS)).update();
        // (Node-label colours are CSS-driven off <html data-theme>, so they re-theme on their own.)
    }

    // ---- Edge "data flow" animation -------------------------------------------------------
    // For every edge whose metadata carries an "animate::datatype" entry, a small chip showing
    // that value streams along the edge from the source node to the target node, like the
    // travelling signals in the intellistream.ai hero graph. Rebuilt whenever the graph
    // changes; positions are recomputed each frame from the live (pan/zoom-aware) geometry.
    refreshEdgeAnimations(){
        if(!this.travelerLayer) return;
        this.edgeTravelers.forEach(tr => tr.el.remove());
        this.edgeTravelers = [];
        this.cy.edges().forEach(edge => {
            const meta = edge.data('metadata');
            // Metadata added via the form is stored on the relationship with a "metadata_"
            // prefix, so the key arrives as "metadata_animate::datatype" (fall back to the bare
            // key in case it was set directly on the relationship).
            const datatype = meta && (meta['metadata_animate::datatype'] || meta['animate::datatype']);
            if(!datatype) return;
            const s = edge.sourceEndpoint(), t = edge.targetEndpoint();
            const len = Math.hypot(t.x - s.x, t.y - s.y);
            const el = Object.assign(document.createElement('div'), {
                className: 'dh-edge-traveler',
                textContent: datatype
            });
            el.style.opacity = 0;
            this.travelerLayer.append(el);
            this.edgeTravelers.push({
                edge,
                el,
                dur: Math.min(10000, Math.max(3200, len * 24)),   // ms — roughly constant speed (slow)
                offset: Math.random()                            // desync travellers across edges
            });
        });
        this.startTravelerLoop();
    }

    startTravelerLoop(){
        if(this.travelerRAF || !this.edgeTravelers.length) return;
        const step = ts => {
            this.travelerRAF = null;
            this.updateTravelers(ts);
            if(this.edgeTravelers.length){
                this.travelerRAF = requestAnimationFrame(step);
            }
        };
        this.travelerRAF = requestAnimationFrame(step);
    }

    updateTravelers(ts){
        const zoom = this.cy.zoom();
        const pan = this.cy.pan();
        this.edgeTravelers.forEach(tr => {
            if(tr.edge.removed() || !tr.edge.visible()){
                tr.el.style.opacity = 0;
                return;
            }
            const t = ((ts / tr.dur) + tr.offset) % 1;
            const p = this.edgePointAt(tr.edge, t);
            const rx = p.x * zoom + pan.x;
            const ry = p.y * zoom + pan.y;
            // fade in near the source and out near the target so it doesn't pop at the nodes
            // Fade in near the source and out near the target (solid at its peak — the
            // subtlety comes from the colour, not from transparency).
            const fade = Math.max(0, Math.min(1, t / 0.12, (1 - t) / 0.12));
            // Scale with the zoom so the chip stays proportional to the graph.
            tr.el.style.transform = `translate(${rx}px, ${ry}px) translate(-50%, -50%) scale(${zoom})`;
            tr.el.style.opacity = fade;
        });
    }

    // Point at parameter t (0..1) along the edge, modelled as a quadratic bezier whose control
    // point is derived so the curve passes through Cytoscape's reported midpoint — matching the
    // rendered unbundled-bezier curve. Returns model coordinates.
    edgePointAt(edge, t){
        const s = edge.sourceEndpoint();
        const e = edge.targetEndpoint();
        const m = edge.midpoint();
        const cx = 2 * m.x - (s.x + e.x) / 2;
        const cy = 2 * m.y - (s.y + e.y) / 2;
        const u = 1 - t;
        return {
            x: u * u * s.x + 2 * u * t * cx + t * t * e.x,
            y: u * u * s.y + 2 * u * t * cy + t * t * e.y
        };
    }

    loadNetwork(id, isRootId){
        if(isRootId){
            this.rootId = id;
        }
        // Returns the load promise so callers can act once the graph is rendered
        // (e.g. select/highlight the root node).
        return fetch("/api/resources/fetch-related-nodes", {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            },
            body: JSON.stringify({
                id: id,
                depth: this.queryDepth
            }),
            signal: AbortSignal.timeout(10000)
        }).then( response => {
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
        // Replace, not append. Without this, re-loading the network (clicking a
        // different root, changing depth, etc.) tries to re-add nodes/edges that
        // are already on the canvas and Cytoscape rejects duplicate ids.
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

        for(let i = 0; i < data.nodes.length; i++){
            const node = data.nodes[i];
            this.addNode(node);
        }

        for(let i = 0; i < data.edges.length; i++){
            const edge = data.edges[i];
            this.addEdge(edge);
        }


        let layout = this.cy.layout({...this.layouts[this.activeLayout], animate: false});
        layout.run();

        const node = this.findNodeById(this.rootId);
        // Highlight the root node so the graph mirrors the left-menu selection
        // (yellow outline + soft glow overlay + connected-edge styling, defined in
        // the stylesheet and the 'select' handler). Centralized here so every page
        // — timeseries, resources, datasets — gets it from a single root load.
        if(node && node.nonempty()) node.select();
        // Animate the view so that it centers on the node
        this.cy.animate({
            center: {
                eles: node
            },
            duration: 500,  // duration in milliseconds for the animation
            easing: 'ease-in-out'
        });

        // A freshly loaded root starts unfiltered; rebuild the option lists from the
        // new graph contents.
        this.filter.reset();
        this.filter.refreshOptions();
        this.refreshEdgeAnimations();
    }

    addNode(node){
        const color = this.getColor(node);
        return this.cy.add({
            group: 'nodes',  // specify that you are adding a node
            data: {
                id: "node-" + node.id,
                label: node.name,             // node name (also used by the filter + hover tooltip)
                labels: node.labels,
                externalId: node.externalId,
                dataSetId: node.dataSetId,    // the dataset this node lives in, so a node created
                                              // off it can default to the same one (see toNodeData)
                color: color,                 // the node's border colour (its label colour)
                w: this.nodeWidth(node.name), // pill width (the label is an HTML overlay)
            },
        });
    }

    // Pill width, from the name length — the name is the widest line; the smaller type line fits
    // within it (and both truncate with ellipsis if they overflow).
    nodeWidth(name){
        const nameW = (name || '').length * 6.6;   // ~11px bold
        return Math.min(240, Math.max(90, Math.ceil(nameW) + 22));
    }

    // The HTML node label: a small type line (built-in types coloured + bold, custom types dimmed
    // + normal weight, built-ins sorted first) over the bold node name. On selection the node
    // fills with its colour, so the text flips to white. Both lines truncate with ellipsis.
    renderNodeLabel(data){
        // Hidden by the graph filter / relation collapse: render an empty label. The plugin keeps
        // the label element (query 'node'), so this reliably hides and later restores it via a data
        // change, with no create/remove churn (which caused fuzzy stacked text + orphaned labels).
        if (data.gfHidden) return '';
        const selected = !!data.sel;   // set by the select/unselect handlers; drives the .sel class
        const w = (data.w || 120) - 12;
        const labels = Array.isArray(data.labels) ? data.labels : (data.labels ? [data.labels] : []);
        const rank = t => { const i = STANDARD_TYPES.indexOf(String(t).toUpperCase()); return i === -1 ? 999 : i; };
        const sorted = [...labels].sort((a, b) => {
            const ra = rank(a), rb = rank(b);
            return ra !== rb ? ra - rb : String(a).localeCompare(String(b));
        });
        // Built-in types get the .dh-nl-type-std class (highlighted), custom names get
        // .dh-nl-type-other (subtle). Colours (and the theme + selected variants) live in all.css.
        const types = sorted.map(t => {
            const up = String(t).toUpperCase();
            const cls = STANDARD_TYPES.includes(up) ? 'dh-nl-type-std' : 'dh-nl-type-other';
            return `<span class="${cls}">${window.escapeHtml(up)}</span>`;
        }).join('<span class="dh-nl-sep">, </span>');
        return `
          <div class="dh-node-label${selected ? ' sel' : ''}" style="width:${w}px">
            <div class="dh-nl-types">${types}</div>
            <div class="dh-nl-name">${window.escapeHtml(data.label || '')}</div>
          </div>`;
    }

    addEdge(edge){
        const added = this.cy.add({
            group: 'edges', // Define that you're adding an edge
            data: {
                id: "edge-" + edge.id,      // Unique identifier for the edge
                source: "node-" + edge.start,  // ID of the source node
                target: "node-" + edge.end,   // ID of the target node
                label: edge.type,
                cpd: RIGHT_OF_TRAVEL * ARC_STEP,   // bezier control-point distance; overwritten
                                            // immediately by the fanParallelEdges() call below
                metadata: edge.metadata     // relationship properties; drives the flow animation
            }
        });
        // Re-fan here rather than at the call sites: every path that draws an edge — initial load,
        // save response, fetch-related, and creating a relation by hand — then renders correctly
        // without having to remember to ask for it.
        this.fanParallelEdges(added);
        return added;
    }

    // Fan the relationships between one pair of nodes onto separate arcs. Several edges between
    // the same two nodes (always different relationship types — the DB enforces one edge per type
    // per pair) would otherwise share one control point and stack exactly on top of each other,
    // leaving all but one invisible.
    //
    // Arcs are placed by "right-hand drive": each edge bows to the right of the way it points, so
    // the two directions separate onto opposite sides like the lanes of a road and the picture is
    // balanced without having to read the arrowheads. Edges sharing a direction fan outward along
    // their own side in ARC_STEP increments.
    //
    // That only balances while the traffic is roughly even. Once one direction outnumbers the
    // other by more than MAX_DIRECTION_IMBALANCE — the all-one-way case especially — honouring
    // direction would pile nearly every arc on one side, so the group falls back to alternating
    // strict left/right (right first, widening every pair) and lets the arrowheads carry the
    // direction on their own.
    //
    // Either way no two arcs share a (side, detour) pair, so a node pair can carry any number of
    // relationships without two of them coinciding.
    //
    // Scoped to the pair the given edge belongs to; parallelEdges() is indexed, so this does not
    // walk the graph. Driven off data(), so an applyTheme restyle cannot flatten the fan.
    fanParallelEdges(edge){
        if(edge.isLoop()) return;   // Cytoscape gives self-loops their own arc
        // Stable order so arc positions don't jump between reloads.
        const group = edge.parallelEdges()
            .filter(candidate => !candidate.isLoop())
            .sort((a, b) => a.id().localeCompare(b.id()));
        if(group.length === 0) return;

        // One end is picked as the reference so "forward" and "reverse" mean something stable
        // across the group; which one it is does not matter, only that it is consistent.
        const reference = group[0].data('source');
        const runsForward = member => member.data('source') === reference;
        const lanes = [group.filter(runsForward), group.filter(member => !runsForward(member))];

        if(lanes.every(lane => lane.length > 0)
                && Math.abs(lanes[0].length - lanes[1].length) <= MAX_DIRECTION_IMBALANCE){
            lanes.forEach(lane => lane.forEach((member, i) => {
                // Both lanes store the same sign: "right of travel" is fixed in the edge's own
                // frame, and Cytoscape already mirrors the perpendicular when a pair of edges
                // point opposite ways — which is exactly what puts the two lanes either side.
                member.data('cpd', RIGHT_OF_TRAVEL * ARC_STEP * (i + 1));
            }));
            return;
        }

        group.forEach((member, i) => {
            const side = i % 2 === 0 ? RIGHT_OF_TRAVEL : -RIGHT_OF_TRAVEL;
            const distance = side * ARC_STEP * (Math.floor(i / 2) + 1);
            // Here the side is absolute rather than relative to travel, so an edge pointing the
            // other way needs its sign flipped to undo that same mirroring and land where meant.
            member.data('cpd', runsForward(member) ? distance : -distance);
        });
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
        fetch("/api/resources/fetch-related-nodes", {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            },
            body: JSON.stringify({
                id: nodeId,
                depth: 1
            }),
            signal: AbortSignal.timeout(10000)
        }).then( response => {
            if(response.status === 200){
                return response.json();
            } else {
                throw Error();
            }
        }).then( json => {
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
                const layout = this.cy.elements().layout(this.layoutOptions);
                layout.run();
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
        // label row is missing from /api/labels — better than throwing and silently
        // dropping the node out of the graph.
        return match ? match.color : "#999999";
    }

    findNodeById(id){
        return this.cy.getElementById("node-" + id);
    }

    findEdgeById(id){
        return this.cy.getElementById("edge-" + id);
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

    refreshLabels() {
        // Re-render every node's HTML label WITHOUT re-registering the plugin spec (re-registering
        // stacked a second overlapping label layer -> fuzzy text, and left orphaned labels behind).
        // Emitting 'data' runs the single registered template per node, which honours data.gfHidden
        // (hidden -> empty, shown -> full) — reliable for both hiding and restoring.
        this.cy.nodes().emit('data');
    }

    applyLayout(name) {
        this.activeLayout = name;
        this.layoutBtns.forEach(btn => btn.classList.toggle('active', btn.dataset.layout === name));
        this.cy.layout({...this.layouts[name]}).run();
    }

    createLayoutButtons() {
        const layouts = [
            { key: 'dagre', icon: 'fa-sitemap', title: 'Hierarchical layout', css: 'layout-dagre-btn' },
            { key: 'fcose', icon: 'fa-circle-nodes', title: 'Force-directed layout', css: 'layout-fcose-btn' },
        ];
        this.layoutBtns = [];
        layouts.forEach(l => {
            const btn = Object.assign(document.createElement('button'), {
                className: `dh-btn secondary network-btns ${l.css}`,
                title: l.title,
                innerHTML: `<i class="fa fa-fw ${l.icon}"></i>`
            });
            btn.dataset.layout = l.key;
            if (l.key === this.activeLayout) btn.classList.add('active');
            btn.addEventListener('click', () => this.applyLayout(l.key));
            this.container.appendChild(btn);
            this.layoutBtns.push(btn);
        });
    }

    createAddRelationsBtn() {
        // Initialize edge handles
        this.eh = this.cy.edgehandles({
            snap: false, // snap to nearest node
            snapThreshold: 5,
            noEdgeEventsInDraw: true, // prevent firing of edge events while drawing
            disableBrowserGestures: true, // disable browser gestures while using the extension
            handleNodes: 'node', // selector for which nodes to be handles
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
                fetch("/api/edges/save", {
                    method: 'POST',
                    headers: {
                        'Accept': 'application/json',
                        'Content-Type': 'application/json',
                        [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
                    },
                    body: JSON.stringify({
                        fromId: sourceNode.id().replace("node-", ""),
                        toId: targetNode.id().replace("node-", ""),
                        relationshipTypeId: selectedData[0].id
                    }),
                    signal: AbortSignal.timeout(10000)
                }).then( response => {
                    if(response.status === 200 || response.status === 201){
                        return response.json();
                    } else {
                        addedEdge.remove();
                        throw Error();
                    }
                }).then( json => {
                    addedEdge.remove();
                    this.addRelation(json);
                })
                    .catch(e => {
                        console.error(e);
                        addedEdge.remove();
                    });
            });
            picker.cancelButton.addEventListener('pointerdown', e => {
                addedEdge.remove();
            });
            this.container.style.cursor = 'default';
            this.addRelBtn.classList.remove("active");
            this.addEdgeModeEnabled = false;
            this.eh.disableDrawMode();
        });


        this.addRelBtn = Object.assign(document.createElement('button'), {
            className: "dh-btn secondary network-btns add-rel-btn",
            title: $L('create.relation.between.2.nodes'),
            innerHTML: `<i class="fa fa-fw fa-diagram-project"></i>`
        });
        this.addEdgeModeEnabled = false;
        this.addRelBtn.addEventListener('click', e => {
            if(this.addEdgeModeEnabled){
                this.container.style.cursor = 'default';
                this.addRelBtn.classList.remove("active");
                this.addEdgeModeEnabled = false;
                this.eh.disableDrawMode();
            } else {
                this.addEdgeModeEnabled = true;
                this.container.style.cursor = 'grab';
                this.removeTooltip();
                this.addRelBtn.classList.add("active");
                this.eh.enableDrawMode();
            }
        });
        this.container.appendChild(this.addRelBtn);
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
        panel.classList.remove('visible');
        if (mainElement.childElementCount === 1) mainElement.classList.remove('visible');
        setTimeout(() => {
            panel.remove();
            if (mainElement.childElementCount === 0) mainElement.classList.remove('visible');
        }, 500);
        this.btn.classList.remove('active');
    }

    refreshOptions() {
        const presentLabels = new Set();
        this.cy.nodes().forEach(n => (n.data('labels') || []).forEach(l => presentLabels.add(l)));

        const presentRelTypes = new Set();
        this.cy.edges().forEach(e => {
            const t = e.data('label');
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
            } else {
                row.append(cb, name);
            }
            li.append(row);
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

    apply() {
        const q = (this.state.name || '').trim().toLowerCase();
        const labels = this.state.activeLabels;
        const types = this.state.activeRelTypes;

        const changedNodes = this.cy.collection();
        this.cy.batch(() => {
            this.cy.nodes().forEach(n => {
                const wasHidden = n.hasClass('filtered-out');
                const name = (n.data('label') || '').toLowerCase();
                const nLabels = n.data('labels') || [];
                const nameOk = !q || name.includes(q);
                const labelOk = !labels || nLabels.length === 0 || nLabels.some(l => labels.has(l));
                const hide = !(nameOk && labelOk);
                n.toggleClass('filtered-out', hide);   // hides the canvas node (display:none)
                n.data('gfHidden', hide);              // empties its HTML label (see renderNodeLabel)
                if (hide !== wasHidden) changedNodes.merge(n);
            });
            this.cy.edges().forEach(e => {
                const typeOk = !types || types.has(e.data('label') || '');
                const endpointsVisible = !e.source().hasClass('filtered-out')
                    && !e.target().hasClass('filtered-out');
                e.toggleClass('filtered-out', !(typeOk && endpointsVisible));
            });
        });
        // Keep the sub-nav button lit while any constraint is active, even with the panel closed.
        const filtered = !!(this.state.name || this.state.activeLabels || this.state.activeRelTypes);
        if (this.btn) this.btn.classList.toggle('has-filter', filtered);
        // cy.batch() coalesces the data events the HTML-label plugin needs, so nudge only the nodes
        // whose visibility flipped: the plugin re-runs their template (hidden -> empty, shown -> full).
        // (Edges carry no HTML label.)
        if (changedNodes.nonempty()) changedNodes.emit('data');
    }
}
