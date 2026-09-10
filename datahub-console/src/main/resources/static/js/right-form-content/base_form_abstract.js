/**
 * Abstract class BaseForm
 * This class contains business logic for the form and the submit button.
 */
class BaseFormAbstract{
	constructor(obj){
		if (new.target === BaseFormAbstract) {
			throw new TypeError("Cannot construct Abstract instances directly");
		}
		this.method = obj.method || 'POST';
		this.action = obj.action || null;
		this.enctype = obj.enctype || null;
		this.buildDOMElements();
		this.updateAttributes();
	}
	buildDOMElements() {
		this.formElement = document.createElement('FORM');
		this.submitButtonElement = document.createElement('BUTTON');
		this.submitButtonElement.type = "submit";
		this.submitButtonElement.className = "dh-btn primary";
		this.submitButtonElement.textContent = 'Submit';
	}
	updateAttributes() {
		this.formElement.method = this.method;
		this.formElement.action = this.action;
		if(this.enctype){
			this.formElement.enctype = this.enctype;
		}
	}
	submit(){
		this.formElement.submit();
	}
    getBool(name){
        return this.formElement.querySelector(`input[name="${name}"]`)?.checked === true;
    }
}

class BaseList{

	constructor(obj) {
		if (new.target === BaseList) {
			throw new TypeError("Cannot construct Abstract instances directly");
		}
		this.multiSelect = obj.multiSelect === undefined ? true : Boolean(obj.multiSelect);
		this.title = obj.title;
		this.data = obj.data;
		this.tableId = obj.tableId || null;
		this.tableClassName = obj.tableClassName || null;
		this.mainElement = obj.mainElement || document.querySelector(".right-form");
		this.mainElement.classList.add("visible");
		this.confirmCallback = null;
		this.dataFilter = obj.dataFilter || [];
		this.editEvent = () => {console.warn('no edit event implemented')};
		this.tableHTML = "";
		this.data = {
			"columns": [],
			"items": []
		};

		this.updateTableList = data => {
			this.loadData(()=>{
				// Move saved item to top of table
				const rows = this.bodyElement.querySelectorAll('table tbody tr');
				for(let i = 0; i < rows.length; i++){
					if(rows[i].getAttribute("data-id") === String(data.id) ){
						rows[i].parentElement.prepend(rows[i]);
					}
				}
			});
		};
	}

	loadData(afterLoadFn){
		fetch(this.apiURL + '/list', {
			method: 'GET',
			headers: {
				'Accept': 'application/json'
			}
		})
			.then( resp => resp.json())
			.then( json => {
				this.data = {
					columns: [
						{
							name: $L('name'),
							prefix: "name"
						},
						{
							icon: "fa-pen"
						}
					],
					items: json.map( it => {
						return {
							id: it.id,
							name: it.name
						};
					})
				}
				this.updateTable();
				if(afterLoadFn instanceof Function){
					afterLoadFn();
				}
			});
	}

	getHtmlData(){
		return `
			<div class="heading flex-container left-right">
				<h3>${this.title}</h3>
			</div>
			<main>
				${this.tableHTML}
			</main>
			<footer class="btns flex-end pad20">
				<button type="button" class="dh-btn primary" tabindex="100" data-btn-type="confirm">
					<span>${$L('confirm')}</span>
				</button>
				<button type="button" class="dh-btn secondary" tabindex="110" data-btn-type="cancel">
					<span>${$L('close')}</span>
				</button>
			</footer>
		`;
	}

	render(){
		this.renderTable();
		this.bodyElement = document.createElement('section');
		setTimeout( () => this.bodyElement.classList.add("visible"), 10);
		this.bodyElement.innerHTML = this.getHtmlData();
		this.cancelButton =
			this.bodyElement
				.querySelector('[data-btn-type="cancel"]');
		this.cancelButton
			.addEventListener('click', e => {
				e.preventDefault();
				if( this.mainElement.childElementCount > 1 ){
					this.bodyElement.classList.remove("visible");
				} else {
					this.mainElement.classList.remove("visible");
				}
				setTimeout( () => {
					this.bodyElement.remove();
					if(this.mainElement.childElementCount === 0){
						this.mainElement.classList.remove("visible");
					}
				}, 500);
			});
		this.confirmBtn = this.bodyElement.querySelector('[data-btn-type="confirm"]');
		this.confirmBtn.disabled = true;
		this.confirmBtn.addEventListener('click', e=> {
			e.preventDefault();
			if(this.confirmCallback instanceof Function){
				this.confirmCallback(this.selectedData);
			} else {
				console.error("Missing confirm callback, use setConfirmCallback()")
			}
			this.cancelButton.dispatchEvent(new Event('click'));
		});
		this.mainElement.appendChild(this.bodyElement);
	}

	renderTable(){
		this.tableHTML = `
			<table class="listing slim objects w100 table-select">
			<thead>
			<tr>
			${this.data.columns.map( column => {
			if (column.name) {
				const th = document.createElement('th');
				th.textContent = column.name;
				return th.outerHTML;
			} else if (column.icon) {
				return `<th></th>`;
			}
		}).join('')}
			</tr>
			</thead>
			<tbody>
			${this.data.items.map( data => {
			return `
					<tr class="pointer" data-id="${data['id']}">
						${this.data.columns.map( column => {
				if(column.name){
					const td = document.createElement('td');
					td.textContent = data[column.prefix];
					return td.outerHTML;
				} else if(column.icon){
					return `<td class="text-right" data-type="edit" data-id="${data['id']}"><i class="fa fa-fw ${column.icon}"></i></td>`;
				}
			}).join('')}
					</tr>
				`;
		}).join('')}
			</tbody>
			</table>
		`;
	}

	updateTable(){
		this.renderTable();
		this.bodyElement.querySelector('main').innerHTML = this.tableHTML;
		this.addPickEventListener();
		this.addChecker();
	}

	addPickEventListener(){
		this.mainElement
			.querySelectorAll('table.table-select tbody tr')
			.forEach( row => {
				// Add row click event listener
				row.addEventListener('click', e => {
					if(this.multiSelect === false){
						this.mainElement
							.querySelectorAll('table tbody tr')
							.forEach( r => r.classList.remove('selected'));
					}
					row.classList.toggle("selected");
					this.updateSelectedData();
				});
				// Add edit btn event listener
				const lastCell = row.querySelector('td:last-child');
				if(lastCell.getAttribute("data-type") === "edit"){
					lastCell.addEventListener('click', this.editEvent);
				}
			});
	}

	addChecker(){
		const q = 'table tbody tr td:first-child';
		this.bodyElement.querySelectorAll(q).forEach( cell => {
			cell.prepend(Object.assign(document.createElement('IMG'), {
				className: "row-check",
				src: "/static/img/row-check.svg",
				alt: "row selected",
				height: 12,
			}));
		});
	}

	updateSelectedData(){
		const q = 'table tbody tr.selected';
		this.selectedData = [];
		this.mainElement.querySelectorAll(q).forEach( row => {
			// Compare as strings: item ids arrive as strings (64-bit-safe JSON), while some
			// lists still map them to numbers.
			const id = row.getAttribute('data-id');
			const dataObj = this.data.items.find(data => String(data.id) === id);
			this.selectedData.push(dataObj);
		});
		if(this.selectedData.length > 0){
			this.confirmBtn.disabled = false;
		}
	}

	setConfirmCallback(cb){
		this.confirmCallback = cb;
	}
}

class DatasetFormAbstract extends BaseFormAbstract{
	constructor(obj){
		super(obj);
		if (new.target === DatasetFormAbstract) {
			throw new TypeError("Cannot construct Abstract instances directly");
		}
		this.title = obj.title;
		this.entityId = obj.entityId || null;
		this.dataUrl = obj.dataUrl;
		this.deleteUrl = obj.deleteUrl || null;
		this.confirmDeleteMessage =
			obj.confirmDeleteMessage || $L('are.you.sure.delete.entity');
		this.mainElement = document.querySelector(".right-form");
		this.mainElement.appendChild(this.formElement);

		// Add event that moves clicked form element to foreground
		if(this.mainElement.children.length > 0){
			for(let i = 0; i < this.mainElement.children.length; i++){
				const childForm = this.mainElement.children[i];
				if(childForm.getAttribute('data-type') === 'move-to-front') continue;
				const moveToFront = e => {
					let formEle = e.target.closest('form');
					if(formEle === null){
						formEle = e.target.closest('section[data-type="move-to-front"]');
					}
					if(formEle !== null && formEle.parentElement.lastElementChild !== formEle){
						formEle.parentElement.append(formEle);
					}
				};
				childForm.addEventListener('pointerdown', moveToFront);
				childForm.setAttribute('data-type', 'move-to-front');
			}
		}

		this.mainElement.classList.add("visible");
		setTimeout( () => this.formElement.classList.add("visible"), 10);
		this.afterSaveFn = obj.afterSave === undefined ? null : obj.afterSave;
		this.afterDeleteFn = obj.afterDelete === undefined ? null : obj.afterDelete;
		this.afterCancelFn = obj.afterCancel === undefined ? null : obj.afterCancel;
		this.createFunctions();
		this.createDeleteBtn();

		this.loadCompleteCallback = json => {
			this.persistedData = json;
			Object.keys(json).forEach( key => {
				const formField =
					this.formElement.querySelector(`[name="${key}"]`);
				if(formField && json[key]){
					if(json[key].id){
						formField.value = json[key].id;
					} else {
						formField.value = json[key] === "null" ? '' : json[key];
					}
				}
			});
		};

		// Focus after a half second as we display an opening animation
		setTimeout( ()=> {
			const firstInputEle = this.formElement.querySelector("input");
			if(firstInputEle){
				firstInputEle.focus();
			}
		}, 500);
	}

	createFunctions(){
		this.cancelEvent = e => {
			e.preventDefault();
			this.formElement.classList.remove("visible");
			if(this.mainElement.childElementCount === 1){
				this.mainElement.classList.remove("visible");
			}
			setTimeout( () => {
				this.formElement.remove();
				if(this.mainElement.childElementCount === 0){
					this.mainElement.classList.remove("visible");
				}
			}, 500);
			if(this.afterCancelFn){
				this.afterCancelFn();
			}
		};
		this.submitEvent = e => {
			if(this.formElement.reportValidity()){
				// Prevent double submit
				this.submitButtonElement.disabled = true;
				setTimeout(()=>{this.submitButtonElement.disabled = false}, 10000);
				// Prevent regular form submit
				e.preventDefault();
				this.submit();
			}
		};
	}

	createDeleteBtn(){
		this.deleteButtonElement = document.createElement('BUTTON');
		this.deleteButtonElement.className = "dh-btn danger";
		this.deleteButtonElement.setAttribute('data-type', 'form-delete-btn');
		this.deleteButtonElement.type = "button";
		this.deleteButtonElement.innerHTML = `<span>${$L('delete')}</span>`;
		this.deleteButtonElement.addEventListener('click', e => {
			if(confirm(this.confirmDeleteMessage)){
				this.delete();
			}
		});
	}

	addEventListeners(){
		// Only support <enter> key press & mouse click
		this.cancelButtonElement.addEventListener('click', this.cancelEvent);
		this.cancelButtonElement.addEventListener('keydown', e => {
			if(e.keyCode === 13) this.cancelEvent(e);
		});
		this.submitButtonElement.addEventListener('click', this.submitEvent);
		this.submitButtonElement.addEventListener('keydown', e => {
			if(e.keyCode === 13) this.submitEvent(e);
		});
	}

	submit(){
		this.formData = new FormData(this.formElement);
		const obj = Object.fromEntries(this.formData);
		fetch(this.formElement.action, {
			method: this.formElement.method,
			headers: {
				'Accept': 'application/json',
				'Content-Type': 'application/json',
				[document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
			},
			signal: AbortSignal.timeout(10000),
			body: JSON.stringify(obj)
		}).then( response => {
			this.handleResponse(response);
		}).catch(() => {
			console.error("Update failed");
		});
	}

	handleResponse(response) {
		if (response.status >= 400) {
			response.json().then(json => {
				/** Errors JSON format should be in
				{
					"errors": {"field": "fieldName", "message": "errorMessage"}
				}
				 Or {
					"error": {"message": "errorMessage"}
				 }
				*/
				this.errors = [];
				// A naming-policy rejection is an RFC 9457 body, not the shape above: it names
				// every offending external id in one response rather than only the first, and the
				// batch is all-or-nothing so nothing was created. Flash it whole, and mark the
				// field so the form itself shows where to look.
				if (window.NamingPolicy && window.NamingPolicy.isViolation(json)) {
					window.NamingPolicy.flashViolations(json);
					this.errors.push({ field: 'externalId', message: json.detail || json.title });
					this.render();
					return;
				}
				if(json.errors){
					this.errors = json.errors;
				} else if(json.error){
					this.errors.push( json.error );
				}
				if(json.error && json.error.duplicated){
					json.error.duplicated.forEach( error => {
						const field = Object.keys(error)[0];
						const message = $L('external.id.exists', null, [error[field]]);
						this.errors.push( {field: field, message: message} );
					});
				}
				// Anything else the api answers with a problem document rather than the field-error
				// shape above: an access denial, a permissions lookup that could not be reached, a
				// concurrency conflict. Without this the form redrew with nothing on it and the save
				// looked like it had simply been ignored.
				if(this.errors.length === 0){
					const problem = this.problemMessage(json);
					if(problem) this.errors.push({ field: null, message: problem });
				}
				this.render();
			},
			() => {
				// The body was not JSON (a few endpoints answer an error with an empty one), so
				// there is nothing to read out of it. Say that the save did not happen anyway:
				// a form that redraws unchanged reads as though the button did nothing.
				this.errors = [{ field: null, message: $L('error.save.failed') }];
				this.render();
			});
		} else {
			this.cancelButtonElement.dispatchEvent(new Event('click'));
			response.json().then(json => {
				// The write succeeded, but the policy may have let a non-conforming external id
				// through and recorded a finding for a steward. `warnings` is absent when empty,
				// so a clean write is unchanged.
				if (window.NamingPolicy) {
					window.NamingPolicy.flashWarnings(json);
				}
				if (this.afterSaveFn) {
					this.afterSaveFn(this.savedItem(json));
				}
			});
		}
	}

	/**
	 * The entity handed to `afterSave`. Forms posting through the console's proxy get a bare object
	 * back; forms calling datahub-api directly get its `{items:[…]}` envelope and override this to
	 * unwrap, so an afterSave callback sees one saved entity either way.
	 */
	savedItem(json){
		return json;
	}

	/**
	 * The message to show for an RFC 9457 problem body, or null when `json` is not one.
	 *
	 * The api's own `detail` is written for an operator (the dataset-ACL one names the Keycloak
	 * group and the admin role by name) and is never translated, so the types the console knows
	 * about get a localized message and anything else falls back to the server's own words, which
	 * beats showing nothing.
	 */
	problemMessage(json){
		if(!json || typeof json !== 'object') return null;
		if(json.errors || json.error) return null;         // the field-error shape, handled above
		if(!json.detail && !json.title) return null;       // not a problem document
		if(json.type === 'https://intellistream.ai/errors/dataset-forbidden'){
			if(json.permission === 'manage') return $L('error.dataset.manage.forbidden');
			if(json.permission === 'read') return $L('error.dataset.read.forbidden');
			return $L('error.dataset.write.forbidden');
		}
		if(json.type === 'https://intellistream.ai/errors/permissions-unavailable'){
			return $L('error.permissions.unavailable');
		}
		const limit = window.LimitErrors && window.LimitErrors.message(json);
		if(limit) return limit;
		return json.detail || json.title;
	}

	/**
	 * One message for a failed request, whatever shape the answer took: the `{error:{message}}` and
	 * `{errors:[…]}` envelopes, or an RFC 9457 problem document. For the places that show a single
	 * flash rather than marking up fields (a failed delete).
	 */
	anyErrorMessage(json){
		if(!json || typeof json !== 'object') return null;
		if(json.error && json.error.message) return json.error.message;
		if(Array.isArray(json.errors) && json.errors.length && json.errors[0].message){
			return json.errors[0].message;
		}
		return this.problemMessage(json);
	}

	/**
	 * Prepends a flash to the form body. For errors raised outside a redraw (a failed delete):
	 * render() would rebuild the form from the submitted values, which is not what a delete wants.
	 */
	flashError(message){
		if(!message) return;
		const flash = Object.assign(document.createElement('DIV'), { className: "flash error" });
		flash.append(Object.assign(document.createElement('span'), { textContent: message }));
		this.formElement.querySelector('main').prepend(flash);
	}

	getFormHTML(){
		return `
            <div class="heading">
				<h3>${this.title}</h3>
			</div>
			<main class="datahub-form-fields">
				${this.getFlashErrors()}
				${this.getFormFields()}
			</main>
			<footer class="btns flex-end pad20">
				<button type="submit" class="dh-btn primary" tabindex="100" data-tutorial="dataset-save">
					<span>${$L('datahub.save')}</span>
				</button>
				<button type="button" class="dh-btn secondary" tabindex="110">
					<span>${$L('cancel')}</span>
				</button>
			</footer>
		`;
	}

	getFormFields(){
		return `
			${this.getEntityIdField()}
			<label>Name</label>
			<input class="${this.fieldError('name')}" 
					type="text" name="name" 
					value="${window.escapeHtml(this.getFormProperty('name'))}"
					placeholder="Name" tabindex="10" required=""/>
			<label>Description</label>
			<input class="${this.fieldError('description')}" 
					type="text" name="description" 
					value="${window.escapeHtml(this.getFormProperty('description'))}"
					placeholder="Description" tabindex="20"/>
		`;
	}

	getEntityIdField(){
		if(this.entityId){
			return `<input type="hidden" name="id" value="${window.escapeHtml(this.entityId)}"/>`;
		}
		return "";
	}

	/**
	 * The `pattern` attribute for an external-id input: the platform's character set floor
	 * (A-Z a-z 0-9 . _ : + = -, 3 to 256 characters), so the browser blocks only what the platform
	 * would genuinely refuse.
	 *
	 * It deliberately does not encode a naming *convention*. Which convention is in force is a
	 * per-tenant policy this page cannot know, and the old hardcoded snake_case pattern here would
	 * reject a plant tag like COM-99-PT-1034 in the browser before the request was ever sent. The
	 * convention half is answered by the preflight check in wireExternalIdField() instead.
	 *
	 * NamingPolicy lives in the site-wide 'app' bundle rather than this one, so the literal is
	 * repeated as a fallback for the (unreachable in practice) case where that bundle is absent.
	 */
	externalIdPattern(){
		// Dash escaped: the browser compiles a `pattern` attribute with the RegExp `v` flag,
		// which rejects a trailing unescaped `-` in a character class and then drops the
		// constraint entirely. Keep in step with NamingPolicy.CHARSET_PATTERN.
		return (window.NamingPolicy && window.NamingPolicy.CHARSET_PATTERN)
			|| '[A-Za-z0-9._:+=\\-]{3,256}';
	}

	/**
	 * A typed name to a suggested external id: "Valve pressure sensors" -> "valve_pressure_sensors".
	 *
	 * Deliberately still lowercase snake_case. It is a *suggested default* for someone who has not
	 * chosen an id, not a statement about what the platform accepts — external ids are stored
	 * verbatim within the charset floor, and a plant tag typed in explicitly is kept exactly as
	 * typed. See wireExternalIdField().
	 */
	deriveExternalId(name){
		return (name || '').toLowerCase().replaceAll(/[\s-]/g, '_');
	}

	/**
	 * Wires the Name -> External id derivation and the naming-policy preflight on the external-id
	 * field. Call from render() after the fields exist.
	 *
	 * Deriving stops the moment the external id holds anything other than what we last derived, so
	 * an explicitly typed COM-99-PT-1034 is never overwritten by the next keystroke in Name. That
	 * overwrite was the old behaviour and it made explicit input impossible to enter. Clearing the
	 * field resumes the suggestion, which is the only sensible reading of an empty id.
	 *
	 * @param options passed through to NamingPolicy.bindField (e.g. {dataSetId})
	 */
	wireExternalIdField(options){
		this.nameField = this.formElement.querySelector('input[name="name"]');
		this.externalIdField = this.formElement.querySelector('input[name="externalId"]');
		if(!this.externalIdField) return;

		if(this.nameField){
			this.nameField.addEventListener('input', () => {
				const current = this.externalIdField.value;
				if(current && current !== this.lastDerivedExternalId) return;   // user chose an id
				this.lastDerivedExternalId = this.deriveExternalId(this.nameField.value);
				this.externalIdField.value = this.lastDerivedExternalId;
				// Not trusted input, so it does not count as the user choosing an id; it is only
				// here so the preflight re-checks the suggestion it just produced.
				this.externalIdField.dispatchEvent(new Event('input', { bubbles: true }));
			});
		}

		if(window.NamingPolicy){
			window.NamingPolicy.bindField(this.externalIdField, options || {});
		}
	}

	fieldError(propertyName){
		if(this.errors){
			for(let i = 0; i < this.errors.length; i++){
				if(this.errors[i].field === propertyName){
					return "error";
				}
			}
		}
		return "";
	}

	getFlashErrors(){
		let errorHTML = "";
		if(this.errors) {
			this.errors.forEach( error => {
				errorHTML +=
					`<div class="flash error">
						<span>${window.escapeHtml(error.message)}</span>
					</div>`;
			});
		}
		return errorHTML;
	}

	getFormProperty(propertyName, defaultValue){
		if(this.formData){
			return this.formData.get(propertyName);
		} else if(defaultValue){
			return defaultValue;
		}
		return "";
	}

	render(){
		this.formElement.innerHTML = this.getFormHTML();
		this.cancelButtonElement = this.formElement.querySelector("footer .dh-btn.secondary");
		this.submitButtonElement = this.formElement.querySelector("footer [type='submit'].dh-btn.primary");
		if(this.deleteUrl){
			// Add delete button before the cancel button
			this.cancelButtonElement.parentElement.appendChild(
				this.deleteButtonElement
			);
		}
		this.addEventListeners();
		// Wires the Name-field help icon for the forms that opt in with NamingHelp.label().
		// Forms without one are unaffected, so this is safe to run for every form.
		if(window.NamingHelp){
			window.NamingHelp.bind(this.formElement);
		}
	}

	loadData(callback){
		if(this.entityId === null) return;
		fetch(this.apiURL + "/" + this.entityId, {
			method: 'GET',
			headers: {
				'Accept': 'application/json'
			}
		})
			.then(response => {
				return response.json();
			})
			.then(json => {
				this.loadCompleteCallback(json);
				if(callback instanceof Function){
					callback(json);
				}
			})
			.catch( e => {
				console.error(e);
			});
	};

	delete(){
		fetch(this.apiURL + "/delete/" + this.entityId,
			{
				method: 'DELETE',
				headers: {
					'Accept': 'application/json',
					[document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
				}
			})
			.then( xhr => {
				// If successful delete
				if(xhr.status === 200 || xhr.status === 204){
					if(this.afterDeleteFn){
						this.afterDeleteFn(this);
					}
					this.cancelButtonElement.dispatchEvent(new Event('click'));
					return;
				}
				// A delete is refused for the same reasons a save is (an access denial, a
				// conflict), so say why rather than leaving the form as though the button had
				// done nothing.
				xhr.json()
					.then( json => this.flashError(this.anyErrorMessage(json)) )
					.catch(() => { /* no body, or not JSON: nothing to say beyond the status */ });
			})
			.catch((e) => {
				console.error(e);
			});
	}

	renderMetadataContent(){
		const mainContent = this.formElement.querySelector('main');
		mainContent.append(Object.assign(document.createElement('section'), {
			className: "metadata-section",
			innerHTML: `
				<h3>${$L('main.metadata')}</h3>
				<table class="metadata w100">
					<thead>
					<tr>
						<th>${$L('main.key')}</th>
						<th>${$L('main.value')}</th>
						<th class="text-right">
							<button data-type="add-metadata-btn" type="button" class="dh-btn primary small" title="${$L('add.metadata')}">
								<i class="fa fa-fw fa-plus"></i>
							</button>
						</th>
					</tr>
					</thead>
					<tbody></tbody>
				</table>
			`
		}));

		this.deleteMetadataRowFn = e => {
			e.target.closest('tr').remove();
		};

		this.metaDataTable = mainContent.querySelector('.metadata-section table.metadata');
		this.addMetadataBtn = mainContent.querySelector('[data-type="add-metadata-btn"]');
		this.addMetadataBtn.addEventListener('click', e => {
			this.addMetadataRow(null, null);
		});

		// Add mutation observer to metadata table body. So we can recalculate metadata id order
		const obs = new MutationObserver((mutationList, observer) => {
			this.updateNameOrder(this.metaDataTable.tBodies[0].rows, "metadata");
		});
		obs.observe(this.metaDataTable.tBodies[0], {childList: true});

		// If user submit validation fails, we can save the request to a object and repopulate
		// metadata entries when re-rendering
		if(this.jsonData && this.jsonData.metadata){
			Object.entries(this.jsonData.metadata).forEach(([key, value]) => {
				this.addMetadataRow(key, value);
			});
		}
	}

	addMetadataRow(key, value){
		const row = this.metaDataTable.tBodies[0].insertRow(-1);
		const keyCell = row.insertCell(-1);
		const valueCell = row.insertCell(-1);
		const btnCell = row.insertCell(-1);
		// maxlength mirrors the api's own caps (FieldLimits), so an over-long key or value is stopped
		// where it is typed rather than after a round trip that refuses the whole form.
		keyCell.innerHTML = `<input type="text" name="metadata[${row.rowIndex+1}].key" maxlength="${FieldLimits.METADATA_KEY_MAX}" placeholder="${$L('metadata.key.here')}" />`;
		valueCell.innerHTML = `<input type="text" name="metadata[${row.rowIndex+1}].value" maxlength="${FieldLimits.METADATA_VALUE_MAX}" placeholder="${$L('metadata.value.here')}"/>`;
		const trashBtn = Object.assign(document.createElement('button'), {
			className: "dh-btn secondary small",
			innerHTML: `<i class="fa fa-fw fa-trash"></i>`
		});
		trashBtn.addEventListener('click', this.deleteMetadataRowFn);
		btnCell.append(trashBtn);
		if(key && value){
			keyCell.querySelector('input').value = key;
			valueCell.querySelector('input').value = value;
		}
	}

	updateNameOrder(rows, name){
		let counter = 0;
		for(let i = 0; i < rows.length; i++){
			const row = rows[i];
			for(let y = 0; y < row.cells.length; y++){
				// find all elements that starts with metadata
				let formElements = row.cells[y].querySelectorAll(`[name^='${name}']`);
				for(let z = 0; z < formElements.length; z++){
					formElements[z].name = formElements[z].name.replace(/(\d+)/g, counter);
					formElements[z].id = formElements[z].name;
				}
			}
			counter++;
		}
	}
}

class DataWrapper {
	constructor() {
		this.items = [];
	}
}
class UpdateFields {
	constructor() {}
	set(field, value){
		this[field] = {"set": value};
	}
	add(field, value){
		if(this[field] === undefined) this[field] = {"add": []};
		this[field]["add"].push(value);
	}
	remove(field, value){
		if(this[field] === undefined) this[field] = {"remove": []};
		this[field]["remove"].push(value);
	}
}