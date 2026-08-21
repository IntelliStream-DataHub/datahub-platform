// This is a manifest file that'll be compiled into application.js.
//
// Any JavaScript file within this directory can be referenced here using a relative path.
//
// You're free to add application-wide JavaScript to this file, but it's generally better
// to create separate JavaScript files as needed.
//
//= require localization
//= require_self

/*
	Simple function for returning localized messages in asset pipeline js
 */
const $L = function(key, val, objects, returnMissing){
	if(returnMissing === undefined){
		if(!this.messages){
			this.messages = {};
			this.missing = {};
		}
		if(arguments.length === 1){
			const message = this.messages[key];
			if(message){
				return message;
			} else {
				console.warn("i18n! Could not find: " + key)
				this.missing[key] = "$L('"+key+"', [[#{"+key+"}]]);\n"
				return key;
			}
		}
		else if(arguments.length === 2){
			this.messages[key] = val;
		}
		else if(arguments.length === 3){
			let tempText = this.messages[key];
			for(let i = 0; i < objects.length; i++){
				tempText = tempText.replaceAll(`{${i}}`, objects[i]);
			}
			return tempText;
		}
	} else {
		const keys = Object.keys(this.missing);
		let text = "";
		for(let i =0; i < keys.length; i++){
			text += this.missing[keys[i]];
		}
		console.debug(text);
	}
};

/*
	Loads the locale-resolved messages.properties and parses its key=value lines
	straight into the $L cache. Exposed as window.i18nReady so page code can wait
	for the bundle before building UI from $L. The files are flat key=value (no
	continuations or escapes), so a split on the first '=' is all it takes.
 */
window.i18nReady = fetch("/api/i18n", { headers: { Accept: "text/plain" }, credentials: "same-origin" })
	.then(response => response.ok ? response.text() : "")
	.then(text => {
		for (const line of text.split(/\r?\n/)) {
			if (!line || line[0] === "#") continue;
			const eq = line.indexOf("=");
			if (eq > 0) $L(line.slice(0, eq).trim(), line.slice(eq + 1));
		}
	})
	.catch(() => { /* keep going; $L warns + returns the key */ });

/*
	Escape a value for interpolation into an HTML string (element-text or double-quoted attribute).
	Use only where an HTML string is unavoidable — e.g. a template literal handed to a plugin that
	renders it; when building the DOM directly, prefer element.textContent / element.value.
*/
window.escapeHtml = function(s){
	return String(s == null ? '' : s)
		.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
};

/*
	Shared timezone/datetime display helpers. The user's real timezone is only known to the browser,
	so client-rendered timestamps are formatted here, once, so every page renders them identically.
	  window.tzAbbr(date)          -> real abbreviation for display ("CEST"/"CET"). The browser default
	                                  locale may only yield "GMT+2", so we fall back to en-GB, which
	                                  gives the abbreviation.
	  window.tzOffset(date)        -> the GMT offset ("GMT+2"), for a title/tooltip.
	  window.formatLocalTime(iso)  -> "YYYY.MM.DD HH:mm" + " CEST", DST-aware, in the browser's zone.
	                                  opts: {seconds:true} adds :ss, {abbr:false} drops the tz suffix.
	  window.applyLocalTime(el,iso)-> sets an element's text to the local time, title to the offset,
	                                  and stashes the raw ISO in data-timestamp.
	  window.formatTimestamps(root)-> renders every [data-timestamp] element under root in local time
	                                  (add data-tz-seconds for :ss). Runs once on DOMContentLoaded.
	The format string here must match the server-side file listing so the two never drift.
*/
window.tzAbbr = function(date){
	const from = loc => {
		try {
			const part = new Intl.DateTimeFormat(loc, { timeZoneName: 'short' })
				.formatToParts(date).find(x => x.type === 'timeZoneName');
			return part ? part.value : '';
		} catch(e) { return ''; }
	};
	let a = from(undefined);
	if(!a || /^(GMT|UTC)/i.test(a)) a = from('en-GB');
	return a;
};
window.tzOffset = function(date){
	try {
		const part = new Intl.DateTimeFormat('en', { timeZoneName: 'shortOffset' })
			.formatToParts(date).find(x => x.type === 'timeZoneName');
		return part ? part.value : '';
	} catch(e) { return ''; }
};
window.formatLocalTime = function(iso, opts){
	if(iso === null || iso === undefined || iso === '') return '';
	const d = (iso instanceof Date) ? iso : new Date(iso);
	if(isNaN(d.getTime())) return String(iso);
	const o = opts || {};
	const p = n => String(n).padStart(2, '0');
	let base = `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
	if(o.seconds) base += ':' + p(d.getSeconds());
	if(o.abbr === false) return base;
	const abbr = window.tzAbbr(d);
	return abbr ? base + ' ' + abbr : base;
};
window.applyLocalTime = function(el, iso, opts){
	if(!el) return;
	el.textContent = window.formatLocalTime(iso, opts);
	if(iso !== null && iso !== undefined && iso !== '') el.setAttribute('data-timestamp', iso);
	const d = (iso instanceof Date) ? iso : new Date(iso);
	if(!isNaN(d.getTime())){ const off = window.tzOffset(d); if(off) el.title = off; }
};
window.formatTimestamps = function(root){
	(root || document).querySelectorAll('[data-timestamp]').forEach(el => {
		const iso = el.getAttribute('data-timestamp');
		if(!iso || el.getAttribute('data-tz-done') === iso) return;
		el.textContent = window.formatLocalTime(iso, { seconds: el.hasAttribute('data-tz-seconds') });
		const d = new Date(iso);
		if(!isNaN(d.getTime())){ const off = window.tzOffset(d); if(off) el.title = off; }
		el.setAttribute('data-tz-done', iso);
	});
};
document.addEventListener('DOMContentLoaded', () => window.formatTimestamps());

/*
	Publish the browser's timezone to the server via a cookie, so server-rendered pages (e.g. the file
	listing) can format timestamps in the user's zone through the DisplayTime bean, matching the
	helpers above. IANA zone ids contain only cookie-safe characters, so no encoding is needed.
*/
(function(){
	try {
		const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
		if(tz) document.cookie = 'dh-tz=' + tz + ';path=/;max-age=31536000;samesite=Lax';
	} catch(e) { /* no cookie; the server falls back to UTC */ }
})();

intellistream_datahub = {};

function updateSelectElementWithData(selectEle, data, value){
	if(value === undefined){
		value = selectEle.value;
	}
	selectEle.innerHTML = "";
	createSelectOptions(selectEle, data, value);
}
function createSelectElementFromDataset(name, data, value){
	if(value === undefined){
		value = 0;
	}
	const selectEle = document.createElement('SELECT');
	selectEle.id = name;
	selectEle.name = name;
	createSelectOptions(selectEle, data, value);
	return selectEle;
}

function createSelectOptions(selectEle, data, value) {
	for (let i = 0; i < data.length; i++) {
		const optionEle = document.createElement('OPTION');
		optionEle.value = data[i].id;
		optionEle.textContent = data[i].text;
		selectEle.appendChild(optionEle);
		if (Number(optionEle.value) === Number(value)) {
			selectEle.value = value;
		}
	}
}

function formData2JSON(formData){
	const obj = {};
	const data = formData.entries();
	let result = data.next();
	while (!result.done) {
		obj[result.value[0]] = result.value[1];
		result = data.next();
	}
	return JSON.stringify(obj);
}

function implementJSONRequest(formEle, responseDataCallback){
	formEle.addEventListener("submit", e => {
		e.preventDefault();
		fetch(formEle.action, {
			method: formEle.method,
			headers: {
				'Accept': 'application/json',
				'Content-Type': 'application/json'
			},
			body: formData2JSON(new FormData(formEle))
		})
		.then(response => response.json())
		.then(data => {
			handleJsonErrors(formEle, responseJSON);
			responseDataCallback(responseJSON);
		});
	});
}

function handleJsonErrors(formEle, data){
	if(data["errors"]){

	}
}

function createLabel(name, color){
	const getColor = labelName => {
		if(intellistream_datahub.labels !== undefined){
			const label = intellistream_datahub.labels.find(it => it.name === labelName);
			if(label){
				return label.color;
			}
		}
		return "#fff";
	};
	if(color === undefined){
		color = getColor(name);
	}
	const label = Object.assign(document.createElement('div'), {
		className: "resource-label",
		style: "border-color: " + color,
		innerHTML: `<div><span></span></div>`
	});
	label.querySelector('span').textContent = name;
	return label;
}

// Check if logged out
(function () {
	window.renderSignedOutDialog = function() {
		const greyBodyEle = document.createElement('DIV');
		greyBodyEle.id = "grey-body-overlay";
		document.body.appendChild(greyBodyEle);
		greyBodyEle.innerHTML = `
			<div class="overlay-container pad20">
				<h2>${$L('you.have.been.signed.out')}</h2>
				<div class="btns">
					<a href="` + document.location + `" class="dh-btn primary mtop20 w50">
						<span>${$L('ok')}</span>
					</a>
				</div>
			</div>`;
	}

	const loggedInInterval = setInterval(() => {
		fetch("/is-logged-in", {
			method: "HEAD"
		}).then(response => {
			if (response.status === 401) {
				clearInterval(loggedInInterval);
				renderSignedOutDialog();
			}
		});
	}, 30000);
})();

// For all help-modals found implement fetcher when hovering over the question icon
intellistream_datahub.invokeModalContent = function(){
	function fetchModalContent() {
		if(this.modalElement.children.length === 0){
			fetch('/modal-content/'+this.modalElement.getAttribute("data-content"))
				.then(response => {
					if(response.status === 200){
						return response.text();
					} else {
						return "<h2>Not Found! Check Attribute Name.</h2>";
					}
				})
				.then(html => {
					this.modalElement.innerHTML = html;
					const scriptElement = this.modalElement.querySelector('script');
					if(scriptElement){
						const scriptText = scriptElement.text;
						var script = document.createElement('script');
						script.text = scriptText;
						document.body.append(script);
					}
				});
		}
	}
	document.querySelectorAll('.help-modal').forEach(modalElement => {
		modalElement.closest('i').addEventListener('pointerenter', fetchModalContent.bind({modalElement}));
	});
};

function showImgAsCenterBodyModal(src){
	const imgObj = Object.assign(document.createElement('DIV'), {
		className: "centered-body-img",
		innerHTML: `<div>
				<i class="fa fa-fw fa-close"></i>
			</div>`
	});
	imgObj.append(Object.assign(document.createElement('IMG'), {src: src}));
	imgObj.querySelector('i').addEventListener('pointerdown', () => {
		imgObj.remove();
	})
	document.body.append(imgObj);
}

/*
 * Flash — small in-page notification banner. Other pages can call:
 *   Flash.warning("..."); Flash.error("..."); Flash.info("...");
 * or the generic Flash.show(type, message, opts).
 *
 * Inserts a `.flash.{type}.dh-flash-toast` div with a × close button. Auto-
 * dismisses after opts.duration ms (default 10000; pass 0 to keep it). A new
 * flash replaces any currently visible one. The default insertion point is
 * directly below `nav.sub-nav` (so it slots between sub-nav and the page
 * content). Pages without a sub-nav fall back to the top of `<main>` or
 * `<body>`. Pass opts.anchor as an HTMLElement to override.
 *
 * opts.details takes an array of strings rendered as a list under the message.
 * A policy rejection has to name every offending item rather than only say that
 * something was wrong, and one run-on sentence per item is unreadable.
 */
window.Flash = (function () {
	const TOAST_CLASS = 'dh-flash-toast';

	function findAnchor() {
		const subNav = document.querySelector('nav.sub-nav');
		if (subNav && subNav.parentNode) {
			return { parent: subNav.parentNode, before: subNav.nextSibling };
		}
		const main = document.querySelector('main.layout-body') || document.querySelector('main');
		if (main) {
			return { parent: main, before: main.firstChild };
		}
		return { parent: document.body, before: document.body.firstChild };
	}

	function show(type, message, opts) {
		opts = opts || {};
		const duration = opts.duration === undefined ? 10000 : opts.duration;

		document.querySelectorAll('.' + TOAST_CLASS).forEach(el => el.remove());

		const flash = document.createElement('div');
		flash.className = 'flash ' + (type || 'info') + ' ' + TOAST_CLASS;

		const text = document.createElement('span');
		text.className = 'dh-flash-text';
		text.textContent = message;

		if (Array.isArray(opts.details) && opts.details.length) {
			const list = document.createElement('ul');
			list.className = 'dh-flash-details';
			opts.details.forEach(detail => {
				const item = document.createElement('li');
				item.textContent = detail;   // never innerHTML: these carry user-supplied external ids
				list.appendChild(item);
			});
			text.appendChild(list);
		}

		const close = document.createElement('button');
		close.type = 'button';
		close.className = 'dh-flash-close';
		close.setAttribute('aria-label', 'Close');
		close.textContent = '×';
		close.addEventListener('click', () => flash.remove());

		flash.append(text, close);

		if (opts.anchor instanceof HTMLElement && opts.anchor.parentNode) {
			opts.anchor.parentNode.insertBefore(flash, opts.anchor.nextSibling);
		} else {
			const a = findAnchor();
			a.parent.insertBefore(flash, a.before);
		}

		if (duration > 0) {
			setTimeout(() => {
				if (flash.parentNode) flash.remove();
			}, duration);
		}

		return flash;
	}

	return {
		show: show,
		warning: (message, opts) => show('warning', message, opts),
		error:   (message, opts) => show('error',   message, opts),
		info:    (message, opts) => show('info',    message, opts)
	};
})();

/*
 * Api — browser-direct access to datahub-api.
 *
 * The console's Feign proxy (DatahubApi in this app) is deprecated for new work: pages call the
 * api themselves with a bearer token from the console's /token endpoint. Every caller needs the
 * same three things (the api host from <meta name="datahub-api-url">, a token, JSON headers), so
 * they live here once instead of being copied per page.
 *
 *   Api.post('/datasets/create', { items: [dataset] }).then(response => ...)
 *
 * The token is cached for a minute so a burst of calls doesn't hit /token once each. /token hands
 * back the session's current access token, so caching only narrows the window in which a refreshed
 * one is picked up.
 *
 * Responses come back unresolved, exactly as fetch returns them: what a non-2xx means is the
 * caller's business (the right-forms hand it to handleResponse, which knows the api's error
 * bodies), and only the caller knows whether the body is JSON at all.
 */
window.Api = (function () {
	let cachedToken = null;
	let cachedAt = 0;

	function url(path) {
		const meta = document.querySelector('meta[name="datahub-api-url"]');
		return (meta ? meta.content : '').replace(/\/+$/, '') + path;
	}

	function token() {
		if (cachedToken && (Date.now() - cachedAt) < 60000) return Promise.resolve(cachedToken);
		return fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(r => r.ok ? r.text() : Promise.reject(r.status))
			.then(t => { cachedToken = t; cachedAt = Date.now(); return t; });
	}

	function request(path, opts) {
		const o = opts || {};
		const hasBody = o.body !== undefined && o.body !== null;
		return token().then(t => fetch(url(path), {
			method: o.method || 'GET',
			headers: Object.assign(
				{ Accept: 'application/json', Authorization: 'Bearer ' + t },
				hasBody ? { 'Content-Type': 'application/json' } : {},
				o.headers || {}),
			body: hasBody ? JSON.stringify(o.body) : undefined,
			signal: AbortSignal.timeout(o.timeout === undefined ? 10000 : o.timeout)
		}));
	}

	return {
		url: url,
		token: token,
		request: request,
		get:  (path, opts)       => request(path, Object.assign({}, opts, { method: 'GET' })),
		post: (path, body, opts) => request(path, Object.assign({}, opts, { method: 'POST', body: body })),
		del:  (path, body, opts) => request(path, Object.assign({}, opts, { method: 'DELETE', body: body }))
	};
})();

function getByteSize(bytes, decimals = 2) {
	if (bytes === 0) return '0 Bytes';

	const k = 1024;
	const dm = decimals < 0 ? 0 : decimals;
	const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];

	const i = Math.floor(Math.log(bytes) / Math.log(k));

	return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

// Compact a large plain number with a K/M/B/T suffix (base 1000), e.g. 12477 -> "12.5K". Small
// values pass through with up to `decimals` places. Companion to getByteSize for non-byte units.
function abbreviateNumber(value, decimals = 1) {
	if (value == null || isNaN(value)) return '';
	const neg = value < 0;
	let n = Math.abs(Number(value));
	const units = ['', 'K', 'M', 'B', 'T'];
	let i = 0;
	while (n >= 1000 && i < units.length - 1) { n /= 1000; i++; }
	const p = Math.pow(10, decimals);
	const s = n >= 100 ? Math.round(n) : Math.round(n * p) / p;
	return (neg ? '-' : '') + s + units[i];
}