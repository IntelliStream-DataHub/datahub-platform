// Manifest for the site-wide app JS bundle (see ASSETS.md). Loaded on every page by layout/main.html.
// Order matters: application.js defines the global helpers ($L, Flash, renderSignedOutDialog, ...)
// that inline page scripts rely on, so it must come first. SearchDropdown and EnhancedSelect are
// standalone reusable widgets with no dependency on application.js.
//
// policy/naming-policy.js is site-wide rather than form-local because two unrelated bundles need it:
// the right-form bundle (write responses, inline external-id validation) and the findings queue.
//
//= require application.js
//= require search-dropdown.js
//= require enhanced-select.js
//= require resource-list.js
//= require policy/naming-policy.js
