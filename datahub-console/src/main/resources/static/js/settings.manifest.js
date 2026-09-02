// Manifest for the tenant settings JS bundle (see ASSETS.md).
// Build-time only - lists the source files, in order, via Sprockets-style require directives.
// The settings page talks to datahub-api directly; it depends only on the app bundle ($L), which
// every page loads first via layout/main.html.
//
//= require settings-page.js
