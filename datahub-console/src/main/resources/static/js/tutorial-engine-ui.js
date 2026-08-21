// /static/js/tutorial-engine-ui.js
(function () {
    // UI primitives used by the tutorial engine:
    // - overlay
    // - tooltip rendering
    // - spotlight rendering
    // - click/keyboard blocker
    // - tooltip positioning (anchor positioning when supported)

    if (window.DataHubTutorialUi) return;

    const Dom = window.DataHubTutorialDom;
    if (!Dom) {
        console.error("[tutorial] tutorial-engine-ui.js loaded before tutorial-dom.js");
        return;
    }

    const { qs, qsa, nextFrame, waitForSelector } = Dom;

    // Uses CSS Anchor Positioning when available.
    // Falls back to fixed positioning otherwise.
    const SUPPORTS_ANCHOR_POSITIONING =
        typeof CSS !== "undefined" &&
        CSS.supports?.("position-anchor: --a") &&
        CSS.supports?.("anchor-name: --a");

    // Active anchor is stored so it can be cleared between steps.
    let activeAnchorEl = null;

    function clearActiveAnchor() {
        if (!activeAnchorEl) return;
        activeAnchorEl.style.removeProperty("anchor-name");
        activeAnchorEl = null;
    }

    function setAnchor(targetEl, anchorName = "--tut-anchor") {
        clearActiveAnchor();
        targetEl.style.setProperty("anchor-name", anchorName);
        activeAnchorEl = targetEl;
    }

    function rectsOverlap(a, b) {
        return !(a.right <= b.left || a.left >= b.right || a.bottom <= b.top || a.top >= b.bottom);
    }

    // Nudges tooltip into viewport if it would clip off-screen.
    // Uses margin offsets so anchor positioning still works.
    function nudgeIntoViewport(el) {
        const pad = 8;

        el.style.marginLeft = "0px";
        el.style.marginTop = "0px";

        const r = el.getBoundingClientRect();
        let dx = 0;
        let dy = 0;

        if (r.left < pad) dx = pad - r.left;
        if (r.right > window.innerWidth - pad) dx = (window.innerWidth - pad) - r.right;
        if (r.top < pad) dy = pad - r.top;
        if (r.bottom > window.innerHeight - pad) dy = (window.innerHeight - pad) - r.bottom;

        if (dx) el.style.marginLeft = `${dx}px`;
        if (dy) el.style.marginTop = `${dy}px`;
    }

    // Selects a position that tends to point the tooltip toward screen center,
    // but only if there is enough available space for the tooltip.
    function chooseAutoPosition(targetRect, tooltipRect) {
        const vw = window.innerWidth;
        const vh = window.innerHeight;
        const pad = 12;

        const spaceLeft = targetRect.left;
        const spaceRight = vw - targetRect.right;
        const spaceTop = targetRect.top;
        const spaceBottom = vh - targetRect.bottom;

        const cx = vw / 2;
        const cy = vh / 2;

        const preferHorizontal = (targetRect.left + targetRect.width / 2) > cx ? "left" : "right";
        const preferVertical = (targetRect.top + targetRect.height / 2) > cy ? "top" : "bottom";

        const can = {
            right: spaceRight >= tooltipRect.width + pad,
            left: spaceLeft >= tooltipRect.width + pad,
            bottom: spaceBottom >= tooltipRect.height + pad,
            top: spaceTop >= tooltipRect.height + pad
        };

        const order = [preferHorizontal, preferVertical, "right", "left", "bottom", "top"];
        for (const pos of order) if (can[pos]) return pos;

        return "bottom";
    }

    // Applies anchor positioning + clears any fallback inline positioning.
    function attachTooltipToAnchor(tooltipEl, position, anchorName = "--tut-anchor") {
        tooltipEl.style.setProperty("position-anchor", anchorName);
        tooltipEl.dataset.pos = position || "bottom";

        tooltipEl.style.removeProperty("top");
        tooltipEl.style.removeProperty("left");
        tooltipEl.style.removeProperty("right");
        tooltipEl.style.removeProperty("bottom");
        tooltipEl.style.removeProperty("transform");
    }

    // Attempts alternate positions if tooltip overlaps the target. Promise-chained (no
    // async/await) per the console's no-async rule: walk the position list one frame at a
    // time, resolving as soon as a side doesn't overlap the target.
    function ensureNoOverlap(tip, targetEl) {
        if (!tip || !targetEl) return Promise.resolve();

        const targetRect = targetEl.getBoundingClientRect();
        const order = ["left", "right", "top", "bottom"];

        const tryPos = (i) => {
            if (i >= order.length) { nudgeIntoViewport(tip); return Promise.resolve(); }
            attachTooltipToAnchor(tip, order[i], "--tut-anchor");
            return nextFrame()
                .then(() => { nudgeIntoViewport(tip); return nextFrame(); })
                .then(() => {
                    if (!rectsOverlap(targetRect, tip.getBoundingClientRect())) return;
                    return tryPos(i + 1);
                });
        };
        return tryPos(0);
    }

    // Used for steps without a concrete DOM target (e.g. graph menu guidance).
    function placeBottomCenter(tip) {
        tip.style.position = "fixed";
        tip.style.left = "50%";
        tip.style.bottom = "18px";
        tip.style.top = "auto";
        tip.style.right = "auto";
        tip.style.transform = "translateX(-50%)";
        tip.style.maxWidth = "560px";
        nudgeIntoViewport(tip);
    }

    // Spotlight is a fixed element with a big outline mask around the highlighted area.
    function spotlightRect(rect) {
        let sp = qs(".tut-spotlight");
        if (!sp) {
            sp = document.createElement("div");
            sp.className = "tut-spotlight tut-pulse";
            document.body.appendChild(sp);
        }

        sp.style.top = `${rect.top + window.scrollY - 8}px`;
        sp.style.left = `${rect.left + window.scrollX - 8}px`;
        sp.style.width = `${rect.width + 16}px`;
        sp.style.height = `${rect.height + 16}px`;

        // Fade the dim/highlight in on first paint (opacity 0 -> 1) so a new step's spotlight
        // eases in instead of snapping. Only needed once; reposition calls just move it.
        if (!sp.classList.contains("tut-visible")) {
            requestAnimationFrame(() => sp.classList.add("tut-visible"));
        }
    }

    // Graph-node targeting: a canvas node has no DOM element to anchor to, so we
    // track its moving screen rect with an invisible proxy element. The proxy is a
    // real element, so the existing tooltip anchoring + spotlight treat it like any
    // DOM target. We realign the proxy (and spotlight) on each canvas redraw — cheap
    // and redraw-driven (fires only when the canvas repaints), so no idle polling.
    let activeGraphTrack = null;

    function clearGraphTrack() {
        if (!activeGraphTrack) return;
        try { activeGraphTrack.unsub?.(); } catch (e) { /* ignore */ }
        activeGraphTrack.proxy?.remove();
        activeGraphTrack = null;
    }

    // getRect: () => {left,top,width,height}|null ; onMove: (cb) => unsubscribe.
    // Returns the proxy element to use as the step target, or null if there's no rect
    // yet (node not rendered). The caller hands the proxy to positionTooltip as usual.
    function trackRect(getRect, onMove) {
        clearGraphTrack();
        const first = getRect();
        if (!first) return null;

        const proxy = document.createElement("div");
        proxy.className = "tut-proxy-target";
        proxy.style.cssText = "position:fixed; pointer-events:none; opacity:0; margin:0;";
        document.body.appendChild(proxy);

        const apply = (r) => {
            proxy.style.left = `${r.left}px`;
            proxy.style.top = `${r.top}px`;
            proxy.style.width = `${r.width}px`;
            proxy.style.height = `${r.height}px`;
        };
        const update = () => {
            const r = getRect();
            if (!r) return;
            apply(r);
            spotlightRect(r);
            // The spotlight's CSS transition smooths scroll/resize on DOM targets, but for a
            // graph node it lags behind the node's own movement (chase = wonky). While tracking
            // a node, pin it to the rect each frame so it stays glued to the node.
            const sp = qs(".tut-spotlight");
            if (sp) sp.style.transition = "none";
        };
        apply(first);

        activeGraphTrack = { proxy, unsub: onMove ? onMove(update) : null };
        return proxy;
    }

    // Global blocker prevents interactions outside allowed elements and the tooltip.
    // Implemented in capture phase so app handlers do not fire.
    let activeBlockerFn = null;

    function clearBlocker() {
        if (!activeBlockerFn) return;
        document.removeEventListener("pointerdown", activeBlockerFn, true);
        document.removeEventListener("click", activeBlockerFn, true);
        document.removeEventListener("keydown", activeBlockerFn, true);
        activeBlockerFn = null;
    }

    function installBlocker(allowedElements = [], lockedExitSelector) {
        clearBlocker();

        const isAllowed = (node) => {
            if (!node) return false;
            if (node.closest?.(".tut-tooltip")) return true;
            return allowedElements.some(el => el && el.contains(node));
        };

        // A real user click on a "locked exit" control (e.g. a guided form's Cancel,
        // which would remove the form and strand the tour) is swallowed so the form
        // can't be left mid-step — the only ways out are completing the step or the
        // tooltip's Quit. Only genuine user input is locked: programmatic clicks
        // (e.isTrusted === false), such as the engine's own closeRightFormBestEffort(),
        // still pass so the tour can close the form itself.
        const isLockedExit = (e) =>
            !!e.isTrusted && !!lockedExitSelector && !!e.target?.closest?.(lockedExitSelector);

        activeBlockerFn = (e) => {
            if (e.type === "keydown") {
                // Escape is deliberately NOT an exit. It used to end the run outright, which
                // made three unrelated gestures destructive: leaving F11 fullscreen, dismissing
                // a picker, and the reflex "close this overlay". Ending clears the resume keys,
                // so there was no banner and no way back in; restarting then reset the sandbox
                // and deleted whatever had just been built on screen. Quit is an explicit,
                // always-rendered tooltip button instead. Escape now falls through to the
                // pass-through below like every other key, so it still closes a picker and
                // still exits fullscreen — it just no longer takes the tour with it.

                // Keeps tab focus within the tutorial UI (navigation stays on-rails without
                // swallowing any typing — Tab just redirects focus, it doesn't block a key).
                if (e.key === "Tab") {
                    const active = document.activeElement;
                    if (!isAllowed(active)) {
                        e.preventDefault();
                        qs(".tut-tooltip button[data-act='next']")?.focus();
                    }
                    return;
                }

                // Every other keystroke passes through untouched — the keyboard stays fully
                // usable (typing anywhere, refresh, find, devtools, app shortcuts). This is
                // safe because keystrokes don't drive the tour: it only advances on the guarded
                // action (a click on the right control, or an input reaching its target). And a
                // keyboard-activated control (Tab -> Cancel -> Enter, Enter on a nav link) still
                // fires a real click event, which the click layer below catches — so the user
                // stays locked into the tour's flow without the keyboard being locked.
                return;
            }

            if (isLockedExit(e)) {
                e.preventDefault();
                e.stopPropagation();
                return;
            }

            if (!isAllowed(e.target)) {
                e.preventDefault();
                e.stopPropagation();
            }
        };

        document.addEventListener("pointerdown", activeBlockerFn, true);
        document.addEventListener("click", activeBlockerFn, true);
        document.addEventListener("keydown", activeBlockerFn, true);
    }

    // Reposition handler is attached per step so tooltip/spotlight track scroll/resize.
    let activeRepositionFn = null;

    function clearRepositionHandler() {
        if (!activeRepositionFn) return;
        window.removeEventListener("scroll", activeRepositionFn, true);
        window.removeEventListener("resize", activeRepositionFn, true);
        activeRepositionFn = null;
    }

    // Clears all tutorial UI artifacts.
    function clearUI() {
        qsa(".tut-overlay,.tut-tooltip,.tut-spotlight").forEach(el => el.remove());
        clearActiveAnchor();
        clearRepositionHandler();
        clearGraphTrack();
        clearBlocker();
        qsa(".tut-allow").forEach(el => el.classList.remove("tut-allow"));
    }

    // Overlay is present for layering consistency; click-blocking is handled by installBlocker.
    function createOverlay() {
        const overlay = document.createElement("div");
        overlay.className = "tut-overlay";
        document.body.appendChild(overlay);
        return overlay;
    }

    // $L returns the key itself for a missing message, so resolve against a fallback
    // (empty label rather than a raw key showing through if a build lacks the string).
    function i18nOr(key, fallback) {
        if (typeof $L !== "function") return fallback;
        const v = $L(key);
        return (v && v !== key) ? v : fallback;
    }

    // A chapter name for the progress label. Events reuses the shared #{events} key;
    // the rest have tutorial.chapters.<key>.
    function progressChapterLabel(key) {
        return i18nOr(key === "events" ? "events" : "tutorial.chapters." + key, key);
    }

    // A slim chapter stepper shown at the top of every tooltip: one segment per
    // chapter, filled up to the current one, with the chapter name beneath. Gives the
    // tour a sense of "where am I / how much is left" without per-step counting noise.
    function renderProgress(progress) {
        if (!progress || !progress.chapters || !progress.chapters.length) return "";
        const segs = progress.chapters.map((_, i) => {
            const state = progress.curIndex < 0 ? ""
                : i < progress.curIndex ? " done"
                    : i === progress.curIndex ? " active" : "";
            return `<span class="tut-seg${state}"></span>`;
        }).join("");
        const label = progress.curIndex < 0
            ? i18nOr("tutorial.progress.intro", "")
            : progressChapterLabel(progress.curKey);
        return `<div class="tut-progress"><div class="tut-progress-bar">${segs}</div>${label ? `<div class="tut-progress-label">${label}</div>` : ""}</div>`;
    }

    // Tooltip renders step content + navigation controls.
    function createTooltip(step, stepIdx, progress) {
        const tip = document.createElement("div");
        tip.className = "tut-tooltip tut-bubble";

        const labelPrev = (typeof $L === "function") ? $L("tutorial.button.back") : "Back";
        const labelQuit = (typeof $L === "function") ? $L("tutorial.button.quit") : "Quit";
        const labelSkip = (typeof $L === "function") ? $L("tutorial.button.skip") : "Skip";
        const labelNext = (typeof $L === "function") ? $L("tutorial.button.next") : "Next";

        tip.innerHTML = `
      ${renderProgress(progress)}
      ${step.title ? `<h4>${step.title}</h4>` : ""}
      <div>${step.content || ""}</div>
      <div class="tut-actions">
        ${stepIdx > 0 ? `<button class="dh-btn" data-act="prev">${labelPrev}</button>` : ""}
        <button class="dh-btn" data-act="end">${labelQuit}</button>
        ${step.skippable ? `<button class="dh-btn" data-act="skip">${labelSkip}</button>` : ""}
        ${step.altAction ? `<button class="dh-btn" data-act="alt">${step.altAction.label}</button>` : ""}
        <button class="dh-btn primary" data-act="next">${step.nextLabel || labelNext}</button>
      </div>
    `;

        document.body.appendChild(tip);
        // The tooltip starts at opacity 0 and is revealed (tut-visible) once positionTooltip
        // has placed it, so it fades in at its final spot instead of popping in and then
        // jumping into place. This timeout is a safety net: the tooltip must never stay hidden
        // if positioning is slow or fails.
        setTimeout(() => tip.classList.add("tut-visible"), 1500);
        return tip;
    }


    // Positions tooltip:
    // - bottomCenter: fixed bottom position (no target)
    // - no target: modal-style center
    // - target: anchored if supported, else fixed near the target rect
    // Promise-chained (no async/await) per the console's no-async rule.
    function positionTooltip({ step, tip, targetEl }) {
        // Center both axes — for the intro / "big picture" narration beats (they carry an SVG
        // illustration, so allow a wider bubble than the default 420px). Explicit placement so
        // intent is legible and doesn't depend on a target-less fall-through.
        if (step.placement === "center") {
            tip.classList.add("tut-centered");
            tip.style.position = "fixed";
            tip.style.top = "50%";
            tip.style.left = "50%";
            tip.style.transform = "translate(-50%, -50%)";
            // Clamped to the viewport: the side-by-side narration steps ask for a wider bubble
            // than a small screen has, and an overflowing centered box can't be nudged back in.
            tip.style.maxWidth = `min(${step.maxWidth || "620px"}, calc(100vw - 32px))`;
            nudgeIntoViewport(tip);
            return Promise.resolve({ reposition: null });
        }

        if (step.placement === "bottomCenter") {
            placeBottomCenter(tip);
            return Promise.resolve({ reposition: null });
        }

        if (!targetEl) {
            tip.style.position = "fixed";
            tip.style.top = "50%";
            tip.style.left = "50%";
            tip.style.transform = "translate(-50%, -50%)";
            return Promise.resolve({ reposition: null });
        }

        // Keeps the spotlight + tooltip aligned on scroll/resize. Returns a promise (the
        // de-overlap step is frame-based) — fine as an event listener (return value ignored).
        const reposition = () => {
            if (step.spotlight !== false) spotlightRect(targetEl.getBoundingClientRect());
            nudgeIntoViewport(tip);
            return ensureNoOverlap(tip, targetEl).then(() => nudgeIntoViewport(tip));
        };

        // Wire the reposition handler once the tooltip is placed, and return its descriptor.
        const finish = () => {
            activeRepositionFn = reposition;
            window.addEventListener("scroll", reposition, true);
            window.addEventListener("resize", reposition, true);
            reposition();
            return { reposition };
        };

        if (!SUPPORTS_ANCHOR_POSITIONING) {
            tip.style.position = "fixed";
            const r = targetEl.getBoundingClientRect();
            tip.style.top = `${Math.min(window.innerHeight - tip.offsetHeight - 12, r.bottom + 12)}px`;
            tip.style.left = `${Math.min(window.innerWidth - tip.offsetWidth - 12, r.left)}px`;
            return Promise.resolve(finish());
        }

        // Anchor positioning: set the anchor, choose a side once measured, then de-overlap.
        setAnchor(targetEl, "--tut-anchor");
        return nextFrame()
            .then(() => {
                const desired =
                    (!step.position || step.position === "auto")
                        ? chooseAutoPosition(targetEl.getBoundingClientRect(), tip.getBoundingClientRect())
                        : step.position;
                attachTooltipToAnchor(tip, desired, "--tut-anchor");
                return nextFrame();
            })
            .then(() => {
                nudgeIntoViewport(tip);
                return ensureNoOverlap(tip, targetEl);
            })
            .then(() => {
                nudgeIntoViewport(tip);
                return finish();
            });
    }

    window.DataHubTutorialUi = {
        clearUI,
        createOverlay,
        createTooltip,
        installBlocker,
        spotlightRect,
        trackRect,
        positionTooltip,
        waitForSelector,
    };
})();
