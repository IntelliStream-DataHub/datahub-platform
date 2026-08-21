// Manifest for the guided-tutorial JS bundle (see ASSETS.md).
// Build-time only — lists the source files, in load order, via Sprockets-style require directives.
// Order matters: dom/engine-ui provide primitives, context the navigation-surviving state, core
// the engine, datasets registers the tour, and page-ui wires the menu/resume UI on top.
//
//= require tutorial-dom.js
//= require tutorial-engine-ui.js
//= require tutorial-context.js
//= require tutorial-core.js
//= require tutorials/datasets.js
//= require tutorial-page-ui.js