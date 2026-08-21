// Manifest for the right-form JS bundle (see ASSETS.md).
// Build-time only — lists the source files, in order, via Sprockets-style require directives.
// Order matters: base_form_abstract defines the base classes (BaseFormAbstract, BaseList,
// DatasetFormAbstract, ...) that every feature form extends, so it must come first. The five
// feature files have no dependencies on each other. naming-help defines the shared window.NamingHelp
// widget the feature forms call at render time, so its position is a readability choice, not a
// load-order one.
//
//= require right-form-content/base_form_abstract.js
//= require right-form-content/naming-help.js
//= require right-form-content/datasets/form.js
//= require right-form-content/files/form.js
//= require right-form-content/resources/form.js
//= require right-form-content/timeseries/form.js
//= require right-form-content/unit/form.js
