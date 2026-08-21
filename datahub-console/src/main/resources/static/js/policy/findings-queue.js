/*
 * Policy findings queue.
 *
 * A finding is one entity's outstanding verdict under one policy: an external id that did not
 * follow the naming policy, was allowed through anyway (mode = warn), and was recorded for a
 * steward to judge. Resolving one is a judgement rather than a fix — the entity still violates the
 * policy, someone has decided that is acceptable.
 *
 * WHY A RESULT ROW IS A GROUP, NOT A FINDING. A bulk import of 10,000 non-conforming resources
 * writes 10,000 findings, and they are all the same mistake made 10,000 times. Ten thousand rows is
 * not a queue anyone works through, so the results table lists groups of (policy, shape of the
 * offending id) — see NamingPolicy.shapeOf — with a count each. A steward makes one judgement about
 * "uppercase tags with hyphens" instead of ten thousand identical ones.
 *
 * The individual entities appear in the details column only for the selected group, so the DOM
 * never holds rows nobody asked to see. That is the same three-column shape as the events page:
 * filters, results, details.
 *
 * Data comes straight from datahub-api (bearer token from /token) via window.NamingPolicy.
 */
(function () {
	"use strict";

	const PAGE_SIZE = 200;

	// Where the previous page stopped, or null to start from the oldest. A cursor rather
	// than a row count because findings are events and the event store is partitioned by event
	// time: resuming from a timestamp skips whole partitions, where an offset has to count past
	// every finding ahead of it and gets slower the longer the queue has existed.
	let cursor = null;
	let exhausted = false;
	let loading = false;
	// Findings folded from the events seen so far: externalId -> current state. A finding is a
	// stream of events, so what the queue shows is a replay, not a row.
	const states = new Map();
	const groups = new Map();   // group key -> {policyName, shape, items: []}

	/**
	 * The results row for a group key, found by scanning rather than by an attribute selector.
	 *
	 * A group key embeds a NUL separator (see groupKeyOf) and CSS.escape turns NUL into U+FFFD — a
	 * lossy substitution, so `tr[data-key="<escaped>"]` could never match the attribute it was built
	 * from. Comparing the attribute directly has no escaping step to get wrong.
	 */
	function rowFor(key) {
		return Array.prototype.find.call(tableBodyEl.rows,
			function (tr) { return tr.getAttribute("data-key") === key; }) || null;
	}

	let tableBodyEl, emptyEl, moreBtn, detailsEl, policyFilter, datasetFilter, statusFilter;
	let selectedKey = null;

	document.addEventListener("DOMContentLoaded", function () {
		const table = document.getElementById("findings-list");
		tableBodyEl = table && table.tBodies[0];
		detailsEl = document.getElementById("findings-details");
		emptyEl = document.getElementById("findings-empty");
		moreBtn = document.getElementById("findings-load-more");
		policyFilter = document.getElementById("findings-filter-policy");
		datasetFilter = document.getElementById("findings-filter-dataset");
		statusFilter = document.getElementById("findings-filter-status");
		if (!tableBodyEl) return;

		[policyFilter, datasetFilter, statusFilter].forEach(function (el) {
			if (el) el.addEventListener("change", reload);
		});
		if (moreBtn) moreBtn.addEventListener("click", loadPage);

		showDetailsPlaceholder();
		reload();
	});

	function filters() {
		return {
			policy: policyFilter && policyFilter.value ? policyFilter.value : null,
			dataSetId: datasetFilter && datasetFilter.value ? datasetFilter.value : null,
			limit: PAGE_SIZE
		};
	}

	/** The status filter's value: "open" (the default view), "resolved" or "all". */
	function statusValue() {
		return statusFilter && statusFilter.value ? statusFilter.value : "open";
	}

	/**
	 * Whether a folded finding passes the status filter.
	 *
	 * "open" is the default because a queue of things already dealt with is not a queue. "resolved"
	 * is the other half of the same question — what has been waved through — which is an audit view
	 * rather than a work list, and the reason it is worth having separately from "all".
	 *
	 * Applied after folding, never sent to the server, and that is forced by the encoding: a stored
	 * OPEN event says "this was raised", not "this is outstanding". Filtering server-side on
	 * status = OPEN would return findings that have since been resolved, and status = RESOLVED would
	 * return ones that have since been raised again. Only the replay knows where a finding stands.
	 */
	function matchesStatus(finding) {
		const value = statusValue();
		if (value === "all") return true;
		return value === "resolved" ? !!finding.resolved : !finding.resolved;
	}

	function reload() {
		cursor = null;
		exhausted = false;
		states.clear();
		groups.clear();
		selectedKey = null;
		tableBodyEl.innerHTML = "";
		showDetailsPlaceholder();
		loadPage();
	}

	function loadPage() {
		if (loading || exhausted) return;
		loading = true;
		if (moreBtn) moreBtn.disabled = true;

		const params = filters();
		params.cursor = cursor;

		window.NamingPolicy.listFindings(params)
			.then(function (page) {
				cursor = page.next;
				if (!cursor) exhausted = true;
				// Fold this page's events into the running state. Pages arrive in eventTime order, so
				// a RESOLVED on page 3 correctly closes a finding whose OPEN arrived on page 1.
				window.NamingPolicy.foldInto(states, page.events);
				regroup();
				render();
			})
			.catch(function () {
				exhausted = true;
				Flash.error($L("findings.load.failed"));
				render();
			})
			.finally(function () {
				loading = false;
				if (moreBtn) {
					moreBtn.disabled = false;
					moreBtn.hidden = exhausted;
				}
			});
	}

	/**
	 * The group a finding belongs to: policy, shape of the offending id, data set, status.
	 *
	 * The data set is in the key so the results table can name one. A group is a single judgement a
	 * steward makes, and the data set is the unit access and ownership are granted on, so the same
	 * naming mistake in two data sets is two people's problem and therefore two judgements. It barely
	 * fragments the case grouping exists for: a bulk import lands in one data set, so its thousands of
	 * identical findings still collapse to one row.
	 *
	 * Status is in the key for a narrower reason: a row is coloured by it in the combined view, and a
	 * row mixing open and resolved findings would have no colour it could honestly wear. It costs
	 * nothing in the single-status views, where everything on screen already shares one.
	 */
	function groupKeyOf(finding) {
		const shape = window.NamingPolicy.shapeOf(finding.offendingValue);
		// NUL as the separator on purpose: it cannot occur in a policy name, a shape or an id, so no
		// two distinct tuples can collide on one key. Written as an escape rather than a literal byte,
		// which made git treat this file as binary and hid its diffs.
		return (finding.policyName || "") + "\u0000" + shape
			+ "\u0000" + (finding.dataSetId == null ? "" : finding.dataSetId)
			+ "\u0000" + (finding.resolved ? "resolved" : "open");
	}

	/**
	 * The external id of a data set, read off the filter dropdown the server already rendered.
	 *
	 * A finding event carries only the numeric data set id, and the page is holding that list in
	 * the DOM anyway, so the alternative would be a second round trip for names it already has.
	 * Returns null for a data set the dropdown does not list (the server caps it at 100), and the
	 * caller falls back to showing the id.
	 */
	function datasetExternalIdOf(dataSetId) {
		if (!datasetFilter || dataSetId == null) return null;
		const option = Array.prototype.find.call(datasetFilter.options,
			function (o) { return o.value === String(dataSetId); });
		return (option && option.getAttribute("data-external-id")) || null;
	}

	/**
	 * Rebuild the groups from the folded state.
	 *
	 * Rebuilt wholesale rather than appended to, because a later page can change a finding that an
	 * earlier page already placed: a RESOLVED event moves it out of the open view, and a re-raise for
	 * a new offending value moves it to a different shape group. Incremental insertion cannot express
	 * either without tracking where every finding was last put, and the state is a few hundred entries.
	 */
	function regroup() {
		groups.clear();
		states.forEach(function (finding) {
			if (!matchesStatus(finding)) return;
			const key = groupKeyOf(finding);
			let group = groups.get(key);
			if (!group) {
				group = {
					policyName: finding.policyName || "",
					shape: window.NamingPolicy.shapeOf(finding.offendingValue),
					// Both single-valued because they are part of the key; see groupKeyOf.
					dataSetId: finding.dataSetId,
					resolved: !!finding.resolved,
					// The message is generated by the policy and is identical across a group, so it can
					// stand as the group's description without repeating it on every row.
					message: finding.message || "",
					items: []
				};
				groups.set(key, group);
			}
			group.items.push(finding);
		});
	}

	function render() {
		tableBodyEl.innerHTML = "";
		const ordered = Array.from(groups.entries()).sort(function (a, b) {
			return b[1].items.length - a[1].items.length;   // the biggest mistake first: it is the one worth fixing
		});
		if (emptyEl) {
			emptyEl.hidden = ordered.length > 0;
			// The default empty text says every external id follows the policy, which is true of an
			// empty "open" or "all" view and false of an empty "resolved" one — nothing having been
			// waved through says nothing about whether anything is outstanding.
			if (!emptyEl.hidden) {
				emptyEl.textContent = $L(statusValue() === "resolved"
					? "findings.empty.resolved" : "findings.empty");
			}
		}
		ordered.forEach(function (entry) { tableBodyEl.appendChild(renderGroupRow(entry[0], entry[1])); });

		// A reload can drop the group that was open; say so rather than leaving stale rows beside
		// filters that no longer produced them.
		if (selectedKey !== null && !groups.has(selectedKey)) {
			selectedKey = null;
			showDetailsPlaceholder();
		}
	}

	/**
	 * The data set cell: its external id, linking to that data set's graph.
	 *
	 * The external id rather than the name, because it is what the finding is about — the policy
	 * judges external ids, and a steward comparing an offending id against the data set it landed in
	 * wants the two in the same vocabulary.
	 *
	 * Returns null when the finding has no data set. That is a real state, not missing information:
	 * an event may belong to none, so an empty cell is the honest rendering.
	 */
	function renderDatasetLink(dataSetId) {
		if (dataSetId == null) return null;
		const link = document.createElement("a");
		// Linked by numeric id even though the external id is what is shown: /datasets/index selects
		// on the node id, and this is the same ?selected= deep link the time series table uses.
		link.href = "/datasets/index?selected=" + encodeURIComponent(dataSetId);
		link.textContent = datasetExternalIdOf(dataSetId) || String(dataSetId);
		link.title = link.textContent;
		// The row itself opens the group in the details column. Following the link is a different
		// intent, so it does not also change what the rest of the page is showing.
		link.addEventListener("click", function (e) { e.stopPropagation(); });
		return link;
	}

	function renderGroupRow(key, group) {
		const row = document.createElement("tr");
		row.setAttribute("data-key", key);
		row.classList.add(group.resolved ? "status-resolved" : "status-open");
		if (key === selectedKey) row.classList.add("selected");

		const count = row.insertCell();
		count.className = "findings-count-cell";
		count.textContent = String(group.items.length);

		row.insertCell().textContent = group.policyName;

		const dataset = row.insertCell();
		dataset.className = "findings-dataset-cell";
		const datasetCell = renderDatasetLink(group.dataSetId);
		if (datasetCell) dataset.appendChild(datasetCell);

		const shape = row.insertCell();
		const shapeCode = document.createElement("code");
		shapeCode.className = "findings-shape";
		shapeCode.textContent = group.shape;
		shapeCode.title = $L("findings.legend.pattern");
		shape.appendChild(shapeCode);

		const message = row.insertCell();
		message.className = "findings-group-message";
		message.textContent = group.message;

		row.addEventListener("click", function () { selectGroup(key); });
		return row;
	}

	function selectGroup(key) {
		selectedKey = key;
		tableBodyEl.querySelectorAll("tr").forEach(function (tr) {
			tr.classList.toggle("selected", tr.getAttribute("data-key") === key);
		});
		const group = groups.get(key);
		if (group) renderDetails(group);
	}

	function showDetailsPlaceholder() {
		if (!detailsEl) return;
		detailsEl.innerHTML = "";
		const hint = document.createElement("p");
		hint.className = "findings-empty";
		hint.textContent = $L("findings.details.empty");
		detailsEl.appendChild(hint);
	}

	function renderDetails(group) {
		if (!detailsEl) return;
		detailsEl.innerHTML = "";

		const caption = document.createElement("label");
		caption.textContent = $L("findings.details.group");
		const heading = document.createElement("div");
		heading.className = "data-item";
		heading.textContent = group.policyName + "  " + group.shape;
		detailsEl.append(caption, heading);

		detailsEl.appendChild(renderRows(group));
	}

	/**
	 * The individual findings of one group, one stacked row each.
	 *
	 * Deliberately not a table. The five things a row carries — offending id, suggestion, subject,
	 * time, action — do not fit side by side in a 460px details column: an external id has no spaces
	 * to wrap at and the subject is a 36-character uuid, so a table's columns can only push the pane
	 * wider than it is. Stacking them puts the id on its own line where it can break, and demotes the
	 * provenance to a muted line under it, which is also the order they matter in.
	 */
	function renderRows(group) {
		const list = document.createElement("div");
		list.className = "findings-rows";

		group.items.forEach(function (finding) {
			const row = document.createElement("div");
			row.className = "finding-row";

			// The offending value IS the entity's external id, so there is nothing else to show
			// here — and showing the id as it stands now would hide the thing complained about
			// whenever a steward has since renamed the entity.
			const offending = document.createElement("div");
			offending.className = "finding-id";
			offending.title = $L("findings.column.externalid");
			offending.textContent = finding.offendingValue || "";
			row.appendChild(offending);

			if (finding.suggestion) {
				const suggestion = document.createElement("div");
				suggestion.className = "finding-suggestion";
				suggestion.title = $L("findings.column.suggestion");
				// An arrow rather than a caption: it reads as "use this instead" in the width
				// available, and the caption is on the hover for anyone who wants it spelled out.
				suggestion.textContent = "→ " + finding.suggestion;
				row.appendChild(suggestion);
			}

			const meta = document.createElement("div");
			meta.className = "finding-meta";
			if (finding.dateCreated) {
				const when = document.createElement("span");
				when.className = "finding-when";
				when.appendChild(document.createTextNode($L("findings.column.raised") + " "));
				// applyLocalTime claims the element's title for the UTC offset, so the caption is a
				// sibling text node rather than a title of its own.
				const time = document.createElement("span");
				window.applyLocalTime(time, finding.dateCreated);
				when.appendChild(time);
				meta.appendChild(when);
			}
			if (finding.raisedBy) {
				// A Keycloak subject: 36 characters of uuid that would blow the column open on its
				// own. Truncated, with the whole value on the hover — a steward chasing the
				// integration that wrote the value needs it complete, but not on screen at all times.
				const who = document.createElement("span");
				who.className = "finding-raisedby";
				who.title = $L("findings.column.raisedby") + ": " + finding.raisedBy;
				who.textContent = finding.raisedBy;
				meta.appendChild(who);
			}
			row.appendChild(meta);
			list.appendChild(row);

			if (finding.resolved) {
				const done = document.createElement("span");
				done.className = "findings-resolved finding-action";
				done.textContent = $L("findings.resolved");
				row.appendChild(done);
				return;
			}
			const button = document.createElement("button");
			button.type = "button";
			button.className = "dh-btn secondary small finding-action";
			button.title = $L("findings.resolve.title");
			button.textContent = $L("findings.resolve");
			row.appendChild(button);
			button.addEventListener("click", function () {
				button.disabled = true;
				window.NamingPolicy.resolveFinding(finding)
					.then(function () {
						// Resolving appended a RESOLVED event. Reflect that in the folded state the
						// same way a replay would, rather than deleting the row: the finding still
						// exists, it is now closed. Re-fetching to see our own write would race the
						// store's ingest anyway.
						finding.resolved = true;
						finding.resolvedAt = new Date().toISOString();
						row.remove();
						// The group's count is now stale; drop the finding and re-render the header.
						const index = group.items.indexOf(finding);
						if (index >= 0) group.items.splice(index, 1);
						// The count lives in the results row, not in this list.
						const resultRow = rowFor(selectedKey);
						const countEl = resultRow && resultRow.querySelector(".findings-count-cell");
						if (countEl) countEl.textContent = String(group.items.length);
					})
					.catch(function () {
						button.disabled = false;
						Flash.error($L("findings.resolve.failed"));
					});
			});
		});

		return list;
	}
})();
