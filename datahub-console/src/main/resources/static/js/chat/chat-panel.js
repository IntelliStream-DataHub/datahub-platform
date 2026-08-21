/**
 * Chat panel — a conversation with the data assistant, docked as a slide-in panel on the right edge.
 *
 * The whole turn is one POST: the server runs the model and any tool calls and answers when it is
 * done, so there is no token streaming. That means a visible wait of several seconds, which is why
 * the pending state shows a spinner and the answer carries a note of which tools were consulted.
 *
 * Assistant replies are rendered as safe Markdown (see chat-markdown.js) so tables, lists and code
 * come through formatted. The panel's width is user-adjustable via the left drag handle and
 * remembered across turns; it is clamped so it can never swallow the whole page.
 *
 * There is no built-in launcher button: the panel is opened by any element carrying
 * data-type="open-chat" (the "Ask AI" button in the header), and closed from its own header.
 */
(function () {
	'use strict';

	const ENDPOINT = '/api/chat';
	// The first turn after a restart is slow: the datahub-api provisions the tenant on demand and
	// the model prompt cache is cold. Give that cold path generous head-room; warm turns are quick.
	// Only the fallback until loadHistory() reports datahub.chat.turn-timeout — a self-hosted model
	// needs minutes per turn where a hosted one needs seconds, so the server owns this number.
	const DEFAULT_TURN_TIMEOUT_MS = 240000;
	/**
	 * Where a pending view is left for the target page to pick up. The console keeps state out of
	 * the URL, so the handoff goes through sessionStorage instead of query parameters: per-tab by
	 * nature, and the page clears it on read so a later visit is unaffected.
	 */
	const PENDING_VIEW_KEY = 'dh-pending-view';
	/**
	 * The effort level, remembered across messages and pages: the picker is per message, but nobody
	 * wants to re-pick "max" for every question in an investigation. localStorage rather than
	 * sessionStorage so the choice outlives the tab, and the server re-validates it anyway.
	 */
	const EFFORT_KEY = 'dh-chat-effort';
	// Mirrors the ChatEffort enum. The server falls back on anything it doesn't recognise, so a
	// stale cached panel after a rename degrades to the deployment default rather than erroring.
	const EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max'];
	// Where each server-emitted view.page navigates to, and the button label for it. A page absent
	// here is silently ignored, so the server can only ever send us destinations we know.
	const VIEW_PAGES = {
		events: '/events',
		resources: '/resources',
		insights: '/timeseries/insights',
		analyze: '/timeseries/analyze'
	};
	const VIEW_LABELS = {
		events: 'chat.view.events',
		resources: 'chat.view.resources',
		insights: 'chat.view.insights',
		analyze: 'chat.view.analyze'
	};

	// Panel width bounds. The max is a fraction of the viewport so the panel never covers the whole
	// page, and the last chosen width persists across turns and page loads.
	const WIDTH_KEY = 'dh-chat-width';
	const MIN_WIDTH = 320;
	const DEFAULT_WIDTH = 560;
	const KEY_STEP = 40;

	// Open/closed state, kept per-tab so navigating with the panel open leaves it open on the next
	// page, and navigating with it closed keeps it closed.
	const OPEN_KEY = 'dh-chat-open';
	// Last rendered transcript, cached per-tab so the panel paints its content instantly on the next
	// page instead of flashing the greeting while it re-fetches.
	const CACHE_KEY = 'dh-chat-log';

	class ChatPanel {
		constructor(root) {
			this.root = root;
			this.busy = false;
			this.history = []; // mirrors the rendered transcript; kept in sync with CACHE_KEY
			// Replaced by the deployment's own default once loadHistory() lands; this only decides
			// what the picker shows for the fraction of a second before that, and matches the
			// server's own fallback so the two agree in the common case.
			this.defaultEffort = 'high';
			this.turnTimeoutMs = DEFAULT_TURN_TIMEOUT_MS;
			this.render();
		}

		render() {
			this.root.innerHTML = `
				<section class="chat-window" aria-hidden="true">
					<div class="chat-resize" role="separator" aria-orientation="vertical" tabindex="0" title=""></div>
					<div class="chat-body">
						<header class="chat-header">
							<span class="chat-title"></span>
							<button type="button" class="chat-reset" title=""><i class="fa-regular fa-trash-can"></i></button>
							<button type="button" class="chat-close" title=""><i class="fa-solid fa-xmark"></i></button>
						</header>
						<div class="chat-log" role="log" aria-live="polite"></div>
						<form class="chat-input">
							<div class="chat-composer">
								<textarea rows="1" maxlength="4000"></textarea>
								<div class="chat-controls">
									<select id="chat-effort" class="chat-effort">
										${EFFORT_LEVELS.map((level) =>
											`<option value="${level}"></option>`).join('')}
									</select>
									<button type="submit"><i class="fa-solid fa-paper-plane"></i></button>
								</div>
							</div>
						</form>
					</div>
				</section>`;

			this.window = this.root.querySelector('.chat-window');
			this.log = this.root.querySelector('.chat-log');
			this.form = this.root.querySelector('.chat-input');
			this.textarea = this.form.querySelector('textarea');
			this.submit = this.form.querySelector('button[type=submit]');
			this.handle = this.root.querySelector('.chat-resize');

			this.effort = this.form.querySelector('#chat-effort');
			this.effort.value = this.storedEffort();
			this.effort.addEventListener('change', () => {
				try {
					localStorage.setItem(EFFORT_KEY, this.effort.value);
				} catch (e) { /* private mode: the choice still applies to this page */ }
			});

			this.root.querySelector('.chat-title').textContent = $L('chat.title');
			this.effort.setAttribute('aria-label', $L('chat.effort.label'));
			this.effort.title = $L('chat.effort.title');
			EFFORT_LEVELS.forEach((level, i) => {
				this.effort.options[i].textContent = $L('chat.effort.' + level);
			});
			this.root.querySelector('.chat-reset').title = $L('chat.reset');
			this.root.querySelector('.chat-close').title = $L('close');
			this.handle.title = $L('chat.resize');
			this.handle.setAttribute('aria-label', $L('chat.resize'));
			this.textarea.placeholder = $L('chat.placeholder');

			this.root.querySelector('.chat-close').addEventListener('click', () => this.close());
			this.root.querySelector('.chat-reset').addEventListener('click', () => this.reset());
			this.form.addEventListener('submit', (e) => {
				e.preventDefault();
				this.send();
			});
			// Enter sends, shift+enter is a newline — the usual chat convention.
			this.textarea.addEventListener('keydown', (e) => {
				if (e.key === 'Enter' && !e.shiftKey) {
					e.preventDefault();
					this.send();
				}
			});

			this.applyWidth(this.storedWidth());
			this.setupResize();
			window.addEventListener('resize', () => this.applyWidth(this.currentWidth()));

			// Paint the transcript synchronously from the per-tab cache so the final content is on
			// screen at first paint — no greeting-then-history flash on navigation. loadHistory()
			// then reconciles against the server and only redraws if something actually changed.
			const cached = this.readCache();
			if (cached && cached.length) {
				this.renderHistory(cached);
			} else {
				this.showGreeting();
			}
		}

		// ---- transcript cache -------------------------------------------------------------------

		readCache() {
			try {
				const parsed = JSON.parse(sessionStorage.getItem(CACHE_KEY));
				return Array.isArray(parsed) ? parsed : null;
			} catch (e) {
				return null;
			}
		}

		persistHistory() {
			try {
				sessionStorage.setItem(CACHE_KEY, JSON.stringify(this.history));
			} catch (e) { /* private mode or quota: the panel still works, it just re-fetches */ }
		}

		/** Renders a whole transcript (prose + navigation buttons) and updates the cache. */
		renderHistory(messages) {
			this.log.innerHTML = '';
			messages.forEach((m) => {
				this.addMessage(m.role, m.text, m.role === 'assistant');
				this.addViews(m.views);
			});
			this.history = messages;
			this.persistHistory();
		}

		showGreeting() {
			this.log.innerHTML = '';
			this.addMessage('assistant', $L('chat.greeting'), true);
			this.history = [];
			this.persistHistory();
		}

		// ---- width / resize ---------------------------------------------------------------------

		maxWidth() {
			return Math.min(720, Math.round(window.innerWidth * 0.92));
		}

		clampWidth(w) {
			return Math.max(MIN_WIDTH, Math.min(this.maxWidth(), Math.round(w)));
		}

		currentWidth() {
			return parseInt(this.window.style.width, 10) || this.storedWidth();
		}

		/** The user's own choice, or the deployment default until they make one. */
		storedEffort() {
			let stored = null;
			try {
				stored = localStorage.getItem(EFFORT_KEY);
			} catch (e) { /* private mode: fall through to the default */ }
			return EFFORT_LEVELS.includes(stored) ? stored : this.defaultEffort;
		}

		storedWidth() {
			let w = NaN;
			try {
				w = parseInt(localStorage.getItem(WIDTH_KEY), 10);
			} catch (e) { /* private mode: fall through to the default */ }
			return this.clampWidth(Number.isFinite(w) ? w : DEFAULT_WIDTH);
		}

		applyWidth(w) {
			this.window.style.width = this.clampWidth(w) + 'px';
		}

		saveWidth() {
			try {
				localStorage.setItem(WIDTH_KEY, String(this.currentWidth()));
			} catch (e) { /* private mode: width just won't persist */ }
		}

		setupResize() {
			let startX = 0;
			let startW = 0;
			let active = false;

			const onMove = (e) => {
				if (!active) return;
				// The handle is on the panel's left edge: dragging it left (smaller clientX) widens it.
				this.applyWidth(startW + (startX - e.clientX));
			};
			const onUp = () => {
				if (!active) return;
				active = false;
				document.removeEventListener('pointermove', onMove);
				document.removeEventListener('pointerup', onUp);
				document.body.classList.remove('chat-resizing');
				this.saveWidth();
			};

			this.handle.addEventListener('pointerdown', (e) => {
				e.preventDefault();
				active = true;
				startX = e.clientX;
				startW = this.window.getBoundingClientRect().width;
				document.body.classList.add('chat-resizing');
				document.addEventListener('pointermove', onMove);
				document.addEventListener('pointerup', onUp);
			});

			// Keyboard: focus the grip and nudge with the arrow keys.
			this.handle.addEventListener('keydown', (e) => {
				if (e.key === 'ArrowLeft') {
					e.preventDefault();
					this.applyWidth(this.currentWidth() + KEY_STEP);
					this.saveWidth();
				} else if (e.key === 'ArrowRight') {
					e.preventDefault();
					this.applyWidth(this.currentWidth() - KEY_STEP);
					this.saveWidth();
				}
			});
		}

		// ---- open / close -----------------------------------------------------------------------

		open() {
			this.applyWidth(this.storedWidth());
			this.root.classList.add('visible');
			this.window.setAttribute('aria-hidden', 'false');
			this.setTriggerState(true);
			this.rememberOpen(true);
			this.textarea.focus();
			this.log.scrollTop = this.log.scrollHeight;
		}

		close() {
			this.root.classList.remove('visible');
			this.window.setAttribute('aria-hidden', 'true');
			this.setTriggerState(false);
			this.rememberOpen(false);
		}

		rememberOpen(open) {
			try {
				sessionStorage.setItem(OPEN_KEY, open ? '1' : '0');
			} catch (e) { /* private mode: state just won't persist */ }
		}

		/**
		 * Reopens the panel on a new page if it was open when the user navigated. The slide
		 * transition is suppressed for this first paint so it appears already-open rather than
		 * animating in on every navigation; transitions are restored immediately after.
		 */
		restoreOpenState() {
			let wasOpen = false;
			try {
				wasOpen = sessionStorage.getItem(OPEN_KEY) === '1';
			} catch (e) { /* private mode: default to closed */ }
			if (!wasOpen) {
				return;
			}
			this.window.classList.add('no-anim');
			this.open();
			void this.window.offsetWidth; // flush the open state with transitions off
			requestAnimationFrame(() => this.window.classList.remove('no-anim'));
		}

		toggle() {
			this.root.classList.contains('visible') ? this.close() : this.open();
		}

		/** Keeps every launcher (the header "Ask AI" button) in sync with the open state. */
		setTriggerState(open) {
			document.querySelectorAll('[data-type="open-chat"]').forEach((trigger) =>
				trigger.setAttribute('aria-expanded', open ? 'true' : 'false'));
		}

		// ---- messages ---------------------------------------------------------------------------

		/**
		 * Appends a message. Assistant text is rendered as safe Markdown; everything else (the user's
		 * own words, errors, the pending note) stays plain text so it can never inject markup.
		 */
		addMessage(who, text, markdown) {
			const node = document.createElement('div');
			node.className = 'chat-message chat-message-' + who;
			if (markdown) {
				node.classList.add('chat-md');
				node.innerHTML = window.dhChatMarkdown(text);
			} else {
				node.textContent = text;
			}
			this.log.appendChild(node);
			this.log.scrollTop = this.log.scrollHeight;
			return node;
		}

		/**
		 * Offers to open a lookup in the console. The filter is handed over verbatim — the target
		 * page maps it to its own controls, so nothing here needs to know about form fields.
		 */
		addViews(views) {
			if (!views || !views.length) {
				return;
			}
			const row = document.createElement('div');
			row.className = 'chat-views';
			views.filter(view => VIEW_PAGES[view.page]).forEach(view => {
				const button = document.createElement('button');
				button.type = 'button';
				button.className = 'chat-view';
				// Events carries a count ("Open these 5 events"); the other pages use a plain
				// per-page label.
				if (view.page === 'events' && view.count !== null && view.count !== undefined) {
					button.textContent = $L('chat.view.events.count', null, [view.count]);
				} else {
					button.textContent = $L(VIEW_LABELS[view.page] || 'chat.view.events');
				}
				button.addEventListener('click', () => {
					sessionStorage.setItem(PENDING_VIEW_KEY,
						JSON.stringify({page: view.page, filter: view.filter}));
					location.href = VIEW_PAGES[view.page];
				});
				row.appendChild(button);
			});
			if (row.childElementCount) {
				this.log.appendChild(row);
				this.log.scrollTop = this.log.scrollHeight;
			}
		}

		addToolTrace(tools) {
			if (!tools || !tools.length) {
				return;
			}
			const node = document.createElement('div');
			node.className = 'chat-tools';
			node.textContent = $L('chat.tools.used') + ' ' + tools.join(', ');
			this.log.appendChild(node);
			this.log.scrollTop = this.log.scrollHeight;
		}

		setBusy(busy) {
			this.busy = busy;
			this.submit.disabled = busy;
			this.textarea.disabled = busy;
		}

		async send() {
			const message = this.textarea.value.trim();
			if (!message || this.busy) {
				return;
			}
			this.textarea.value = '';
			this.addMessage('user', message);
			this.setBusy(true);

			const pending = this.addMessage('assistant', $L('chat.thinking'));
			pending.classList.add('chat-pending');

			// How long the browser waited is the one fact the server cannot record for a request it
			// never got to answer, and it identifies the failure: a drop at the shutdown grace
			// period means the server restarted under the turn, not that anything timed out.
			const startedAt = Date.now();
			try {
				const response = await fetch(ENDPOINT, {
					method: 'POST',
					headers: {
						'Accept': 'application/json',
						'Content-Type': 'application/json',
						[document.querySelector('meta[name="_csrf_header"]').content]:
							document.querySelector('meta[name="_csrf"]').content
					},
					signal: AbortSignal.timeout(this.turnTimeoutMs),
					// The zone travels with the question so "this weekend" resolves where the user
					// is, not where the server runs.
					body: JSON.stringify({
						message: message,
						timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
						effort: this.effort.value
					})
				});
				// Read as text first so a non-JSON response (e.g. an auth redirect returning the
				// login HTML) is surfaced with its status instead of throwing an opaque parse error.
				const raw = await response.text();
				pending.remove();

				let body = null;
				if (raw) {
					try {
						body = JSON.parse(raw);
					} catch (parseErr) {
						// Non-JSON (e.g. an auth redirect returning login HTML). body stays null and
						// the status check below turns it into a specific message.
						console.error('[chat] non-JSON response', response.status, raw.slice(0, 500));
					}
				}

				if (!response.ok || !body || body.error) {
					console.error('[chat] request failed after ' + (Date.now() - startedAt) + 'ms',
						response.status, body);
					this.addMessage('error', this.errorForResponse(response.status, body));
					return;
				}
				this.addToolTrace(body.toolsUsed);
				this.addMessage('assistant', body.reply || '', true);
				this.addViews(body.views);
				if (body.truncated) {
					this.addMessage('error', $L('chat.error.truncated'));
				}
				// Keep the per-tab cache in step so the next page repaints this turn instantly. The
				// tool trace and any error line are intentionally left out — they are ephemeral and
				// the server transcript does not carry them either.
				// Shape must match the server's history JSON exactly (role, text, views — always
				// present) so loadHistory()'s equality check stays true and never triggers a redraw.
				this.history.push({role: 'user', text: message, views: []});
				this.history.push({role: 'assistant', text: body.reply || '', views: body.views || []});
				this.persistHistory();
			} catch (e) {
				pending.remove();
				// Surface the real error to the browser console — the server log stays silent for a
				// client-side failure (a timeout, or a dropped connection while the server restarts),
				// which otherwise looks like nothing went wrong at all.
				console.error('[chat] request threw after ' + (Date.now() - startedAt) + 'ms', e);
				this.addMessage('error', this.errorForThrown(e));
			} finally {
				this.setBusy(false);
				this.textarea.focus();
			}
		}

		/** A specific, actionable message for an HTTP error response. */
		errorForResponse(status, body) {
			if (body && body.error) {
				return body.error; // the server already gave a specific, localised message
			}
			if (status === 401) {
				return $L('chat.error.session.expired');
			}
			if (status === 403) {
				return $L('chat.error.forbidden');
			}
			if (status === 502 || status === 503 || status === 504) {
				return $L('chat.error.api.unreachable');
			}
			return $L('chat.error.generic') + ' (HTTP ' + status + ')';
		}

		/** A specific message for a fetch that never got a response (timeout, dropped connection). */
		errorForThrown(e) {
			if (e && e.name === 'TimeoutError') {
				return $L('chat.error.timeout');
			}
			// "Failed to fetch" / NetworkError — the connection dropped before a reply arrived,
			// usually because the server restarted or briefly went unreachable.
			if (e && e.name === 'TypeError') {
				return $L('chat.error.network');
			}
			return $L('chat.error.generic');
		}

		/**
		 * Restores the transcript from the server session so it survives navigation between pages.
		 * The conversation itself has always lived in the HTTP session; only the rendered panel is
		 * rebuilt per page load, so without this the history looks lost even though it isn't.
		 */
		async loadHistory() {
			try {
				const response = await fetch(ENDPOINT, {headers: {'Accept': 'application/json'}});
				if (!response.ok) {
					return; // keep whatever is shown; a failure here shouldn't disturb the panel
				}
				const body = await response.json();
				this.applyDefaultEffort(body && body.defaultEffort);
				this.applyTurnTimeout(body && body.turnTimeoutMs);
				const messages = (body && Array.isArray(body.messages)) ? body.messages : [];
				// Only touch the DOM if the server disagrees with what we already painted from cache;
				// when they match (the common case on navigation) the reconcile is invisible.
				if (JSON.stringify(messages) === JSON.stringify(this.history)) {
					return;
				}
				if (messages.length) {
					this.renderHistory(messages);
				} else {
					this.showGreeting();
				}
			} catch (e) {
				console.error('[chat] failed to load history', e);
			}
		}

		/**
		 * Adopts the deployment's turn budget. Ignores a missing or nonsensical value rather than
		 * leaving the panel with no timeout at all, which would hang a tab on a wedged request.
		 */
		applyTurnTimeout(ms) {
			if (typeof ms === 'number' && ms > 0) {
				this.turnTimeoutMs = ms;
			}
		}

		/**
		 * Adopts the deployment's configured default, but never overrides a choice the user has
		 * already made — that choice is theirs until they change it.
		 */
		applyDefaultEffort(level) {
			if (!EFFORT_LEVELS.includes(level)) {
				return;
			}
			this.defaultEffort = level;
			let chosen = null;
			try {
				chosen = localStorage.getItem(EFFORT_KEY);
			} catch (e) { /* private mode: treat as no choice made */ }
			if (!EFFORT_LEVELS.includes(chosen)) {
				this.effort.value = level;
			}
		}

		async reset() {
			await fetch(ENDPOINT + '/reset', {
				method: 'POST',
				headers: {
					[document.querySelector('meta[name="_csrf_header"]').content]:
						document.querySelector('meta[name="_csrf"]').content
				}
			});
			this.showGreeting();
		}
	}

	document.addEventListener('DOMContentLoaded', async () => {
		const root = document.querySelector('.chat-panel');
		if (!root) {
			return;
		}
		// $L is seeded asynchronously from /api/i18n; building the panel first would bake in the
		// raw message keys as labels.
		if (window.i18nReady) {
			await window.i18nReady;
		}
		const panel = new ChatPanel(root);
		panel.loadHistory();
		panel.restoreOpenState();
		document.querySelectorAll('[data-type="open-chat"]').forEach((trigger) =>
			trigger.addEventListener('click', () => panel.toggle()));
	});
})();
