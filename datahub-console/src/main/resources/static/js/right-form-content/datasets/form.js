/*
 * The dataset right-form talks to datahub-api directly (window.Api: api host from the page's
 * <meta name="datahub-api-url">, bearer token from the console's /token), not through this app's
 * Feign proxy, which is deprecated for new work. The api takes and returns the {items:[…]}
 * envelope, so submit() wraps and savedItem() unwraps; permissions are unchanged either way, since
 * the proxy forwarded this same user's token.
 */
class DataSetForm extends DatasetFormAbstract {

	constructor(obj) {
		super(obj);
		this.title = $L('create.dataset');
		this.apiPath = "/datasets";
	}

	getFormFields() {
		return `
			${this.getEntityIdField()}
			${this.showLabels ? `
			<label for="label">${$L('main.label')}</label>
			<div class="flex-container left-right label-data">
				<div style="flex-grow: 1;" class="mright20" data-type="labels-container"></div>
				<div>
					<button data-type="label-list-btn" type="button" class="dh-btn primary small" title="${$L('add.label')}">
						<i class="fa fa-fw fa-plus"></i>
					</button>
				</div>
			</div>
			` : ''}
			<label for="dataset">${$L('part.of.dataset')}</label>
			<div class="flex-container left-right dataset-container">
				<div style="flex-grow: 1;min-height: 20px;" class="mright20" data-type="dataset-container"></div>
				<div>
					<button data-type="dataset-find-btn" type="button" class="dh-btn primary small" title="${$L('find.dataset')}" tabindex="10">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			</div>
			
			${NamingHelp.label('dataset')}
			<input class="${this.fieldError('name')}" 
					type="text" name="name"
					required
					value="${this.getFormProperty('name')}" 
					placeholder="${$L('write.name.here')}" tabindex="20"/>
					
			<label>${$L('external.id')}</label>
			<input class="${this.fieldError('externalId')}"
					type="text" name="externalId"
					required
					pattern="${this.externalIdPattern()}"
					value="${this.getFormProperty('externalId')}"
					placeholder="${$L('write.external.id.here')}" tabindex="30"/>
			<p class="field-hint">${$L('external.id.charset.help')}</p>

			<label>${$L('description')}</label>
			<textarea class="w100 ${this.fieldError('description')}" name="description" maxlength="${FieldLimits.DESCRIPTION_MAX}" placeholder="${$L('write.description.here')}..." tabindex="40"></textarea>
			
		`;
	}

	render() {
		super.render();
		this.savePath = this.apiPath + "/create";
		this.formElement.action = Api.url(this.savePath);
		this.findDataSetBtn = this.formElement.querySelector('[data-type="dataset-find-btn"]');

		const findDataSetEvent = e => {
			const picker = new DataSetList({
				"title": `${$L('choose.dataset')}`,
                multiple: true,
			});
			picker.setConfirmCallback( selectedData => {
				const eleContainer = this.formElement.querySelector('[data-type="dataset-container"]');
				eleContainer.innerHTML = "";
				for(let i = 0; i < selectedData.length; i++){
					const idx = i + eleContainer.children.length;
					const eleExists = eleContainer.querySelector(`[data-id="${selectedData[i].id}"]`);
					if(!eleExists){
						const label = this.createDataSetLabel(selectedData[i]);
						eleContainer.append(label);
					}
				}
			});
		};
		this.findDataSetBtn.addEventListener('click', findDataSetEvent);

		// A data set may carry its own naming policy overriding the tenant's, but this form IS the
		// data set being named, so the tenant policy is what governs it — no dataSetId is passed.
		this.wireExternalIdField();

		this.renderMetadataContent();
	}

	createDataSetLabel(obj){
		const label = Object.assign(document.createElement('div'), {
			className: "resource-label",
			style: "border-color: #fff",
			innerHTML: `<div><span></span><input type="hidden" name="connectedDataSets" /></div>`
		});
		label.querySelector('span').textContent = obj.name;
		label.querySelector('input[name="connectedDataSets"]').value = obj.id;
		return label;
	}

    submit() {
        this.formData = new FormData(this.formElement);
        const obj = Object.fromEntries(this.formData);

        // No deactivated/writeProtected here: #300 removed both from the dataset model, and this
        // form never rendered a checkbox for either, so getBool() only ever returned false. Since
        // #324 the api rejects a body naming fields it does not have, so sending them 400s the
        // whole create. The write-protect policy push went with them — it was gated on the same
        // always-false flag.
        // The hidden inputs hold ids as text and List<Long> reads text, so there is nothing for
        // .map(Number) to do here except lose precision on a big enough id.
        obj.connectedDataSets = this.formData.getAll('connectedDataSets');
        obj.policies = [];

        Api.post(this.savePath, { items: [obj] })
            .then(response => this.handleResponse(response))
            .catch(() => console.error("Dataset save failed"));
    }

    /** The api answers a write with its envelope; afterSave callbacks expect the data set itself. */
    savedItem(json) {
        return (json && Array.isArray(json.items)) ? json.items[0] : json;
    }
}

class EditDataSetForm extends DataSetForm {

	constructor(obj) {
		super(obj);
		this.title = $L('edit.dataset') + " : " + obj.entityId;
		this.deleteUrl = this.apiPath + "/delete";
		// Show the label picker on the edit form (create stays auto-tagged DATASET only).
		this.showLabels = true;
		this.labelColors = {};
		this.labelNetwork = { labels: [] };
	}

	render(){
		super.render();
		this.savePath = this.apiPath + "/update";
		this.formElement.action = Api.url(this.savePath);

		// Wire the label picker. The DATASET type-label is fixed and shown locked; the picker
		// offers no type-labels (selectableTypeLabels: []) and manages only the other labels.
		const labelBtn = this.formElement.querySelector('[data-type="label-list-btn"]');
		if(labelBtn){
			labelBtn.addEventListener('click', e => {
				const picker = new LabelList({
					"title": `${$L('choose.label')}`,
					network: this.labelNetwork,
					selectableTypeLabels: []
				});
				picker.setConfirmCallback( selectedData => {
					const container = this.formElement.querySelector('[data-type="labels-container"]');
					for(let i = 0; i < selectedData.length; i++){
						if(!container.querySelector(`[data-id="${selectedData[i].id}"]`)){
							container.append(this.createLabelChip(selectedData[i], container.children.length, false));
						}
					}
				});
			});
		}

		// Load the label list once for chip colours + the picker's colour network, then load the
		// dataset (metadata + labels). Ordering it after the colour fetch keeps preloaded chips coloured.
		const loadDataset = () => {
			if(this.errors) return;
			this.loadData( json => {
				Object.entries(json.metadata).forEach(([key, value]) => {
					this.addMetadataRow(key, value);
				});
				this.preloadLabels(json.labels || []);
			});
		};
		fetch('/api/label/list', { headers: { 'Accept': 'application/json' } })
			.then(r => r.json())
			.then(json => {
				this.labelNetwork.labels = json.items || [];
				(json.items || []).forEach(l => { this.labelColors[(l.name || '').toUpperCase()] = l.color; });
			})
			.catch(() => {})
			.finally(loadDataset);

		// Not possible to change parent dataset, no need for this button
		this.formElement.querySelector('[data-type="dataset-find-btn"]').remove();
		this.submitButtonElement.firstElementChild.textContent = $L('update');
	}

	/**
	 * The api has no GET /datasets/{id}: looking several up at once is the only shape it offers, so
	 * ask for the one and unwrap it. Same contract as the base loadData otherwise.
	 */
	loadData(callback){
		if(this.entityId === null) return;
		// The id stays the string it arrived as (every caller reads it off the DOM). The server field
		// is a Long that reads it fine, so converting buys nothing and rounds above 2^53.
		Api.post(this.apiPath + "/byids", { items: [{ id: this.entityId }] })
			.then( response => response.json())
			.then( json => {
				const dataSet = (json.items || [])[0];
				if(!dataSet) return;
				this.loadCompleteCallback(dataSet);
				if(callback instanceof Function){
					callback(dataSet);
				}
			})
			.catch( e => {
				console.error(e);
			});
	}

	// Render a dataset label as a chip (mirrors ResourceForm.createLabel). Type-labels carry a
	// data-type-label marker; the DATASET chip is passed locked (no remove control).
	createLabelChip(data, idx, locked){
		const isType = TYPE_LABELS_ALL.includes((data.name || '').toUpperCase());
		const color = data.color || this.labelColors[(data.name || '').toUpperCase()] || '#fff';
		const chip = Object.assign(document.createElement('div'), {
			className: "resource-label" + (locked ? " locked" : ""),
			style: "border-color: " + color,
			innerHTML: `
				<div>
					<span></span>
					<input type="hidden"/>
				</div>` + (locked ? '' : `<div class="remove pointer"><i class="fa fa-fw fa-xmark"></i></div>`)
		});
		const hidden = chip.querySelector('input[type="hidden"]');
		hidden.name = `labels[${idx}]`;
		hidden.value = data.name || '';
		hidden.setAttribute("data-id", data.id);
		chip.setAttribute("data-id", data.id);
		if(isType){
			chip.setAttribute("data-type-label", "true");
		}
		chip.querySelector('span').textContent = data.name || '';
		const removeBtn = chip.querySelector('.remove');
		if(removeBtn){
			removeBtn.addEventListener('click', e => { chip.remove(); });
		}
		return chip;
	}

	// Preload the dataset's current labels as chips. The DATASET type-label is locked.
	preloadLabels(labelNames){
		const container = this.formElement.querySelector('[data-type="labels-container"]');
		if(!container) return;
		labelNames.forEach( name => {
			const upper = (name || '').toUpperCase();
			const known = (this.labelNetwork.labels || []).find( l => (l.name || '').toUpperCase() === upper);
			const data = { id: known ? known.id : upper, name: name, color: known ? known.color : undefined };
			if(!container.querySelector(`[data-id="${data.id}"]`)){
				container.append(this.createLabelChip(data, container.children.length, TYPE_LABELS_ALL.includes(upper)));
			}
		});
	}

	submit(){
		this.formData = new FormData(this.formElement);
		const obj = Object.fromEntries(this.formData);
		obj.metadata = {};
		const metadataFields = this.formElement.querySelectorAll(`table.metadata tbody tr`);
		metadataFields.forEach( it => {
			const keyField = it.querySelector('[name$="key"]');
			const valueField = it.querySelector('[name$="value"]');
			if(keyField && valueField){
				obj.metadata[keyField.value] = valueField.value;
			}
		});
		// Collect the label chips (includes the locked DATASET chip). The api re-enforces DATASET.
		const labels = [];
		this.formElement.querySelectorAll('[data-type="labels-container"] input[name^="labels"]').forEach( it => {
			labels.push(it.value);
		});
		const datasetUpdateForm = {
			id: obj.id,
			externalId: this.persistedData["externalId"],
			update: {
				name: {
					set: obj.name
				},
				externalId: {
					set: obj.externalId
				},
				description: {
					set: obj.description
				},
				metadata: {
					set: obj.metadata
				},
				labels: {
					set: labels
				}
			}
		};
		Api.post(this.savePath, { items: [datasetUpdateForm] })
			.then( response => {
				this.handleResponse(response);
			}).catch(() => {
				console.error("Updating Dataset failed");
			});
	}

	delete(){
		Api.del(this.deleteUrl, { items: [{ id: this.entityId }] })
			.then( xhr => {
				// If successful delete
				if(xhr.status === 200 || xhr.status === 204){
					if(this.afterDeleteFn){
						this.afterDeleteFn(this);
					}
					this.cancelButtonElement.dispatchEvent(new Event('click'));
					return;
				}
				// A refused delete is either an access denial (a problem document) or the api's
				// error envelope naming what blocked it, e.g. resources the delete would strand.
				xhr.json()
					.then( errorJson => this.flashError(this.anyErrorMessage(errorJson)) )
					.catch(() => { /* no body, or not JSON: nothing to say beyond the status */ });
			})
			.catch((e) => {
				console.error(e);
			});
	}
}

class DataSetList extends BaseList{

	constructor(obj) {
		super(obj);
		this.apiPath = '/datasets';
		this.title = this.title || $L('choose.dataset');   // default when opened without an explicit title
		this.loadData();
		this.render();
	}

	loadData(afterLoadFn){
		// Data sets are a small, slow-changing set per tenant, so the picker asks for the lot and
		// filters client side (doSearch below) rather than round-tripping per keystroke.
		Api.post(this.apiPath + "/list", { limit: 100 })
			.then( resp => resp.json())
			.then( json => {
				this.data = {
					columns: [
						{
							name: $L('name'),
							prefix: "name"
						},
					],
                    items: json.items.map( it => {
                        return {
                            id: it.id,
                            name: it.name,
                        };
                    })
				}
				this.allItems = this.data.items;   // keep the full list for client-side filtering
				this.updateTable();
			});
	}

	render() {
		super.render();
	}

	// Render datasets as the same dl-row list the datasets index page uses (colour dot + name +
	// DATASET chip) rather than the plain BaseList table. render() drops an empty <ul> in place; rows
	// are built as DOM nodes (names via textContent, so they are safe) once data loads.
	renderTable(){
		this.tableHTML = '<ul class="data-list slim pointer" data-type="dataset-list"></ul>';
	}

	_buildList(){
		const ul = document.createElement('ul');
		ul.className = 'data-list slim pointer';
		ul.setAttribute('data-type', 'dataset-list');
		this.data.items.forEach(it => {
			const li = document.createElement('li');
			li.className = 'dl-row';
			li.setAttribute('data-id', it.id);
			const main = document.createElement('span');
			main.className = 'dl-main';
			const name = document.createElement('span');
			name.className = 'dl-name';
			name.textContent = it.name;
			const chips = document.createElement('span');
			chips.className = 'dl-chips';
			const chip = document.createElement('span');
			chip.className = 'dl-chip';
			chip.setAttribute('data-label', 'DATASET');
			chip.textContent = 'DATASET';
			chips.appendChild(chip);
			main.append(name, chips);
			li.append(main);
			li.addEventListener('click', () => {
				if(this.multiSelect === false){
					ul.querySelectorAll('li.dl-row.selected').forEach(r => r.classList.remove('selected'));
				}
				li.classList.toggle('selected');
				this.updateSelectedData();
			});
			ul.appendChild(li);
		});
		return ul;
	}

	// Replace just the list, keeping the search field (and its focus) in place.
	_replaceList(){
		const main = this.bodyElement.querySelector('main');
		const old = main.querySelector('ul.data-list');
		const ul = this._buildList();
		if(old){ old.replaceWith(ul); } else { main.appendChild(ul); }
	}

	updateSelectedData(){
		this.selectedData = [];
		this.bodyElement.querySelectorAll('ul.data-list li.dl-row.selected').forEach(row => {
			const id = row.getAttribute('data-id');
			const dataObj = this.allItems.find(d => String(d.id) === id);
			if(dataObj){ this.selectedData.push(dataObj); }
		});
		this.confirmBtn.disabled = this.selectedData.length === 0;
	}

	// Filter the already-loaded datasets client-side and re-render just the list, keeping the
	// search field's focus. The full list was fetched in loadData.
	doSearch(query){
		const q = (query || '').trim().toLowerCase();
		this.data.items = (!q || q.length < 3)
			? this.allItems
			: this.allItems.filter(it => (it.name || '').toLowerCase().includes(q));
		this._replaceList();
	}

	renderSearchField(){
		// updateTable runs on load and after saves; only ever add one search field.
		if(this.bodyElement.querySelector("[data-type='dataset-list-search-dataset']")) return;
		this.searchField = Object.assign(document.createElement('DIV'), {
			innerHTML: `
				<label for="dataset-list-search-dataset">${$L('search.for.dataset')}</label>
				<div class="posrel datahub-form-fields">
					<input id="dataset-list-search-dataset" data-type="dataset-list-search-dataset"
							class="w100 left-icon" type="text" 
							name="search-dataset" placeholder="${$L('write.query.here')}"/>
					<i class="fa fa-fw fa-search inside-input"></i>
				</div>`
		});
		this.bodyElement.querySelector('main').prepend(this.searchField);

		let searchTimer;
		this.searchField
			.querySelector("[data-type='dataset-list-search-dataset']")
			.addEventListener('input', e => {
				clearTimeout(searchTimer);
				searchTimer = setTimeout(() => this.doSearch(e.target.value), 250);
			});
	}

	updateTable() {
		this._replaceList();
		this.renderSearchField();
	}
}