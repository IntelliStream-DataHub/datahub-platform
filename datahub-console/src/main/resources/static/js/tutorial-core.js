// /static/js/tutorial-core.js
(function () {
    // Tutorial engine/state machine:
    // - Tracks current tutorial + step
    // - Restores state from localStorage
    // - Resolves targets/fallback UI opening
    // - Renders UI primitives (overlay/tooltip/spotlight)
    // - Guards step progression (click/inputs)
    // - Blocks interactions outside the allowed area

    if (window.DataHubTutorialEngine) return;

    const Dom = window.DataHubTutorialDom;
    const Ui = window.DataHubTutorialUi;

    if (!Dom || !Ui) {
        console.error("[tutorial] tutorial-core.js loaded before tutorial-dom.js / tutorial-engine-ui.js");
        return;
    }

    const {
        qs,
        nextFrame,
        waitForSelector,
        waitForOutcome,
        waitForStableRect,
        closeRightFormBestEffort,
        fireHumanClick,
    } = Dom;

    const {
        clearUI,
        createOverlay,
        createTooltip,
        installBlocker,
        spotlightRect,
        trackRect,
        positionTooltip,
    } = Ui;

    // localStorage keys for tutorial progress
    const LS_KEY_ACTIVE = "activeTutorial";
    const LS_KEY_STEP = "tutorialStep";
    // When set, the tour is running a single chapter (steps tagged with this id);
    // it ends as soon as it would advance into a different chapter. null = full tour.
    const LS_KEY_CHAPTER = "tutorialChapter";

    // sessionStorage flag set just before a tour-driven page navigation so the
    // destination page knows to resume silently (read by tutorial-page-ui.js).
    const SS_AUTO_RESUME = "tutorial:autoResume";

    // The GUIDED FORM's Cancel (footer secondary button). A user click on it during a
    // guided step is swallowed by the blocker so the form can't be left mid-step and strand
    // the tour; the user completes the step or quits via the tooltip. The engine's own
    // programmatic close (closeRightFormBestEffort) is exempt — only trusted input is locked.
    //
    // Scoped to `.right-form > form` (the entity forms — DatasetFormAbstract) ONLY. In-form
    // pickers (ResourceSearch/RelationList/unit/label) mount as `.right-form > section`
    // (BaseList); their Close must stay clickable. Locking those too meant re-opening a
    // picker after a selection trapped the user — its Confirm is disabled (nothing newly
    // picked) and Close was swallowed, so there was no way out of the picker.
    const LOCKED_EXIT_SELECTOR = ".right-form > form footer .dh-btn.secondary";

    // localStorage keys for shared tutorial context (multi-tutorial friendly)
    const LS_CTX = {
        selectedDatasetId: "tutorial:ctx:selectedDatasetId",
    };

    // Shared, navigation-surviving context lives in tutorial-context.js. These
    // wrappers route through it when present and fall back to direct
    // localStorage / in-memory writes, so the engine still works standalone.
    function ctxSetSelectedDatasetId(id) {
        if (window.DataHubTutorialContext) window.DataHubTutorialContext.setSelectedDatasetId(id);
        else if (id) localStorage.setItem(LS_CTX.selectedDatasetId, id);
    }
    function ctxGetSelectedDatasetId() {
        return window.DataHubTutorialContext
            ? window.DataHubTutorialContext.getSelectedDatasetId()
            : localStorage.getItem(LS_CTX.selectedDatasetId);
    }
    function ctxRemember(key, value) {
        if (window.DataHubTutorialContext) {
            window.DataHubTutorialContext.remember(key, value);
        } else {
            window.__tutorialRemember = window.__tutorialRemember || {};
            window.__tutorialRemember[key] = value;
        }
    }

    // True if the browser is currently on the given page path, ignoring trailing
    // slashes and any query/hash. Used to gate page-aware step navigation.
    function samePage(page) {
        const norm = (p) => String(p || "").replace(/[?#].*$/, "").replace(/\/+$/, "");
        return norm(location.pathname) === norm(page);
    }

    // Captures which dataset the user interacted with so resume can restore selection.
    // Capture phase ensures this runs before app handlers.
    function rememberSelectedDatasetFromClick(e) {
        const li = e.target?.closest?.("ul[data-type='root-dataset-list'] li[data-id]");
        if (!li) return;
        const id = li.getAttribute("data-id");
        if (id) ctxSetSelectedDatasetId(id);
    }

    document.addEventListener("pointerdown", rememberSelectedDatasetFromClick, true);
    document.addEventListener("click", rememberSelectedDatasetFromClick, true);

    function getRememberedDatasetId() {
        return ctxGetSelectedDatasetId();
    }

    // Used to avoid re-selecting dataset during normal step progression.
    // Re-selection can close right-form and menus depending on app behavior.
    function isDatasetAlreadySelected(li) {
        if (!li) return false;

        if (li.matches?.("[aria-selected='true']")) return true;

        const cls = li.className || "";
        if (/\b(selected|active|is-selected|current)\b/.test(cls)) return true;

        return !!li.querySelector?.("[aria-selected='true'], .selected, .active, .is-selected, .current");
    }

    function selectDatasetByIdBestEffort(id) {
        if (!id) return false;

        const li = qs(`ul[data-type="root-dataset-list"] li[data-id="${CSS.escape(id)}"]`);
        if (!li) return false;

        if (isDatasetAlreadySelected(li)) return true;

        const clickable = li.querySelector("span,button,a") || li;
        fireHumanClick(clickable);
        return true;
    }

    // Normalizes step schema for backward compatibility.
    // New: step.context = { dataset: "none|required", rightForm: "none|entry|inside" }
    // Old: requiresSelectedDataset / entersRightForm / inRightForm
    function normalizeStep(step) {
        const s = { ...step };

        if (!s.context) s.context = { dataset: "none", rightForm: "none" };

        if (s.requiresSelectedDataset && s.context.dataset === "none") {
            s.context.dataset = "required";
        }

        if (s.entersRightForm && s.context.rightForm === "none") {
            s.context.rightForm = "entry";
        }

        if (s.inRightForm && s.context.rightForm === "none") {
            s.context.rightForm = "inside";
        }

        return s;
    }

    function normalizeSteps(steps) {
        return (steps || []).map(normalizeStep);
    }

    // Guard helpers used by Next button when a step needs validation.
    // Promise-chained (no async/await) per the console's no-async rule.
    const Guards = {
        click: (selOrFn, opts = {}) =>
            waitForSelector(selOrFn, opts.timeout || 10000).then((el) => {
                if (!el) return false;

                const eventType = opts.eventType || "pointerdown";

                return new Promise((resolve) => {
                    let done = false;

                    const finish = () => {
                        if (done) return;
                        done = true;
                        resolve(true);
                    };

                    el.addEventListener(eventType, finish, { once: true });
                    if (eventType !== "click") el.addEventListener("click", finish, { once: true });
                });
            }),

        inputFilled: (sel, opts = {}) =>
            waitForSelector(sel, opts.timeout || 10000).then((el) => {
                if (!el) return false;

                return new Promise((resolve) => {
                    const check = () => {
                        if ((el.value || "").trim().length > 0) {
                            el.removeEventListener("input", check);
                            resolve(true);
                        }
                    };

                    el.addEventListener("input", check);
                    check();
                });
            }),

        inputMin: (sel, opts = {}) =>
            waitForSelector(sel, opts.timeout || 10000).then((el) => {
                if (!el) return { ok: false };

                const min = Number(opts.min ?? 3);
                const isOk = () => ((el.value || "").trim().length >= min);

                return { ok: isOk(), el, isOk };
            }),
    };

    // Resolves a step target and optionally opens required UI if the target is inside it.
    // Promise-chained (no async/await) to keep this file free of async functions.
    function resolveTarget(step) {
        // Graph-node target: spotlight a node on the Cytoscape canvas. The node has no
        // DOM element, so we center it, then hand the engine a proxy element that
        // tracks the node's live screen rect (see Ui.trackRect). step.graphNode is an
        // externalId string or a function returning one.
        if (step.graphNode) {
            const graph = window.resourceNetwork;
            const ext = (typeof step.graphNode === "function") ? step.graphNode() : step.graphNode;
            if (!graph || !ext) return Promise.resolve(null);

            // A just-created resource may still be propagating to the graph (Postgres ->
            // Pulsar -> Neo4j); waitForNode re-loads the network until the node shows.
            return graph.waitForNode(ext).then((node) => {
                if (!node) return null;

                // Snap (duration 0) instead of animating the centering: a moving node makes the
                // spotlight chase it, which reads as wonky. Snapping places the node at its final
                // spot before we measure + track, so the outline lands cleanly.
                graph.focusNodeIntoView(ext, { duration: 0 });

                // Poll (per animation frame) until the node has a rect (exists + rendered)
                // before tracking. The graph is a canvas, so this isn't a DOM mutation — a
                // frame tick is the right cadence. Time-bounded (~3s) so a node that never
                // renders doesn't wait forever.
                const tRect0 = performance.now();
                const pollRect = () => {
                    const rect = graph.nodeScreenRect(ext);
                    if (rect) return Promise.resolve(rect);
                    if (performance.now() - tRect0 >= 3000) return Promise.resolve(null);
                    return nextFrame().then(pollRect);
                };

                return pollRect().then((rect) => {
                    if (!rect) return null;
                    return trackRect(
                        () => graph.nodeScreenRect(ext),
                        (cb) => graph.onViewportChange(cb)
                    );
                });
            });
        }

        if (!step.target) return Promise.resolve(null);

        // When a fallbackOpen is declared the target lives inside UI that may be closed
        // (e.g. arriving on a right-form step via Back). Only briefly probe for an
        // already-open target before opening it — otherwise this initial wait burns the
        // full step.timeout (~12s) before fallbackOpen ever runs.
        const initialWait = step.fallbackOpen ? 600 : (step.timeout || 5000);

        // Once resolved, scroll the target into view and wait for its rect to settle
        // before the caller spotlights it. Smooth-scrolling a long list (e.g. the
        // root-resource list) takes longer than any fixed delay, so snapshotting the
        // rect after a static wait would spotlight the row mid-scroll — the outline
        // lands on whichever row happens to be at that position, which reads as
        // "highlighting the wrong resource". waitForStableRect polls until the element
        // stops moving, so this holds for every target, not just right-form fly-ins.
        const settle = (targetEl) => {
            if (!targetEl) return targetEl;
            targetEl.scrollIntoView({ behavior: "smooth", block: "center" });
            return nextFrame()                                    // one paint so a just-started smooth scroll registers as movement
                .then(() => waitForStableRect(targetEl, 6, 1500)) // wait until the target stops moving (scroll/fly-in finished)
                .then(() => targetEl);                            // return the now-stationary element for the caller to spotlight
        };

        return waitForSelector(step.target, initialWait).then((targetEl) => {
            if (targetEl || !step.fallbackOpen) return targetEl;
            // fallbackOpen is used when the target is inside UI that must be opened first.
            return waitForSelector(step.fallbackOpen, 2000).then((opener) => {
                fireHumanClick(opener);
                // waitForSelector already polls until the target mounts, so the panel opening
                // on click needs no fixed delay — we wait for the real target to appear.
                return waitForSelector(step.target, step.timeout || 10000);
            });
        }).then(settle);
    }

    class Engine {
        constructor() {
            this.tutorials = {};     // tutorialId -> normalized steps
            this.current = null;     // current tutorialId
            this.stepIdx = 0;        // current step index
            this._waitingForGuard = false;
            this._runSeq = 0;        // incremented per run() to cancel stale async work
        }

        define(id, steps) {
            this.tutorials[id] = normalizeSteps(steps);
        }

        start(id) {
            localStorage.setItem(LS_KEY_ACTIVE, id);
            localStorage.setItem(LS_KEY_STEP, "0");
            localStorage.removeItem(LS_KEY_CHAPTER);
            this._chapter = null;
            this.current = id;
            this.stepIdx = 0;
            return this.run();
        }

        // Jump into a chapter and play THROUGH to the end of the tour (not just that chapter).
        // Steps are tagged with `chapter`; we start at the chosen chapter's first step and run
        // normally to the recap. Prerequisite assets for the skipped earlier chapters are seeded
        // by the caller (tutorial-page-ui) so the later chapters have what they build on.
        startChapter(chapterId, id = "datasets") {
            const steps = this.tutorials[id] || [];
            const idx = steps.findIndex(s => s && s.chapter === chapterId);
            if (idx < 0) return Promise.resolve();
            localStorage.setItem(LS_KEY_ACTIVE, id);
            localStorage.setItem(LS_KEY_STEP, String(idx));
            localStorage.removeItem(LS_KEY_CHAPTER);   // no single-chapter fence — play to the end
            this._chapter = null;
            this.current = id;
            this.stepIdx = idx;
            return this.run();
        }

        resumeOrStart(id) {
            const active = localStorage.getItem(LS_KEY_ACTIVE);
            const storedStep = parseInt(localStorage.getItem(LS_KEY_STEP) || "0", 10);
            this._chapter = localStorage.getItem(LS_KEY_CHAPTER) || null;

            this.current = id;

            // If stored state belongs to another tutorial, start from scratch.
            if (active !== id) {
                this.stepIdx = 0;
                localStorage.setItem(LS_KEY_ACTIVE, id);
                localStorage.setItem(LS_KEY_STEP, "0");
                return this.run();
            }

            const steps = this.tutorials[id] || [];
            let idx = Math.min(Math.max(storedStep, 0), Math.max(steps.length - 1, 0));

            // Right-form content is not persisted across refresh.
            // If resuming inside right-form, rewind to the last entry step.
            const cur = steps[idx];
            if (cur?.context?.rightForm === "inside") {
                let entryIdx = 0;
                for (let i = idx - 1; i >= 0; i--) {
                    if (steps[i]?.context?.rightForm === "entry") { entryIdx = i; break; }
                }
                idx = entryIdx;
            }

            this.stepIdx = idx;
            localStorage.setItem(LS_KEY_STEP, String(this.stepIdx));

            // Restore selected dataset only once on resume (not on next/prev), and
            // only when we're already on the step's page — otherwise run() below
            // navigates first and the restore happens after arrival. Without this
            // guard, resuming from another page would block ~8s waiting for the
            // dataset list that only exists on the datasets page.
            const targetStep = steps[this.stepIdx];
            const onStepPage = !targetStep?.page || samePage(targetStep.page);
            const needsDataset = onStepPage
                && steps.slice(this.stepIdx).some(s => s?.context?.dataset === "required");

            let restore = Promise.resolve();
            if (needsDataset) {
                const dsId = getRememberedDatasetId();
                if (dsId) {
                    restore = waitForSelector("ul[data-type='root-dataset-list'] li[data-id]", 8000)
                        // Select, then yield one frame so the click dispatches; run() below
                        // does the real waiting for the dataset's node/target to appear.
                        .then(() => { selectDatasetByIdBestEffort(dsId); return nextFrame(); });
                }
            }

            return restore.then(() => this.run());
        }

        next() {
            this.stepIdx++;
            localStorage.setItem(LS_KEY_STEP, String(this.stepIdx));
            return this.run();
        }

        prev() {
            this.stepIdx = Math.max(0, this.stepIdx - 1);
            localStorage.setItem(LS_KEY_STEP, String(this.stepIdx));
            // After the step renders, run its back-undo hook if it defines one — e.g. a
            // save step deletes the entity it created and refills the reopened form, so
            // Back consistently undoes a creation. Runs only on prev() (going back), so
            // forward navigation through the same step is unaffected. The hook self-guards
            // (no pending entity -> no-op), so repeated Backs are safe.
            return this.run().then(() => {
                const step = (this.tutorials[this.current] || [])[this.stepIdx];
                if (typeof step?.onBack === "function") {
                    // Tolerate a sync throw or a rejected promise from the hook.
                    return Promise.resolve().then(() => step.onBack()).catch(() => { /* best effort */ });
                }
            });
        }

        end() {
            clearUI();
            localStorage.removeItem(LS_KEY_STEP);
            localStorage.removeItem(LS_KEY_ACTIVE);
            localStorage.removeItem(LS_KEY_CHAPTER);
            this._chapter = null;
            this.current = null;
            this.stepIdx = 0;
        }

        // Chapter-based progress for the tooltip's stepper. Every step carries a
        // step.chapter (tagged in the tutorial definition) except the intro, so the
        // ordered unique chapters ARE the journey. Returns the chapter list plus where
        // the current step sits in it (curIndex -1 = pre-chapter intro).
        progressFor(idx) {
            const steps = this.tutorials[this.current] || [];
            const chapters = [];
            steps.forEach(s => { if (s.chapter && chapters.indexOf(s.chapter) === -1) chapters.push(s.chapter); });
            const curKey = (steps[idx] && steps[idx].chapter) || null;
            return { chapters, curKey, curIndex: curKey ? chapters.indexOf(curKey) : -1 };
        }

        run() {
            clearUI();

            const runSeq = ++this._runSeq;
            this._waitingForGuard = false;

            const steps = this.tutorials[this.current] || [];
            const step = steps[this.stepIdx];

            if (!step) {
                this.end();
                return Promise.resolve();
            }

            // Single-chapter run: stop once we'd advance into a different chapter.
            if (this._chapter && step.chapter && step.chapter !== this._chapter) {
                this.end();
                return Promise.resolve();
            }

            // Page-aware step: if it belongs to another page, navigate there and
            // let that page resume at this step (the step index is already
            // persisted to localStorage). No-op for same-page tutorials (no
            // step.page), so existing tutorials are unaffected. The autoResume
            // flag tells the destination page this navigation was tour-driven,
            // so tutorial-page-ui.js resumes silently instead of showing the
            // manual resume banner.
            if (step.page && !samePage(step.page)) {
                clearUI();
                closeRightFormBestEffort();
                try { sessionStorage.setItem(SS_AUTO_RESUME, "1"); } catch (e) { /* private mode */ }
                location.assign(step.page);
                return Promise.resolve();
            }

            // Fire a one-time side effect when this step is shown on its page — e.g. trigger
            // the network auto-build, or highlight a set of graph nodes/relations for an
            // explanation beat. Best-effort: a throwing hook never blocks the step.
            if (typeof step.onEnter === "function") {
                try { step.onEnter(); } catch (_) { /* best effort */ }
            }

            // Overlay exists for consistent layering; input blocking handled by installBlocker.
            createOverlay();

            // 3) Compute allowed click area (tooltip is always allowed by UI blocker).
            // step.allow may be a single selector or an array — a step that spans two
            // regions (e.g. the graph canvas AND the right-form that opens from it) needs
            // both. Anything inside an allowed container stays interactive, so allowing a
            // persistent container like .right-form also covers a form opened later.
            // Resolved in order, dropping misses, without async/await.
            const resolveAllowed = () => {
                if (!step.allow) return Promise.resolve([]);
                const allowSelectors = Array.isArray(step.allow) ? step.allow : [step.allow];
                return allowSelectors.reduce(
                    (chain, sel) => chain.then((acc) =>
                        waitForSelector(sel, 10000).then((el) => { if (el) acc.push(el); return acc; })
                    ),
                    Promise.resolve([])
                );
            };

            // 7) Auto-advance for click steps (user action triggers progression).
            const startClickGuardWatcher = (nextBtn) => {
                if (step.guard?.type !== "click") return;

                if (step.guard.hideNext && nextBtn) {
                    nextBtn.style.display = "none";
                } else if (nextBtn) {
                    nextBtn.disabled = true;
                    nextBtn.textContent = step.guard.hintText || "Do the highlighted action";
                }

                waitForSelector(step.guard.selector, step.timeout || 10000).then((el) => {
                    if (!el || runSeq !== this._runSeq) return;

                    const ev = step.guard.eventType || "pointerdown";

                    const handler = () => {
                        if (runSeq !== this._runSeq) return;

                        // Optional: capture values before UI changes (e.g., dataset name before save)
                        if (step.guard.rememberKey && step.guard.rememberSelector) {
                            const f = qs(step.guard.rememberSelector);
                            const v = (f && "value" in f) ? String(f.value || "").trim() : "";
                            if (v) {
                                ctxRemember(step.guard.rememberKey, v);
                            }
                        }

                        // Let the step capture pre-action state (e.g. which list rows
                        // already exist) before the guarded action mutates the DOM, so
                        // its success signal can tell "this action just happened" from
                        // "something matching was already there."
                        if (typeof step.guard.onAction === "function") {
                            try { step.guard.onAction(); } catch (_) { /* best effort */ }
                        }

                        // A failure signal left over from a PREVIOUS rejected attempt (e.g. the
                        // "duplicate external id" flash from the save the user is now retrying)
                        // would make waitForOutcome report failure before this action's real
                        // outcome lands — leaving the tour stuck on the step after a successful
                        // retry. Clear stale matches so we only react to a fresh outcome.
                        if (typeof step.guard.afterFailFor === "string") {
                            document.querySelectorAll(step.guard.afterFailFor).forEach(el => el.remove());
                        }

                        // When the step defines a success signal, only advance if it
                        // actually appears. If instead the failure signal appears (e.g.
                        // the form re-renders a validation error because the server
                        // rejected the save for a duplicate external id), or neither
                        // appears in time, the action didn't really happen: re-arm the
                        // listener so the user can fix the error and retry, and don't
                        // close the form or advance.
                        Promise.resolve().then(() => {
                            if (!step.guard.afterWaitFor) return true;
                            return waitForOutcome(
                                step.guard.afterWaitFor,
                                step.guard.afterFailFor,
                                step.guard.afterWaitTimeout || 8000
                            );
                        }).then((ok) => {
                            if (runSeq !== this._runSeq) return;
                            if (!ok) {
                                // The action was rejected. The form usually re-renders (a
                                // fresh submit button replaces the old one), so re-attaching
                                // to the original element would bind a dead listener and the
                                // corrected retry would never be caught. Re-run the step to
                                // re-resolve the target and re-arm against the live DOM.
                                return this.run();
                            }

                            // Optional: close right-form after save/update so next steps can target list/graph
                            const closed = step.guard.closeRightFormAfter
                                ? nextFrame().then(() => { closeRightFormBestEffort(); })
                                : Promise.resolve();

                            return closed.then(() => {
                                if (runSeq !== this._runSeq) return;
                                return this.next();
                            });
                        });
                    };

                    el.addEventListener(ev, handler, { once: true });
                    if (ev !== "click") el.addEventListener("click", handler, { once: true });
                });
            };

            // 7b) appear guard: advance when a selector/predicate becomes truthy, with
            // no click to bind to. Used for canvas-built outcomes the click guard can't
            // catch — e.g. "a new graph node appeared" after the user works the graph's
            // own tap → tooltip → add-node flow. onAction snapshots pre-action state so
            // the predicate can tell a freshly created element from a pre-existing one.
            const startAppearWatcher = (nextBtn) => {
                if (step.guard?.type !== "appear") return;

                if (step.guard.hideNext && nextBtn) {
                    nextBtn.style.display = "none";
                } else if (nextBtn) {
                    nextBtn.disabled = true;
                    nextBtn.textContent = step.guard.hintText || "Do the highlighted action";
                }

                if (typeof step.guard.onAction === "function") {
                    try { step.guard.onAction(); } catch (_) { /* best effort */ }
                }

                const advance = () => { if (runSeq === this._runSeq) this.next(); };

                // Long timeout — the user may take a while to work the graph.
                waitForSelector(step.guard.selector, step.guard.timeout || 120000).then((found) => {
                    if (!found || runSeq !== this._runSeq) return;
                    if (step.guard.closeRightFormAfter) {
                        nextFrame().then(() => { closeRightFormBestEffort(); advance(); });
                    } else {
                        advance();
                    }
                });
            };

            // 8) enableNext mode: user must type enough, then Next becomes enabled.
            const wireEnableNext = (nextBtn) => {
                if (!(step.guard?.mode === "enableNext" && nextBtn)) return Promise.resolve();
                nextBtn.disabled = true;

                return waitForSelector(step.guard.selector, step.timeout || 10000).then((field) => {
                    if (field) {
                        const min = Number(step.guard.min ?? 3);
                        const isOk = () => ((field.value || "").trim().length >= min);

                        const update = () => { nextBtn.disabled = !isOk(); };

                        field.addEventListener("input", update);
                        field.addEventListener("change", update);
                        update();
                    } else {
                        // If selector is wrong, avoid deadlock.
                        nextBtn.disabled = false;
                    }
                });
            };

            // 9) Tooltip navigation
            const wireTooltipNav = (tip) => {
                tip.addEventListener("click", (e) => {
                    const btn = e.target.closest("[data-act]");
                    if (!btn) return;

                    if (this._waitingForGuard) return;

                    const act = btn.getAttribute("data-act");

                    if (act === "prev") { this.prev(); return; }
                    if (act === "skip") { this.end(); return; }
                    if (act === "end")  { this.end(); return; }
                    // Secondary action (e.g. the recap's "Remove tutorial data"): run the
                    // step's onAlt hook, then end the tour regardless of its outcome.
                    if (act === "alt")  {
                        Promise.resolve().then(() => step.onAlt && step.onAlt()).catch(() => {}).finally(() => this.end());
                        return;
                    }

                    if (act === "next") {
                        if (step.guard?.mode === "enableNext") {
                            if (btn.disabled) return;
                            this.next();
                            return;
                        }

                        if (!step.guard) {
                            this.next();
                            return;
                        }

                        // Guarded Next: wait until the guard condition is satisfied.
                        this._waitingForGuard = true;
                        btn.disabled = true;
                        btn.textContent = "Waiting…";

                        Promise.resolve(Guards[step.guard.type]?.(step.guard.selector, step.guard)).then((ok) => {
                            if (runSeq !== this._runSeq) return;

                            this._waitingForGuard = false;

                            if (ok === true || (ok && typeof ok === "object" && ok.ok === true)) {
                                this.next();
                            } else {
                                btn.disabled = false;
                                btn.textContent = step.nextLabel || "Next →";
                            }
                        });
                    }
                });
            };

            // 1) Resolve target element (or null for conceptual steps)
            return resolveTarget(step).then((targetEl) => {
                // 2) Render tooltip
                const tip = createTooltip(step, this.stepIdx, this.progressFor(this.stepIdx));
                const nextBtn = tip.querySelector('[data-act="next"]');

                return resolveAllowed().then((allowed) => {
                    // The target is interactive by default. Passive "look at this" steps set
                    // interactive:false so the spotlight shows but the element can't be clicked
                    // — e.g. a graph spotlight where tapping a node would open a form the blocker
                    // then traps.
                    if (targetEl && step.interactive !== false) allowed.push(targetEl);

                    // 4) Block everything except allowed elements + tooltip UI, and lock
                    // the form's exit controls so a guided form can't be closed mid-step.
                    // step.lockSubmit additionally locks the form's Save button on the steps
                    // BEFORE the save step, so saving early can't strand the tour on a
                    // reopened empty form. The pickers' Confirm is type="button", so it's
                    // unaffected — only the real submit is swallowed.
                    const lockedSel = step.lockSubmit
                        ? LOCKED_EXIT_SELECTOR + ', .right-form form footer button[type="submit"]'
                        : LOCKED_EXIT_SELECTOR;
                    installBlocker(allowed, lockedSel);

                    // 5) Spotlight target unless disabled
                    if (targetEl && step.spotlight !== false) {
                        spotlightRect(targetEl.getBoundingClientRect());
                    }

                    // 6) Position tooltip relative to target, then fade it in at its final
                    // spot (tut-visible) so it doesn't pop in and jump while positioning.
                    return positionTooltip({ step, tip, targetEl }).then(() => {
                        tip.classList.add("tut-visible");
                        // 7 + 7b) start the click/appear watchers (fire-and-forget)
                        startClickGuardWatcher(nextBtn);
                        startAppearWatcher(nextBtn);

                        // 8) enableNext, then 9) wire tooltip navigation last (matches the
                        // original ordering: the click handler is attached after enableNext).
                        return wireEnableNext(nextBtn).then(() => wireTooltipNav(tip));
                    });
                });
            });
        }
    }

    window.DataHubTutorialEngine = new Engine();
})();
