// /static/js/tutorial-dom.js
(function () {
    // Low-level DOM helpers only.
    // No tutorial state, no overlays, no step logic.
    // Kept separate so core + UI can reuse these primitives.

    if (window.DataHubTutorialDom) return;

    function qs(sel) { return document.querySelector(sel); }
    function qsa(sel) { return Array.from(document.querySelectorAll(sel)); }

    // Resolve after the next animation frame (one paint). The frame-based replacement for
    // a fixed-millisecond yield: it waits for the browser to actually lay out/paint rather
    // than guessing a duration, so it can't be outrun by a slow render or high latency.
    function nextFrame() { return new Promise(r => requestAnimationFrame(() => r())); }

    // Resolve when predicate() returns truthy, re-checking once per animation frame until
    // it does or the timeout deadline passes. Frame-driven, not a fixed delay: an outcome
    // that lands in 50ms or 5s is awaited the same way — we react to the result, never the
    // clock (the timeout is only a safety deadline so it can't hang forever). Re-checking
    // every frame (rather than a MutationObserver) also catches outcomes that aren't DOM
    // mutations — e.g. the canvas graph finishing a build. Resolves the truthy value or null.
    function waitFor(predicate, timeout = 10000) {
        const t0 = performance.now();
        const step = () => {
            let v = null;
            try { v = predicate(); } catch (e) { v = null; }
            if (v) return Promise.resolve(v);
            if (performance.now() - t0 >= timeout) return Promise.resolve(null);
            return nextFrame().then(step);
        };
        return step();
    }

    const asPredicate = (selOrFn) => (typeof selOrFn === "function" ? selOrFn : () => qs(selOrFn));

    // Waits until an element exists (selector string OR a function returning an element)
    // or times out. Resolves the element, or null on timeout.
    function waitForSelector(selOrFn, timeout = 10000) {
        return waitFor(asPredicate(selOrFn), timeout);
    }

    // Like waitForSelector, but races a success signal against a failure signal.
    // Resolves to the success element if it appears first, or null if the failure
    // selector appears first (or the timeout elapses). Lets a guarded action stop
    // waiting the moment the outcome is known — e.g. a save either inserts a new
    // list row or re-renders the form with a validation error — instead of always
    // burning the full timeout when the action was rejected. Success takes precedence
    // within a frame, matching a simultaneous success + fail.
    function waitForOutcome(successSelOrFn, failSelOrFn, timeout = 8000) {
        const ok = asPredicate(successSelOrFn);
        const bad = failSelOrFn ? asPredicate(failSelOrFn) : null;
        return waitFor(() => {
            const v = ok();
            if (v) return { ok: v };
            if (bad && bad()) return { fail: true };
            return null;
        }, timeout).then(r => (r && r.ok) ? r.ok : null);
    }

    // Some UI (especially the right-form) has fly-in animations; tooltip/spotlight
    // positioning should wait until the element's rect stops changing. Frame-driven via
    // nextFrame() (no fixed interval): resolves true once the rect holds for `frames`
    // consecutive frames, or false on timeout.
    function waitForStableRect(el, frames = 6, timeout = 1500) {
        if (!el) return Promise.resolve(false);
        const t0 = performance.now();
        let stable = 0;
        let last = el.getBoundingClientRect();
        const step = () => {
            if (performance.now() - t0 >= timeout) return Promise.resolve(false);
            return nextFrame().then(() => {
                const now = el.getBoundingClientRect();
                const moved =
                    Math.abs(now.left - last.left) > 0.5 ||
                    Math.abs(now.top - last.top) > 0.5 ||
                    Math.abs(now.width - last.width) > 0.5 ||
                    Math.abs(now.height - last.height) > 0.5;
                stable = moved ? 0 : (stable + 1);
                last = now;
                return stable >= frames ? Promise.resolve(true) : step();
            });
        };
        return step();
    }

    // Right-form is the slide-in container used by the app forms.
    function isInsideRightForm(el) {
        return !!el?.closest?.(".right-form");
    }

    // Best-effort close of right-form without coupling to app internals.
    // Tries button-based cancel first, then clears the container as a fallback.
    function closeRightFormBestEffort() {
        const cancel =
            qs(".right-form form footer button[type='button']") ||
            qs(".right-form form button[type='button']");

        if (cancel) {
            cancel.click();
            return true;
        }

        const rf = qs(".right-form");
        if (rf && rf.innerHTML.trim()) {
            rf.innerHTML = "";
            return true;
        }

        return false;
    }

    // Fires pointerdown/mousedown/click/mouseup/pointerup to match apps that listen
    // on different event types (many components listen to pointerdown).
    function fireHumanClick(el) {
        if (!el) return;

        const opts = { bubbles: true, cancelable: true, view: window };

        try { el.dispatchEvent(new PointerEvent("pointerdown", opts)); } catch {}
        try { el.dispatchEvent(new MouseEvent("mousedown", opts)); } catch {}

        el.click?.();

        try { el.dispatchEvent(new MouseEvent("mouseup", opts)); } catch {}
        try { el.dispatchEvent(new PointerEvent("pointerup", opts)); } catch {}
    }

    window.DataHubTutorialDom = {
        qs, qsa,
        nextFrame,
        waitFor,
        waitForSelector,
        waitForOutcome,
        waitForStableRect,
        isInsideRightForm,
        closeRightFormBestEffort,
        fireHumanClick,
    };
})();
