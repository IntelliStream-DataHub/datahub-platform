// Manifest for the Flows section's JS bundle (see ASSETS.md).
// Build-time only - lists the source files, in order, via Sprockets-style require directives.
// flow-api.js defines window.FlowApi and window.FlowRunStream, which every page script uses, so it
// comes first; flow-runs.js defines window.FlowRuns, which the flow page's Runs tab calls. Each
// page script returns at once when its own markup is absent, so one bundle serves the section.
// The flow page also loads the right-form bundle (its forms extend DatasetFormAbstract) and
// cytoscape with dagre as raw scripts, the way the resources graph does.
//
//= require flows/flow-api.js
//= require flows/event-picker.js
//= require flows/run-as.js
//= require flows/flow-runs.js
//= require flows/flows-list.js
//= require flows/flow-editor.js
//= require flows/flows-overview.js
//= require flows/flows-attention.js
//= require flows/flow-clients.js
