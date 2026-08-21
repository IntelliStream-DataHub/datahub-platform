// /static/js/tutorials/tutorial-page-ui.js
(function () {
    // Wires the tutorial engine to page-level UI:
    // - "Guided tutorial" menu entry in the header dropdown
    // - Start modal ("Start tutorial?" prompt)
    // - Industry modal (scenario selection)
    // - Resume banner if a tutorial is already in progress
    // - Auto-resume after a tour-driven page navigation (step.page)
    //
    // The menu/modals + resume live on every page (the cross-page tour can start
    // and continue anywhere); they are no longer gated to the datasets page.
    //
    // This file intentionally does NOT include:
    // - Tutorial engine/state machine (tutorial-core.js)
    // - Overlay/tooltip primitives (tutorial-engine-ui.js)

    // Prevent double-registration when scripts are included multiple times.
    if (window.__DataHubTutorialPageUiLoaded) return;
    window.__DataHubTutorialPageUiLoaded = true;

    // The tour offered by the Start/industry flow. Only one tour exists today;
    // resume/auto-resume read the active tour id from storage instead.
    const TOUR_ID = "datasets";

    // Page-specific keys for the datasets tutorial (kept separate from engine keys).
    const LS = {
        started: "tutorial:datasets:started",     // whether user started at least once
        industry: "tutorial:datasets:industry",   // chosen industry scenario
        dismissed: "tutorial:datasets:dismissed", // suppress resume banner + skip prompt
    };

    // Engine keys used by tutorial-core.js (shared across tutorials).
    const CORE_LS = {
        active: "activeTutorial", // current tutorial id
        step: "tutorialStep",     // current step index
    };

    // sessionStorage flag set by tutorial-core.js before a tour-driven navigation.
    const SS_AUTO_RESUME = "tutorial:autoResume";

    function qs(sel) { return document.querySelector(sel); }
    function qsa(sel) { return Array.from(document.querySelectorAll(sel)); }

    // Ensures the given tutorial's steps are defined before resuming/starting.
    // Only the datasets tutorial exists today; future tours register here.
    function registerTutorial(id) {
        window.DataHubTutorialRegisterDatasets?.();
    }

    // A fresh start deletes the previous run's sandbox (so tutorial data doesn't accumulate
    // across runs), stamps a new per-run token (so this run's created assets get unique
    // external ids), and forgets the PREVIOUS run's tracked refs so a new run doesn't treat
    // them as its own. Without forgetting lastResourceRootId, skipping straight to the diagram
    // after a run would extend the old resource instead of making a fresh "Tutorial resource N".
    function stampNewRun() {
        // All per-run state lives in the tutorial context (mirrored to localStorage so it
        // survives the cross-page navigation).
        const ctx = window.DataHubTutorialContext;
        if (!ctx || !ctx.remember) return;
        // Delete the PREVIOUS run's sandbox (dataset, resources, timeseries, cluster) before a
        // new run starts, so tutorial data can't pile up. Best-effort + async; it reads the
        // tracked ids synchronously here, before the loop below forgets them. A finished
        // network still persists after the tour (to inspect/use) — it is only cleared when the
        // user starts ANOTHER run, or picks "Remove tutorial data" at the end.
        window.DataHubTutorialResetSandbox?.();
        [
            "lastResourceRootId", "lastResourceExternalId", "lastTimeseriesExternalId",
            "buildoutExternalIds", "buildoutResourceIds", "lastDatasetName",
            "lastDatasetExternalId", "tutorialTimeseriesId",
        ].forEach(k => ctx.remember(k, null));
        ctx.clearSelectedDatasetId?.();
        ctx.remember("runToken", Date.now().toString(36));
    }

    function startFreshTour(id) {
        stampNewRun();
        registerTutorial(id);
        window.DataHubTutorialEngine?.start(id);
    }

    // Show a modal:
    // - remove hidden class
    // - remove aria-hidden so it is visible to assistive tech
    // - disable inert so it can receive focus/click
    function show(el) {
        if (!el) return;
        el.classList.remove("hidden");
        el.removeAttribute("aria-hidden");
        el.inert = false;
    }


    // Hide a modal:
    // - if focus is inside it, move focus to a safe element first
    // - enable inert to prevent focus/interaction
    // - add hidden class + aria-hidden for accessibility
    function hide(el) {
        if (!el) return;
        if (el.contains(document.activeElement)) {
            document.querySelector('[data-type="guided-tutorial"]')?.focus?.();
        }
        el.inert = true;
        el.classList.add("hidden");
        el.setAttribute("aria-hidden", "true");
    }

    //
    // Auto-resume after a tour-driven page navigation
    //
    // tutorial-core.js sets SS_AUTO_RESUME just before location.assign(step.page).
    // On the destination page we consume it and resume silently (no banner).
    function consumeAutoResume() {
        let flag = null;
        try { flag = sessionStorage.getItem(SS_AUTO_RESUME); } catch (e) { /* ignore */ }
        if (!flag) return false;

        try { sessionStorage.removeItem(SS_AUTO_RESUME); } catch (e) { /* ignore */ }

        const active = localStorage.getItem(CORE_LS.active);
        if (!active) return false;

        registerTutorial(active);
        window.DataHubTutorialEngine?.resumeOrStart(active);
        return true;
    }

    //
    // Resume banner (manual return to an in-progress tutorial)
    //
    function renderResumeBannerIfNeeded() {
        const active = localStorage.getItem(CORE_LS.active);
        const step = localStorage.getItem(CORE_LS.step);

        if (!active) return;
        if (step == null) return;
        if (localStorage.getItem(LS.dismissed) === "1") return;
        if (qs(".tutorial-resume-banner")) return;

        const title = (typeof $L === "function") ? $L("tutorial.resume.title") : "Guided tutorial";
        const restart = (typeof $L === "function") ? $L("tutorial.button.restart") : "Restart";
        const quit = (typeof $L === "function") ? $L("tutorial.button.quit") : "Quit";
        const resume = (typeof $L === "function") ? $L("tutorial.button.resume") : "Resume";

        const descTemplate = (typeof $L === "function")
            ? $L("tutorial.resume.description")
            : "You have an active tutorial in progress (step {0}).";

        const desc = String(descTemplate).replace("{0}", String(step));

        const banner = document.createElement("div");
        banner.className = "tutorial-resume-banner";
        banner.innerHTML = `
          <div style="font-weight:600; margin-bottom:6px;">${title}</div>
          <div style="opacity:.9; margin-bottom:10px;">${desc}</div>
          <div style="display:flex; gap:8px; justify-content:flex-end;">
            <button class="dh-btn" data-act="resume">${resume}</button>
            <button class="dh-btn" data-act="restart">${restart}</button>
            <button class="dh-btn" data-act="quit">${quit}</button>
          </div>
        `;

        document.body.appendChild(banner);

        // Promise-chained (no async/await) per the console's no-async rule. resumeOrStart/end
        // may return a promise or undefined; Promise.resolve(...) handles both, closing after.
        banner.addEventListener("click", (e) => {
            const act = e.target?.getAttribute?.("data-act");
            if (!act) return;

            const close = () => banner.remove();

            if (act === "resume") {
                registerTutorial(active);
                Promise.resolve(window.DataHubTutorialEngine?.resumeOrStart(active)).then(close);
                return;
            }

            if (act === "restart") {
                startFreshTour(active);
                close();
                return;
            }

            if (act === "quit") {
                Promise.resolve(window.DataHubTutorialEngine?.end()).then(close);
            }
        });
    }


    //
    // Menu + modal wiring (every page)
    //
    function wireMenuAndModals() {
        // Modal elements are rendered by layout/main.html as passive UI shells.
        const startModal = qs("#tutorial-start-modal");
        const industryModal = qs("#tutorial-industry-modal");

        // Dropdown entry in the header menu.
        const guidedEntry = qs('[data-type="guided-tutorial"]');

        // Menu click: open start modal and clear any previous dismissal.
        if (!guidedEntry) {
            console.warn("[tutorial] Could not find [data-type='guided-tutorial'] in DOM. Tutorial menu click won't work.");
        } else {
            guidedEntry.addEventListener("click", (e) => {
                e.preventDefault();
                e.stopPropagation();

                // Explicit user action should re-enable prompts/banners.
                localStorage.removeItem(LS.dismissed);

                if (!startModal) {
                    console.error("[tutorial] Missing #tutorial-start-modal in HTML.");
                    return;
                }

                show(startModal);
            });
        }

        // Chapter buttons live in the start modal ("or jump to a chapter"). Register the tour
        // (with the stored/default industry), seed the prerequisite a skipped earlier chapter
        // would have built, then play from the chosen chapter to the end of the tour.
        qsa(".tutorial-chapter").forEach(btn => {
            btn.addEventListener("click", () => {
                const chapter = btn.getAttribute("data-chapter");
                if (!chapter) return;
                hide(startModal);
                localStorage.removeItem(LS.dismissed);
                stampNewRun();
                window.DataHubTutorialRegisterDatasets?.();
                // These chapters (and the ones after them) attach to a resource that the skipped
                // resources chapter would have created — seed one if the tenant has none. The
                // correlation chapter also seeds its own base timeseries on demand.
                const seed = ["timeseries", "correlation", "events"].includes(chapter)
                    ? (window.DataHubTutorialEnsureResource?.() || Promise.resolve())
                    : Promise.resolve();
                seed.finally(() => window.DataHubTutorialEngine?.startChapter(chapter));
            });
        });

        // Start modal primary action: proceed to industry selection.
        qsa('[data-tutorial-action="start"]').forEach(btn => {
            btn.addEventListener("click", () => {
                hide(startModal);

                if (!industryModal) {
                    console.error("[tutorial] Missing #tutorial-industry-modal in HTML.");
                    return;
                }

                show(industryModal);
            });
        });

        // Start modal dismiss action: hide modal and remember dismissal.
        qsa('[data-tutorial-action="dismiss"]').forEach(btn => {
            btn.addEventListener("click", () => {
                hide(startModal);
                localStorage.setItem(LS.dismissed, "1");
            });
        });

        // Backdrop clicks close the corresponding modal.
        qsa("[data-tutorial-dismiss]").forEach(bg => {
            bg.addEventListener("click", () => {
                const which = bg.getAttribute("data-tutorial-dismiss");
                if (which === "start") hide(startModal);
                if (which === "industry") hide(industryModal);
            });
        });

        // Industry selection:
        // - store selection in localStorage
        // - register tutorial steps (datasets.js)
        // - start the engine at step 0 (the engine navigates to the tour's first
        //   page if we are not already there)
        qsa(".tutorial-industry").forEach(btn => {
            btn.addEventListener("click", () => {
                const industry = btn.getAttribute("data-industry");
                if (!industry) return;

                localStorage.setItem(LS.started, "1");
                localStorage.setItem(LS.industry, industry);

                hide(industryModal);

                startFreshTour(TOUR_ID);
            });
        });

        // Industry modal navigation: back goes to start modal.
        qsa('[data-tutorial-action="back"]').forEach(btn => {
            btn.addEventListener("click", () => {
                hide(industryModal);
                show(startModal);
            });
        });

        // Industry modal navigation: close hides industry modal only.
        qsa('[data-tutorial-action="close-industry"]').forEach(btn => {
            btn.addEventListener("click", () => {
                hide(industryModal);
            });
        });
    }

    //
    // Main wiring
    //
    document.addEventListener("DOMContentLoaded", () => {
        // Engine must be loaded before this file (script order dependency).
        if (!window.DataHubTutorialEngine) {
            console.error("[tutorial] DataHubTutorialEngine missing. Check script order.");
            return;
        }

        // Modal text is server-rendered (Thymeleaf #{...}), so the menu/modals can
        // be wired immediately without waiting for the i18n bundle.
        wireMenuAndModals();

        // Resume/auto-resume need the tutorial steps defined, which datasets.js
        // registers on window.i18nReady. Our handler is registered after that one
        // (script order: datasets.js loads first), so the steps exist when this runs.
        // A tour-driven navigation resumes silently; otherwise offer the banner.
        window.i18nReady?.then(() => {
            if (consumeAutoResume()) return;
            renderResumeBannerIfNeeded();
        });
    });
})();
