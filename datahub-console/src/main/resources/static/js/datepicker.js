// datepicker.js — locale-aware date/time pickers that always render 24h time
// and use the locale's preferred calendar system (Gregorian, Julian, Buddhist,
// etc.) for label formatting via Intl.DateTimeFormat.
//
// Usage — paired inputs inside the same container (typically a <label>):
//   <label>
//     <input type="text" data-picker="date" data-locale="nb">
//     <input type="text" data-picker="time" data-locale="nb">
//   </label>
//
// The auto-init walks every input[data-picker]. When it finds a date input
// and a time input sharing the same parent it attaches a single
// DateTimePicker that opens from either one and edits both. Lone date or
// time inputs get a standalone DatePicker / TimePicker.
//
// Inputs are forced readOnly. Picks update internal state but only commit
// (write input.value + dispatch 'change') on OK or outside-click — Escape
// cancels. That keeps existing change listeners from firing on every cell.
//
// Calendar systems: month / weekday / day labels go through
// Intl.DateTimeFormat with the locale's default calendar. The day grid is
// iterated in Gregorian (since JS Date is Gregorian) and each day is
// formatted via the chosen calendar for display, so Julian/Buddhist/etc.
// locales see their own month names and day numbers. The committed value
// stays ISO YYYY-MM-DD so the server keeps a stable parse.
(function() {
	'use strict';

	function pad(n) { return String(n).padStart(2, '0'); }
	function clamp(n, min, max) { return Math.min(max, Math.max(min, n)); }
	function isoFromDate(d) {
		return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
	}
	function parseIso(s) {
		const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || '');
		if (!m) return null;
		return new Date(parseInt(m[1], 10), parseInt(m[2], 10) - 1, parseInt(m[3], 10));
	}
	// Strict validators for the opt-in editable mode (data-picker-editable). The date check
	// round-trips through parseIso/isoFromDate so rolled-over values (e.g. 2026-02-31) are rejected.
	function isValidIsoDate(s) {
		const v = (s || '').trim();
		const d = parseIso(v);
		return !!d && isoFromDate(d) === v;
	}
	function isValidIsoTime(s) {
		return /^([01]\d|2[0-3]):[0-5]\d$/.test((s || '').trim());
	}
	// ISO 8601 week number: weeks start Monday, week 1 contains the year's
	// first Thursday. Good enough across locales for a calendar UI.
	function isoWeekNumber(date) {
		const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
		const dayNum = d.getUTCDay() || 7; // Mon=1..Sun=7
		d.setUTCDate(d.getUTCDate() + 4 - dayNum);
		const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
		return Math.ceil((((d - yearStart) / 86400000) + 1) / 7);
	}
	// Week boundaries for the week containing `date`, honoring firstDow.
	// start = firstDow at 00:00, end = firstDow + 6 at 23:59.
	function weekBoundsFor(date, firstDow) {
		const dow = date.getDay();
		const offsetToStart = (dow - firstDow + 7) % 7;
		const start = new Date(date.getFullYear(), date.getMonth(), date.getDate() - offsetToStart, 0, 0, 0);
		const end = new Date(date.getFullYear(), date.getMonth(), date.getDate() - offsetToStart + 6, 23, 59, 0);
		return { start: start, end: end };
	}
	function localeFor(input) {
		return (input && input.dataset.locale)
			|| document.documentElement.lang
			|| navigator.language
			|| 'en';
	}
	function defaultCalendarFor(locale) {
		try {
			const cal = new Intl.Locale(locale).getCalendars();
			return (cal && cal.length) ? cal[0] : 'gregory';
		} catch (e) {
			return 'gregory';
		}
	}
	// Returns 0..6 (Sunday..Saturday), matching Date.prototype.getDay().
	function firstDayOfWeek(locale) {
		try {
			const info = new Intl.Locale(locale).getWeekInfo();
			// CLDR uses 1..7 (Mon..Sun); convert Sunday (7) to 0.
			return info.firstDay === 7 ? 0 : info.firstDay;
		} catch (e) {
			return /^(en-US|en-CA|fil|he|ja|ko|pt-BR|zh-TW)/i.test(locale) ? 0 : 1;
		}
	}

	function makeButton(label, onClick) {
		const b = document.createElement('button');
		b.type = 'button';
		b.className = 'dh-picker-btn';
		b.textContent = label;
		b.addEventListener('click', onClick);
		return b;
	}

	// Renders a localized month grid. Returns the root element. The caller is
	// expected to set onSelect (called with an ISO date string) and to call
	// render() whenever the view month/year or selection changes.
	class CalendarPanel {
		constructor(opts) {
			this.locale = opts.locale;
			this.calendar = opts.calendar;
			this.firstDow = opts.firstDow;
			this.onSelect = opts.onSelect || (() => {});
			this.onWeekClick = opts.onWeekClick || null;
			// Optional (year, month, day) -> bool. Disabled days render with
			// the .disabled class and don't fire clicks.
			this.isDayDisabled = opts.isDayDisabled || null;
			this.selectedIso = opts.initialIso || isoFromDate(new Date());
			const base = parseIso(this.selectedIso) || new Date();
			this.viewYear = base.getFullYear();
			this.viewMonth = base.getMonth();
			// 'days' | 'months' | 'years'
			this.mode = 'days';
			this.yearAnchor = this.viewYear;

			this.root = document.createElement('div');
			this.root.className = 'dh-picker-calendar';
			this.render();
		}

		setSelected(iso) {
			this.selectedIso = iso;
			this.render();
		}

		// Picking a year or month from the header grids should keep the rest of the date, not just
		// move the view: carry the selected day into (year, month), clamping it to that month's length
		// (e.g. Jan 31 -> Feb 28), then return to the day grid. Reported as a bug where choosing a year
		// dropped the current date/month. The plain ‹ › arrows stay pure view navigation (no carry).
		_carrySelection(year, month) {
			const sel = parseIso(this.selectedIso) || new Date();
			const day = Math.min(sel.getDate(), new Date(year, month + 1, 0).getDate());
			const iso = isoFromDate(new Date(year, month, day));
			this.selectedIso = iso;
			this.viewYear = year;
			this.viewMonth = month;
			this.mode = 'days';
			this.render();
			this.onSelect(iso, { navigation: true });
		}

		render() {
			this.root.innerHTML = '';
			if (this.mode === 'months') {
				this._renderMonthsView();
			} else if (this.mode === 'years') {
				this._renderYearsView();
			} else {
				this._renderDaysView();
			}
		}

		_renderDaysView() {
			const root = this.root;

			const monthFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, month: 'long'
			});
			const yearFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, year: 'numeric'
			});
			const dowFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, weekday: 'short'
			});
			const dayFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, day: 'numeric'
			});

			const header = document.createElement('div');
			header.className = 'dh-picker-header';
			const prev = makeButton('‹', () => {
				this.viewMonth--;
				if (this.viewMonth < 0) { this.viewMonth = 11; this.viewYear--; }
				this.render();
			});
			const next = makeButton('›', () => {
				this.viewMonth++;
				if (this.viewMonth > 11) { this.viewMonth = 0; this.viewYear++; }
				this.render();
			});
			const title = document.createElement('span');
			title.className = 'dh-picker-title';
			const monthBtn = document.createElement('button');
			monthBtn.type = 'button';
			monthBtn.className = 'dh-picker-title-btn';
			monthBtn.textContent = monthFmt.format(new Date(this.viewYear, this.viewMonth, 1));
			monthBtn.addEventListener('click', () => {
				this.mode = 'months';
				this.render();
			});
			const yearBtn = document.createElement('button');
			yearBtn.type = 'button';
			yearBtn.className = 'dh-picker-title-btn';
			yearBtn.textContent = yearFmt.format(new Date(this.viewYear, this.viewMonth, 1));
			yearBtn.addEventListener('click', () => {
				this.mode = 'years';
				this.yearAnchor = this.viewYear;
				this.render();
			});
			title.append(monthBtn, yearBtn);
			header.append(prev, title, next);
			root.appendChild(header);

			// Weekday header — Jan 7..13 2024 was Sun..Sat, rotate by firstDow.
			// First cell is empty (sits above the week-number column).
			const weekRow = document.createElement('div');
			weekRow.className = 'dh-picker-weekdays';
			const weekHeaderCell = document.createElement('span');
			weekHeaderCell.className = 'dh-picker-weekday dh-picker-week-header';
			weekRow.appendChild(weekHeaderCell);
			for (let i = 0; i < 7; i++) {
				const dowIdx = (this.firstDow + i) % 7;
				const refDate = new Date(2024, 0, 7 + dowIdx);
				const cell = document.createElement('span');
				cell.className = 'dh-picker-weekday';
				cell.textContent = dowFmt.format(refDate);
				weekRow.appendChild(cell);
			}
			root.appendChild(weekRow);

			const grid = document.createElement('div');
			grid.className = 'dh-picker-grid';
			const firstOfMonth = new Date(this.viewYear, this.viewMonth, 1);
			const lastOfMonth = new Date(this.viewYear, this.viewMonth + 1, 0);
			const leadingBlanks = (firstOfMonth.getDay() - this.firstDow + 7) % 7;
			const totalCells = leadingBlanks + lastOfMonth.getDate();
			const totalWeeks = Math.ceil(totalCells / 7);
			// First day shown in the grid (may belong to the previous month).
			const gridStart = new Date(this.viewYear, this.viewMonth, 1 - leadingBlanks);

			const todayIso = isoFromDate(new Date());
			for (let w = 0; w < totalWeeks; w++) {
				const weekFirst = new Date(gridStart);
				weekFirst.setDate(gridStart.getDate() + w * 7);
				const weekNum = isoWeekNumber(weekFirst);

				// Week number cell (start of row). Weeks are never bounds-
				// disabled by start↔end pairing — clicking a week replaces
				// both inputs with that week's [Mon 00:00, Sun 23:59], which
				// is always a valid range regardless of the existing peer.
				const weekCell = document.createElement('span');
				weekCell.className = 'dh-picker-week';
				weekCell.textContent = String(weekNum);
				if (this.onWeekClick) {
					weekCell.addEventListener('click', () => {
						const bounds = weekBoundsFor(weekFirst, this.firstDow);
						this.onWeekClick(bounds.start, bounds.end, weekNum);
					});
				} else {
					weekCell.classList.add('disabled');
				}
				grid.appendChild(weekCell);

				// Seven day cells for this row
				for (let i = 0; i < 7; i++) {
					const dt = new Date(weekFirst);
					dt.setDate(weekFirst.getDate() + i);
					const inMonth = dt.getMonth() === this.viewMonth;
					if (!inMonth) {
						const blank = document.createElement('span');
						blank.className = 'dh-picker-day blank';
						grid.appendChild(blank);
						continue;
					}
					const iso = isoFromDate(dt);
					const cell = document.createElement('span');
					cell.className = 'dh-picker-day';
					cell.textContent = dayFmt.format(dt);
					if (iso === this.selectedIso) cell.classList.add('selected');
					if (iso === todayIso) cell.classList.add('today');
					const disabled = this.isDayDisabled
						&& this.isDayDisabled(this.viewYear, this.viewMonth, dt.getDate());
					if (disabled) {
						cell.classList.add('disabled');
					} else {
						cell.addEventListener('click', () => {
							this.selectedIso = iso;
							this.render();
							this.onSelect(iso);
						});
					}
					grid.appendChild(cell);
				}
			}
			root.appendChild(grid);
		}

		_renderMonthsView() {
			const root = this.root;

			const monthShortFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, month: 'short'
			});
			const yearFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, year: 'numeric'
			});

			const header = document.createElement('div');
			header.className = 'dh-picker-header';
			const prev = makeButton('‹', () => { this.viewYear--; this.render(); });
			const next = makeButton('›', () => { this.viewYear++; this.render(); });
			const yearLabel = document.createElement('button');
			yearLabel.type = 'button';
			yearLabel.className = 'dh-picker-title-btn';
			yearLabel.textContent = yearFmt.format(new Date(this.viewYear, 0, 1));
			yearLabel.addEventListener('click', () => {
				this.mode = 'years';
				this.yearAnchor = this.viewYear;
				this.render();
			});
			const title = document.createElement('span');
			title.className = 'dh-picker-title';
			title.appendChild(yearLabel);
			header.append(prev, title, next);
			root.appendChild(header);

			const grid = document.createElement('div');
			grid.className = 'dh-picker-month-grid';
			for (let m = 0; m < 12; m++) {
				const cell = document.createElement('span');
				cell.className = 'dh-picker-month';
				cell.textContent = monthShortFmt.format(new Date(this.viewYear, m, 1));
				if (m === this.viewMonth) cell.classList.add('selected');
				cell.addEventListener('click', () => this._carrySelection(this.viewYear, m));
				grid.appendChild(cell);
			}
			root.appendChild(grid);
		}

		_renderYearsView() {
			const root = this.root;
			const yearFmt = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, year: 'numeric'
			});

			// 12 years: anchor − 5 .. anchor + 6 (the user said ±5; rounding up
			// to 12 gives a clean 3×4 grid).
			const startYear = this.yearAnchor - 5;
			const endYear = this.yearAnchor + 6;

			const header = document.createElement('div');
			header.className = 'dh-picker-header';
			const prev = makeButton('‹', () => { this.yearAnchor -= 12; this.render(); });
			const next = makeButton('›', () => { this.yearAnchor += 12; this.render(); });

			const range = document.createElement('span');
			range.className = 'dh-picker-title';
			range.textContent =
				yearFmt.format(new Date(startYear, 0, 1)) + ' – ' +
				yearFmt.format(new Date(endYear, 0, 1));
			header.append(prev, range, next);
			root.appendChild(header);

			// Free-text year input — for jumping outside the visible window.
			const inputRow = document.createElement('div');
			inputRow.className = 'dh-picker-year-input-row';
			const yearInput = document.createElement('input');
			yearInput.type = 'number';
			yearInput.className = 'dh-picker-year-input';
			yearInput.min = '1';
			yearInput.max = '9999';
			yearInput.placeholder = String(this.viewYear);
			yearInput.addEventListener('keydown', e => {
				if (e.key === 'Enter') {
					e.preventDefault();
					commitInput();
				}
			});
			const commitInput = () => {
				const v = parseInt(yearInput.value, 10);
				if (!Number.isFinite(v) || v < 1 || v > 9999) return;
				this._carrySelection(v, this.viewMonth);
			};
			const goBtn = makeButton('OK', commitInput);
			inputRow.append(yearInput, goBtn);
			root.appendChild(inputRow);

			const grid = document.createElement('div');
			grid.className = 'dh-picker-year-grid';
			for (let y = startYear; y <= endYear; y++) {
				const cell = document.createElement('span');
				cell.className = 'dh-picker-year';
				cell.textContent = yearFmt.format(new Date(y, 0, 1));
				if (y === this.viewYear) cell.classList.add('selected');
				cell.addEventListener('click', () => this._carrySelection(y, this.viewMonth));
				grid.appendChild(cell);
			}
			root.appendChild(grid);

			// Focus the input so the user can start typing right away.
			requestAnimationFrame(() => yearInput.focus());
		}
	}

	// Renders a pair of scrollable hour + minute lists. Returns the root
	// element. onChange is called with ('hour'|'minute', value) on each pick.
	class TimePanel {
		constructor(opts) {
			this.hour = clamp(opts.initialHour | 0, 0, 23);
			this.minute = clamp(opts.initialMinute | 0, 0, 59);
			this.onChange = opts.onChange || (() => {});
			// Optional disabled predicates. Re-evaluated on every render so
			// the parent picker can vary them with the currently-selected day.
			this.isHourDisabled = opts.isHourDisabled || null;
			this.isMinuteDisabled = opts.isMinuteDisabled || null;

			this.root = document.createElement('div');
			this.root.className = 'dh-picker-time';
			this.render();
		}

		setHour(h) { this.hour = h; this.render(); }
		setMinute(m) { this.minute = m; this.render(); }

		render() {
			const root = this.root;
			root.innerHTML = '';

			const hourCol = this.buildScroller(0, 23, this.hour, h => {
				this.hour = h;
				this.render();
				this.onChange('hour', h);
			}, this.isHourDisabled);
			const minCol = this.buildScroller(0, 59, this.minute, m => {
				this.minute = m;
				this.render();
				this.onChange('minute', m);
			}, this.isMinuteDisabled);
			hourCol.classList.add('dh-timepicker-col');
			minCol.classList.add('dh-timepicker-col');

			const sep = document.createElement('span');
			sep.className = 'dh-timepicker-sep';
			sep.textContent = ':';

			const cols = document.createElement('div');
			cols.className = 'dh-timepicker-cols';
			cols.append(hourCol, sep, minCol);
			root.appendChild(cols);

			// Center-scroll the selected items once layout settles.
			requestAnimationFrame(() => {
				root.querySelectorAll('.dh-timepicker-item.selected')
					.forEach(el => el.scrollIntoView({ block: 'center' }));
			});
		}

		buildScroller(min, max, current, onPick, isDisabled) {
			const list = document.createElement('div');
			list.className = 'dh-timepicker-scroller';
			for (let v = min; v <= max; v++) {
				const item = document.createElement('span');
				item.className = 'dh-timepicker-item';
				item.textContent = pad(v);
				if (v === current) item.classList.add('selected');
				if (isDisabled && isDisabled(v)) {
					item.classList.add('disabled');
				} else {
					item.addEventListener('click', () => onPick(v));
				}
				list.appendChild(item);
			}
			return list;
		}
	}

	// Common popover plumbing: positioning, outside-close, escape handling.
	class PopoverHost {
		constructor(triggers) {
			this.triggers = triggers.filter(Boolean);
			this.popover = null;
			this._closeOutside = null;
			this._dirty = false;

			this.triggers.forEach(input => {
				// Opt-in editable mode (data-picker-editable): the field stays typeable and is
				// ISO-validated as you type, instead of being a readOnly pick-only field. Default
				// (timeseries) behaviour is unchanged.
				const editable = input.hasAttribute('data-picker-editable');
				if (!editable) input.readOnly = true;
				input.classList.add('dh-picker-input');
				input.addEventListener('pointerdown', e => {
					if (editable) {
						// Let the click place the text cursor; open the calendar but never toggle it
						// closed here (outside-click / Escape close it), so typing is never interrupted.
						if (!this.popover) this.open();
						return;
					}
					e.preventDefault();
					input.focus();
					if (this.popover) this.close(true); else this.open();
				});
				input.addEventListener('keydown', e => {
					if (e.key === 'Escape' && this.popover) {
						this.close(false); // cancel — don't commit
					} else if (editable && e.key === 'Enter') {
						if (this.popover) this.close(true); // commit the typed/picked value
					} else if (!editable && (e.key === 'Enter' || e.key === ' ') && !this.popover) {
						e.preventDefault();
						this.open();
					}
				});
				if (editable) {
					// Flag a non-empty value that isn't a valid ISO date/time. Blank is allowed (a cleared
					// bound just drops out of the query).
					input.addEventListener('input', () => {
						const v = input.value.trim();
						const ok = v.length === 0
							|| (input.dataset.picker === 'time' ? isValidIsoTime(v) : isValidIsoDate(v));
						input.classList.toggle('error', !ok);
					});
				}
			});
		}

		positionPopover(anchor) {
			const rect = anchor.getBoundingClientRect();
			this.popover.style.position = 'absolute';
			this.popover.style.top = `${rect.bottom + window.scrollY + 4}px`;
			const maxLeft = window.scrollX + document.documentElement.clientWidth
				- this.popover.offsetWidth - 8;
			this.popover.style.left = `${Math.min(rect.left + window.scrollX, Math.max(0, maxLeft))}px`;
		}

		attachOutsideClose() {
			setTimeout(() => {
				this._closeOutside = (e) => {
					if (!this.popover) return;
					if (!this.popover.contains(e.target) && !this.triggers.includes(e.target)) {
						// Clicking away commits the in-flight pick.
						this.close(true);
					}
				};
				document.addEventListener('pointerdown', this._closeOutside, true);
			}, 0);
		}

		close(commit) {
			if (!this.popover) return;
			if (commit && this._dirty) this.commit();
			this.popover.remove();
			this.popover = null;
			this._dirty = false;
			if (this._closeOutside) {
				document.removeEventListener('pointerdown', this._closeOutside, true);
				this._closeOutside = null;
			}
		}

		// Subclasses override these.
		open() { throw new Error('open() not implemented'); }
		commit() { throw new Error('commit() not implemented'); }

		fireChange(input) {
			input.dispatchEvent(new Event('input', { bubbles: true }));
			input.dispatchEvent(new Event('change', { bubbles: true }));
		}
	}

	// Combined date + time picker. Opens a popover containing a CalendarPanel
	// on the left and a TimePanel on the right, plus an OK button. Picks update
	// internal state; values are written to both inputs and change events fire
	// only on commit (OK button or outside-click).
	class DateTimePicker extends PopoverHost {
		constructor(dateInput, timeInput) {
			super([dateInput, timeInput]);
			this.dateInput = dateInput;
			this.timeInput = timeInput;
			this._dateEditable = dateInput.hasAttribute('data-picker-editable');
			this._timeEditable = timeInput.hasAttribute('data-picker-editable');
			this.locale = localeFor(dateInput || timeInput);
			this.calendar = defaultCalendarFor(this.locale);
			this.firstDow = firstDayOfWeek(this.locale);
			// Optional pairing with another DateTimePicker that holds the
			// opposite end of a [start, end] range. When set, commit() clamps
			// so the start can never be after the end (and vice versa).
			this.role = null; // 'start' | 'end' | null
			this.peer = null;
		}

		setBound(role, peer) {
			this.role = role;
			this.peer = peer;
		}

		// Date instant currently committed in the inputs (not the in-flight
		// popover state). Returns null if either input is empty/invalid.
		getCurrentInstant() {
			if (!this.dateInput.value) return null;
			const date = parseIso(this.dateInput.value);
			if (!date) return null;
			const tparts = (this.timeInput.value || '00:00').split(':');
			date.setHours(clamp(parseInt(tparts[0], 10) || 0, 0, 23));
			date.setMinutes(clamp(parseInt(tparts[1], 10) || 0, 0, 59));
			return date;
		}

		open() {
			this.selectedIso = parseIso(this.dateInput.value)
				? this.dateInput.value
				: isoFromDate(new Date());
			const tparts = (this.timeInput.value || '00:00').split(':');
			this.hour = clamp(parseInt(tparts[0], 10) || 0, 0, 23);
			this.minute = clamp(parseInt(tparts[1], 10) || 0, 0, 59);
			this._dirty = false;
			// In editable mode, track whether the user actually picked a day / time this session.
			// A bound left untouched stays blank instead of being filled with today / 00:00 on commit.
			this._dateTouched = false;
			this._timeTouched = false;
			// Values as the popover opened. commit() fires change on the NET change vs these, because
			// editable picks live-write the inputs before commit runs (so a diff against the current
			// value would miss the change and the events search wouldn't refresh on OK).
			this._openDateValue = this.dateInput.value;
			this._openTimeValue = this.timeInput.value;

			this.popover = document.createElement('div');
			this.popover.className = 'dh-picker-popover dh-datetimepicker';

			const body = document.createElement('div');
			body.className = 'dh-picker-body';

			this.calendarPanel = new CalendarPanel({
				locale: this.locale,
				calendar: this.calendar,
				firstDow: this.firstDow,
				initialIso: this.selectedIso,
				isDayDisabled: (y, m, d) => this._isDayDisabled(y, m, d),
				onSelect: iso => {
					this.selectedIso = iso;
					this._dirty = true;
					this._dateTouched = true;
					if (this._dateEditable) { this.dateInput.value = iso; this.dateInput.classList.remove('error'); }
					// Hour/minute disable state depends on the selected day,
					// so the time panel needs to re-evaluate when day changes.
					if (this.timePanel) this.timePanel.render();
					this._updatePreview();
				},
				onWeekClick: (start, end, weekNum) => this._dispatchWeekClick(start, end, weekNum)
			});
			this.timePanel = new TimePanel({
				initialHour: this.hour,
				initialMinute: this.minute,
				isHourDisabled: h => this._isHourDisabled(h),
				isMinuteDisabled: m => this._isMinuteDisabled(m),
				onChange: (kind, v) => {
					if (kind === 'hour') this.hour = v; else this.minute = v;
					this._dirty = true;
					this._timeTouched = true;
					if (this._timeEditable) {
						this.timeInput.value = `${pad(this.hour)}:${pad(this.minute)}`;
						this.timeInput.classList.remove('error');
					}
					this._updatePreview();
				}
			});

			body.append(this.calendarPanel.root, this.timePanel.root);

			const footer = document.createElement('div');
			footer.className = 'dh-picker-footer';
			this.previewEl = document.createElement('span');
			this.previewEl.className = 'dh-picker-preview';
			const ok = makeButton('OK', () => this.close(true));
			footer.append(this.previewEl, ok);
			this._updatePreview();

			this.popover.append(body, footer);
			document.body.appendChild(this.popover);
			this.positionPopover(document.activeElement && this.triggers.includes(document.activeElement)
				? document.activeElement
				: this.dateInput);
			this.attachOutsideClose();
		}

		// Footer preview of the full selection, e.g. "Tue, 8 Jul 2026, 16:40", localized. Refreshed on
		// every date/time pick so the user sees exactly what OK will commit.
		_updatePreview() {
			if (!this.previewEl) return;
			const d = parseIso(this.selectedIso);
			if (!d) { this.previewEl.textContent = '—'; return; }
			const dateStr = new Intl.DateTimeFormat(this.locale, {
				calendar: this.calendar, weekday: 'short', day: 'numeric', month: 'short', year: 'numeric'
			}).format(d);
			const hh = Number(this.hour), mm = Number(this.minute);
			const hasTime = Number.isFinite(hh) && Number.isFinite(mm);
			this.previewEl.textContent = hasTime ? `${dateStr}, ${pad(hh)}:${pad(mm)}` : dateStr;
		}

		commit() {
			// Non-editable: commit the picked state. Editable: the input already holds the typed/picked
			// value, so prefer it when valid; a bound the user never touched stays blank.
			let newDate = this.selectedIso;
			if (this._dateEditable) {
				const typed = this.dateInput.value.trim();
				if (isValidIsoDate(typed)) newDate = typed;
				else if (typed.length === 0 && !this._dateTouched) newDate = '';
				else newDate = this.selectedIso;
				this.dateInput.classList.remove('error');
			}
			let newTime = `${pad(this.hour)}:${pad(this.minute)}`;
			if (this._timeEditable) {
				const typed = this.timeInput.value.trim();
				if (isValidIsoTime(typed)) newTime = typed;
				else if (typed.length === 0 && !this._timeTouched) newTime = '';
				else newTime = `${pad(this.hour)}:${pad(this.minute)}`;
				this.timeInput.classList.remove('error');
			}
			// Compare against the open-time values, not the current input, so editable live-writes
			// don't mask the change (otherwise OK wouldn't refresh the events search).
			const dateChanged = newDate !== this._openDateValue;
			const timeChanged = newTime !== this._openTimeValue;
			if (this.dateInput.value !== newDate) this.dateInput.value = newDate;
			if (this.timeInput.value !== newTime) this.timeInput.value = newTime;
			if (dateChanged) this.fireChange(this.dateInput);
			if (timeChanged) this.fireChange(this.timeInput);
		}

		// Bound-aware predicates: when paired with a peer picker, days/hours/
		// minutes that would violate start ≤ end render disabled in the
		// calendar/time panels, so the user can't pick them in the first
		// place. When unpaired (no peer) everything is enabled.
		_isDayDisabled(year, month, day) {
			if (!this.peer || !this.role) return false;
			const peerInstant = this.peer.getCurrentInstant();
			if (!peerInstant) return false;
			const dayStart = new Date(year, month, day, 0, 0, 0, 0);
			const dayEnd = new Date(year, month, day, 23, 59, 59, 999);
			if (this.role === 'start') return dayStart > peerInstant;
			return dayEnd < peerInstant;
		}
		_isHourDisabled(h) {
			if (!this.peer || !this.role) return false;
			const peerInstant = this.peer.getCurrentInstant();
			if (!peerInstant) return false;
			const day = parseIso(this.selectedIso);
			if (!day) return false;
			// Hour constraint only kicks in on the same calendar day as the
			// peer; on any other day the whole hour is on the right side.
			if (day.getFullYear() !== peerInstant.getFullYear()
				|| day.getMonth() !== peerInstant.getMonth()
				|| day.getDate() !== peerInstant.getDate()) return false;
			if (this.role === 'start') return h > peerInstant.getHours();
			return h < peerInstant.getHours();
		}
		_isMinuteDisabled(m) {
			if (!this.peer || !this.role) return false;
			const peerInstant = this.peer.getCurrentInstant();
			if (!peerInstant) return false;
			const day = parseIso(this.selectedIso);
			if (!day) return false;
			if (day.getFullYear() !== peerInstant.getFullYear()
				|| day.getMonth() !== peerInstant.getMonth()
				|| day.getDate() !== peerInstant.getDate()) return false;
			// Minute only matters when the selected hour matches the peer's.
			if (this.hour !== peerInstant.getHours()) return false;
			if (this.role === 'start') return m > peerInstant.getMinutes();
			return m < peerInstant.getMinutes();
		}

		// Write a Date into this picker's inputs (+ internal state) and fire change, so the page's
		// own change listeners (search / refetch) run.
		_setValue(date) {
			this.selectedIso = isoFromDate(date);
			this.hour = date.getHours();
			this.minute = date.getMinutes();
			this.dateInput.value = this.selectedIso;
			this.timeInput.value = `${pad(this.hour)}:${pad(this.minute)}`;
			this.dateInput.classList.remove('error');
			this.timeInput.classList.remove('error');
			this.fireChange(this.dateInput);
			this.fireChange(this.timeInput);
		}

		_dispatchWeekClick(start, end, weekNum) {
			// Paired start/end pickers (data-picker-role) get the built-in range behavior: confirm,
			// then set the whole period [firstDow 00:00, firstDow+6 23:59] across both inputs and fire
			// change so the page refetches. Works on the event filter / timeseries / insights with no
			// per-page wiring.
			const startPicker = this.role === 'start' ? this : (this.role === 'end' ? this.peer : null);
			const endPicker   = this.role === 'end'   ? this : (this.role === 'start' ? this.peer : null);
			if (startPicker && endPicker) {
				// 1-arg $L is null-safe (returns the key if missing); interpolate {0} ourselves and fall
				// back to English so a not-yet-loaded key can never throw.
				const tmpl = (typeof $L === 'function') ? $L('picker.weekRange.confirm') : '';
				const msg = (tmpl && tmpl !== 'picker.weekRange.confirm')
					? tmpl.replace('{0}', String(weekNum))
					: `Set the time period to week ${weekNum}?`;
				if (!window.confirm(msg)) return;
				startPicker._setValue(start);
				endPicker._setValue(end);
				if (this.peer && this.peer.popover) this.peer.close(false);
				this.close(false); // inputs already written + change fired; don't let commit override
				return;
			}
			// Unpaired: let the page decide via a custom event.
			if (!this.popover) return;
			const ev = new CustomEvent('dh-picker:weekclick', {
				bubbles: true,
				cancelable: true,
				detail: {
					start: start, end: end, weekNum: weekNum,
					picker: this,
					dateInput: this.dateInput, timeInput: this.timeInput
				}
			});
			this.popover.dispatchEvent(ev);
		}
	}

	// Standalone date picker — used when an input[data-picker="date"] has no
	// matching time sibling.
	class DatePicker extends PopoverHost {
		constructor(input) {
			super([input]);
			this.input = input;
			this.locale = localeFor(input);
			this.calendar = defaultCalendarFor(this.locale);
			this.firstDow = firstDayOfWeek(this.locale);
		}

		open() {
			this.selectedIso = parseIso(this.input.value) ? this.input.value : isoFromDate(new Date());
			this._dirty = false;
			this.popover = document.createElement('div');
			this.popover.className = 'dh-picker-popover';
			this.calendarPanel = new CalendarPanel({
				locale: this.locale,
				calendar: this.calendar,
				firstDow: this.firstDow,
				initialIso: this.selectedIso,
				onSelect: (iso, opts) => {
					this.selectedIso = iso;
					this._dirty = true;
					// A year/month header pick (navigation) carries the date but stays open; an explicit
					// day click commits and closes.
					if (!opts || !opts.navigation) this.close(true);
				},
				onWeekClick: (start, end, weekNum) => {
					if (!this.popover) return;
					this.popover.dispatchEvent(new CustomEvent('dh-picker:weekclick', {
						bubbles: true,
						cancelable: true,
						detail: {
							start: start, end: end, weekNum: weekNum,
							picker: this,
							dateInput: this.input, timeInput: null
						}
					}));
				}
			});
			this.popover.appendChild(this.calendarPanel.root);
			document.body.appendChild(this.popover);
			this.positionPopover(this.input);
			this.attachOutsideClose();
		}

		commit() {
			if (this.input.value !== this.selectedIso) {
				this.input.value = this.selectedIso;
				this.fireChange(this.input);
			}
		}
	}

	// Standalone time picker — used when an input[data-picker="time"] has no
	// matching date sibling.
	class TimePicker extends PopoverHost {
		constructor(input) {
			super([input]);
			this.input = input;
		}

		open() {
			const parts = (this.input.value || '00:00').split(':');
			this.hour = clamp(parseInt(parts[0], 10) || 0, 0, 23);
			this.minute = clamp(parseInt(parts[1], 10) || 0, 0, 59);
			this._dirty = false;
			this.popover = document.createElement('div');
			this.popover.className = 'dh-picker-popover';
			this.timePanel = new TimePanel({
				initialHour: this.hour,
				initialMinute: this.minute,
				onChange: (kind, v) => {
					if (kind === 'hour') this.hour = v; else this.minute = v;
					this._dirty = true;
				}
			});
			const footer = document.createElement('div');
			footer.className = 'dh-picker-footer';
			footer.appendChild(makeButton('OK', () => this.close(true)));
			this.popover.append(this.timePanel.root, footer);
			document.body.appendChild(this.popover);
			this.positionPopover(this.input);
			this.attachOutsideClose();
		}

		commit() {
			const newTime = `${pad(this.hour)}:${pad(this.minute)}`;
			if (this.input.value !== newTime) {
				this.input.value = newTime;
				this.fireChange(this.input);
			}
		}
	}

	function attachAll(root) {
		const scope = root || document;
		const seen = new WeakSet();
		// Collect pickers tagged with data-picker-role on their parent; the
		// pass after attach links start↔end so each can clamp the other.
		const byRole = {};

		scope.querySelectorAll('input[data-picker]:not([data-picker-attached])').forEach(el => {
			if (seen.has(el)) return;
			const parent = el.closest('label, [data-picker-pair]');
			if (parent) {
				const dateEl = parent.querySelector('input[data-picker="date"]');
				const timeEl = parent.querySelector('input[data-picker="time"]');
				if (dateEl && timeEl) {
					const picker = new DateTimePicker(dateEl, timeEl);
					const role = parent.dataset.pickerRole;
					if (role === 'start' || role === 'end') byRole[role] = picker;
					[dateEl, timeEl].forEach(input => {
						input.dataset.pickerAttached = '1';
						seen.add(input);
					});
					return;
				}
			}
			if (el.dataset.picker === 'date') new DatePicker(el);
			else if (el.dataset.picker === 'time') new TimePicker(el);
			el.dataset.pickerAttached = '1';
			seen.add(el);
		});

		if (byRole.start && byRole.end) {
			byRole.start.setBound('start', byRole.end);
			byRole.end.setBound('end', byRole.start);
		}
	}

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', () => attachAll());
	} else {
		attachAll();
	}

	window.DhPickers = {
		DatePicker: DatePicker,
		TimePicker: TimePicker,
		DateTimePicker: DateTimePicker,
		attachAll: attachAll
	};
})();
