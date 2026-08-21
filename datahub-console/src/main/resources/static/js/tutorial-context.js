// /static/js/tutorial-context.js
(function () {
    // Persistent, navigation-surviving context for the tutorial engine.
    //
    // The cross-page tour navigates between pages with full page loads, which
    // would otherwise wipe in-memory tutorial state. This module mirrors that
    // state to localStorage and rehydrates it on every page load, so:
    //   - window.__tutorialRemember (read directly by tutorials/*.js) survives
    //     navigation;
    //   - the selected sandbox dataset id is remembered across pages.
    //
    // Existing readers keep using window.__tutorialRemember directly; this
    // module just keeps that object in sync with localStorage.

    if (window.DataHubTutorialContext) return;

    const LS_REMEMBER = "tutorial:ctx:remember";              // JSON mirror of __tutorialRemember
    const LS_SELECTED_DATASET = "tutorial:ctx:selectedDatasetId";

    function readJson(key) {
        try { return JSON.parse(localStorage.getItem(key) || "{}") || {}; }
        catch (e) { return {}; }
    }

    function persistRemember() {
        try { localStorage.setItem(LS_REMEMBER, JSON.stringify(window.__tutorialRemember || {})); }
        catch (e) { /* storage full/disabled — in-memory still works this session */ }
    }

    // Rehydrate __tutorialRemember from storage on load. Anything already set
    // in-memory this page wins over the persisted copy.
    function rehydrate() {
        const stored = readJson(LS_REMEMBER);
        window.__tutorialRemember = Object.assign(stored, window.__tutorialRemember || {});
    }

    rehydrate();

    window.DataHubTutorialContext = {
        // Remembered values, mirrored to window.__tutorialRemember + localStorage.
        remember(key, value) {
            if (!key) return;
            window.__tutorialRemember = window.__tutorialRemember || {};
            window.__tutorialRemember[key] = value;
            persistRemember();
        },
        recall(key) {
            return window.__tutorialRemember ? window.__tutorialRemember[key] : undefined;
        },

        // Selected sandbox dataset id (persisted so selection survives navigation).
        setSelectedDatasetId(id) {
            if (id) localStorage.setItem(LS_SELECTED_DATASET, id);
        },
        getSelectedDatasetId() {
            return localStorage.getItem(LS_SELECTED_DATASET);
        },
        clearSelectedDatasetId() {
            localStorage.removeItem(LS_SELECTED_DATASET);
        },
    };
})();