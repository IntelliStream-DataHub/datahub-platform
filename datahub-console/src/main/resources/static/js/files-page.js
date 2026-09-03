/**
 * Files listing page behaviour: the upload button, the right-click context menu (update / rename /
 * move / set dataset / delete), the file-info dialog and the file/folder update form.
 *
 * Bundled via the 'files' asset bundle (see files.manifest.js / assets.gradle). All page data comes
 * from the DOM - the current folder from the upload button's data-current-path, and the full node
 * records from the table's data-nodes-json (serialised server-side) - so this file carries no
 * Thymeleaf expressions and defines no globals. It depends on the right-form bundle (UploadFileForm,
 * UpdateFileForm, SetDataSetForm, DataSetList, ResourceSearch) and application.js ($L, getByteSize,
 * Flash, renderSignedOutDialog), all loaded before it.
 */
(function(){
	const uploadFileBtn = document.querySelector('[data-type="upload-file-btn"]');
	if (!uploadFileBtn) return;
	uploadFileBtn.addEventListener('pointerdown', () => {
		new UploadFileForm({
			path: uploadFileBtn.dataset.currentPath || '/',
			afterSave: data => {
				console.log(data);
			}
		}).render();
	});
})();

/* Right-click context menu on the files listing, plus the file-info dialog and update form. */
(function(){
	const table = document.querySelector('table.files-table');
	if (!table) return;
	const uploadBtn = document.querySelector('[data-type="upload-file-btn"]');
	const currentPath = (uploadBtn && uploadBtn.dataset.currentPath) || '/';

	// Full node records for the current folder (kept local to this IIFE, no global), read by the
	// file-info dialog and the update form. The JSON is rendered into the table's data attribute
	// server-side (HTML-attribute-escaped, so it is XSS-safe).
	// Re-parse on each lookup so it reflects the table's current contents — the folder listing OR
	// the search results (files/index.html swaps data-nodes-json while searching).
	const parseNodes = () => {
		try { return JSON.parse(table.dataset.nodesJson || '[]') || []; }
		catch (e) { return []; }
	};
	const findNode = externalId => parseNodes().find(n => n.externalId === externalId) || null;

	// Fetch a single file/folder record straight from datahub-api (browser-direct, bearer token), so
	// callers always see the saved state. Resolves to the node, or null on any failure.
	const fetchFileNode = externalId => {
		const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]').content.replace(/\/+$/, '');
		return fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(response => response.ok ? response.text() : null)
			.then(token => {
				if (!token) return null;
				return fetch(apiBaseUrl + '/files?externalId=' + encodeURIComponent(externalId), {
					headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json' }
				});
			})
			.then(response => (response && response.ok) ? response.json() : null)
			.then(json => (json && json.items && json.items[0]) ? json.items[0] : null)
			.catch(() => null);
	};

	// The shared context-menu widget from the app bundle (context-menu.js), loaded before this one.
	const ctxMenu = window.DhContextMenu;

	table.addEventListener('contextmenu', e => {
		const row = e.target.closest('tr[data-external-id]');
		if (!row) return;
		e.preventDefault();
		const node = {
			externalId: row.dataset.externalId,
			name: row.dataset.name,
			isFolder: row.dataset.nodeType === 'FOLDER',
			// Search-result rows sit elsewhere in the tree, so they carry their own parent path;
			// folder-listing rows fall back to the folder being viewed.
			parentPath: row.dataset.parentPath || currentPath,
			row: row
		};
		ctxMenu.open(e.clientX, e.clientY, [
			{ label: $L('update'), icon: 'fa-pen-to-square', action: () => openUpdateForm(node) },
			{ label: $L('rename'), icon: 'fa-pen', action: () => openRenameDialog(node) },
			{ label: $L('move'), icon: 'fa-folder-tree', action: () => openMoveDialog(node) },
			{ label: $L('set.dataset'), icon: 'fa-database', action: () => openSetDataSetForm(node) },
			{ label: $L('set.related.resources'), icon: 'fa-diagram-project', action: () => openSetRelatedResourcesForm(node) },
			{ label: $L('delete'), icon: 'fa-trash', danger: true, action: () => openDeleteDialog(node) }
		]);
	});

	// A click on a file name opens the info dialog instead of downloading. The anchor keeps
	// its download href as a no-JS fallback, so we only intercept when JS is running.
	table.addEventListener('click', e => {
		const link = e.target.closest('a[data-type="file-info"]');
		if (!link) return;
		e.preventDefault();
		const row = link.closest('tr[data-external-id]');
		if (row) openInfoDialog(row.dataset.externalId);
	});

	function openUpdateForm(node){
		fetchFileNode(node.externalId).then(fetched => {
			const full = fetched || findNode(node.externalId) || { externalId: node.externalId, name: node.name };
			new UpdateFileForm({ node: full }).render();
		});
	}

	function openInfoDialog(externalId){
		// Fetch the current record from datahub-api so the dialog reflects saved state (e.g. related
		// resources just changed); fall back to the cached node if the fetch fails.
		fetchFileNode(externalId).then(fetched => {
			const n = fetched || findNode(externalId);
			if (!n) { document.location = '/files/download/' + encodeURIComponent(externalId); return; }
			renderInfoDialog(n);
		});
	}

	function renderInfoDialog(n){
		const overlay = document.createElement('div');
		overlay.className = 'dh-modal-overlay';
		overlay.innerHTML =
			'<div class="dh-modal pad20 dh-file-info" role="dialog" aria-modal="true">'
			+ '<h2><i class="fa fa-fw fa-circle-info"></i><span></span></h2>'
			+ '<div class="dh-file-body">'
			+   '<dl class="dh-file-info-list"></dl>'
			+   '<div class="dh-file-preview" hidden></div>'
			+ '</div>'
			+ '<div class="btns flex-end mtop20">'
			+ '<button type="button" class="dh-btn secondary" data-act="close"><span></span></button>'
			+ '<a class="dh-btn primary" data-act="download"><i class="fa fa-fw fa-download"></i> <span></span></a>'
			+ '</div></div>';
		document.body.appendChild(overlay);

		overlay.querySelector('h2 span').textContent = $L('file.information');
		overlay.querySelector('[data-act="close"] span').textContent = $L('close');

		// Image files get an inline preview at the top. mimeType is sometimes null in the index, so
		// fall back to the filename extension. The <img> streams via the same authenticated download
		// route as the button; it hides itself if the image fails to load.
		if (n.type === 'FILE') {
			const isImage = (n.mimeType && n.mimeType.indexOf('image/') === 0)
				|| /\.(gif|png|jpe?g|webp|svg|bmp|avif|ico)$/i.test(n.name || '');
			if (isImage) {
				const preview = overlay.querySelector('.dh-file-preview');
				const img = document.createElement('img');
				img.alt = n.name || '';
				img.addEventListener('error', () => { preview.hidden = true; });
				img.src = '/files/download/' + encodeURIComponent(n.externalId);
				preview.appendChild(img);
				preview.hidden = false;
			}
		}

		const dl = overlay.querySelector('.dh-file-info-list');
		addInfoRow(dl, $L('file.name'), n.name);
		addInfoRow(dl, $L('external.id'), n.externalId);
		addInfoRow(dl, $L('main.path'), n.path);
		addInfoRow(dl, $L('file.type'), n.mimeType);
		if (n.type === 'FILE') addInfoRow(dl, $L('size'), getByteSize(n.size || 0));
		addInfoRow(dl, $L('source'), n.source);
		addInfoRow(dl, $L('description'), n.description);
		addInfoRow(dl, $L('main.dataset'), n.dataSetId != null ? ('#' + n.dataSetId) : null);
		addInfoRow(dl, $L('checksum'), n.checksum);
		addInfoRow(dl, $L('date.created'), formatDate(n.dateCreated));
		addInfoRow(dl, $L('last.modified'), formatDate(n.lastUpdated));
		addInfoRow(dl, $L('source.date.created'), formatDate(n.sourceDateCreated));
		addInfoRow(dl, $L('source.last.updated'), formatDate(n.sourceLastUpdated));
		addMetadataInfo(dl, n.metadata);
		addRelatedInfo(dl, n.relatedResources);

		const download = overlay.querySelector('[data-act="download"]');
		if (n.type === 'FILE') {
			download.href = '/files/download/' + encodeURIComponent(n.externalId);
			download.querySelector('span').textContent = $L('download');
		} else {
			download.remove(); // folders cannot be downloaded
		}

		function close(){ overlay.remove(); document.removeEventListener('keydown', onKey); }
		function onKey(ev){ if (ev.key === 'Escape') close(); }
		document.addEventListener('keydown', onKey);
		overlay.addEventListener('mousedown', ev => { if (ev.target === overlay) close(); });
		overlay.querySelector('[data-act="close"]').addEventListener('click', close);
	}

	// Append a <dt>/<dd> pair, skipping empty values. Values are set as text, never HTML.
	// Styling (font weight, spacing, wrapping) lives in the .dh-file-info-list CSS.
	function addInfoRow(dl, label, value){
		if (value == null || value === '' || value === 'null' || value === 'undefined') return;
		const dt = document.createElement('dt');
		dt.textContent = label;
		const dd = document.createElement('dd');
		dd.textContent = value;
		dl.appendChild(dt);
		dl.appendChild(dd);
	}

	// Metadata is always shown (part of the full file information); renders an empty state when
	// the file has no metadata rather than hiding the section.
	function addMetadataInfo(dl, metadata){
		const dt = document.createElement('dt');
		dt.textContent = $L('metadata');
		const dd = document.createElement('dd');
		const entries = Object.entries(metadata || {});
		if (!entries.length) {
			dd.classList.add('empty');
			dd.textContent = $L('none');
		} else {
			entries.forEach(([k, v]) => {
				const pair = document.createElement('div');
				pair.className = 'dh-file-info-meta';
				const key = document.createElement('strong');
				key.textContent = k + ': ';
				pair.appendChild(key);
				pair.appendChild(document.createTextNode(v));
				dd.appendChild(pair);
			});
		}
		dl.appendChild(dt);
		dl.appendChild(dd);
	}

	// Related resources are always shown; renders an empty state when there are none.
	function addRelatedInfo(dl, ids){
		const dt = document.createElement('dt');
		dt.textContent = $L('related.resources');
		const dd = document.createElement('dd');
		if (!ids || !ids.length) {
			dd.classList.add('empty');
			dd.textContent = $L('none');
		} else {
			dd.textContent = ids.join(', ');
		}
		dl.appendChild(dt);
		dl.appendChild(dd);
	}

	function formatDate(value){
		if (!value) return null;
		const d = new Date(value);
		return isNaN(d.getTime()) ? value : d.toLocaleString();
	}

	function openDeleteDialog(node){
		const overlay = document.createElement('div');
		overlay.className = 'dh-modal-overlay';
		const warning = node.isFolder ? '<p class="dh-modal-warning"></p>' : '';
		overlay.innerHTML =
			'<div class="dh-modal pad20" role="dialog" aria-modal="true">'
			+ '<h2></h2>'
			+ warning
			+ '<p class="dh-modal-instruction"></p>'
			+ '<input type="text" class="dh-modal-input" autocomplete="off" spellcheck="false" />'
			+ '<div class="btns flex-end mtop20">'
			+ '<button type="button" class="dh-btn secondary" data-act="cancel"><span></span></button>'
			+ '<button type="button" class="dh-btn primary dh-danger-btn" data-act="confirm" disabled><span></span></button>'
			+ '</div></div>';
		document.body.appendChild(overlay);

		overlay.querySelector('h2').textContent =
			node.isFolder ? $L('delete.folder.title') : $L('delete.file.title');
		if (node.isFolder) {
			overlay.querySelector('.dh-modal-warning').textContent = $L('delete.folder.warning');
		}
		overlay.querySelector('.dh-modal-instruction').textContent =
			$L('delete.type.name.to.confirm', null, [node.name]);
		overlay.querySelector('[data-act="cancel"] span').textContent = $L('cancel');
		overlay.querySelector('[data-act="confirm"] span').textContent = $L('delete');

		const input = overlay.querySelector('.dh-modal-input');
		const confirmBtn = overlay.querySelector('[data-act="confirm"]');
		const cancelBtn = overlay.querySelector('[data-act="cancel"]');

		function close(){ overlay.remove(); document.removeEventListener('keydown', onKey); }
		function onKey(e){ if (e.key === 'Escape') close(); }
		document.addEventListener('keydown', onKey);
		overlay.addEventListener('mousedown', e => { if (e.target === overlay) close(); });
		cancelBtn.addEventListener('click', close);
		input.addEventListener('input', () => { confirmBtn.disabled = input.value !== node.name; });
		confirmBtn.addEventListener('click', () => {
			if (input.value !== node.name) return;
			confirmBtn.disabled = true;
			cancelBtn.disabled = true;
			performDelete(node, close);
		});
		input.focus();
	}

	function performDelete(node, close){
		const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]').content;
		fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(r => {
				if (r.status === 401) { renderSignedOutDialog(); return null; }
				if (!r.ok) throw new Error('token');
				return r.text();
			})
			.then(token => {
				if (token === null) { close(); return; }
				return fetch(apiBaseUrl + '/files/delete', {
					method: 'POST',
					headers: {
						'Authorization': 'Bearer ' + token,
						'Content-Type': 'application/json',
						'Accept': 'application/json'
					},
					body: JSON.stringify({ items: [ { externalId: node.externalId } ] })
				}).then(resp => {
					close();
					if (resp.status === 401) { renderSignedOutDialog(); return; }
					if (resp.ok || resp.status === 204) {
						node.row.remove();
						Flash.info($L('deleted.name', null, [node.name]));
					} else {
						Flash.error($L('delete.failed'));
					}
				});
			})
			.catch(() => { close(); Flash.error($L('delete.failed')); });
	}

	function openSetDataSetForm(node){
		new SetDataSetForm({
			node: node,
			afterSave: () => document.location.reload()
		}).render();
	}

	function openSetRelatedResourcesForm(node){
		// Fetch the current record (with relatedResources) straight from the api; no page reload on
		// save, since re-opening fetches fresh again.
		fetchFileNode(node.externalId).then(fetched => {
			const full = fetched || findNode(node.externalId) || { externalId: node.externalId, name: node.name };
			new SetRelatedResourcesForm({ node: full }).render();
		});
	}

	function openRenameDialog(node){
		openInputModal({
			title: $L('rename'),
			label: $L('new.name'),
			value: node.name,
			applyLabel: $L('rename'),
			onApply: (val, close, reenable) =>
				requestFileUpdate({ externalId: node.externalId, name: val })
					.then(resp => handleUpdateResponse(resp, close, reenable))
					.catch(() => { reenable(); Flash.error($L('update.failed')); })
		});
	}

	function openMoveDialog(node){
		openInputModal({
			title: $L('move'),
			label: $L('move.to.folder'),
			value: node.parentPath,
			placeholder: '/',
			applyLabel: $L('move'),
			onApply: (val, close, reenable) =>
				requestFileUpdate({ externalId: node.externalId, path: val })
					.then(resp => handleUpdateResponse(resp, close, reenable))
					.catch(() => { reenable(); Flash.error($L('update.failed')); })
		});
	}

	function handleUpdateResponse(resp, close, reenable){
		if (!resp) { close(); return; } // signed out
		if (resp.ok) {
			close();
			document.location.reload();
		} else if (resp.status === 409) {
			reenable();
			Flash.error($L('target.already.exists'));
		} else {
			reenable();
			Flash.error(window.LimitErrors.fromStatus(resp.status) || $L('update.failed'));
		}
	}

	// Generic single-input modal (used by Rename and Move).
	function openInputModal(opts){
		const overlay = document.createElement('div');
		overlay.className = 'dh-modal-overlay';
		overlay.innerHTML =
			'<div class="dh-modal pad20" role="dialog" aria-modal="true">'
			+ '<h2></h2>'
			+ '<p class="dh-modal-instruction"></p>'
			+ '<input type="text" class="dh-modal-input" autocomplete="off" spellcheck="false" />'
			+ '<div class="btns flex-end mtop20">'
			+ '<button type="button" class="dh-btn secondary" data-act="cancel"><span></span></button>'
			+ '<button type="button" class="dh-btn primary" data-act="confirm"><span></span></button>'
			+ '</div></div>';
		document.body.appendChild(overlay);
		overlay.querySelector('h2').textContent = opts.title;
		overlay.querySelector('.dh-modal-instruction').textContent = opts.label || '';
		overlay.querySelector('[data-act="cancel"] span').textContent = $L('cancel');
		overlay.querySelector('[data-act="confirm"] span').textContent = opts.applyLabel || $L('datahub.save');
		const input = overlay.querySelector('.dh-modal-input');
		input.value = opts.value || '';
		if (opts.placeholder) input.placeholder = opts.placeholder;
		const confirmBtn = overlay.querySelector('[data-act="confirm"]');
		const cancelBtn = overlay.querySelector('[data-act="cancel"]');
		function close(){ overlay.remove(); document.removeEventListener('keydown', onKey); }
		function onKey(e){ if (e.key === 'Escape') close(); }
		document.addEventListener('keydown', onKey);
		overlay.addEventListener('mousedown', e => { if (e.target === overlay) close(); });
		cancelBtn.addEventListener('click', close);
		function apply(){
			const val = input.value.trim();
			if (!val) return;
			confirmBtn.disabled = true;
			cancelBtn.disabled = true;
			opts.onApply(val, close, () => { confirmBtn.disabled = false; cancelBtn.disabled = false; });
		}
		confirmBtn.addEventListener('click', apply);
		input.addEventListener('keydown', e => { if (e.key === 'Enter') apply(); });
		input.focus();
		input.select();
	}
})();

/**
 * Deleted-files (trash) tab: list soft-deleted files (GET /files/trash) and restore them
 * (POST /files/restore). Talks to datahub-api directly with a bearer from /token — same pattern as
 * the file-info fetch. A trashed file's externalId is DELETED_<checksum>_<origId>_<epochMillis>, so
 * the trailing _<epoch> gives the deletion time; name and path are the pre-deletion values.
 */
(function(){
	const meta = document.querySelector('meta[name="datahub-api-url"]');
	const tabFiles = document.querySelector('[data-type="tab-files"]');
	const tabTrash = document.querySelector('[data-type="tab-trash"]');
	const viewTrash = document.querySelector('#view-trash');
	if (!meta || !tabFiles || !tabTrash || !viewTrash) return;

	const apiBase = meta.content.replace(/\/+$/, '');
	const i18n = window.FILES_I18N || { restore: 'Restore', restoreFail: 'Could not restore', loadFail: 'Could not load deleted files' };
	const filesTable = document.querySelector('table.files-table[data-nodes-json]');
	const filesActions = document.querySelector('.files-content > .flex-container.left-right');
	const trashBody = viewTrash.querySelector('[data-type="trash-body"]');
	const trashEmpty = viewTrash.querySelector('[data-type="trash-empty"]');

	const token = () => fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
		.then(r => r.ok ? r.text() : null);
	const flashErr = m => { if (typeof Flash !== 'undefined' && Flash.error) Flash.error(m); };
	const flashOk = m => { if (typeof Flash !== 'undefined' && Flash.info) Flash.info(m); };

	function showFiles(){
		tabFiles.classList.add('primary'); tabTrash.classList.remove('primary');
		viewTrash.hidden = true;
		if (filesTable) filesTable.hidden = false;
		if (filesActions) filesActions.hidden = false;
	}
	function showTrash(){
		tabTrash.classList.add('primary'); tabFiles.classList.remove('primary');
		if (filesTable) filesTable.hidden = true;
		if (filesActions) filesActions.hidden = true;
		viewTrash.hidden = false;
		loadTrash();
	}
	function deletedDate(externalId){
		const m = /_(\d+)$/.exec(externalId || '');
		if (!m) return '';
		const d = new Date(Number(m[1]));
		return isNaN(d.getTime()) ? '' : d.toLocaleString();
	}
	function loadTrash(){
		trashBody.innerHTML = '';
		trashEmpty.hidden = true;
		token().then(t => {
			if (!t) throw new Error('no token');
			return fetch(apiBase + '/files/trash', { headers: { Authorization: 'Bearer ' + t, Accept: 'application/json' } });
		}).then(r => {
			if (!r.ok) throw new Error('http ' + r.status);
			return r.json();
		}).then(data => {
			const items = (data && data.items) || [];
			if (!items.length) { trashEmpty.hidden = false; return; }
			items.forEach(n => trashBody.appendChild(trashRow(n)));
		}).catch(() => { flashErr(i18n.loadFail || i18n.restoreFail); });
	}
	function trashRow(n){
		const tr = document.createElement('tr');
		const nameTd = document.createElement('td');
		const icon = document.createElement('i'); icon.className = 'fa-solid fa-file-lines';
		const name = document.createElement('span'); name.textContent = n.name || '';
		nameTd.appendChild(icon); nameTd.appendChild(document.createTextNode(' ')); nameTd.appendChild(name);
		const pathTd = document.createElement('td'); pathTd.className = 'fs-path'; pathTd.textContent = n.path || '';
		const whenTd = document.createElement('td'); whenTd.textContent = deletedDate(n.externalId);
		const actTd = document.createElement('td');
		const btn = document.createElement('button');
		btn.type = 'button'; btn.className = 'dh-btn small'; btn.textContent = i18n.restore;
		btn.addEventListener('click', () => restore(n, tr, btn));
		actTd.appendChild(btn);
		[nameTd, pathTd, whenTd, actTd].forEach(td => tr.appendChild(td));
		return tr;
	}
	function restore(n, tr, btn){
		btn.disabled = true;
		token().then(t => {
			if (!t) { btn.disabled = false; return; }
			return fetch(apiBase + '/files/restore', {
				method: 'POST',
				headers: { Authorization: 'Bearer ' + t, 'Content-Type': 'application/json', Accept: 'application/json' },
				body: JSON.stringify({ items: [{ externalId: n.externalId }] })
			}).then(r => {
				if (r.ok) {
					tr.remove();
					if (!trashBody.children.length) trashEmpty.hidden = false;
					flashOk((n.name || '') + ' → ' + i18n.restore.toLowerCase() + 'd');
					return;
				}
				btn.disabled = false;
				// A limit refusal is a whole sentence of its own; anything else keeps the old
				// "could not restore: <server text>" shape.
				return r.text().then(msg => {
					const limit = window.LimitErrors.fromStatus(r.status, msg);
					flashErr(limit || (i18n.restoreFail + (msg ? ': ' + msg : '')));
				});
			});
		}).catch(() => { btn.disabled = false; flashErr(i18n.restoreFail); });
	}

	tabFiles.addEventListener('click', showFiles);
	tabTrash.addEventListener('click', showTrash);
})();
