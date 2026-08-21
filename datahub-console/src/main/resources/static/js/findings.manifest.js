// Manifest for the policy-findings JS bundle (see ASSETS.md).
// Build-time only — lists the source files, in order, via Sprockets-style require directives.
// The queue reads its data through window.NamingPolicy, which ships in the site-wide 'app' bundle
// that layout/main.html loads on every page, so it is not required here.
//
//= require policy/findings-queue.js
