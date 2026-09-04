// Manifest for the settings pages bundle (see ASSETS.md).
// Build-time only - lists the source files, in order, via Sprockets-style require directives.
// settings-api.js defines window.SettingsApi, which the page scripts use, so it comes first.
// Each page script returns immediately when its own markup is absent, so one bundle serves the
// section as it grows.
//
//= require settings/settings-api.js
//= require settings/settings-ai.js
