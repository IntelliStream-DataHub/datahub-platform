// Manifest for the insights JS bundle (see ASSETS.md).
// Build-time only — lists the source files, in order, via Sprockets-style require directives.
// d3 is loaded separately (vendored minified file) before this bundle in the template.
// The data lineage panel asks datahub-flow, through flow-api.js; it does nothing on a page
// without the panel, as for a tenant without flows.
//
//= require charts/insights.js
//= require charts/datapoint-stream.js
//= require flows/flow-api.js
//= require flows/data-lineage.js
