/**
 * ResourceList — decorates the left-panel resource pickers (resources, datasets, timeseries) so
 * each row mirrors its graph node: a colour dot in the resource's label colour, the name, and its
 * labels as tinted chips. Colours come from the shared label-definition cache
 * (/api/label/list -> intellistream_datahub.labels), the same source GraphNetwork.getColor uses,
 * so the lists and the graph stay visually consistent.
 *
 * Row markup (server-rendered, or built with ResourceList.decorate):
 *   <li class="dl-row" ...>
 *     <span class="dl-dot"></span>
 *     <span class="dl-main">
 *       <span class="dl-name">Name</span>
 *       <span class="dl-chips"><span class="dl-chip" data-label="DATASET">DATASET</span>...</span>
 *     </span>
 *   </li>
 *
 * Usage:
 *   ResourceList.colorize();                         // colour every server-rendered .dl-row
 *   ResourceList.decorate(li, name, ['DATASET']);    // build a row's inner structure, then colour it
 */
const ResourceList = {

	FALLBACK_COLOR: "#8a949a",

	labelColor(name){
		const defs = (typeof intellistream_datahub !== 'undefined') && intellistream_datahub.labels;
		const match = defs && defs.find(it => it.name === name);
		return match ? match.color : null;
	},

	// Resource colour = its first label that has a defined colour (matches GraphNetwork.getColor).
	resourceColor(labels){
		for(const l of labels){ const c = this.labelColor(l); if(c) return c; }
		return this.FALLBACK_COLOR;
	},

	ensureLabels(){
		if(typeof intellistream_datahub !== 'undefined' && intellistream_datahub.labels !== undefined){
			return Promise.resolve();
		}
		return fetch('/api/label/list', { headers: { Accept: 'application/json' } })
			.then(r => r.json())
			.then(j => { intellistream_datahub.labels = j.items; })
			.catch(() => { /* leave dots/chips on their neutral fallback */ });
	},

	colorizeItem(li){
		const chips = Array.prototype.slice.call(li.querySelectorAll('.dl-chip'));
		chips.forEach(chip => {
			const c = this.labelColor(chip.getAttribute('data-label'));
			if(c) chip.style.setProperty('--dl-chip-color', c);
		});
	},

	// Colour every .dl-row under root (default: document) once the label definitions have loaded.
	colorize(root){
		return this.ensureLabels().then(() => {
			(root || document).querySelectorAll('li.dl-row').forEach(li => this.colorizeItem(li));
		});
	},

	// Build the name + chips structure inside an existing (empty) <li>, then colour it. The
	// caller keeps ownership of the li's attributes and click handler. Returns the same li.
	decorate(li, name, labels){
		li.classList.add('dl-row');
		const main = Object.assign(document.createElement('span'), { className: 'dl-main' });
		const nameEl = Object.assign(document.createElement('span'), { className: 'dl-name', textContent: name });
		const chips = Object.assign(document.createElement('span'), { className: 'dl-chips' });
		(labels || []).forEach(l => {
			const chip = Object.assign(document.createElement('span'), { className: 'dl-chip', textContent: l });
			chip.setAttribute('data-label', l);
			chips.append(chip);
		});
		main.append(nameEl, chips);
		li.append(main);
		this.colorizeItem(li);
		return li;
	}
};

window.ResourceList = ResourceList;

/**
 * ResourcePicker — the shared "left-panel picker" controller behind the resources, datasets and
 * timeseries lists, which were near-duplicate implementations. It colourises the server-rendered
 * rows, wires row clicks, and runs the debounced search (rendering results as the SAME dl-row
 * list, so there is one row renderer everywhere). Each page passes only its differences.
 *
 * ResourcePicker.init({
 *   list:         '[data-type="root-asset-list"]',   // the <ul> (required)
 *   section:      '#section-root-asset-list',         // wrapper hidden while searching (optional)
 *   searchInput:  '#search-asset',                    // search <input> (optional)
 *   idAttr:       'data-root-id',                     // attribute holding a server row's id
 *   attrs:        item => ({ 'data-root-id': item.id }),   // attributes to set on a built row
 *   labels:       item => item.labels || [],          // chip labels for a row
 *   id / name:    item => ...,                         // extract id / display name (default .id/.name)
 *   select:       'single' | 'none',                   // row highlight mode (default 'single')
 *   event:        'click' | 'pointerdown',             // row activation event (default 'click')
 *   search:       { url, method, body: q => (...), items: json => [...] },
 *   onSelect:     (id, ctx) => {...}                   // ctx = { el, item, fromSearch }
 * })  ->  { buildRow(item), list }
 */
const ResourcePicker = {

	init(cfg){
		const list = document.querySelector(cfg.list);
		if(!list) return null;
		const section = cfg.section ? document.querySelector(cfg.section) : null;
		const searchInput = cfg.searchInput ? document.querySelector(cfg.searchInput) : null;

		const idOf = cfg.id || (it => it.id);
		const nameOf = cfg.name || (it => it.name);
		const labelsOf = cfg.labels || (it => it.labels || []);
		const attrsOf = cfg.attrs || (it => ({ 'data-id': idOf(it) }));
		const evt = cfg.event || 'click';
		const single = (cfg.select || 'single') === 'single';

		const markSelected = (li, scope) => {
			if(!single) return; // caller (onSelect) owns highlighting in multi-select mode
			scope.querySelectorAll('li.selected').forEach(el => el.classList.remove('selected'));
			li.classList.add('selected');
		};

		// Wire a row: highlight (single mode) + fire onSelect. Server rows pass item=null (id read
		// from the row attribute); built rows (create/search) pass the source item.
		const wire = (li, item, fromSearch) => {
			li.addEventListener(evt, () => {
				markSelected(li, li.parentElement || list);
				const id = item ? idOf(item) : li.getAttribute(cfg.idAttr || 'data-id');
				cfg.onSelect(id, { el: li, item: item || null, fromSearch: fromSearch });
			});
		};

		// Colour + wire the server-rendered rows.
		ResourceList.colorize(list);
		list.querySelectorAll('li').forEach(li => wire(li, null, false));

		// Build a dl-row for a data item (create flows + search results), colour + wire it.
		const buildRow = (item, fromSearch) => {
			const li = document.createElement('li');
			const attrs = attrsOf(item);
			Object.keys(attrs).forEach(k => li.setAttribute(k, attrs[k]));
			ResourceList.decorate(li, nameOf(item), labelsOf(item));
			wire(li, item, !!fromSearch);
			return li;
		};

		// --- search -----------------------------------------------------------------------
		if(searchInput && cfg.search){
			const resultsId = cfg.searchResultsId || (cfg.list.replace(/[^A-Za-z0-9]+/g, '-') + '-search-results');
			const clearResults = () => { const r = document.getElementById(resultsId); if(r) r.remove(); };
			const showList = () => { clearResults(); if(section) section.style.display = ''; };

			const render = items => {
				clearResults();
				const ul = Object.assign(document.createElement('ul'), { className: 'data-list slim pointer', id: resultsId });
				// Inherit the list's data-type so pages that track selection by it (e.g. the insights
				// chart reads [data-type="timeseries-list"] li.selected) also see search-result picks.
				const dataType = list.getAttribute('data-type');
				if(dataType) ul.setAttribute('data-type', dataType);
				items.forEach(item => ul.append(buildRow(item, true)));
				(section ? section.parentElement : list.parentElement).append(ul);
				if(section) section.style.display = 'none';
			};

			const extractItems = cfg.search.items || (j => j.items);
			// A non-OK response (e.g. an empty-result 404) renders as an empty list rather than
			// throwing on r.json(); an OK response is parsed, labels are ensured, then rendered.
			const handleResponse = r => (r && r.ok)
				? r.json().then(json => ResourceList.ensureLabels().then(() => render(extractItems(json))))
				: render([]);

			const doSearch = query => {
				const payload = JSON.stringify(cfg.search.body(query));
				if(cfg.search.direct){
					// Go straight to datahub-api with a bearer token from the console /token endpoint —
					// no console proxy. Same pattern as the file upload / datapoint stream.
					const apiBase = document.querySelector('meta[name="datahub-api-url"]').content.replace(/\/+$/, '');
					fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
						.then(r => {
							if(r.status === 401){ if(window.renderSignedOutDialog) window.renderSignedOutDialog(); return null; }
							if(!r.ok) throw new Error('token');
							return r.text();
						})
						.then(token => token === null ? null : fetch(apiBase + cfg.search.path, {
							method: cfg.search.method || 'POST',
							headers: { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
							body: payload
						}))
						.then(r => r === null ? undefined : handleResponse(r))
						.catch(e => console.log(e));
				} else {
					const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
					const csrfToken = document.querySelector('meta[name="_csrf"]').content;
					const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
					headers[csrfHeader] = csrfToken;
					fetch(cfg.search.url, { method: cfg.search.method || 'POST', headers: headers, body: payload })
						.then(handleResponse)
						.catch(e => console.log(e));
				}
			};

			let timer;
			searchInput.addEventListener('input', e => {
				clearTimeout(timer);
				const q = e.target.value;
				if(q && q.trim().length >= 3){
					if(section) section.style.display = 'none';
					timer = setTimeout(() => doSearch(q), 500);
				} else {
					showList();
				}
			});
		}

		return { buildRow: item => buildRow(item, false), list: list };
	}
};

window.ResourcePicker = ResourcePicker;
