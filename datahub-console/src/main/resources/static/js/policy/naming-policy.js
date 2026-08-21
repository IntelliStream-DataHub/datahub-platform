/*
 * Naming policy — the browser side of the external-id naming rules.
 *
 * The platform stores external ids verbatim within a charset floor (A-Z a-z 0-9 . _ : + = -,
 * 3 to 256 characters) and layers a configurable *naming policy* on top of that. A write can
 * therefore come back three ways, and this module is the one place that knows all three:
 *
 *   - clean            : nothing to show;
 *   - allowed + warned : the response envelope carries `warnings[]` beside its items, the write
 *                        went through, and a finding was recorded for a steward;
 *   - rejected         : HTTP 400 with an RFC 9457 body whose `type` is NAMING_POLICY_ERROR and
 *                        whose `violations[]` name every offending item. Batch writes are
 *                        all-or-nothing, so the flash has to lead with "nothing was created" —
 *                        that is the first thing the person looking at it needs to know.
 *
 * It also wraps the preflight endpoint (POST /policies/naming/check), which is what lets the
 * external-id field validate as you type rather than on submit, and the findings queue — which is
 * not a findings endpoint at all: a finding is an event, so the queue reads and resolves through
 * /events like everything else in the store. See the Findings section below.
 *
 * Calls go straight to datahub-api with a bearer token from the console's /token endpoint — the
 * Feign proxy in this app is deprecated for new work (see DatahubApi's javadoc), so nothing here
 * routes through it.
 */
window.NamingPolicy = (function () {

	/** `type` on the RFC 9457 body that marks a 400 as a naming-policy rejection. */
	const NAMING_POLICY_ERROR = 'https://intellistream.ai/errors/naming-policy';

	/*
	 * The platform charset floor, mirroring TextValidator.validateExternalIdCharset. Used for the
	 * `pattern` attribute on external-id inputs so the browser blocks only what the platform would
	 * genuinely refuse. It deliberately does NOT encode a naming *convention*: which convention is
	 * in force is a per-tenant policy the browser cannot know, so that half is answered by the
	 * preflight endpoint instead of by a hardcoded regex.
	 */
	// The trailing dash is escaped deliberately. This string is used as an HTML `pattern`
	// attribute, which browsers compile with the RegExp `v` flag, and `v` mode rejects an
	// unescaped `-` at the end of a character class ("Invalid character in character class").
	// Chrome then silently DROPS the constraint, so reportValidity() started returning true
	// for any external id at all. `\-` is valid in both `u` and `v` mode and matches exactly
	// the same characters, so the floor is unchanged.
	const CHARSET_PATTERN = '[A-Za-z0-9._:+=\\-]{3,256}';

	// ---- API access ---------------------------------------------------------------------------
	// Same shape as the other browser-direct callers (home-dashboard.js, files-page.js): a short
	// token cache so a burst of keystroke-driven preflights doesn't hit /token once per call.
	let cachedToken = null;
	let cachedAt = 0;

	function apiBase() {
		const meta = document.querySelector('meta[name="datahub-api-url"]');
		return (meta ? meta.content : '').replace(/\/+$/, '');
	}

	function token() {
		if (cachedToken && (Date.now() - cachedAt) < 60000) return Promise.resolve(cachedToken);
		return fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(r => r.ok ? r.text() : Promise.reject(r.status))
			.then(t => { cachedToken = t; cachedAt = Date.now(); return t; });
	}

	function apiFetch(path, opts) {
		const o = opts || {};
		return token().then(t => fetch(apiBase() + path, {
			method: o.method || 'GET',
			headers: Object.assign({
				Accept: 'application/json',
				Authorization: 'Bearer ' + t
			}, o.body ? { 'Content-Type': 'application/json' } : {}),
			body: o.body ? JSON.stringify(o.body) : undefined,
			signal: AbortSignal.timeout(10000)
		}));
	}

	// ---- Response handling --------------------------------------------------------------------

	/** True when `json` is the RFC 9457 body of a naming-policy rejection. */
	function isViolation(json) {
		return !!(json && json.type === NAMING_POLICY_ERROR);
	}

	// "<external id> — <message>", with the suggestion appended when the server offered one. The
	// suggestion is never applied automatically: not rewriting what the caller sent is the entire
	// point of this change.
	function line(externalId, message, suggestion) {
		let text = $L('policy.naming.finding.line', null, [externalId || '', message || '']);
		if (suggestion) text += '  ' + $L('policy.naming.finding.suggestion', null, [suggestion]);
		return text;
	}

	/**
	 * Flashes the warnings on a successful write, if it carries any.
	 * @return true when something was shown.
	 */
	function flashWarnings(json) {
		const warnings = json && json.warnings;
		if (!Array.isArray(warnings) || warnings.length === 0) return false;
		Flash.warning($L('policy.naming.warning.title'), {
			duration: 0,   // a steward has to read these; they should not vanish on a timer
			details: warnings.map(w => line(w.externalId, w.message, w.suggestion))
		});
		return true;
	}

	/**
	 * Flashes a naming-policy rejection, naming every offending id.
	 * @return true when `json` was a naming-policy rejection and was shown.
	 */
	function flashViolations(json) {
		if (!isViolation(json)) return false;
		const violations = Array.isArray(json.violations) ? json.violations : [];
		// The server's `detail` already states that nothing was created, and states it with the
		// real counts ("2 of 500 ..."). Prefer it, and fall back to our own wording only if a
		// server ever omits it — losing the "nothing was created" half would be the worst thing
		// to get wrong here.
		const title = json.detail || $L('policy.naming.violation.title');
		Flash.error(title, {
			duration: 0,
			details: violations.map(v => line(v.externalId, v.reason || v.message, v.suggestion))
		});
		return true;
	}

	// ---- Preflight ----------------------------------------------------------------------------

	/**
	 * Asks the API what the active policy would say about these ids, without writing anything.
	 * Findings come back only for non-OK ids, so an empty array means every id is fine.
	 *
	 * @param externalIds array of candidate ids
	 * @param dataSetId   optional; a data set may override the tenant's naming policy
	 * @return Promise<Array<{index, externalId, decision, policy, message, suggestion}>>
	 */
	function check(externalIds, dataSetId) {
		const body = { externalIds: externalIds };
		// Passed through as given, never Number()-ed. The api hands ids over as strings and reads
		// them back as strings, so the conversion buys nothing and can only lose: above 2^53 it does
		// not fail, it quietly yields a different id.
		if (dataSetId) body.dataSetId = dataSetId;
		return apiFetch('/policies/naming/check', { method: 'POST', body: body })
			.then(r => r.ok ? r.json() : Promise.reject(r.status))
			.then(json => (json && json.findings) || []);
	}

	/**
	 * Wires debounced preflight validation onto an external-id input.
	 *
	 * Validating as you type rather than on submit is the whole reason the preflight endpoint
	 * exists: a naming convention you only discover you broke after filling in a form is a
	 * convention people route around.
	 *
	 * @param input   the <input name="externalId"> element
	 * @param options {dataSetId: number|function} optional data-set context for the policy lookup
	 */
	function bindField(input, options) {
		if (!input || input.dataset.namingPolicyBound === 'true') return;
		input.dataset.namingPolicyBound = 'true';
		const opts = options || {};

		const note = Object.assign(document.createElement('div'), { className: 'naming-check' });
		input.insertAdjacentElement('afterend', note);

		let timer = null;
		let sequence = 0;   // guards against a slow early response landing after a fast later one

		const clear = () => {
			note.textContent = '';
			note.className = 'naming-check';
			input.classList.remove('naming-invalid');
		};

		const show = (state, text, suggestion) => {
			note.className = 'naming-check is-' + state;
			note.textContent = text;
			input.classList.toggle('naming-invalid', state === 'error');
			if (suggestion) {
				const apply = Object.assign(document.createElement('button'), {
					type: 'button',
					className: 'naming-check-apply',
					textContent: $L('policy.naming.check.apply')
				});
				// Offered, never applied on the user's behalf.
				apply.addEventListener('click', () => {
					input.value = suggestion;
					input.dispatchEvent(new Event('input', { bubbles: true }));
				});
				note.append(' ', apply);
			}
		};

		const run = () => {
			const value = (input.value || '').trim();
			if (!value) { clear(); return; }
			if (!new RegExp('^' + CHARSET_PATTERN + '$').test(value)) {
				// The charset floor is knowable client-side, so answer it without a round trip.
				show('error', $L('policy.naming.check.charset'));
				return;
			}
			const mine = ++sequence;
			const dataSetId = typeof opts.dataSetId === 'function' ? opts.dataSetId() : opts.dataSetId;
			check([value], dataSetId)
				.then(findings => {
					if (mine !== sequence) return;
					const finding = findings.find(f => f.externalId === value) || findings[0];
					if (!finding) { show('ok', $L('policy.naming.check.ok')); return; }
					show(finding.decision === 'NOT_OK' ? 'error' : 'warn',
						finding.message || '', finding.suggestion);
				})
				// A preflight is an aid, not a gate: if it cannot be reached the form still submits
				// and the server remains the authority.
				.catch(() => { if (mine === sequence) clear(); });
		};

		input.addEventListener('input', () => {
			clearTimeout(timer);
			timer = setTimeout(run, 400);
		});
		input.addEventListener('blur', () => { clearTimeout(timer); run(); });
	}

	// ---- Findings -----------------------------------------------------------------------------

	/**
	 * The shape of an external id: every run of uppercase letters becomes "A+", lowercase "a+",
	 * digits "9+", and separators are kept as they are. So COM-99-PT-1034 and PMP-07-XX-2201 both
	 * become "A+-9+-A+-9+".
	 *
	 * This is what the findings queue groups on, and it is the reason the queue is usable at all:
	 * a bulk import of 10,000 non-conforming resources produces 10,000 findings that are all the
	 * same mistake, and ten thousand rows is not a queue anyone works through. Grouping on the
	 * shape collapses them to one row a steward can actually judge.
	 */
	function shapeOf(value) {
		const RUNS = 'Aa9';
		let out = '';
		let previous = '';
		for (const ch of String(value == null ? '' : value)) {
			let cls;
			if (ch >= 'A' && ch <= 'Z') cls = 'A';
			else if (ch >= 'a' && ch <= 'z') cls = 'a';
			else if (ch >= '0' && ch <= '9') cls = '9';
			else cls = ch;
			if (cls === previous && RUNS.includes(cls)) continue;   // collapse the run
			out += RUNS.includes(cls) ? cls + '+' : cls;
			previous = cls;
		}
		return out;
	}

	/*
	 * A finding IS an event. There is no findings endpoint — findings are read, filtered and
	 * resolved through /events like the alarms and work orders they sit beside, which is what lets a
	 * steward see a policy complaint in the same timeline as everything else about a resource.
	 *
	 * The encoding is defined server-side in PolicyFindingEvent.java; these constants mirror it and
	 * have to be changed together with it.
	 */
	const FINDING_TYPE = 'policy_finding';
	const STATUS_OPEN = 'OPEN';
	const STATUS_RESOLVED = 'RESOLVED';

	/**
	 * Read one finding event into the flat shape the queue renders.
	 *
	 * Confining the encoding to this one function is deliberate: the rest of the page should not
	 * have to know that the policy hides in `subType` or that the offending value is a metadata key.
	 */
	function toFinding(event) {
		const metadata = (event && event.metadata) || {};
		// A list of {id, externalId} — not the bare id list the two-parallel-lists encoding used
		// before it was collapsed into one. `id` arrives as a string (the api serializes ids that way)
		// and stays one on the way back out: converting it gains nothing, and above 2^53 Number()
		// does not fail, it returns a different id.
		const related = (event && event.relatedResources) || [];
		return {
			id: event.id,
			// The correlation key: every event in this finding's lifecycle carries it, and it is what
			// the fold groups on and what a resolve names.
			externalId: event.externalId,
			nodeId: related.length && related[0].id != null ? related[0].id : null,
			dataSetId: event.dataSetId != null ? event.dataSetId : null,
			policyName: event.subType || '',
			message: event.description || '',
			suggestion: metadata.suggestion || '',
			offendingValue: metadata.offendingValue || '',
			raisedBy: metadata.raisedBy || '',
			eventTime: event.eventTime,
			resolved: event.status === STATUS_RESOLVED
		};
	}

	/**
	 * Fold a finding's events into its current state: last event wins.
	 *
	 * A finding is a stream, not a row. Raising appends OPEN, resolving appends RESOLVED with the
	 * same externalId, and a later raise for a new offending value appends another OPEN after that.
	 * Replaying in eventTime order is therefore the only way to know where a finding stands — and it
	 * is why the server cannot filter on `status` for us: a stored OPEN says "this was raised", not
	 * "this is outstanding".
	 *
	 * Events arrive already sorted ascending, so a later event for a key simply overwrites the
	 * earlier one. `dateCreated` is carried forward from the first event seen, so the queue keeps
	 * showing when the finding was first raised rather than when it was last touched — a steward
	 * sorting by age wants the age of the problem.
	 *
	 * @param states Map<externalId, finding> accumulated across pages, mutated in place
	 */
	function foldInto(states, events) {
		events.forEach(function (event) {
			const finding = toFinding(event);
			const previous = states.get(finding.externalId);
			finding.dateCreated = previous ? previous.dateCreated : finding.eventTime;
			finding.resolvedAt = finding.resolved ? finding.eventTime : null;
			// A RESOLVED event carries no message or offending value of its own — it only asserts a
			// judgement — so keep what the raise it answers said.
			if (previous) {
				finding.message = finding.message || previous.message;
				finding.offendingValue = finding.offendingValue || previous.offendingValue;
				finding.suggestion = finding.suggestion || previous.suggestion;
				finding.raisedBy = finding.raisedBy || previous.raisedBy;
				finding.nodeId = finding.nodeId != null ? finding.nodeId : previous.nodeId;
				finding.dataSetId = finding.dataSetId != null ? finding.dataSetId : previous.dataSetId;
			}
			states.set(finding.externalId, finding);
		});
		return states;
	}

	/**
	 * The page cursor for an event: `<eventTime epoch millis>_<id>`, the format the API's
	 * `cursor` field takes.
	 *
	 * The id is carried as well as the timestamp because event times are not unique — a bulk import
	 * lands thousands of findings in the same millisecond — and a cursor on the timestamp alone
	 * would either skip that group or repeat it forever.
	 */
	function cursorOf(event) {
		return String(Date.parse(event.eventTime)) + '_' + event.id;
	}

	/**
	 * POST /events/filter, narrowed to findings.
	 *
	 * Note what is NOT sent: a `status` filter. Open-ness is folded, not stored, so asking the server
	 * for OPEN events would return the raises of findings that have since been resolved. The caller
	 * folds what comes back and decides.
	 *
	 * @param params {policy, dataSetId, limit, cursor}
	 *   `cursor` is where the previous page stopped. Paging is a keyset rather than an offset
	 *   because the events store is partitioned by event time, so resuming from a timestamp skips
	 *   whole partitions instead of counting past every row ahead of it — page 400 costs what page 1
	 *   costs.
	 * @return Promise<{events, next}> — `next` is the cursor to pass back, or null at the end
	 */
	function listFindings(params) {
		const p = params || {};
		const limit = p.limit || 200;

		const filter = { type: FINDING_TYPE };
		if (p.policy) filter.subType = p.policy;
		if (p.dataSetId) filter.dataSetId = [{ id: p.dataSetId }];

		const body = {
			filter: filter,
			limit: limit,
			// Ascending is what the fold requires — replaying out of order would let a stale OPEN
			// overwrite the RESOLVED that followed it. It also has to match the order the cursor was
			// produced in, or the next page skips rows.
			sort: { property: ['eventTime'], order: 'asc' }
		};
		if (p.cursor) body.cursor = p.cursor;

		// apiFetch serializes and sets the content type; handing it a string would post a JSON
		// string literal, which the endpoint cannot bind to a filter.
		return apiFetch('/events/filter', { method: 'POST', body: body })
			.then(r => r.ok ? r.json() : Promise.reject(r.status))
			.then(json => {
				const events = (json && json.items) || [];
				const last = events.length ? events[events.length - 1] : null;
				return {
					events: events,
					// A short page is the last page. Handing back a cursor anyway would cost one
					// more round trip to discover the same thing.
					next: (last && events.length >= limit) ? cursorOf(last) : null
				};
			});
	}

	/**
	 * Resolve a finding by appending a RESOLVED event that names the same finding.
	 *
	 * Not an edit of the raise. Resolving is a judgement, not a fix — the entity still violates the
	 * policy, someone has decided that is acceptable — and recording it as its own event keeps that
	 * judgement legible next to what it answers, instead of overwriting the complaint with the verdict
	 * on it. If the id later changes to another non-conforming value the policy appends a fresh OPEN
	 * after this, and the finding is outstanding again with no reopen rule anywhere.
	 *
	 * The data set and related resource are copied from the raise deliberately: the resolve has to
	 * come back from the same filtered query the raise does, or a queue narrowed to one data set would
	 * see the complaint and miss the answer to it.
	 *
	 * @param finding a folded finding, as produced by foldInto
	 * @return Promise<'resolved'> — rejects on anything else
	 */
	function resolveFinding(finding) {
		const event = {
			externalId: finding.externalId,
			type: FINDING_TYPE,
			subType: finding.policyName,
			status: STATUS_RESOLVED,
			eventTime: new Date().toISOString(),
			relatedResources: finding.nodeId != null ? [{ id: finding.nodeId }] : []
		};
		if (finding.dataSetId != null) event.dataSetId = finding.dataSetId;

		return apiFetch('/events/create', { method: 'POST', body: { items: [event] } })
			.then(r => r.ok ? 'resolved' : Promise.reject(r.status));
	}

	/**
	 * The open findings for one node.
	 *
	 * These used to ride along free in the entity's `metadata` and deliberately no longer do:
	 * metadata is a user-editable map, so a finding kept there could be forged or silently deleted
	 * by the same caller who triggered it, which makes it a note rather than an audit record. The
	 * cost of that decision is exactly this extra fetch.
	 *
	 * An exact server-side lookup on the entity: a finding names it through `relatedResources`, which
	 * the event filter has always supported. Open-ness, though, is folded here rather than filtered —
	 * a stored OPEN says "this was raised", not "this is outstanding", so the whole stream for this
	 * entity is fetched and replayed.
	 */
	function findingsForNode(nodeId) {
		if (!nodeId) return Promise.resolve([]);
		const body = {
			filter: {
				type: FINDING_TYPE,
				relatedResources: [{ id: nodeId }]
			},
			limit: 100,
			sort: { property: ['eventTime'], order: 'asc' }
		};
		return apiFetch('/events/filter', { method: 'POST', body: body })
			.then(r => r.ok ? r.json() : Promise.reject(r.status))
			.then(json => {
				const states = foldInto(new Map(), (json && json.items) || []);
				return Array.from(states.values()).filter(f => !f.resolved);
			})
			.catch(() => []);
	}

	return {
		NAMING_POLICY_ERROR: NAMING_POLICY_ERROR,
		CHARSET_PATTERN: CHARSET_PATTERN,
		isViolation: isViolation,
		flashWarnings: flashWarnings,
		flashViolations: flashViolations,
		check: check,
		bindField: bindField,
		shapeOf: shapeOf,
		listFindings: listFindings,
		foldInto: foldInto,
		resolveFinding: resolveFinding,
		findingsForNode: findingsForNode
	};
})();
