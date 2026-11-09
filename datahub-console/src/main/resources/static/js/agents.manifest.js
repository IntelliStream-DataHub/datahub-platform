// Manifest for the Agents section's JS bundle (see ASSETS.md).
// Build-time only - lists the source files, in order, via Sprockets-style require directives.
// An agent is a flow, so the section reuses the Flows scripts: flow-api.js (window.FlowApi) first,
// flow-runs.js (the runs tab and the run page's facts and records), flows-list.js (the list, told
// its kind and section by the page). The agent's own pages are the form, the transcript and the
// attention list. Each script returns at once when its own markup is absent.
//
//= require flows/flow-api.js
//= require flows/event-picker.js
//= require flows/run-as.js
//= require flows/flow-runs.js
//= require flows/flows-list.js
//= require flows/flows-overview.js
//= require agents/agent-form.js
//= require agents/agent-run.js
//= require agents/agents-attention.js
