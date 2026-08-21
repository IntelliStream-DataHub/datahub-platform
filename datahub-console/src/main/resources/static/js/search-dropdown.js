/**
 * SearchDropdown — a reusable search-as-you-type dropdown for a single text <input>.
 *
 * Generic by design: it knows nothing about events, datahub-api, or auth. You give it an input and
 * a `source(query)` function that returns a Promise of string suggestions; it renders them in a
 * panel under the input, supports mouse and keyboard selection, and writes the chosen value back
 * into the input (dispatching a bubbling `input` event so any existing listeners react).
 *
 * Usage:
 *   new SearchDropdown(inputEl, {
 *       source: query => Promise.resolve(['alarm', 'warning']),  // query = the trimmed input value
 *       minChars: 0,    // 0 means also query when the field is empty (e.g. on focus, list the top N)
 *       debounceMs: 200,
 *       emptyText: 'No results found', // optional; a non-selectable row shown when a query returns
 *                                      // nothing. Omit it to hide the panel on empty results instead.
 *       applySelection: (value, input) => { input.value = value; }  // optional; how a pick lands in
 *                                      // the input. Override for a field holding a list of values.
 *   });
 *
 * The source owns where the data comes from and any result limit. Results render in arrival order
 * guarded by a sequence number, so a slow earlier query can never overwrite a newer one. Reuse it
 * for any field backed by a "list / substring-search distinct values" endpoint.
 */
class SearchDropdown {

	constructor(input, options) {
		options = options || {};
		this.input = input;
		this.source = options.source;
		this.minChars = options.minChars === undefined ? 0 : options.minChars;
		this.debounceMs = options.debounceMs === undefined ? 200 : options.debounceMs;
		this.emptyText = options.emptyText === undefined ? null : options.emptyText;
		// How a picked suggestion lands in the input. The default replaces the whole value, which is
		// what a single-value field wants; a field holding a list overrides this to append instead.
		this.applySelection = options.applySelection || ((value, input) => { input.value = value; });

		this.items = [];
		this.activeIndex = -1;
		this.seq = 0;
		this.debounceTimer = null;

		// The panel is absolutely positioned within the input's parent, so make sure that's positioned.
		const parent = input.parentElement;
		if (getComputedStyle(parent).position === 'static') {
			parent.style.position = 'relative';
		}
		this.panel = document.createElement('ul');
		this.panel.className = 'search-dropdown';
		parent.appendChild(this.panel);

		input.setAttribute('autocomplete', 'off');

		input.addEventListener('focus', () => this.requestQuery());
		input.addEventListener('input', e => {
			if (!e.isTrusted) return; // ignore the value we write programmatically on selection
			this.requestQuery();
		});
		input.addEventListener('keydown', e => this.onKeyDown(e));
		// Close when a click lands outside the input and the panel. mousedown fires before blur, and
		// clicks inside the panel are skipped so an item's own mousedown handler runs first.
		document.addEventListener('mousedown', e => {
			if (e.target !== this.input && !this.panel.contains(e.target)) {
				this.close();
			}
		});
	}

	requestQuery() {
		clearTimeout(this.debounceTimer);
		const term = this.input.value.trim();
		if (term.length < this.minChars) {
			this.close();
			return;
		}
		this.debounceTimer = setTimeout(() => this.runQuery(term), this.debounceMs);
	}

	runQuery(term) {
		const seq = ++this.seq;
		Promise.resolve(this.source(term))
			.then(items => {
				if (seq !== this.seq) return; // a newer query has superseded this one
				this.render(Array.isArray(items) ? items : []);
			})
			.catch(error => {
				if (seq === this.seq) this.close();
				console.log(error);
			});
	}

	render(items) {
		this.items = items;
		this.activeIndex = -1;
		if (items.length === 0) {
			// Nothing matched. Show a non-selectable "no results" row when the caller supplied text,
			// so the field visibly responds; otherwise hide the panel.
			if (!this.emptyText) {
				this.close();
				return;
			}
			const empty = document.createElement('li');
			empty.className = 'empty';
			empty.textContent = this.emptyText;
			this.panel.replaceChildren(empty);
			this.open();
			return;
		}
		const options = items.map(value => {
			const li = document.createElement('li');
			li.textContent = value;
			// mousedown (not click): keeps the input focused and beats the document mousedown-close.
			li.addEventListener('mousedown', e => {
				e.preventDefault();
				this.select(value);
			});
			return li;
		});
		this.panel.replaceChildren(...options);
		this.open();
	}

	open() {
		const input = this.input;
		this.panel.style.top = (input.offsetTop + input.offsetHeight) + 'px';
		this.panel.style.left = input.offsetLeft + 'px';
		this.panel.style.width = input.offsetWidth + 'px';
		this.panel.classList.add('visible');
	}

	close() {
		this.panel.classList.remove('visible');
		this.activeIndex = -1;
	}

	isOpen() {
		return this.panel.classList.contains('visible');
	}

	onKeyDown(e) {
		if (!this.isOpen()) return;
		switch (e.key) {
			case 'ArrowDown':
				e.preventDefault();
				this.moveActive(1);
				break;
			case 'ArrowUp':
				e.preventDefault();
				this.moveActive(-1);
				break;
			case 'Enter':
				if (this.activeIndex >= 0) {
					e.preventDefault();
					this.select(this.items[this.activeIndex]);
				}
				break;
			case 'Escape':
				this.close();
				break;
		}
	}

	moveActive(delta) {
		const count = this.items.length;
		if (count === 0) return;
		this.activeIndex = (this.activeIndex + delta + count) % count;
		const children = this.panel.children;
		for (let i = 0; i < children.length; i++) {
			children[i].classList.toggle('active', i === this.activeIndex);
		}
		children[this.activeIndex].scrollIntoView({ block: 'nearest' });
	}

	select(value) {
		this.applySelection(value, this.input);
		this.close();
		// Let any existing listeners (e.g. the events filter search) react to the new value.
		this.input.dispatchEvent(new Event('input', { bubbles: true }));
	}
}
