/**
 * Shared base for the file upload/update right-forms: the metadata key/value editor and the
 * related-resources chip picker (which uses the ResourceSearch list).
 */
class FileFormAbstract extends DatasetFormAbstract {

	escAttr(s) {
		return String(s == null ? '' : s)
			.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
	}

	// metadata: an editable key/value table (matching the resource form's layout) that serialises to
	// a JSON object.
	metadataRowsHtml(metadata) {
		const rows = Object.entries(metadata || {}).map(([k, v]) => this.metadataRowHtml(k, v)).join('');
		return `
			<table class="metadata w100">
				<thead>
				<tr>
					<th>${$L('key')}</th>
					<th>${$L('value')}</th>
					<th class="text-right">
						<button type="button" class="dh-btn primary small" data-type="metadata-add" title="${$L('add')}"><i class="fa fa-fw fa-plus"></i></button>
					</th>
				</tr>
				</thead>
				<tbody data-type="metadata-rows">${rows}</tbody>
			</table>
		`;
	}

	metadataRowHtml(key, value) {
		return `
			<tr class="metadata-row">
				<td><input type="text" class="metadata-key" placeholder="${$L('key')}" value="${this.escAttr(key)}"/></td>
				<td><input type="text" class="metadata-value" placeholder="${$L('value')}" value="${this.escAttr(value)}"/></td>
				<td class="text-right"><button type="button" class="dh-btn secondary small" data-type="metadata-remove"><i class="fa fa-fw fa-trash"></i></button></td>
			</tr>
		`;
	}

	wireMetadataRows(scope) {
		const rows = scope.querySelector('[data-type="metadata-rows"]');
		scope.querySelector('[data-type="metadata-add"]').addEventListener('click', () => {
			rows.insertAdjacentHTML('beforeend', this.metadataRowHtml('', ''));
		});
		scope.addEventListener('click', e => {
			const remove = e.target.closest('[data-type="metadata-remove"]');
			if (remove) remove.closest('.metadata-row').remove();
		});
	}

	readMetadataRows(scope) {
		const out = {};
		scope.querySelectorAll('.metadata-row').forEach(row => {
			const key = row.querySelector('.metadata-key').value.trim();
			if (key) out[key] = row.querySelector('.metadata-value').value;
		});
		return out;
	}

	// related resources: chips of resource ids, found via the ResourceSearch picker. The button
	// searches (it doesn't create), so it mirrors the choose-dataset button: a right-aligned
	// secondary search button.
	relatedResourcesHtml(ids) {
		const chips = (ids || []).map(id => this.resourceChipHtml(id)).join('');
		return `
			<div class="flex-container left-right" style="align-items:flex-start;">
				<div data-type="related-resources" style="display:flex;flex-wrap:wrap;gap:6px;">${chips}</div>
				<button type="button" class="dh-btn secondary small" data-type="related-add"><i class="fa fa-fw fa-search"></i></button>
			</div>
		`;
	}

	resourceChipHtml(id, name) {
		return `
			<span class="resource-chip" data-id="${id}">
				<span class="resource-chip-label">${this.escAttr(name || ('#' + id))}</span>
				<button type="button" data-type="resource-remove" class="dh-btn-plain"><i class="fa fa-fw fa-xmark"></i></button>
			</span>
		`;
	}

	wireRelatedResources(scope) {
		const container = scope.querySelector('[data-type="related-resources"]');
		scope.querySelector('[data-type="related-add"]').addEventListener('click', () => {
			const picker = new ResourceSearch({ multiSelect: true });
			picker.setConfirmCallback(selected => {
				(selected || []).forEach(r => this.addResourceChip(container, r.id, r.name));
			});
			picker.render();   // ResourceSearch doesn't open in its constructor; render() shows the picker
		});
		scope.addEventListener('click', e => {
			const remove = e.target.closest('[data-type="resource-remove"]');
			if (remove) remove.closest('.resource-chip').remove();
		});
	}

	addResourceChip(container, id, name) {
		if (container.querySelector('.resource-chip[data-id="' + id + '"]')) return; // dedupe
		container.insertAdjacentHTML('beforeend', this.resourceChipHtml(id, name));
	}

	readRelatedResources(scope) {
		// Keep ids as strings: Number() rounds 64-bit ids above 2^53. The server fields are
		// Longs and Jackson coerces the JSON strings.
		return Array.from(scope.querySelectorAll('[data-type="related-resources"] .resource-chip'))
			.map(chip => chip.dataset.id);
	}

	// Chips pre-filled from stored ids show a "#id" label; look the resources up by id and replace
	// each label with the resource name.
	resolveRelatedResourceNames(scope) {
		const ids = Array.from(scope.querySelectorAll('[data-type="related-resources"] .resource-chip'))
			.map(chip => chip.dataset.id).filter(Boolean);
		if (!ids.length) return;
		// Fetch the resources straight on datahub-api (browser-direct, bearer token), not the console
		// proxy, the same pattern the file/dataset search uses.
		const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]').content.replace(/\/+$/, '');
		fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(response => {
				if (response.status === 401) { renderSignedOutDialog(); return null; }
				if (!response.ok) throw new Error('token');
				return response.text();
			})
			.then(token => {
				if (!token) return null;
				return fetch(apiBaseUrl + '/resources/byids', {
					method: 'POST',
					headers: {
						'Authorization': 'Bearer ' + token,
						'Accept': 'application/json',
						'Content-Type': 'application/json'
					},
					body: JSON.stringify({ items: ids.map(id => ({ id: id })) })
				});
			})
			.then(response => (response && response.ok) ? response.json() : null)
			.then(json => {
				if (!json || !json.items) return;
				json.items.forEach(res => {
					const label = scope.querySelector('.resource-chip[data-id="' + res.id + '"] .resource-chip-label');
					if (label && res.name) label.textContent = res.name;
				});
			})
			.catch(() => { /* leave the #id labels in place */ });
	}

}

class UploadFileForm extends FileFormAbstract {

	constructor(obj) {
		super(obj);
		this.title = $L('upload.file');
		this.validPathRegex = new RegExp(/^(\/|(\/[a-z0-9._-]+)+)$/);
		// The folder the user is currently browsing, used to pre-fill the path field.
		this.defaultPath = obj.path || '';
	}

	getFormFields() {
		return `
			${this.getEntityIdField()}

			<label>${$L('main.path')}</label>
			<input class="${this.fieldError('path')}"
					type="text" name="path"
					value="${this.getFormProperty('path', this.defaultPath)}"
					placeholder="${$L('write.the.upload.path.here')}" tabindex="20"/>

					<input type="file" name="file" hidden=""/>
					<div class="file-upload-info flex-container left-c-right">
						<i class="fa fa-fw fa-cloud-arrow-up file-upload-icon"></i>
						<button type="button" class="dh-btn secondary" data-type="browse-file"><i class="fa fa-fw fa-folder-open"></i> ${$L('browse.file')}</button>
						<span class="file-name">${$L('no.file.selected')}</span>
						<span class="file-size"></span>
					</div>
			<label>${$L('external.id')}</label>
			<input class="${this.fieldError('externalId')}"
					type="text" name="externalId"
					required
					pattern="${this.externalIdPattern()}"
					value="${this.getFormProperty('externalId')}"
					placeholder="${$L('write.external.id.here')}" tabindex="40"/>
			<p class="field-hint">${$L('external.id.charset.help')}</p>

			<label>${$L('file.name')}</label>
			<input class="${this.fieldError('name')}"
					type="text" name="name"
					required
					pattern="[^/]+"
					value="${this.getFormProperty('name')}"
					placeholder="${$L('write.external.id.here')}" tabindex="50"/>

			<label>${$L('description')}</label>
			<textarea class="w100 ${this.fieldError('description')}" name="description" maxlength="${FieldLimits.DESCRIPTION_MAX}" placeholder="${$L('write.description.here')}..." tabindex="70"></textarea>

			<label>${$L('source')}</label>
			<input type="text" name="source" value="" placeholder="${$L('write.source.here')}" tabindex="72"/>

			<label>${$L('metadata')}</label>
			${this.metadataRowsHtml({})}

			<label>${$L('related.resources')}</label>
			${this.relatedResourcesHtml([])}

			<label>${$L('choose.dataset')}</label>
			<div class="flex-container left-right">
				<input type="hidden" name="dataSet" value="${this.getFormProperty('dataSet')}"/>
				<span data-type="data-set" class="mright10"></span>
				<button type="button" class="dh-btn secondary small" data-type="data-set-list-btn" tabindex="80">
					<i class="fa fa-fw fa-search"></i>
				</button>
			</div>
		`;
	}

	render() {
		super.render();
		this.fileUploadInput = this.formElement.querySelector('input[name="file"]');
		this.externalIdInput = this.formElement.querySelector('input[name="externalId"]');
		this.filenameInput = this.formElement.querySelector('input[name="name"]');
		this.addUploadFileEvent();

		// Abort an in-flight upload if the form is closed mid-transfer.
		this.cancelButtonElement.addEventListener('click', () => {
			if (this.activeXhr) {
				this.activeXhr.abort();
			}
		});

		// Folder path validation
		this.formElement.querySelector('input[name="path"]').addEventListener('input', e => {
			if(!this.validateFolderPath(e.target.value)){
				e.target.classList.add('error');
			} else {
				e.target.classList.remove('error');
			}
		});

		// Dataset picker — same searchable list the timeseries form uses.
		this.formElement.querySelector('[data-type="data-set-list-btn"]').addEventListener('click', () => {
			const list = new DataSetList({});
			list.setConfirmCallback(selected => {
				if (selected && selected[0]) {
					this.setDataSet(selected[0]);
				}
			});
		});

		this.wireMetadataRows(this.formElement);
		this.wireRelatedResources(this.formElement);

		this.submitButtonElement.firstElementChild.textContent = $L('upload');
	}

	setDataSet(dataSet) {
		this.formElement.querySelector('[name="dataSet"]').value = dataSet.id;
		this.formElement.querySelector('[data-type="data-set"]').textContent = dataSet.name;
	}

	addUploadFileEvent() {
		const fileNameSpan = this.mainElement.querySelector('.file-upload-info .file-name');
		const fileSizeSpan = this.mainElement.querySelector('.file-upload-info .file-size');

		this.fileUploadInput.addEventListener('change', e => {
			if (this.fileUploadInput.files.length > 0) {
				const f = this.fileUploadInput.files[0];

				fileNameSpan.textContent = f.name;
				this.filenameInput.value = f.name;
				fileSizeSpan.textContent = getByteSize(f.size);
				this.externalIdInput.value = f.name
					.toLowerCase()
					.replaceAll(/[\s-.]/g, '_');
				const flashField = this.mainElement.querySelector('div.flash');
				if(flashField){
					flashField.remove();
				}
			} else {
				fileNameSpan.textContent = $L('no.file.selected');
			}
		});

		const browseButton = this.mainElement.querySelector('[data-type="browse-file"]');

		if (browseButton && this.fileUploadInput) {
			browseButton.addEventListener('click', () => {
				this.fileUploadInput.click();
			});
		}
	}

	submit(){
		this.uploadFile();
	}

	uploadFile() {
		// Guard against a second submit while an upload is running (the base
		// form re-enables the submit button on a timer).
		if (this.activeXhr) {
			return;
		}

		const file = this.fileUploadInput.files[0];
		if (!file) {
			alert($L('please.select.a.file.to.upload.first'));
			return;
		}

		const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]').content;
		const filename = this.filenameInput.value || file.name;

		this.submitButtonElement.disabled = true;
		this._uploadCancelled = false;
		this.clearInlineError();
		this.buildProgressUI(filename);

		// Upload straight to datahub-api (CORS-enabled) instead of proxying the
		// multipart body through the console. We fetch a fresh bearer token from
		// the console session, then PUT the file directly to the API.
		fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(response => {
				if (response.status === 401) {
					renderSignedOutDialog();
					this.resetUpload();
					return null;
				}
				if (!response.ok) {
					throw new Error('token');
				}
				return response.text();
			})
			.then(token => {
				if (token === null || this._uploadCancelled) return;
				this.sendUpload(apiBaseUrl, token, file, filename);
			})
			.catch(() => {
				this.handleUploadError($L('upload.failed'));
			});
	}

	sendUpload(apiBaseUrl, token, file, filename) {
		// The file content is the raw PUT body; all metadata goes in headers, so the API
		// validates and authorises before reading the body. Path segments and the description are
		// percent-encoded so non-ASCII / spaces survive the HTTP headers. The Content-Type is the
		// file's MIME type; application/octet-stream makes the API auto-detect it (via Tika) from
		// the stored bytes.
		const path = this.formElement.querySelector('input[name="path"]').value;
		const fullPath = (path.endsWith('/') ? path : path + '/') + filename;
		const encodedPath = fullPath.split('/').map(encodeURIComponent).join('/');
		const dataSetId = this.formElement.querySelector('[name="dataSet"]').value;
		const description = this.formElement.querySelector('textarea[name="description"]').value;

		const xhr = new XMLHttpRequest();
		this.activeXhr = xhr;
		xhr.open('PUT', apiBaseUrl + '/files');
		xhr.setRequestHeader('Authorization', 'Bearer ' + token);
		xhr.setRequestHeader('Accept', 'application/json');
		xhr.setRequestHeader('X-Datahub-Path', encodedPath);
		if (this.externalIdInput.value) {
			xhr.setRequestHeader('X-Datahub-External-Id', encodeURIComponent(this.externalIdInput.value));
		}
		if (dataSetId) {
			xhr.setRequestHeader('X-Datahub-Dataset-Id', dataSetId);
		}
		if (description) {
			xhr.setRequestHeader('X-Datahub-Description', encodeURIComponent(description));
		}
		const source = this.formElement.querySelector('input[name="source"]').value;
		if (source) {
			xhr.setRequestHeader('X-Datahub-Source', encodeURIComponent(source));
		}
		const metadata = this.readMetadataRows(this.formElement);
		if (Object.keys(metadata).length) {
			xhr.setRequestHeader('X-Datahub-Metadata', encodeURIComponent(JSON.stringify(metadata)));
		}
		const related = this.readRelatedResources(this.formElement);
		if (related.length) {
			xhr.setRequestHeader('X-Datahub-Related-Resources', encodeURIComponent(JSON.stringify(related)));
		}
		xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');

		let lastTime = performance.now();
		let lastLoaded = 0;
		let speed = 0; // bytes/sec, exponentially smoothed

		xhr.upload.addEventListener('progress', e => {
			if (!e.lengthComputable) return;
			const now = performance.now();
			const dt = (now - lastTime) / 1000;
			if (dt >= 0.15) {
				const instant = (e.loaded - lastLoaded) / dt;
				speed = speed === 0 ? instant : (speed * 0.6 + instant * 0.4);
				lastTime = now;
				lastLoaded = e.loaded;
			}
			const percent = Math.floor((e.loaded / e.total) * 100);
			this.updateProgressUI(percent, e.loaded, e.total, speed);
		});

		xhr.addEventListener('load', () => {
			this.activeXhr = null;
			if (xhr.status >= 200 && xhr.status < 300) {
				this.updateProgressUI(100, file.size, file.size, speed);
				let data = null;
				try {
					data = JSON.parse(xhr.responseText);
				} catch (ignored) { /* fall through to the plain listing */ }
				if (data && data.items && data.items[0] && data.items[0].path) {
					const fullPath = data.items[0].path;
					const folder = fullPath.substring(0, fullPath.lastIndexOf('/'));
					// The listing is path-routed (/files/list/<folder>), so append the
					// folder as a path segment. An empty folder lands on the root listing.
					document.location = '/files/list' + folder;
				} else {
					document.location = '/files/list';
				}
			} else {
				// The raw body is a problem document, so show what it means rather than its JSON.
				this.handleUploadError(
					window.LimitErrors.fromStatus(xhr.status, xhr.responseText)
					|| $L('upload.failed'));
			}
		});

		xhr.addEventListener('error', () => {
			this.activeXhr = null;
			this.handleUploadError($L('upload.failed'));
		});

		xhr.addEventListener('abort', () => {
			this.resetUpload();
		});

		xhr.send(file);
	}

	buildProgressUI(filename) {
		const main = this.formElement.querySelector('main');
		const existing = main.querySelector('.upload-progress');
		if (existing) existing.remove();

		const section = document.createElement('div');
		section.className = 'upload-progress';
		section.innerHTML = `
			<div class="upload-progress-head">
				<span class="upload-progress-filename"></span>
				<div class="upload-progress-actions">
					<span class="upload-progress-percent">0%</span>
					<button type="button" class="upload-progress-cancel" title="${$L('cancel')}" aria-label="${$L('cancel')}">
						<i class="fa fa-fw fa-xmark"></i>
					</button>
				</div>
			</div>
			<div class="upload-progress-track">
				<div class="upload-progress-bar"></div>
			</div>
			<div class="upload-progress-stats">
				<span class="upload-progress-transferred"></span>
				<span class="upload-progress-speed"></span>
			</div>
		`;
		section.querySelector('.upload-progress-filename').textContent = filename;
		section.querySelector('.upload-progress-cancel')
			.addEventListener('click', () => this.abortUpload());
		main.appendChild(section);

		this.progressEls = {
			section: section,
			percent: section.querySelector('.upload-progress-percent'),
			bar: section.querySelector('.upload-progress-bar'),
			transferred: section.querySelector('.upload-progress-transferred'),
			speed: section.querySelector('.upload-progress-speed')
		};
	}

	abortUpload() {
		// Cancel the in-flight upload but keep the form open so the user can
		// adjust and retry. The flag also stops an upload whose token fetch is
		// still pending (no xhr yet). xhr.abort() fires the 'abort' handler,
		// which resets the UI; otherwise we clear the progress UI directly.
		this._uploadCancelled = true;
		if (this.activeXhr) {
			this.activeXhr.abort();
		} else {
			this.resetUpload();
		}
	}

	updateProgressUI(percent, loaded, total, bytesPerSecond) {
		if (!this.progressEls) return;
		this.progressEls.percent.textContent = percent + '%';
		this.progressEls.bar.style.width = percent + '%';
		this.progressEls.transferred.textContent = getByteSize(loaded) + ' / ' + getByteSize(total);
		this.progressEls.speed.textContent = bytesPerSecond > 0 ? getByteSize(bytesPerSecond) + '/s' : '';
	}

	resetUpload() {
		this.activeXhr = null;
		this.submitButtonElement.disabled = false;
		if (this.progressEls && this.progressEls.section) {
			this.progressEls.section.remove();
		}
		this.progressEls = null;
	}

	handleUploadError(message) {
		this.resetUpload();
		this.showInlineError(message);
	}

	showInlineError(message) {
		const main = this.formElement.querySelector('main');
		let flash = main.querySelector('.flash.error.upload-error');
		if (!flash) {
			flash = document.createElement('div');
			flash.className = 'flash error upload-error';
			main.insertBefore(flash, main.firstChild);
		}
		flash.textContent = message;
	}

	clearInlineError() {
		const main = this.formElement.querySelector('main');
		const flash = main.querySelector('.flash.error.upload-error');
		if (flash) flash.remove();
	}

	validateFolderPath(path){
		// Check if the input is a non-empty string
		if (typeof path !== 'string' || path.length === 0) {
			return false;
		}

		// This regex enforces all the specified rules.
		// Explanation:
		// ^                   - Asserts the start of the string.
		// /                   - Matches the literal forward slash at the beginning.
		// (                   - Starts a group for the rest of the path.
		//   |                 - Acts as an OR.
		//                     - The first alternative is empty, allowing for just the root path '/'.
		//   (                 - The second alternative is for paths with segments.
		//     [a-z0-9._-]+    - Matches the first segment (directory/file name).
		//                     - It must contain one or more of the allowed characters.
		//     (               - Starts a group for any subsequent segments.
		//       /             - Each subsequent segment must be preceded by a '/'.
		//       [a-z0-9._-]+  - Matches the segment name.
		//     )*              - The group for subsequent segments can appear zero or more times.
		//   )
		// )
		// $                   - Asserts the end of the string, preventing trailing slashes.

		return this.validPathRegex.test(path);
	}

}

/**
 * Update a file or folder straight on datahub-api (rename / move / set dataset / description),
 * using a fresh bearer token from the console session. `payload` is the JSON body for
 * POST /files/update (always include externalId). Resolves to the fetch Response, or null when the
 * session has expired (the signed-out dialog is shown in that case).
 */
function requestFileUpdate(payload) {
	const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]').content;
	return fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
		.then(response => {
			if (response.status === 401) { renderSignedOutDialog(); return null; }
			if (!response.ok) throw new Error('token');
			return response.text();
		})
		.then(token => {
			if (token === null) return null;
			return fetch(apiBaseUrl + '/files/update', {
				method: 'POST',
				headers: {
					'Authorization': 'Bearer ' + token,
					'Content-Type': 'application/json',
					'Accept': 'application/json'
				},
				body: JSON.stringify(payload)
			});
		});
}

/**
 * Right-menu form to assign a dataset to a file or folder. Reuses the same DataSetList search the
 * timeseries form uses, and submits directly to datahub-api via requestFileUpdate.
 */
class SetDataSetForm extends DatasetFormAbstract {

	constructor(obj) {
		super(obj);
		this.title = $L('set.dataset');
		this.node = obj.node; // { externalId, name, isFolder }
	}

	getFormFields() {
		const warning = this.node.isFolder
			? `<div class="flash info" style="margin-top:16px;">${$L('set.dataset.inherit.warning')}</div>`
			: '';
		return `
			<p>${$L('set.dataset.for')}: <strong class="dh-target-name"></strong></p>
			<label>${$L('choose.dataset')}</label>
			<div class="flex-container left-right">
				<input type="hidden" name="dataSetId" value=""/>
				<span data-type="data-set" class="mright10"></span>
				<button type="button" class="dh-btn secondary small" data-type="data-set-list-btn">
					<i class="fa fa-fw fa-search"></i>
				</button>
			</div>
			${warning}
		`;
	}

	render() {
		super.render();
		// Set the (user-controlled) name as text, never as HTML.
		this.formElement.querySelector('.dh-target-name').textContent = this.node.name;

		this.formElement.querySelector('[data-type="data-set-list-btn"]').addEventListener('click', () => {
			const list = new DataSetList({});
			list.setConfirmCallback(selected => {
				if (selected && selected[0]) {
					this.formElement.querySelector('[name="dataSetId"]').value = selected[0].id;
					this.formElement.querySelector('[data-type="data-set"]').textContent = selected[0].name;
				}
			});
		});

		this.submitButtonElement.firstElementChild.textContent = $L('datahub.save');
	}

	submit() {
		const dataSetId = this.formElement.querySelector('[name="dataSetId"]').value;
		if (!dataSetId) {
			alert($L('please.choose.a.dataset'));
			return;
		}
		this.submitButtonElement.disabled = true;
		requestFileUpdate({ externalId: this.node.externalId, dataSetId: dataSetId })
			.then(response => {
				if (!response) return; // signed out
				if (response.ok) {
					if (this.afterSaveFn) this.afterSaveFn();
					this.cancelButtonElement.dispatchEvent(new Event('click'));
					Flash.info($L('dataset.updated'));
				} else {
					this.submitButtonElement.disabled = false;
					Flash.error($L('update.failed'));
				}
			})
			.catch(() => {
				this.submitButtonElement.disabled = false;
				Flash.error($L('update.failed'));
			});
	}

}

/**
 * Right-menu form to edit just a file or folder's related resources. Pre-filled from the node and
 * submitted to POST /files/update.
 */
class SetRelatedResourcesForm extends FileFormAbstract {

	constructor(obj) {
		super(obj);
		this.title = $L('set.related.resources');
		this.node = obj.node;
		this.afterSaveFn = obj.afterSave;
	}

	getFormFields() {
		return `
			<p><strong class="dh-target-name"></strong></p>
			<label>${$L('related.resources')}</label>
			${this.relatedResourcesHtml(this.node.relatedResources || [])}
		`;
	}

	render() {
		super.render();
		this.formElement.querySelector('.dh-target-name').textContent = this.node.name;
		this.wireRelatedResources(this.formElement);
		this.resolveRelatedResourceNames(this.formElement);   // turn pre-filled #id chips into names
		this.submitButtonElement.firstElementChild.textContent = $L('datahub.save');
	}

	submit() {
		this.submitButtonElement.disabled = true;
		requestFileUpdate({
			externalId: this.node.externalId,
			relatedResources: this.readRelatedResources(this.formElement)
		})
			.then(response => {
				if (!response) return; // signed out
				if (response.ok) {
					if (this.afterSaveFn) this.afterSaveFn();
					this.cancelButtonElement.dispatchEvent(new Event('click'));
					Flash.info($L('file.updated'));
				} else {
					this.submitButtonElement.disabled = false;
					response.text().then(t =>
						Flash.error(window.LimitErrors.fromStatus(response.status, t) || t || $L('update.failed')));
				}
			})
			.catch(() => {
				this.submitButtonElement.disabled = false;
				Flash.error($L('update.failed'));
			});
	}

}

/**
 * Right-menu form to edit a file's metadata (name, description, source, metadata map, related
 * resources, dataset). Pre-filled from the node and submitted to POST /files/update.
 */
class UpdateFileForm extends FileFormAbstract {

	constructor(obj) {
		super(obj);
		this.title = $L('update.file');
		this.node = obj.node;          // an IndexNode-shaped object
		this.afterSaveFn = obj.afterSave;
	}

	getFormFields() {
		const n = this.node;
		return `
			<p>${$L('update.file')}: <strong class="dh-target-name"></strong></p>

			<label>${$L('file.name')}</label>
			<input type="text" name="name" required pattern="[^/]+" value="${this.escAttr(n.name)}" tabindex="10"/>

			<label>${$L('description')}</label>
			<textarea class="w100" name="description" maxlength="${FieldLimits.DESCRIPTION_MAX}" tabindex="20"></textarea>

			<label>${$L('source')}</label>
			<input type="text" name="source" value="${this.escAttr(n.source)}" tabindex="30"/>

			<label>${$L('metadata')}</label>
			${this.metadataRowsHtml(n.metadata || {})}

			<label>${$L('related.resources')}</label>
			${this.relatedResourcesHtml(n.relatedResources || [])}

			<label>${$L('choose.dataset')}</label>
			<div class="flex-container left-right">
				<input type="hidden" name="dataSetId" value="${n.dataSetId != null ? n.dataSetId : ''}"/>
				<span data-type="data-set" class="mright10"></span>
				<button type="button" class="dh-btn secondary small" data-type="data-set-list-btn"><i class="fa fa-fw fa-search"></i></button>
			</div>
		`;
	}

	render() {
		super.render();
		this.formElement.querySelector('.dh-target-name').textContent = this.node.name;
		// Set the description via the DOM (do not interpolate user text into HTML).
		this.formElement.querySelector('textarea[name="description"]').value = this.node.description || '';
		if (this.node.dataSetId != null) {
			this.formElement.querySelector('[data-type="data-set"]').textContent = '#' + this.node.dataSetId;
		}

		this.wireMetadataRows(this.formElement);
		this.wireRelatedResources(this.formElement);
		this.resolveRelatedResourceNames(this.formElement);   // turn pre-filled #id chips into names

		this.formElement.querySelector('[data-type="data-set-list-btn"]').addEventListener('click', () => {
			const list = new DataSetList({});
			list.setConfirmCallback(selected => {
				if (selected && selected[0]) {
					this.formElement.querySelector('[name="dataSetId"]').value = selected[0].id;
					this.formElement.querySelector('[data-type="data-set"]').textContent = selected[0].name;
				}
			});
		});

		this.submitButtonElement.firstElementChild.textContent = $L('datahub.save');
	}

	submit() {
		const dataSetId = this.formElement.querySelector('[name="dataSetId"]').value;
		const payload = {
			externalId: this.node.externalId,
			name: this.formElement.querySelector('input[name="name"]').value,
			description: this.formElement.querySelector('textarea[name="description"]').value,
			source: this.formElement.querySelector('input[name="source"]').value,
			metadata: this.readMetadataRows(this.formElement),
			relatedResources: this.readRelatedResources(this.formElement)
		};
		if (dataSetId) payload.dataSetId = dataSetId;

		this.submitButtonElement.disabled = true;
		requestFileUpdate(payload)
			.then(response => {
				if (!response) return; // signed out
				if (response.ok) {
					if (this.afterSaveFn) this.afterSaveFn();
					this.cancelButtonElement.dispatchEvent(new Event('click'));
					Flash.info($L('file.updated'));
					document.location.reload();
				} else {
					this.submitButtonElement.disabled = false;
					response.text().then(t =>
						Flash.error(window.LimitErrors.fromStatus(response.status, t) || t || $L('update.failed')));
				}
			})
			.catch(() => {
				this.submitButtonElement.disabled = false;
				Flash.error($L('update.failed'));
			});
	}

}
