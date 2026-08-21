// Manifest for the home dashboard JS bundle (see ASSETS.md).
// Build-time only. Core home-page behaviour (not the tutorial engine), loaded ungated on
// every visit to the home page: dashboard.js wires the tile-grid navigation;
// home/home-dashboard.js fills the tile counts, the event-type widget, the spotlight sparklines and
// the activity feed by calling the datahub API directly (bearer token from /token) — no console relay.
//
//= require tutorials/dashboard.js
//= require home/home-dashboard.js