// Manifest for the site-wide app JS bundle (see ASSETS.md). Loaded on every page by layout/main.html.
// Order matters: application.js defines the global helpers ($L, Flash, renderSignedOutDialog, ...)
// that inline page scripts rely on, so it must come first. SearchDropdown and EnhancedSelect are
// standalone reusable widgets with no dependency on application.js.
//
// policy/naming-policy.js is site-wide rather than form-local because two unrelated bundles need it:
// the right-form bundle (write responses, inline external-id validation) and the findings queue.
//
// limit-errors.js needs $L and getByteSize from application.js, and is needed in turn by the form
// bundle and by the ad-hoc file/upload error paths, so it belongs here rather than in either.
//
//= require application.js
//= require limit-errors.js
//= require search-dropdown.js
//= require enhanced-select.js
//= require context-menu.js
//= require resource-list.js
//= require policy/naming-policy.js
