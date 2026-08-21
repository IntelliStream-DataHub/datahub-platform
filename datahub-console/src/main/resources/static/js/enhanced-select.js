/**
 * EnhancedSelect — a lightweight, dependency-free custom dropdown that ENHANCES a native
 * <select> rather than replacing it. A standalone reusable widget (like SearchDropdown): drop it
 * in the app bundle and mark any select with `data-enhance` to upgrade it.
 *
 * The native element stays in the DOM as the single source of truth — its value, options,
 * disabled state and 'change' event all keep working — so existing code that reads select.value,
 * listens for 'change', or toggles option.disabled needs no changes. This layer only renders a
 * styleable trigger + option list on top and keeps them in sync automatically:
 *
 *   - clicking a custom option writes select.value and dispatches a native 'change'
 *   - programmatic `select.value = x` is intercepted (instance property override) and re-syncs
 *   - option `disabled` changes are picked up via a MutationObserver
 *
 * Each option carries a unit class (from its value suffix, or an explicit data-unit attribute) so
 * the list can colour-code by category — e.g. minutes / hours / days / weeks / months / years.
 *
 * Progressive enhancement: if this script never runs, the plain <select> (themed via
 * color-scheme) still works.
 *
 * Usage:  <select data-enhance> ... </select>              (auto-enhanced on DOMContentLoaded)
 *    or:  new EnhancedSelect(selectEl, { unitOf: v => '...' })
 */
class EnhancedSelect {

	constructor(select, opts){
		opts = opts || {};
		if(!select || select.dataset.enhanced) return;
		this.select = select;
		this.unitOf = opts.unitOf || EnhancedSelect.defaultUnitOf;
		select.dataset.enhanced = 'true';

		this._buildDom();
		this._interceptValueSetter();
		this._observeDisabled();
		this._bindEvents();
		this.refresh();
	}

	// Enhance every <select data-enhance> currently in the DOM. Idempotent (guarded per element).
	static enhanceAll(root){
		(root || document).querySelectorAll('select[data-enhance]').forEach(sel => new EnhancedSelect(sel));
	}

	// Map an option value to a unit key used for colour coding. Suffix-based so it fits the
	// timeseries window presets (2m, 1h, 1d, 1w, 1M, 1y) with no extra config; an explicit
	// data-unit attribute on the <option> overrides it.
	static defaultUnitOf(value){
		if(value === '') return 'custom';
		if(value === 'grow') return 'grow';
		switch(value.slice(-1)){
			case 'm': return 'min';
			case 'h': return 'hour';
			case 'd': return 'day';
			case 'w': return 'week';
			case 'M': return 'month';
			case 'y': return 'year';
			default:  return 'custom';
		}
	}

	_unitFor(opt){
		return opt.dataset && opt.dataset.unit ? opt.dataset.unit : this.unitOf(opt.value);
	}

	_buildDom(){
		const wrap = document.createElement('div');
		wrap.className = 'dh-select';

		const trigger = document.createElement('button');
		trigger.type = 'button';
		trigger.className = 'dh-select-trigger';
		trigger.setAttribute('aria-haspopup', 'listbox');
		trigger.setAttribute('aria-expanded', 'false');
		trigger.innerHTML =
			'<span class="dh-select-dot"></span>' +
			'<span class="dh-select-label"></span>' +
			'<svg class="dh-select-chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">' +
			'<path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M2.5 4.5 6 8l3.5-3.5"/></svg>';

		const menu = document.createElement('div');
		menu.className = 'dh-select-menu';
		menu.setAttribute('role', 'listbox');

		this.select.classList.add('dh-select-native');
		this.select.parentNode.insertBefore(wrap, this.select);
		wrap.appendChild(trigger);
		wrap.appendChild(menu);
		wrap.appendChild(this.select); // keep the (hidden) native select adjacent

		this.wrap = wrap;
		this.trigger = trigger;
		this.menu = menu;
		this.labelEl = trigger.querySelector('.dh-select-label');
		this.dotEl = trigger.querySelector('.dh-select-dot');
	}

	_renderMenu(){
		this.menu.innerHTML = '';
		this.optionEls = [];
		Array.prototype.forEach.call(this.select.options, opt => {
			const el = document.createElement('button');
			el.type = 'button';
			el.className = 'dh-select-option unit-' + this._unitFor(opt);
			el.setAttribute('role', 'option');
			el.dataset.value = opt.value;
			el.disabled = opt.disabled;
			el.innerHTML = '<span class="dh-select-dot"></span><span class="dh-select-opt-label"></span>';
			el.querySelector('.dh-select-opt-label').textContent = opt.textContent;
			el.addEventListener('click', () => {
				if(el.disabled) return;
				this._choose(opt.value);
			});
			this.menu.appendChild(el);
			this.optionEls.push(el);
		});
	}

	_choose(value){
		this.close();
		this.trigger.focus();
		if(this.select.value === value) return;
		this.select.value = value; // intercepted setter -> _syncFromSelect
		this.select.dispatchEvent(new Event('change', { bubbles: true }));
	}

	// Reflect the native select's current value + per-option disabled state into the UI.
	_syncFromSelect(){
		if(!this.optionEls) return;
		const value = this.select.value;
		const selected = this.select.selectedOptions[0];
		if(this.labelEl) this.labelEl.textContent = selected ? selected.textContent : '';
		if(this.dotEl) this.dotEl.className = 'dh-select-dot unit-' + (selected ? this._unitFor(selected) : 'custom');
		this.optionEls.forEach(el => {
			const opt = this._optionByValue(el.dataset.value);
			el.disabled = opt ? opt.disabled : true;
			el.classList.toggle('selected', el.dataset.value === value);
			el.setAttribute('aria-selected', String(el.dataset.value === value));
		});
	}

	_optionByValue(value){
		return Array.prototype.find.call(this.select.options, o => o.value === value);
	}

	refresh(){
		this._renderMenu();
		this._syncFromSelect();
	}

	// --- open / close / positioning ------------------------------------------------------

	open(){
		if(this.wrap.classList.contains('open')) return;
		this._syncFromSelect();
		this.wrap.classList.add('open');
		this.trigger.setAttribute('aria-expanded', 'true');
		this._position();

		const active = this.optionEls.find(el => el.classList.contains('selected') && !el.disabled)
			|| this.optionEls.find(el => !el.disabled);
		this._activate(active);

		this._onDocPointer = e => {
			if(!this.wrap.contains(e.target)) this.close();
		};
		this._onDismiss = () => this.close();
		document.addEventListener('pointerdown', this._onDocPointer, true);
		// The menu is fixed-positioned off the trigger; a scroll/resize would detach it, so close.
		window.addEventListener('scroll', this._onDismiss, true);
		window.addEventListener('resize', this._onDismiss, true);
	}

	close(){
		if(!this.wrap.classList.contains('open')) return;
		this.wrap.classList.remove('open');
		this.trigger.setAttribute('aria-expanded', 'false');
		document.removeEventListener('pointerdown', this._onDocPointer, true);
		window.removeEventListener('scroll', this._onDismiss, true);
		window.removeEventListener('resize', this._onDismiss, true);
	}

	toggle(){
		this.wrap.classList.contains('open') ? this.close() : this.open();
	}

	// Fixed positioning so no ancestor overflow can clip the popup. Flips above the trigger when
	// there isn't room below.
	_position(){
		const r = this.trigger.getBoundingClientRect();
		this.menu.style.position = 'fixed';
		this.menu.style.minWidth = r.width + 'px';
		this.menu.style.left = r.left + 'px';
		this.menu.style.top = (r.bottom + 4) + 'px';
		const menuH = this.menu.getBoundingClientRect().height;
		if(r.bottom + 4 + menuH > window.innerHeight && r.top - 4 - menuH > 0){
			this.menu.style.top = (r.top - 4 - menuH) + 'px';
		}
	}

	// --- keyboard ------------------------------------------------------------------------

	_bindEvents(){
		this.trigger.addEventListener('click', () => this.toggle());
		this.trigger.addEventListener('keydown', e => {
			if(e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' '){
				e.preventDefault();
				this.open();
			}
		});
		this.menu.addEventListener('keydown', e => this._onMenuKey(e));
	}

	_onMenuKey(e){
		const enabled = this.optionEls.filter(el => !el.disabled);
		if(enabled.length === 0) return;
		const idx = enabled.indexOf(this._active);
		switch(e.key){
			case 'Escape':    e.preventDefault(); this.close(); this.trigger.focus(); break;
			case 'ArrowDown': e.preventDefault(); this._activate(enabled[Math.min(enabled.length - 1, idx + 1)] || enabled[0]); break;
			case 'ArrowUp':   e.preventDefault(); this._activate(enabled[Math.max(0, idx - 1)] || enabled[enabled.length - 1]); break;
			case 'Home':      e.preventDefault(); this._activate(enabled[0]); break;
			case 'End':       e.preventDefault(); this._activate(enabled[enabled.length - 1]); break;
			case 'Enter':
			case ' ':         e.preventDefault(); if(this._active) this._choose(this._active.dataset.value); break;
		}
	}

	_activate(el){
		if(!el) return;
		this.optionEls.forEach(o => o.classList.remove('active'));
		el.classList.add('active');
		el.focus();
		this._active = el;
	}

	// --- reactivity ----------------------------------------------------------------------

	// Intercept `select.value = x` on this instance so external code that sets the value (without
	// dispatching 'change') still updates the custom UI.
	_interceptValueSetter(){
		const proto = window.HTMLSelectElement && window.HTMLSelectElement.prototype;
		const desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
		if(!desc || !desc.set) return;
		const self = this;
		Object.defineProperty(this.select, 'value', {
			configurable: true,
			get(){ return desc.get.call(this); },
			set(v){ desc.set.call(this, v); self._syncFromSelect(); }
		});
	}

	// Pick up option.disabled toggles (e.g. per-mode enable/disable) with no explicit refresh.
	_observeDisabled(){
		this._mo = new MutationObserver(() => this._syncFromSelect());
		this._mo.observe(this.select, { attributes: true, attributeFilter: ['disabled'], subtree: true });
	}
}

window.EnhancedSelect = EnhancedSelect;

// Auto-enhance any <select data-enhance> on the page. The native element stays the source of
// truth, so this is safe to run alongside page scripts that drive the same select.
document.addEventListener('DOMContentLoaded', () => EnhancedSelect.enhanceAll());
