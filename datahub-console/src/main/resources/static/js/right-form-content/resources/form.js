// Privileged "type-labels" — mirror of ai.intellistream.datahub.jpa.domains.TypeLabels (backend).
// A node carries at most one; it is chosen only at create and is immutable afterwards. Only the
// creatable subset is ever selectable in the picker; the backend enforces the same rules.
const TYPE_LABELS_ALL = ['ASSET', 'DATASET', 'POLICY', 'TIMESERIES', 'FUNCTION'];
const TYPE_LABELS_SELECTABLE = ['ASSET', 'DATASET', 'POLICY'];

// The dataset the last resource was saved into, remembered for the rest of the browser tab.
//
// Work tends to stay inside one dataset, and the api reads a missing dataset as "orphan" — which
// only an all-datasets writer may create — so a per-dataset writer who leaves the field alone is
// refused. Defaulting to the dataset you just used saves a search per resource without ever
// widening what a save may touch: the id still has to survive the server's own write check.
//
// sessionStorage rather than localStorage: a dataset id means nothing outside the organization it
// belongs to, and a tab is the longest a stale one can then survive.
const LAST_DATASET_KEY = 'dh.lastDataSet';
const LastDataSet = {
    read(){
        try {
            const stored = JSON.parse(sessionStorage.getItem(LAST_DATASET_KEY) || 'null');
            return stored && stored.id ? stored : null;
        } catch(e){
            return null;   // storage unavailable, or a value written by an older shape
        }
    },
    write(dataSet){
        if(!dataSet || !dataSet.id) return;
        try {
            sessionStorage.setItem(LAST_DATASET_KEY,
                JSON.stringify({id: String(dataSet.id), name: dataSet.name || ''}));
        } catch(e){ /* storage unavailable: the prefill is a convenience, not a requirement */ }
    }
};

// The dataset a node puts a new neighbour in. A DATASET node *is* the dataset; any other node
// contributes the dataset it belongs to. Only the id is known here — graph nodes carry no dataset
// name — so the caller resolves it for display.
const dataSetOfNode = node => {
    if(!node) return null;
    const labels = (node.labels || []).map(label => String(label).toUpperCase());
    if(labels.includes('DATASET')){
        return {id: node.id, name: node.name || node.label};
    }
    return node.dataSetId ? {id: node.dataSetId} : null;
};

class ResourceForm extends DatasetFormAbstract{

    constructor(obj) {
        super(obj);
        this.network = obj.network || null;
        this.isRoot = obj.isRoot === undefined ? false : obj.isRoot;
        this.title = $L('create.resource');
        this.apiURL = "/api/resources";
        this.labelApiUri = "/api/label";
        // A dataset the caller already knows about — what we are cloning, or the node we are
        // connecting from. Beats both the remembered one and an empty field; see prefillDataSet().
        this.dataSet = obj.dataSet || null;
        // Create context: a type-label may be chosen (one of the creatable set) and is removable
        // until saved. Edit forms override both — the type is fixed and shown locked.
        this.selectableTypeLabels = TYPE_LABELS_SELECTABLE;
        this.lockTypeLabels = false;
    }

    getFormFields(){
        return `
			${this.getEntityIdField()}
			<input type="hidden" name="isRoot" value="${this.isRoot}"/>
			<label for="label">${$L('main.label')}</label>
			<div class="flex-container left-right label-data">
				<div style="flex-grow: 1;" class="mright20" data-type="labels-container"></div>
				<div>
					<button data-type="label-list-btn" type="button" class="dh-btn primary small" title="${$L('add.label')}">
						<i class="fa fa-fw fa-plus"></i>
					</button>
				</div>
			</div>
			
			${NamingHelp.label('asset')}
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
			<textarea class="w100 ${this.fieldError('description')}" name="description" placeholder="${$L('write.description.here')}..." tabindex="40"></textarea>

			<label>${$L('source')}</label>
			<input class="${this.fieldError('source')}"
					type="text" name="source"
					value="${this.getFormProperty('source')}"
					placeholder="${$L('write.data.source.here')}" tabindex="50"/>

			<div class="form-section">
				<label>${$L('choose.dataset')}</label>
				<div class="flex-container left-right">
					<input type="hidden" name="dataSetId" value="${this.getFormProperty('dataSetId')}"/>
					<span data-type="data-set" class="mright10"></span>
					<div>
						<button type="button" class="dh-btn secondary small" data-type="data-set-clear-btn"
								title="${$L('dataset.clear')}" tabindex="55" hidden>
							<i class="fa fa-fw fa-xmark"></i>
						</button>
						<button type="button" class="dh-btn primary small" data-type="data-set-list-btn"
								title="${$L('choose.dataset')}" tabindex="60">
							<i class="fa fa-fw fa-search"></i>
						</button>
					</div>
				</div>
				${this.dataSetHint()}
			</div>
		`;
    }

    // Create only. Leaving the field empty is what makes a new resource an orphan, and only an
    // all-datasets writer may create one. Clearing the dataset of a resource that already exists
    // is a different check — it needs write on the dataset it is leaving — so the warning would
    // be wrong on an edit.
    dataSetHint(){
        return `<p class="field-hint">${$L('dataset.required.unless.write.all')}</p>`;
    }

    render() {
        super.render();
        this.formElement.action = this.apiURL + "/save";
        this.labelListBtn = this.formElement.querySelector('[data-type="label-list-btn"]');
        const openLabelListEvent = e => {
            const picker = new LabelList({
                "title": `${$L('choose.label')}`,
                network: this.network,
                selectableTypeLabels: this.selectableTypeLabels
            });
            picker.setConfirmCallback( selectedData => {
                const labelContainer = this.formElement.querySelector('[data-type="labels-container"]');
                for(let i = 0; i < selectedData.length; i++){
                    const idx = i + labelContainer.children.length;
                    const labelExists = labelContainer.querySelector(`[data-id="${selectedData[i].id}"]`);
                    // A node may carry at most one type-label — skip a second one.
                    const isType = TYPE_LABELS_ALL.includes((selectedData[i].name || '').toUpperCase());
                    const hasType = labelContainer.querySelector('[data-type-label="true"]');
                    if(!labelExists && !(isType && hasType)){
                        labelContainer.append(this.createLabel(selectedData[i], idx, "labels"));
                    }
                }
            });
        };
        this.wireExternalIdField();
        this.labelListBtn.addEventListener('click', openLabelListEvent);
        if(this.labelHTML){
            this.formElement.querySelector('[data-type="labels-container"]').innerHTML = this.labelHTML;
        }
        this.wireDataSetPicker();
        this.renderMetadataContent();
    }

    wireDataSetPicker(){
        this.formElement.querySelector('[data-type="data-set-list-btn"]')
            .addEventListener('click', () => {
                const picker = new DataSetList({});
                picker.setConfirmCallback( selected => {
                    if(selected && selected[0]){
                        this.setDataSet(selected[0]);
                    }
                });
            });
        this.formElement.querySelector('[data-type="data-set-clear-btn"]')
            .addEventListener('click', () => this.setDataSet(null));
        this.prefillDataSet();
    }

    setDataSet(dataSet){
        const idField = this.formElement.querySelector('[name="dataSetId"]');
        const nameField = this.formElement.querySelector('[data-type="data-set"]');
        idField.value = dataSet && dataSet.id ? String(dataSet.id) : '';
        // The id stands in until the name is known (or if the lookup fails): a bare number reads
        // poorly, but a field that looks empty while it posts a dataset reads as a bug.
        nameField.textContent = idField.value ? (dataSet.name || idField.value) : $L('none');
        this.formElement.querySelector('[data-type="data-set-clear-btn"]').hidden = !idField.value;
    }

    /*
     * Which dataset the field starts on, most specific first:
     *
     *   1. one handed to the form — the original we are cloning, or the node we are connecting from;
     *   2. the value that survived a rejected submit (getFormProperty put it back in the field);
     *   3. the dataset the last resource was saved into, this tab.
     *
     * Only (1) may already know the name; the other two are ids, so they are resolved for display.
     */
    prefillDataSet(){
        const candidate = this.dataSetCandidate();
        if(candidate && candidate.name){
            this.setDataSet(candidate);
        } else {
            this.showDataSetById(candidate && candidate.id);
        }
    }

    dataSetCandidate(){
        const surviving = this.formElement.querySelector('[name="dataSetId"]').value;
        return this.dataSet || (surviving ? {id: surviving} : null) || LastDataSet.read();
    }

    // Show a dataset known only by id. The field is claimed straight away so a slow lookup cannot
    // be overtaken by a save; the name replaces the id once it arrives. A lookup that fails leaves
    // the id in place — dropping it would silently change which dataset the save targets.
    showDataSetById(id){
        if(!id){
            this.setDataSet(null);
            return;
        }
        this.setDataSet({id: id});
        this.resolveDataSet(id).then( dataSet => {
            // The user can pick a different dataset while the lookup is in flight; naming one they
            // have already moved off would put the target back without them touching anything.
            const stillWanted = this.formElement.querySelector('[name="dataSetId"]').value === String(id);
            if(dataSet && stillWanted) this.setDataSet(dataSet);
        });
    }

    resolveDataSet(id){
        return Api.get('/datasets/' + encodeURIComponent(id))
            .then( response => response.ok ? response.json() : null)
            .then( json => (json && json.items && json.items[0]) || null)
            .catch( () => null);
    }

    submit(){
        this.labelHTML = this.formElement.querySelector('[data-type="labels-container"]').innerHTML;
        this.formData = new FormData(this.formElement);
        const obj = Object.fromEntries(this.formData);
        // An empty picker means "no dataset", which the api spells as an absent field — "" is not
        // a number. On an update the same absence is what clears an existing dataset.
        if(obj.dataSetId){
            // The label may still be the id standing in for a name that never loaded — remember
            // it as nameless rather than as a dataset called "1234", and it resolves next time.
            const shown = this.formElement.querySelector('[data-type="data-set"]').textContent;
            LastDataSet.write({id: obj.dataSetId, name: shown === obj.dataSetId ? '' : shown});
        } else {
            delete obj.dataSetId;
        }
        obj.metadata = {};
        obj.labels = [];
        this.formElement.querySelectorAll('input[name^="label"]').forEach( it => {
            obj.labels.push(it.value);
        });
        if(this.formData.has("relationTypes")){
            obj.relationTypes = [];
            this.formElement.querySelectorAll('input[name="relationTypes"]').forEach( it => {
                obj.relationTypes.push(it.value);
            });
        }
        const metadataFields = this.formElement.querySelectorAll(`table.metadata tbody tr`);
        metadataFields.forEach( it => {
            const keyField = it.querySelector('[name$="key"]');
            const valueField = it.querySelector('[name$="value"]');
            if(keyField && valueField && keyField.value !== "" && valueField.value !== ""){
                obj.metadata[keyField.value] = valueField.value;
            }
        });
        fetch(this.formElement.action, {
            method: this.formElement.method,
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            },
            //signal: AbortSignal.timeout(10000),
            body: JSON.stringify(obj)
        }).then( response => {
            this.handleResponse(response);
        }).catch( error => {
            console.error("Update failed", error);
        });
    }

    createLabel(data, idx, name){
        const getColor = (data) =>{
            if(data.color === undefined && this.network && data.labels){
                const label = this.network.labels.find(it => it.name === data.labels);
                if(label){
                    return label.color;
                }
                return "#fff";
            }
            // A label-less resource (e.g. a from-node picked via ResourceSearch) has no color and
            // labels === '', so it skips the block above; fall back rather than deref undefined.
            if(data.color === undefined){
                return "#fff";
            }
            if(data.color.background){
                return data.color.background;
            }
            return data.color;
        }
        const color = getColor(data);
        // Only actual labels can be type-labels (relations reuse this method with a different name).
        const isTypeLabel = name === "labels" && TYPE_LABELS_ALL.includes((data.name || '').toUpperCase());
        const locked = isTypeLabel && this.lockTypeLabels;
        const label = Object.assign(document.createElement('div'), {
            className: "resource-label" + (locked ? " locked" : ""),
            style: "border-color: " + color,
            innerHTML: `
				<div>
					<span></span>
					<input type="hidden"/>
				</div>` + (locked ? '' : `<div class="remove pointer"><i class="fa fa-fw fa-xmark"></i></div>`)
        });
        const hidden = label.querySelector('input[type="hidden"]');
        hidden.name = `${name}[${idx}]`;
        hidden.value = data.name || '';
        hidden.setAttribute("data-id", data.id);
        label.setAttribute("data-id", data.id);
        if(isTypeLabel){
            label.setAttribute("data-type-label", "true");
        }
        label.querySelector('span').textContent = data.name || '';
        const removeBtn = label.querySelector('.remove');
        if(removeBtn){
            removeBtn.addEventListener('click', e=> {
                label.remove();
            });
        }
        return label;
    }

    addLabels(data){
        if(data.labels && this.network){
            const container = this.formElement.querySelector('[data-type="labels-container"]');
            for(let i = 0; i < data.labels.length; i++){
                const labelObj = this.network.labels.find(it => it.name === data.labels[i]);
                if(labelObj){
                    const labelExists = container.querySelector(`[data-id="${labelObj.id}"]`);
                    if(!labelExists){
                        container.prepend(this.createLabel(labelObj, container.children.length, "labels"));
                    }
                }
            }
        }
    }

    addRelations(data){
        const container = this.resourceRelationContent.querySelector('div > div:first-child');
        for(let i = 0; i < data.length; i++){
            if(data[i].name === undefined){
                data[i].name = data[i].type;
                data[i].id = data[i].properties.typeId;
            }
            const labelExists = container.querySelector(`[data-id="${data[i].id}"]`);
            if(!labelExists){
                data[i].color = "#9ec5b2";
                container.prepend(this.createLabel(data[i], i, "relations"));
            }
        }
    }

}

class ResourceEditForm extends ResourceForm{

    constructor(obj) {
        super(obj);
        this.title = $L('edit.resource') + " : " + obj.entityId;
        this.deleteUrl = this.apiURL + "/delete";
        // The node's type is fixed at create: no type-label is offered in the picker, and the
        // existing one is shown as a locked (non-removable) chip.
        this.selectableTypeLabels = [];
        this.lockTypeLabels = true;
    }

    render(){
        super.render();
        this.formElement.action = this.apiURL + "/update";
        if(!this.errors){
            this.loadData( json => {
                this.addLabels(json);
                // loadCompleteCallback has already put the id in the hidden field; name it.
                this.showDataSetById(json.dataSetId);
                Object.entries(json.metadata).forEach(([key, value]) => {
                    this.addMetadataRow(key, value);
                });
            });
        }
        this.renderOpenFindings();
        this.submitButtonElement.firstElementChild.textContent = $L('update');
    }

    // Never the remembered dataset. This form posts whatever the field holds, so prefilling an
    // edit would quietly move the resource on the next save — only the resource's own dataset
    // (loaded above) or a value that survived a rejected submit belongs here.
    dataSetCandidate(){
        const surviving = this.formElement.querySelector('[name="dataSetId"]').value;
        return surviving ? {id: surviving} : null;
    }

    dataSetHint(){
        return '';   // the orphan-create warning does not apply to a resource that already exists
    }

    /*
     * This resource's open policy findings.
     *
     * They used to arrive free with the entity, as a key in `metadata`. They deliberately no longer
     * do: metadata is a user-editable map, so a finding kept there could be forged or quietly
     * deleted by whoever triggered it, which makes it a note rather than a record. The price of
     * that is this second fetch, and it is worth paying.
     *
     * The section is only added once there is something to show, so a conforming resource's form is
     * unchanged.
     */
    renderOpenFindings(){
        if(!window.NamingPolicy || !this.entityId) return;
        window.NamingPolicy.findingsForNode(this.entityId).then(findings => {
            if(!findings.length || !this.formElement.isConnected) return;
            const section = Object.assign(document.createElement('section'), {
                className: "resource-findings",
                innerHTML: `<h3>${window.escapeHtml($L('findings.resource.title'))}</h3>`
            });
            findings.forEach(finding => {
                const row = Object.assign(document.createElement('div'), { className: "finding-row" });
                const message = Object.assign(document.createElement('div'), { className: "finding-message" });
                message.textContent = finding.message || '';
                const meta = Object.assign(document.createElement('div'), { className: "finding-meta" });
                meta.textContent = finding.policyName || '';
                if(finding.suggestion){
                    meta.textContent += '  ' + $L('policy.naming.finding.suggestion', null, [finding.suggestion]);
                }
                const resolve = Object.assign(document.createElement('button'), {
                    type: "button",
                    className: "dh-btn secondary small",
                    title: $L('findings.resolve.title'),
                    textContent: $L('findings.resolve')
                });
                resolve.addEventListener('click', () => {
                    resolve.disabled = true;
                    // The folded finding, not its id: resolving appends an event that has to carry
                    // the finding's own external id, policy and node to name what it closes.
                    window.NamingPolicy.resolveFinding(finding)
                        .then(() => row.remove())
                        .catch(() => {
                            resolve.disabled = false;
                            Flash.error($L('findings.resolve.failed'));
                        });
                });
                row.append(message, meta, resolve);
                section.append(row);
            });
            this.formElement.querySelector('main').append(section);
        });
    }
}

class RootResourceEditForm extends ResourceForm{

    constructor(obj) {
        obj.isRoot = true;
        super(obj);
        this.title = $L('create.root.resource');
    }

}

class ResourceRelationForm extends ResourceForm{

    constructor(obj) {
        super(obj);
        if(obj.relationFrom === undefined){
            throw new Error('Missing relation from value');
        }
        this.relationFrom = obj.relationFrom;
        this.title = $L('create.resource.and.relation');
        // A resource created off a node belongs where that node lives: in the dataset itself when
        // the "+" was on a dataset node, otherwise in whatever dataset the from-node belongs to.
        // Still editable before saving, and an explicit dataSet from the caller wins.
        this.dataSet = this.dataSet || dataSetOfNode(this.relationFrom);

        this.addRelationBtn = Object.assign(document.createElement('button'), {
            type: 'button',
            className: 'dh-btn primary small',
            innerHTML: `<i class="fa fa-fw fa-plus"></i>`
        });
        this.addRelationBtn.setAttribute('data-type', 'add-relation-btn');
        this.addRelationBtn.addEventListener('click', e => {
            const picker = new RelationList({network: this.network});
            picker.setConfirmCallback( data => {
                this.addRelations(data);
            });
            picker.render();
        });
    }

    hasRelationship(){
        return this.resourceRelationContent.querySelectorAll('div.resource-label').length > 0;
    }

    submit(){
        if(this.hasRelationship()){
            this.formElement.querySelectorAll('input[name="relationFromName"]').forEach( it => {
                const inputEle = Object.assign(document.createElement('input'), {
                    type: 'hidden',
                    name: "relationFrom",
                    value: it.getAttribute("data-id")
                });
                this.formElement.append(inputEle);
            })
            this.formElement.querySelectorAll('input[name^="relations"]').forEach( it => {
                const inputEle = Object.assign(document.createElement('input'), {
                    type: 'hidden',
                    name: "relationTypes",
                    value: it.value
                });
                this.formElement.append(inputEle);
            });
            super.submit();
        } else {
            this.formElement.querySelector('main').prepend(
                Object.assign(document.createElement('div'), {
                    className: "flash error",
                    innerHTML: `<span>${$L('select.at.least.one.relation')}</span>`
                })
            );
        }
    }

    render(){
        super.render();
        if(this.resourceRelationContent === undefined){
            this.resourceRelationContent = Object.assign(document.createElement('div'), {
                style: "margin-bottom: 15px;",
                innerHTML: `
				<label>${$L('relationship')}</label>
				<div class="flex-container left-right"><div></div></div>
			`});
            this.resourceRelationContent.querySelector('div').append(this.addRelationBtn);
        }
        if(this.relationFromSection === undefined){
            this.relationFromSection = Object.assign(document.createElement('div'), {
                style: "margin-bottom: 15px;",
                innerHTML: `
				<label>${$L('from.node')}</label>
				<div class="flex-container left-right">
					<button type="button" class="dh-btn primary small" data-type="find-from-node-btn">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			`});
            this.relationFrom.name = this.relationFrom.label;
            this.setFromNode(this.relationFrom);
        }
        const mainContainer = this.formElement.querySelector('main');
        const errorFlash = mainContainer.querySelector(".flash.error");
        if(errorFlash){
            errorFlash.after(this.resourceRelationContent);
            errorFlash.after(this.relationFromSection);
        } else {
            mainContainer.prepend(this.resourceRelationContent);
            mainContainer.prepend(this.relationFromSection);
        }
        this.createFromNodeBtnClickEvent();
    }

    createFromNodeBtnClickEvent(){
        const fromNodeBtn = this.formElement.querySelector('[data-type="find-from-node-btn"]');
        fromNodeBtn.addEventListener('click', e => {
            const searchModal = new ResourceSearch({
                multiSelect: false
            });
            searchModal.setConfirmCallback( selectedData => {
                this.setFromNode(selectedData[0]);
            });
            searchModal.render();
        });
    }

    setFromNode(data){
        const fromNodeLabel = this.createLabel(data, 0, "relationFrom");
        fromNodeLabel.querySelector('input').name = "relationFromName";
        fromNodeLabel.querySelector('.remove.pointer').remove();
        this.relationFromSection.querySelectorAll('.resource-label').forEach( it => {
            it.remove();
        });
        this.relationFromSection.querySelector('div').prepend(fromNodeLabel);
    }

}

class ResourceCloneForm extends ResourceRelationForm{

    constructor(obj) {
        super(obj);
        this.title = $L('clone.resource');
    }

    render(){
        super.render();
        this.formElement.action = this.apiURL + "/save";
        this.submitButtonElement.textContent = $L('datahub.save');
        if(!this.errors){
            this.loadData( json => {
                this.addLabels(json);
                this.addRelations(json.relations);
                // A clone lands where its original lives. Authoritative, unlike the guess the
                // relation form makes from the graph node — and it is the original's own dataset
                // even when the original is itself a dataset node, which has none.
                this.showDataSetById(json.dataSetId);
            });
        }
    }
}

class LabelForm extends DatasetFormAbstract{

    constructor(obj) {
        super(obj);
        this.apiURL = '/api/label';
    }

    getFormFields(){
        return `
			${this.getEntityIdField()}
					
			<label for="name">${$L('name')}</label>
			<input class="${this.fieldError('name')}" type="text" name="name" value="" placeholder="${$L('write.label.name.here')}" pattern="^[a-zA-Z][a-zA-Z0-9_]{0,64}$" tabindex="110"/>
			<label for="description">${$L('description')}</label>
			<textarea class="w100 ${this.fieldError('description')}" name="description" placeholder="${$L('write.description.here')}..." tabindex="120"></textarea>
			<label for="i18nCode">${$L('i18ncode')}</label>
			<input class="${this.fieldError('i18nCode')}" type="text" name="i18nCode" value="" placeholder="${$L('write.i18n.code.here')}" tabindex="130"/>
			<label for="color">${$L('color')}</label>
			<div class="color-pick-wrap">
				<input type="color" name="color" value="#a3528a" tabindex="140"/>
			</div>
		`;
    }

    render() {
        super.render();
        this.formElement.action = this.apiURL + "/save";
    }

    handleResponse(response) {
        if (response.status >= 400) {
            response.json().then(json => {
                /** Errors JSON format should be in
                 {
                 "errors": {"field": "fieldName", "message": "errorMessage"}
                 }
                 */
                this.errors = [];
                if(json.errors){
                    this.errors = json.errors;
                }
                if(json.error && json.error.duplicated){
                    json.error.duplicated.forEach( error => {
                        const field = Object.keys(error)[0];
                        const message = `${json.error.message}`;
                        this.errors.push( {field: field, message: message} );
                    });
                }
                this.render();
            });
        } else {
            this.cancelButtonElement.dispatchEvent(new Event('click'));
            response.json().then(json => {
                if (this.afterSaveFn) {
                    this.afterSaveFn(json);
                }
            });
        }
    }

}

class LabelEditForm extends LabelForm{

    constructor(obj) {
        super(obj);
        this.title = $L('edit.label');
        // A truthy deleteUrl makes BaseFormAbstract.render() add the delete button.
        this.deleteUrl = this.apiURL + "/delete";
    }

    render(){
        super.render();
        this.formElement.action = this.apiURL + "/update";
        if(!this.errors){
            this.loadData();
        }
        this.submitButtonElement.textContent = $L('update');
    }

    // Delete straight on datahub-api (browser-direct, bearer token) rather than through a console
    // proxy — the same pattern the file/dataset/resource lookups use.
    delete(){
        const apiBaseUrl = document.querySelector('meta[name="datahub-api-url"]').content.replace(/\/+$/, '');
        fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
            .then(response => {
                if (response.status === 401) { renderSignedOutDialog(); return null; }
                if (!response.ok) throw new Error('token');
                return response.text();
            })
            .then(token => {
                if (!token) return null;
                return fetch(apiBaseUrl + '/labels/delete', {
                    method: 'DELETE',
                    headers: {
                        'Authorization': 'Bearer ' + token,
                        'Accept': 'application/json',
                        'Content-Type': 'application/json'
                    },
                    // Keep the id as the string it arrived as: the server field is a Long that
                    // reads it fine, so converting buys nothing and rounds above 2^53.
                    body: JSON.stringify({ items: [{ id: this.entityId }] })
                });
            })
            .then(response => {
                if (!response) return;
                if (response.ok) {
                    if (this.afterDeleteFn) this.afterDeleteFn(this);
                    this.cancelButtonElement.dispatchEvent(new Event('click'));
                } else {
                    // The api rejects deleting a label still used by resources — surface why.
                    response.json()
                        .then(json => Flash.error((json && json.error && json.error.message) || $L('could.not.delete.label')))
                        .catch(() => Flash.error($L('could.not.delete.label')));
                }
            })
            .catch(e => { console.error('Label delete failed', e); Flash.error($L('could.not.delete.label')); });
    }
}

class LabelList extends BaseList{

    constructor(obj) {
        super(obj);
        this.network = obj.network || null;
        // Type-labels a caller may pick here (upper-cased). Others in TYPE_LABELS_ALL are hidden
        // from the pick list; the selectable ones are mutually exclusive (see updateTable).
        this.selectableTypeLabels = (obj.selectableTypeLabels || []).map(s => s.toUpperCase());
        this.apiURL = '/api/label';
        this.loadData();
        this.render();
        this.addCRUDButtons();
        this.cancelButton.focus();
        this.editEvent = e => {
            e.stopPropagation(); // Prevent row click event to run
            const id = e.target.closest('td').getAttribute("data-id");
            new LabelEditForm({
                entityId: id,
                afterSave: this.updateTableList,
                afterDelete: this.updateTableList
            }).render();
        }
        this.selectAfterCreate = data => {
            // Reloading rebuilds the table rows, which would drop the .selected state of
            // labels the user already picked. Capture them first and re-select them along
            // with the newly created label so creating a label never clears the selection.
            const idsToSelect = new Set((this.selectedData || []).map(d => String(d.id)));
            idsToSelect.add(String(data.id));
            this.loadData(() => {
                const rows = this.bodyElement.querySelectorAll('table tbody tr');
                for (let i = 0; i < rows.length; i++) {
                    const id = rows[i].getAttribute("data-id");
                    if (idsToSelect.has(id)) {
                        rows[i].classList.add("selected");
                    }
                    if (id === String(data.id)) {
                        rows[i].parentElement.prepend(rows[i]);
                    }
                }
                this.updateSelectedData();
            });
        }
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
                const allItems = json.items || [];
                // Hide the type-labels that aren't selectable in this picker; keep network.labels
                // complete so chip color lookups still resolve every label.
                const visibleItems = allItems.filter( it => {
                    const upper = (it.name || '').toUpperCase();
                    return !(TYPE_LABELS_ALL.includes(upper) && !this.selectableTypeLabels.includes(upper));
                });
                this.data = {
                    columns: [
                        {
                            name: $L('name'),
                            prefix: "name"
                        },
                        {
                            name: $L('color'),
                            prefix: "color"
                        },
                        {
                            icon: "fa-pen"
                        }
                    ],
                    items: visibleItems
                }
                this.updateTable();
                if(afterLoadFn instanceof Function){
                    afterLoadFn();
                }
                if(this.network){
                    // Update available labels for network
                    this.network.labels = allItems;
                }
            });
    }

    render() {
        super.render();
    }

    updateTable() {
        super.updateTable();
        this.bodyElement.querySelectorAll('table tbody td:nth-child(2)').forEach( colorCell => {
            const labelId = colorCell.parentElement.getAttribute("data-id");
            const labelObj = this.data.items.find( it => String(it.id) === labelId);
            if(labelObj){
                colorCell.style.backgroundColor = labelObj.color;
            }
        });
        // Type-labels are mutually exclusive: selecting one clears any other selected type-label,
        // so a node can never be given two. Runs after the base row-click handler.
        if(this.selectableTypeLabels.length){
            const typeLabelName = row => {
                const item = this.data.items.find( it => String(it.id) === row.getAttribute('data-id'));
                return (item && item.name || '').toUpperCase();
            };
            this.bodyElement.querySelectorAll('table tbody tr').forEach( row => {
                if(!this.selectableTypeLabels.includes(typeLabelName(row))) return;
                row.addEventListener('click', () => {
                    if(!row.classList.contains('selected')) return;
                    this.bodyElement.querySelectorAll('table tbody tr.selected').forEach( other => {
                        if(other !== row && this.selectableTypeLabels.includes(typeLabelName(other))){
                            other.classList.remove('selected');
                        }
                    });
                    this.updateSelectedData();
                });
            });
        }
    }

    addCRUDButtons() {
        const btnsHTML = Object.assign(document.createElement("div"), {
            innerHTML: `
				<button type="button" class="dh-btn primary small" title="${$L('create.label')}" tabindex="100">
					<i class="fa fa-fw fa-plus"></i>
				</button>
			`,
        });
        btnsHTML.querySelector('button').addEventListener('click', e => {
            new LabelForm({
                title: $L('create.label'),
                afterSave: this.selectAfterCreate
            }).render();
        });
        // Add buttonsHTML to the right of header
        this.bodyElement.querySelector("div.heading").appendChild(btnsHTML);
    }
}

class RelationForm extends DatasetFormAbstract{

    constructor(obj) {
        super(obj);
        this.title = obj.title || $L('create.relationship');
        this.apiURL = "/api/relationship";
    }

    getFormFields(){
        return `
			${this.getEntityIdField()}
			
			<label>${$L('name')}</label>
			<input class="${this.fieldError('name')}"
					type="text" name="name" 
					value="${this.getFormProperty('name')}" 
					placeholder="${$L('write.relation.name.here')}" tabindex="10" required=""/>
			<label>${$L('i18ncode')}</label>
			<input class="${this.fieldError('i18nCode')}" 
					type="text" name="i18nCode" 
					value="${this.getFormProperty('i18nCode')}" tabindex="20" />
			<label>${$L('description')}</label>
			<textarea class="w100" name="description" placeholder="${$L('write.description.here')}..." tabindex="30"></textarea>
		`;
    }

    render() {
        super.render();
        this.formElement.action = this.apiURL + "/save";
    }
}

class RelationEditForm extends RelationForm{

    constructor(obj) {
        super(obj);
        this.title = obj.title || $L('edit.relationship')
        this.deleteUrl = this.apiURL + "/delete";
    }

    render(){
        super.render();
        this.formElement.action = this.apiURL + "/update";
        if(!this.errors){
            this.loadData();
        }
        this.submitButtonElement.firstElementChild.textContent = $L('update');
    }
}

class RelationList extends BaseList{

    constructor(obj) {
        super(obj);
        this.network = obj.network || null;
        this.apiURL = '/api/relationship';
        this.title = obj.title || $L('relationships')

        this.editEvent = e => {
            e.stopPropagation(); // Prevent row click event to run
            const id = e.target.closest('td').getAttribute("data-id");
            new RelationEditForm({
                entityId: id,
                afterSave: this.updateTableList
            }).render();
        }
    }

    render() {
        super.render();
        this.loadData();
        this.addCRUDButtons();
    }

    addCRUDButtons() {
        const btnsHTML = Object.assign(document.createElement("div"), {
            innerHTML: `
				<button type="button" class="dh-btn primary small" title="${$L('create.relationship')}" tabindex="100">
					<i class="fa fa-fw fa-plus"></i>
				</button>
			`,
        });
        btnsHTML.querySelector('button').addEventListener('click', e => {
            new RelationForm({
                afterSave: this.updateTableList
            }).render();
        });
        // Add buttonsHTML to the right of header
        this.bodyElement.querySelector("div.heading").appendChild(btnsHTML);
    }
}

class ResourceSearch extends BaseList{

    constructor(obj) {
        super(obj);
        this.network = obj.network || null;
        this.apiURL = '/api/resources/search';
        this.title = obj.title || $L('search.for.resource')
    }

    createSearchContainer(){
        const searchContainer = Object.assign(document.createElement('div'), {
            className: 'posrel datahub-form-fields',
            innerHTML: `
				<input id="search-resource" class="w100 left-icon" type="text" name="search-resource" placeholder="${$L('write.resource.name.here')}">
				<i class="fa fa-fw fa-search inside-input"></i>
			`
        });

        let searchTimer;
        searchContainer.querySelector('input[name="search-resource"]').addEventListener('input', e => {
            clearTimeout(searchTimer);
            if(e.target.value && e.target.value.length >= 3){
                searchTimer = setTimeout(() => {
                    this.fetchAndRefreshSearchResults(e.target.value);
                }, 500);
            }
        });
        return searchContainer;
    }

    fetchAndRefreshSearchResults(query) {
        fetch(this.apiURL, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-type': 'application/json',
                [document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
            },
            body: JSON.stringify({"query": query})
        })
            .then(response => {
                return response.json();
            })
            .then( json => {
                this.data = {
                    items: json.items.map( it => {
                        return {
                            // Keep the id as a string: Number() rounds 64-bit ids above 2^53.
                            id: it.id,
                            name: it.name,
                            labels: it.labels || []   // array -> one chip per label
                        };
                    })
                }
                this.updateTable();
            })
            .catch((e) => {
                console.error(e);
            });
    }

    // Render resources as the same dl-row list the datasets index / dataset picker use (colour dot +
    // name + a chip per label) instead of the BaseList table. render() drops an empty <ul> in place;
    // rows are built as DOM nodes (names/labels via textContent, so they are safe) as results arrive.
    renderTable(){
        this.tableHTML = '<ul class="data-list slim pointer" data-type="resource-search-list"></ul>';
    }

    _buildList(){
        const ul = document.createElement('ul');
        ul.className = 'data-list slim pointer';
        ul.setAttribute('data-type', 'resource-search-list');
        (this.data.items || []).forEach(it => {
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
            (it.labels || []).forEach(lbl => {
                const chip = document.createElement('span');
                chip.className = 'dl-chip';
                chip.setAttribute('data-label', lbl);
                chip.textContent = lbl;
                chips.appendChild(chip);
            });
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

    updateTable(){
        const main = this.bodyElement.querySelector('main');
        const old = main.querySelector('ul.data-list');
        const ul = this._buildList();
        if(old){ old.replaceWith(ul); } else { main.appendChild(ul); }
    }

    updateSelectedData(){
        this.selectedData = [];
        this.bodyElement.querySelectorAll('ul.data-list li.dl-row.selected').forEach(row => {
            const id = row.getAttribute('data-id');
            const dataObj = (this.data.items || []).find(d => String(d.id) === id);
            if(dataObj){ this.selectedData.push(dataObj); }
        });
        this.confirmBtn.disabled = this.selectedData.length === 0;
    }

    addChecker(){ /* dl-rows show selection via the accent bar (CSS li.selected); no row-check img */ }

    render() {
        super.render();
        this.mainBody = this.bodyElement.querySelector('main');
        this.mainBody.prepend(this.createSearchContainer());
        this.mainBody.querySelector('#search-resource').focus();
    }

}

class ResourceEdgeForm extends DatasetFormAbstract{

    constructor(obj) {
        super(obj);
        this.title = obj.title || $L('create.edge');
        this.apiURL = "/api/edges";
        this.fromNode = obj.fromNode || null;
        this.network = obj.network || null;
    }

    getFormFields(){
        return `
			${this.getEntityIdField()}
			
			<label>${$L('from.node')}</label>
			<div class="flex-container left-right" data-type="fromNode">
				<span></span>
				<button type="button" class="dh-btn primary small" data-type="find-from-node-btn">
					<i class="fa fa-fw fa-search"></i>
				</button>
			</div>

			<label>${$L('relationship')}</label>
			<div class="flex-container left-right" data-type="relationship">
				<span></span>
				<button type="button" class="dh-btn primary small" data-type="find-relationship-btn">
					<i class="fa fa-fw fa-search"></i>
				</button>
			</div>

			<label>${$L('to.node')}</label>
			<div class="flex-container left-right" data-type="toNode">
				<span></span>
				<button type="button" class="dh-btn primary small" data-type="find-to-node-btn">
					<i class="fa fa-fw fa-search"></i>
				</button>
			</div>
			
			<label>${$L('description')}</label>
			<textarea class="w100 h80px" name="description" placeholder="${$L('write.description.here')}..." tabindex="50"></textarea>

			<div class="animate-flow-field" data-type="animate-flow">
				<label class="animate-flow-toggle-label">
					<input type="checkbox" data-type="animate-flow-toggle" tabindex="55"/>
					<span>${$L('animate.dataflow')}</span>
				</label>
				<span class="animate-flow-help" data-type="animate-flow-help">
					<i class="fa fa-fw fa-circle-question"></i>
					<div class="help-modal">${$L('animate.dataflow.help')}</div>
				</span>
				<input type="text" data-type="animate-flow-value" placeholder="${$L('animate.dataflow.placeholder')}" tabindex="56" disabled/>
			</div>
		`;
    }

    // The animate-data-flow control (checkbox + datatype field) is stored as the reserved
    // "animate::datatype" metadata key, but shown as its own field rather than a metadata row.
    static get ANIMATE_KEY(){ return 'animate::datatype'; }

    setAnimateDatatype(value){
        const toggle = this.formElement.querySelector('[data-type="animate-flow-toggle"]');
        const field = this.formElement.querySelector('[data-type="animate-flow-value"]');
        if(toggle && field){
            toggle.checked = true;
            field.disabled = false;
            field.value = value || '';
        }
    }

    render() {
        super.render();
        this.createClickEvents();
        this.formElement.action = this.apiURL + "/save";
        this.setFromNode(this.fromNode);
        this.renderMetadataContent();
    }

    createLabel(data, name){
        // Guard against a missing node — e.g. an edge endpoint the edit response didn't
        // include — so the form degrades gracefully instead of throwing on data.color.
        if(!data){
            console.warn("createLabel: missing node data for field", name);
            data = {};
        }
        const getColor = (data) =>{
            if(data.color === undefined && this.network && data.labels){
                const label = this.network.labels.find(it => it.name === data.labels);
                if(label){
                    return label.color;
                }
                return "#fff";
            }
            if(data.color === undefined){
                return "#fff";
            }
            if(data.color.background){
                return data.color.background;
            }
            return data.color;
        }
        const color = getColor(data);
        const label = Object.assign(document.createElement('div'), {
            className: "resource-label",
            "data-id": data.id,
            style: "border-color: " + color,
            innerHTML: `
				<div>
					<span></span>
					<input type="hidden" name="${name}" value="${data.id}"/>
				</div>
				<div class="remove pointer"><i class="fa fa-fw fa-xmark"></i></div>`
        });
        label.querySelector('span').textContent = data.name || '';
        label.querySelector('.remove').addEventListener('click', e=> {
            label.remove();
        });
        return label;
    }

    createClickEvents(){

        // From Asset node search
        const fromNodeBtn = this.formElement.querySelector('[data-type="fromNode"] button');
        fromNodeBtn.addEventListener('click', e => {
            const searchModal = new ResourceSearch({
                multiSelect: false
            });
            searchModal.setConfirmCallback( selectedData => {
                this.setFromNode(selectedData[0]);
            });
            searchModal.render();
        });

        // Relationship picker
        const relationshipBtn = this.formElement.querySelector('[data-type="relationship"] button');
        relationshipBtn.addEventListener('click', e => {
            const picker = new RelationList({
                multiSelect: false
            });
            picker.setConfirmCallback( selectedData => {
                this.setRelationship(selectedData[0]);
            });
            picker.render();
        });

        // To Asset node search
        const toNodeBtn = this.formElement.querySelector('[data-type="toNode"] button');
        toNodeBtn.addEventListener('click', e => {
            const searchModal = new ResourceSearch({
                multiSelect: false
            });
            searchModal.setConfirmCallback( selectedData => {
                this.setToNode(selectedData[0]);
            });
            searchModal.render();
        });

        // Animate data-flow: the datatype field is only usable when the toggle is on.
        const animateToggle = this.formElement.querySelector('[data-type="animate-flow-toggle"]');
        const animateValue = this.formElement.querySelector('[data-type="animate-flow-value"]');
        if(animateToggle && animateValue){
            animateToggle.addEventListener('change', () => {
                animateValue.disabled = !animateToggle.checked;
                if(!animateToggle.checked){ animateValue.value = ''; }
                else { animateValue.focus(); }
            });
        }
        // Clickable help tooltip explaining what the feature does.
        const animateHelp = this.formElement.querySelector('[data-type="animate-flow-help"]');
        if(animateHelp){
            const helpModal = animateHelp.querySelector('.help-modal');
            animateHelp.addEventListener('click', e => {
                e.stopPropagation();
                helpModal.style.display = helpModal.style.display === 'block' ? 'none' : 'block';
            });
        }
    }

    setFromNode(data){
        if(data){
            const fromNodeEle = this.formElement.querySelector('[data-type="fromNode"]');
            fromNodeEle.querySelectorAll('.resource-label').forEach( it => it.remove());
            const fromNode = this.createLabel(data, "fromId");
            fromNodeEle.prepend(fromNode);
        }
    }

    setToNode(data){
        const toNodeEle = this.formElement.querySelector('[data-type="toNode"]');
        toNodeEle.querySelectorAll('.resource-label').forEach( it => it.remove());
        const toNode = this.createLabel(data, "toId");
        toNodeEle.prepend(toNode);
    }

    setRelationship(data){
        const relationshipEle = this.formElement.querySelector('[data-type="relationship"]');
        relationshipEle.querySelectorAll('.resource-label').forEach( it => it.remove());
        const rel = this.createLabel(data, "relationshipTypeId");
        relationshipEle.prepend(rel);
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
    }

    addMetadataRow(key, value){
        const row = this.metaDataTable.tBodies[0].insertRow(-1);
        const keyCell = row.insertCell(-1);
        const valueCell = row.insertCell(-1);
        const btnCell = row.insertCell(-1);
        keyCell.innerHTML = `<input type="text" name="metadata[${row.rowIndex+1}].key" placeholder="${$L('metadata.key.here')}" />`;
        valueCell.innerHTML = `<input type="text" name="metadata[${row.rowIndex+1}].value" placeholder="${$L('metadata.value.here')}"/>`;
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

    submit(){
        this.formData = new FormData(this.formElement);
        const obj = Object.fromEntries(this.formData);
        obj.metadata = {};
        const metadataFields = this.formElement.querySelectorAll(`table.metadata tbody tr`);
        metadataFields.forEach( it => {
            const keyField = it.querySelector('[name$="key"]');
            const valueField = it.querySelector('[name$="value"]');
            if(keyField && valueField && keyField.value !== ResourceEdgeForm.ANIMATE_KEY){
                obj.metadata[keyField.value] = valueField.value;
            }
        });
        // The animate-data-flow control is stored under the reserved metadata key, but it lives
        // outside the metadata table (its own checkbox + field).
        const animateToggle = this.formElement.querySelector('[data-type="animate-flow-toggle"]');
        const animateValue = this.formElement.querySelector('[data-type="animate-flow-value"]');
        if(animateToggle && animateToggle.checked && animateValue && animateValue.value.trim()){
            obj.metadata[ResourceEdgeForm.ANIMATE_KEY] = animateValue.value.trim();
        }
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
            console.error("Saving edge failed");
        });
    }

}

class ResourceEditEdgeForm extends ResourceEdgeForm {

    constructor(obj) {
        super(obj);
        this.title = $L('edit.edge') + " : " + obj.entityId;
        this.deleteUrl = this.apiURL + "/delete";
    }

    render() {
        super.render();
        this.formElement.action = this.apiURL + "/update";
        if(!this.errors){
            this.loadData( data => {
                // GET /api/edges/{id} → DataWrapper { items: [EdgeProxy] }. (Tolerate a
                // { relations } shape too, in case the endpoint returns a GraphDataWrapper.)
                const edge = (data.items || data.relations || [])[0];
                if(!edge){
                    console.warn("edit edge: no edge returned for id", this.entityId);
                    return;
                }
                // The from/to resources come from the graph the user is already looking at, so
                // the form doesn't depend on the edge endpoint also returning its endpoints.
                this.setFromNode(this.nodeFromGraph(edge.start));
                this.setToNode(this.nodeFromGraph(edge.end));
                this.setRelationship({id: edge.relationshipTypeId, name: edge.type});
                this.formElement.querySelector('[name="description"]').value = edge.description || '';
                Object.entries(edge.metadata || {}).forEach(([key, value]) => {
                    if(key === ResourceEdgeForm.ANIMATE_KEY){
                        this.setAnimateDatatype(value);   // shown as its own field, not a metadata row
                        return;
                    }
                    this.addMetadataRow(key, value);
                });
            });
        }
        this.submitButtonElement.firstElementChild.textContent = $L('update');
    }

    // Build the from/to node data for a label from the live cytoscape graph (the edge being
    // edited is on screen, so its two endpoints are already loaded there).
    nodeFromGraph(nodeId){
        const n = this.network && this.network.cy.getElementById("node-" + nodeId);
        if(!n || n.empty()){
            return { id: nodeId, name: '', labels: [] };
        }
        const d = n.data();
        return { id: nodeId, name: d.label, labels: d.labels, color: d.color };
    }

    // First validate if start node and end node will have any relations left
    delete(){
        const edge = this.network.cy.getElementById("edge-" + this.entityId);
        const sourceNode = this.network.cy.$('#node-' + edge.source());
        const targetNode = this.network.cy.$('#node-' + edge.target());
        const countEdgesForStartNode = sourceNode.connectedEdges().length;
        const countEdgesForEndNode = targetNode.connectedEdges().length;

        let doDelete = true;
        if(countEdgesForStartNode === 1){
            if(!confirm($L('sum.of.relations.for.start.node.will.be.0.and.node.will.be.deleted'))){
                doDelete = false;
            }
        }
        if(countEdgesForEndNode === 1){
            if(!confirm($L('sum.of.relations.for.end.node.will.be.0.and.node.will.be.deleted'))){
                doDelete = false;
            }
        }
        if(doDelete){
            super.delete();
        }
    }
}

class ResourceAndRelationForm extends DatasetFormAbstract{

    constructor(obj) {
        super(obj);
        this.network = obj.network || null;
        this.title = obj.title || $L('select.resource.and.relationship');
        this.submitEvent = () => {
            if (this.afterSaveFn) {
                const json = [];

                this.formElement.querySelectorAll('[data-type="rel-container"] .resource-label').forEach( label => {
                    const id = label.getAttribute("data-id");
                    const name = label.getAttribute("data-name");

                    const nodes = [];
                    this.formElement.querySelectorAll('[data-type="node-container"] .resource-label').forEach( label => {
                        const nodeId = label.getAttribute("data-id");
                        const nodeName = label.getAttribute("data-name");
                        nodes.push({id: nodeId, name: nodeName});
                    });
                    if(nodes.length > 0){
                        json.push({id: id, name: name, nodes: nodes});
                    }
                });

                if(json.length > 0){
                    this.cancelButtonElement.dispatchEvent(new Event('click'));
                    this.afterSaveFn(json);
                } else {
                    this.renderMissingNodeAndRelFlash();
                }
            } else { console.warn("afterSave is not set"); }
        }
    }

    renderMissingNodeAndRelFlash() {
        this.formElement.querySelector('main').prepend(
            Object.assign(document.createElement('div'), {
                className: "flash error",
                innerHTML: `<span>${$L('select.at.least.one.node.and.one.relation')}</span>`
            })
        );
    }

    getFormFields(){
        return `

			<div data-type="nodes" class="form-section">
				<label>${$L('selected.resource')}</label>
				<div class="flex-container left-right">
					<ul data-type="node-container" class="node-container w100"></ul>
					<button type="button" class="dh-btn primary small" data-type="node-list-btn">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			</div>
			
			<div data-type="relations" class="form-section">
				<label>${$L('selected.relationship')}</label>
				<div class="flex-container left-right">
					<ul data-type="rel-container" class="rel-container w100"></ul>
					<button type="button" class="dh-btn primary small" data-type="relation-list-btn">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			</div>
		`;
    }

    render(){
        super.render();
        this.createClickEvents();
        this.submitButtonElement.type = "button";
        this.submitButtonElement.textContent = $L('confirm');
    }

    createClickEvents(){
        const nodeListBtn = this.formElement.querySelector('[data-type="node-list-btn"]');
        nodeListBtn.addEventListener('click', e => {
            const picker = new ResourceSearch({
                multiSelect: true
            });
            picker.setConfirmCallback( selectedData => {
                const nodeContainer = this.formElement.querySelector('[data-type="node-container"]');
                nodeContainer.innerHTML = "";
                selectedData.forEach( it => {
                    const label = createLabel(it.name, "nodeId");
                    label.setAttribute("data-id", it.id);
                    label.setAttribute("data-name", it.name);
                    nodeContainer.append(label);
                });
            });
            picker.render();
        });
        const relationListBtn = this.formElement.querySelector('[data-type="relation-list-btn"]');
        relationListBtn.addEventListener('click', e => {
            const picker = new RelationList({
                multiSelect: false
            });
            picker.setConfirmCallback( selectedData => {
                const relContainer = this.formElement.querySelector('[data-type="rel-container"]');
                relContainer.innerHTML = "";
                const label = createLabel(selectedData[0].name, "relationshipTypeId");
                label.setAttribute("data-id", selectedData[0].id);
                label.setAttribute("data-name", selectedData[0].name);
                relContainer.append(label);
            });
            picker.render();
        });
    }

}

class PolicyForm extends DatasetFormAbstract {

    constructor(obj) {
        // Treat only a real, positive id as an edit: a row rendered without a data-id would
        // otherwise fall through to the create title and the create URL. Tested as digits rather
        // than converted, and kept as the string it arrived as — the id goes back out in a URL and
        // an update payload, never into arithmetic, so there is nothing to gain by parsing it.
        obj.entityId = /^[1-9]\d*$/.test(String(obj.entityId ?? '')) ? String(obj.entityId) : null;
        // A caller may name the form itself. The policies page does, for the shipped naming
        // default: that row is technically a create, because no policy node exists yet, but it is
        // presented as the rule already in force, so "Create policy" would misdescribe it.
        obj.title = obj.title || (obj.entityId ? $L('edit.policy') : $L('create.policy'));
        obj.apiUrl = "/api/policies";               // used by loadData()
        // Deliberately no deleteUrl: the base form draws its delete button from one, and a policy
        // is deactivated rather than deleted. See addDeactivateButton().
        super(obj);

        this.entityId = obj.entityId || null;
        // When a policy is created from a dataset node, attach it to that dataset: the id rides
        // inside the existing /api/policies/create payload and the API adds a ENFORCED_ON edge.
        this.dataSetId = obj.dataSetId || null;
        // Optional starting values for a create; see the assignment to this.meta in render().
        this.seedMeta = obj.meta || null;
        // Mirrors the policy's `deactivated` metadata key; loaded in loadExistingPolicy().
        this.deactivated = false;

        this.submitUrl = this.entityId
            ? "/api/policies/update"
            : "/api/policies/create";
    }

    // Policy kinds (the issue's families). Params are captured as node metadata.
    //
    // 'naming' is the odd one out in two ways, both deliberate. Its key is lowercase because the
    // metadata value the API reads is the literal string "naming" (NamingPolicy.KIND_NAMING), and
    // it is the only kind that is actually *enforced* today — the other three are still captured
    // and inert, which is why the summary line below says so for them and not for this one.
    kinds() {
        return [
            { key: 'LIFECYCLE',   label: $L('policy.kind.lifecycle')   },
            { key: 'ACCESS',      label: $L('policy.kind.access')      },
            { key: 'REQUIREMENT', label: $L('policy.kind.requirement') },
            { key: 'naming',      label: $L('policy.kind.naming') },
        ];
    }
    // Starter presets — pick one to pre-fill kind + params, then adjust. `needsFunctions` ones
    // are shown disabled (they require the not-yet-built Functions feature).
    starters() {
        return [
            { icon: 'fa-lock',             label: $L('policy.starter.readonly90'),
              meta: { kind:'LIFECYCLE', lifecycleAction:'FREEZE', afterAmount:'90', afterUnit:'days', appliesTo:'dataset', enforced:'SCHEDULED', onFailure:'WARN' } },
            { icon: 'fa-trash',            label: $L('policy.starter.delete30'),
              meta: { kind:'LIFECYCLE', lifecycleAction:'DELETE', afterAmount:'30', afterUnit:'days', appliesTo:'dataset', enforced:'SCHEDULED', onFailure:'BLOCK' } },
            { icon: 'fa-user-lock',        label: $L('policy.starter.restricted'),
              meta: { kind:'ACCESS', accessMode:'RESTRICTED', enforced:'CONTINUOUS', onFailure:'BLOCK' } },
            { icon: 'fa-clipboard-check',  label: $L('policy.starter.owner.source'),
              meta: { kind:'REQUIREMENT', requiredKeys:'owner,source', reqMode:'REQUIRE', enforced:'CONTINUOUS', onFailure:'WARN' } },
            { icon: 'fa-tag',              label: $L('policy.naming.starter'),
              meta: { kind:'naming', preset:'verbatim_tag', mode:'reject', nearDuplicateMode:'reject' } },
            { icon: 'fa-diagram-project',  label: $L('policy.naming.starter.qualified'),
              meta: { kind:'naming', preset:'qualified_tag', mode:'warn', nearDuplicateMode:'warn' } },
            { icon: 'fa-pen',              label: $L('policy.starter.custom'), meta: { kind:'LIFECYCLE' } },
            { icon: 'fa-mask',             label: $L('policy.starter.mask'),          needsFunctions: true },
            { icon: 'fa-key',              label: $L('policy.starter.encrypt'),       needsFunctions: true },
        ];
    }

    // FORM FIELDS
    getFormFields() {
        return `
            ${this.getEntityIdField()}

            <label>${$L('name')}</label>
            <input class="${this.fieldError('name')}" type="text" name="name"
                    placeholder="${$L('write.name.here')}" tabindex="10"/>

            <label>${$L('external.id')}</label>
            <input class="${this.fieldError('externalId')}" type="text" name="externalId"
                    placeholder="${$L('write.external.id.here')}" tabindex="20"/>

            <label>${$L('description')}</label>
            <textarea class="w100 ${this.fieldError('description')}" name="description"
                    placeholder="${$L('write.description.here')}..." tabindex="30"></textarea>

            <div data-type="starter-block">
                <label>${$L('policy.template')}</label>
                <div data-type="starter-gallery" class="policy-starter-gallery"></div>
            </div>

            <label>${$L('policy.kind')}</label>
            <div data-type="kind-selector" class="policy-kind-selector"></div>

            <div data-type="kind-params"></div>

            <div data-type="enforcement-block">
                <label>${$L('policy.enforcement')}</label>
                <div class="policy-field-row">
                    <select data-type="enforced">
                        <option value="CONTINUOUS">${$L('policy.enforcement.continuous')}</option>
                        <option value="SCHEDULED">${$L('policy.enforcement.scheduled')}</option>
                    </select>
                    <select data-type="onFailure">
                        <option value="WARN">${$L('policy.onfailure.warn')}</option>
                        <option value="BLOCK">${$L('policy.onfailure.block')}</option>
                        <option value="SKIP">${$L('policy.onfailure.skip')}</option>
                    </select>
                </div>
            </div>
            <div data-type="policy-summary" class="policy-summary"></div>
        `;
    }

    // RENDER
    render() {
        super.render();

        const meta = this.formElement.querySelector(".metadata-section");
        if (meta) meta.remove();
        const lbl = this.formElement.querySelector('[data-type="label-list-btn"]');
        if (lbl) lbl.remove();

        // Working state — the policy's kind + params. Serialized to metadata on save.
        //
        // A caller may seed it (the policies page opens the shipped naming default this way), so a
        // create can start from values that are already in force rather than from the generic
        // lifecycle starting point.
        this.meta = this.seedMeta
            ? Object.assign({}, this.seedMeta)
            : { kind: 'LIFECYCLE', enforced: 'CONTINUOUS', onFailure: 'WARN' };

        // Templates are a starting point for a blank policy. Editing one, or opening a create that
        // was already seeded with values, means the choice has been made: offering to overwrite it
        // with a template is an invitation to lose the settings you came to look at.
        const starterBlock = this.formElement.querySelector('[data-type="starter-block"]');
        if (starterBlock && (this.entityId || this.seedMeta)) {
            starterBlock.remove();
        } else {
            this.renderStarterGallery();
        }
        this.renderKindSelector();
        this.renderKindParams();
        this.syncCommonInputs();
        this.updateSummary();

        this.formElement.querySelector('[data-type="enforced"]')
            .addEventListener('change', e => { this.meta.enforced = e.target.value; this.updateSummary(); });
        this.formElement.querySelector('[data-type="onFailure"]')
            .addEventListener('change', e => { this.meta.onFailure = e.target.value; this.updateSummary(); });

        this.addDeactivateButton();

        if (this.entityId) this.loadExistingPolicy();
        else this.autoFillIdentity();
    }

    // Create: identity is auto (name defaults, externalId a stable stamp) — the user only opens
    // Advanced to override. Every policy is addressable from creation (future Functions bind to it).
    autoFillIdentity() {
        const nameEl = this.formElement.querySelector('input[name="name"]');
        const extEl = this.formElement.querySelector('input[name="externalId"]');
        if (nameEl && !nameEl.value) nameEl.value = $L('policy.default.name');
        if (extEl && !extEl.value) extEl.value = 'policy_' + Date.now().toString(36);
    }

    // Deactivate rather than delete. A policy is a node other things point at and its findings
    // outlive it, so deleting one to stop it applying discards the record of what it decided. The
    // server drops a deactivated policy out of resolution, so whatever it overrode applies again.
    addDeactivateButton() {
        if (!this.entityId) return;   // nothing to deactivate until it exists
        this.deactivateButton = document.createElement('button');
        this.deactivateButton.type = 'button';
        this.deactivateButton.className = 'dh-btn danger';
        this.deactivateButton.setAttribute('data-type', 'form-deactivate-btn');
        this.deactivateButton.addEventListener('click', () => {
            this.deactivated = !this.deactivated;
            this.submit();
        });
        this.formElement.querySelector('footer').appendChild(this.deactivateButton);
        this.syncDeactivateButton();
    }

    syncDeactivateButton() {
        if (!this.deactivateButton) return;
        this.deactivateButton.innerHTML =
            `<span>${$L(this.deactivated ? 'policy.activate' : 'policy.deactivate')}</span>`;
        this.deactivateButton.title = $L(this.deactivated ? 'policy.activate.title' : 'policy.deactivate.title');
    }

    renderStarterGallery() {
        const c = this.formElement.querySelector('[data-type="starter-gallery"]');
        c.innerHTML = '';
        this.starters().forEach(s => {
            const card = document.createElement('button');
            card.type = 'button';
            card.className = 'policy-starter' + (s.needsFunctions ? ' is-disabled' : '');
            card.innerHTML = `<i class="fa-solid ${s.icon}"></i>`
                + `<span class="policy-starter-text">${s.label}`
                + (s.needsFunctions ? `<em>${$L('policy.starter.needs.functions')}</em>` : '')
                + `</span>`;
            if (!s.needsFunctions) card.addEventListener('click', () => this.applyStarter(s, card));
            c.appendChild(card);
        });
    }

    applyStarter(s, card) {
        // A starting point: reset to the preset, then the user can adjust freely.
        this.meta = Object.assign({ kind:'LIFECYCLE', enforced:'CONTINUOUS', onFailure:'WARN' }, s.meta || {});
        this.formElement.querySelectorAll('.policy-starter').forEach(el => el.classList.remove('is-selected'));
        card.classList.add('is-selected');
        this.renderKindSelector();
        this.renderKindParams();
        this.syncCommonInputs();
        this.updateSummary();
    }

    renderKindSelector() {
        const c = this.formElement.querySelector('[data-type="kind-selector"]');
        c.innerHTML = '';
        // A saved policy's kind is fixed. Each kind stores a different set of metadata keys and is
        // validated against a different scope, so switching it on an existing node would leave the
        // policy holding params for a kind it no longer is. Create a new policy instead.
        const locked = !!this.entityId;
        this.kinds().forEach(k => {
            const selected = this.meta.kind === k.key;
            if (locked && !selected) {
                return;   // show what it is, not what it could have been
            }
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'policy-kind' + (selected ? ' is-selected' : '') + (locked ? ' is-locked' : '');
            b.textContent = k.label;
            if (locked) {
                b.disabled = true;
                b.title = $L('policy.kind.locked');
            } else {
                b.addEventListener('click', () => {
                    this.meta.kind = k.key;
                    this.renderKindSelector();
                    this.renderKindParams();
                    this.updateSummary();
                });
            }
            c.appendChild(b);
        });
    }

    renderKindParams() {
        const c = this.formElement.querySelector('[data-type="kind-params"]');
        const m = this.meta;
        const opt = (v, list) => list.map(o => `<option value="${o[0]}" ${v===o[0]?'selected':''}>${o[1]}</option>`).join('');

        // Naming is enforced on the write path, not by a sweep, so the schedule/on-failure pair
        // describes nothing for it. Its own mode selects below carry that decision instead.
        const enforcement = this.formElement.querySelector('[data-type="enforcement-block"]');
        if (enforcement) enforcement.hidden = m.kind === 'naming';

        if (m.kind === 'LIFECYCLE') {
            c.innerHTML = `
                <label>${$L('policy.lifecycle.action')}</label>
                <select data-p="lifecycleAction">${opt(m.lifecycleAction||'FREEZE',[['FREEZE',$L('policy.lifecycle.freeze')],['DELETE',$L('policy.lifecycle.delete')],['COLD_STORE',$L('policy.lifecycle.coldstore')]])}</select>
                <label>${$L('policy.lifecycle.after')}</label>
                <div class="policy-field-row">
                    <input data-p="afterAmount" type="number" min="1" style="max-width:110px;" value="${m.afterAmount||''}" placeholder="e.g. 90"/>
                    <select data-p="afterUnit">${opt(m.afterUnit||'days',[['days',$L('policy.unit.days')],['weeks',$L('policy.unit.weeks')],['months',$L('policy.unit.months')]])}</select>
                </div>
                <label>${$L('policy.appliesto')}</label>
                <select data-p="appliesTo">${opt(m.appliesTo||'dataset',[['dataset',$L('policy.appliesto.dataset')],['labels',$L('policy.appliesto.labels')],['timeseries',$L('policy.appliesto.timeseries')],['events',$L('policy.appliesto.events')]])}</select>`;
        } else if (m.kind === 'ACCESS') {
            c.innerHTML = `
                <label>${$L('policy.mode')}</label>
                <select data-p="accessMode">${opt(m.accessMode||'RESTRICTED',[['RESTRICTED',$L('policy.access.restricted')],['HIDDEN',$L('policy.access.hidden')]])}</select>
                <label>${$L('policy.access.role')}</label>
                <input data-p="accessRole" type="text" value="${m.accessRole||''}" placeholder="e.g. datasets:owner"/>`;
        } else if (m.kind === 'REQUIREMENT') {
            c.innerHTML = `
                <label>${$L('policy.requirement.label')}</label>
                <input data-p="reqLabel" type="text" value="${m.reqLabel||''}" placeholder="e.g. external_function"/>
                <label>${$L('policy.requirement.keys')}</label>
                <input data-p="requiredKeys" type="text" value="${m.requiredKeys||''}" placeholder="owner, source, host"/>
                <label>${$L('policy.mode')}</label>
                <select data-p="reqMode">${opt(m.reqMode||'REQUIRE',[['REQUIRE',$L('policy.requirement.require')],['WARN',$L('policy.requirement.warnonly')]])}</select>`;
        } else if (m.kind === 'naming') {
            // The regex field only exists for preset=pattern: shown unconditionally it reads as a
            // second, independent rule rather than as the body of the third preset.
            // Defaults mirror NamingPolicy.shippedDefault(): qualified_tag, warning rather than
            // rejecting. A form that opened on something other than what an unconfigured tenant is
            // already running would misreport the current behaviour as a change the user made.
            const preset = m.preset || 'qualified_tag';
            c.innerHTML = `
                <label>${$L('policy.naming.preset')}</label>
                <select data-p="preset">${opt(preset, [
                    ['qualified_tag', $L('policy.naming.preset.qualified_tag')],
                    ['verbatim_tag', $L('policy.naming.preset.verbatim_tag')],
                    ['snake_case',   $L('policy.naming.preset.snake_case')],
                    ['pattern',      $L('policy.naming.preset.pattern')]])}</select>
                <div data-type="naming-pattern" ${preset === 'pattern' ? '' : 'hidden'}>
                    <label>${$L('policy.naming.pattern')}</label>
                    <input data-p="pattern" type="text" value="${window.escapeHtml(m.pattern||'')}"
                            placeholder="${$L('policy.naming.pattern.placeholder')}"/>
                </div>
                <label>${$L('policy.naming.mode')}</label>
                <select data-p="mode">${opt(m.mode||'warn', [
                    ['reject', $L('policy.naming.mode.reject')],
                    ['warn',   $L('policy.naming.mode.warn')]])}</select>
                <label>${$L('policy.naming.nearduplicate')}</label>
                <select data-p="nearDuplicateMode">${opt(m.nearDuplicateMode||'warn', [
                    ['reject', $L('policy.naming.nearduplicate.reject')],
                    ['warn',   $L('policy.naming.nearduplicate.warn')]])}</select>
                <p class="field-hint">${$L('policy.naming.nearduplicate.help')}</p>`;
        }
        c.querySelectorAll('[data-p]').forEach(el => {
            const key = el.getAttribute('data-p');
            const set = () => {
                this.meta[key] = el.value;
                if (key === 'preset') {
                    const patternBlock = c.querySelector('[data-type="naming-pattern"]');
                    if (patternBlock) patternBlock.hidden = el.value !== 'pattern';
                }
                this.updateSummary();
            };
            el.addEventListener('input', set);
            el.addEventListener('change', set);
        });
    }

    syncCommonInputs() {
        const e = this.formElement.querySelector('[data-type="enforced"]');
        const f = this.formElement.querySelector('[data-type="onFailure"]');
        if (e) e.value = this.meta.enforced || 'CONTINUOUS';
        if (f) f.value = this.meta.onFailure || 'WARN';
    }

    updateSummary() {
        const m = this.meta, el = this.formElement.querySelector('[data-type="policy-summary"]');
        if (!el) return;
        let s = '';
        if (m.kind === 'LIFECYCLE') {
            const act = $L('policy.summary.action.' + (m.lifecycleAction || 'FREEZE').toLowerCase());
            const when = m.afterAmount
                ? $L('policy.summary.after', null, [m.afterAmount, $L('policy.unit.' + (m.afterUnit || 'days'))])
                : $L('policy.summary.after.unset');
            const scope = m.appliesTo && m.appliesTo !== 'dataset'
                ? ' (' + $L('policy.appliesto.' + m.appliesTo) + ')' : '';
            s = $L('policy.summary.lifecycle', null, [act, when]) + scope;
        } else if (m.kind === 'ACCESS') {
            s = m.accessMode === 'HIDDEN'
                ? $L('policy.summary.access.hidden')
                : (m.accessRole
                    ? $L('policy.summary.access.restricted.role', null, [m.accessRole])
                    : $L('policy.summary.access.restricted'));
        } else if (m.kind === 'REQUIREMENT') {
            s = $L('policy.summary.requirement', null, [
                m.requiredKeys || $L('policy.summary.requirement.keys.any'),
                m.reqLabel
                    ? $L('policy.summary.requirement.nodes', null, [m.reqLabel])
                    : $L('policy.summary.requirement.label.any'),
                $L(m.reqMode === 'WARN' ? 'policy.summary.warns' : 'policy.summary.blocks')
            ]);
        } else if (m.kind === 'naming') {
            // Naming is enforced at write time, so it gets neither the enforcement selects (which
            // describe scheduled sweeps) nor the "not enforced yet" disclaimer below.
            // Same defaults as the fields above, and as NamingPolicy.shippedDefault().
            const preset = m.preset || 'qualified_tag';
            const presetText = preset === 'pattern'
                ? $L('policy.naming.summary.pattern', null, [m.pattern || ''])
                : $L('policy.naming.summary.' + preset);
            el.textContent = [
                presetText,
                $L('policy.naming.summary.mode.' + (m.mode || 'warn')),
                $L('policy.naming.summary.nearduplicate.' + (m.nearDuplicateMode || 'warn'))
            ].join(' ');
            return;
        }
        const how = $L('policy.summary.enforced.'
            + ((m.enforced || 'CONTINUOUS') === 'CONTINUOUS' ? 'continuous' : 'scheduled'));
        const onFail = $L('policy.summary.onfailure.' + (m.onFailure || 'WARN').toLowerCase());
        s += ' ' + $L('policy.summary.enforcement', null, [how, onFail]);
        el.textContent = s + '  ' + $L('policy.summary.not.enforced');
    }

    loadExistingPolicy() {
        fetch(`/api/policies/${this.entityId}`)
            .then(r => r.json())
            .then(json => {
                const p = json.items[0];
                this.formElement.querySelector('input[name="name"]').value = p.name || '';
                this.formElement.querySelector('input[name="externalId"]').value = p.externalId || '';
                this.formElement.querySelector('textarea[name="description"]').value = p.description || '';
                // Rebuild working state from stored metadata (kind + params).
                this.meta = Object.assign({ kind:'LIFECYCLE', enforced:'CONTINUOUS', onFailure:'WARN' }, p.metadata || {});
                if (!this.meta.kind) this.meta.kind = 'LIFECYCLE';
                this.deactivated = p.isDeactivated === true;
                delete this.meta.deactivated;   // storage detail, not a kind param
                this.syncDeactivateButton();
                this.renderKindSelector();
                this.renderKindParams();
                this.syncCommonInputs();
                this.updateSummary();
            });
    }

    createFunctions() {
        // completely override parent createFunctions

        this.cancelEvent = e => {
            e.preventDefault();

            const f = this.formElement;
            const main = this.mainElement;

            // Start fly-out animation
            f.classList.remove("visible");

            // If this is the only form → hide the whole right-panel
            if (main.childElementCount === 1) {
                main.classList.remove("visible");
            }

            // Remove after animation completes (500 ms)
            setTimeout(() => {
                f.remove();

                // If no forms left → keep panel hidden
                if (main.childElementCount === 0) {
                    main.classList.remove("visible");
                }

                if (this.afterCancelFn) {
                    this.afterCancelFn();
                }

            }, 500);
        };

        this.submitEvent = e => {
            e.preventDefault();
            this.submit();   // your custom submit
        };
    }

    attachPolicyListeners() {
        const submitBtn = this.formElement.querySelector("footer .dh-btn.primary");
        const cancelBtn = this.formElement.querySelector(".dh-btn.secondary");

        // remove leftover listeners
        const submitClone = submitBtn.cloneNode(true);
        const cancelClone = cancelBtn.cloneNode(true);

        submitBtn.replaceWith(submitClone);
        cancelBtn.replaceWith(cancelClone);

        // attach custom
        cancelClone.addEventListener("click", this.cancelEvent);
        submitClone.addEventListener("click", e => {
            e.preventDefault();
            this.submit();
        });
    }



    // Only the keys relevant to the chosen kind (+ common) are saved, so switching kinds doesn't
    // leave stale params behind.
    buildMetadata() {
        const m = this.meta || { kind: 'LIFECYCLE' };
        const byKind = {
            LIFECYCLE:   ['lifecycleAction','afterAmount','afterUnit','appliesTo'],
            ACCESS:      ['accessMode','accessRole'],
            REQUIREMENT: ['reqLabel','requiredKeys','reqMode'],
            // Mirrors NamingPolicy.KEY_* in api-model. `pattern` is dropped by the empty-value
            // filter below unless preset=pattern actually put one there.
            naming:      ['preset','pattern','mode','nearDuplicateMode'],
        };
        // enforced/onFailure describe a sweep and mean nothing for naming, which is enforced on the
        // write path — writing them anyway would leave misleading keys in the policy's metadata.
        const common = m.kind === 'naming' ? ['kind'] : ['kind','enforced','onFailure'];
        const keep = common.concat(byKind[m.kind] || []);
        const out = { kind: m.kind || 'LIFECYCLE' };
        keep.forEach(k => { if (m[k] !== undefined && m[k] !== null && String(m[k]) !== '') out[k] = String(m[k]); });
        return out;
    }

    // SUBMIT
    submit() {
        this.errors = null;
        const name = this.formElement.querySelector('input[name="name"]').value || $L('policy.default.name');
        const externalId = this.formElement.querySelector('input[name="externalId"]').value;
        const description = this.formElement.querySelector('textarea[name="description"]').value;

        // Create takes the whole policy; update takes an explicit patch (`{field: {set: ...}}`),
        // so each field says whether it is being changed. Re-sending `deactivated` on every save is
        // no longer needed to stop an edit switching a disabled policy back on — omitting a field
        // now means "leave it alone" — but the toggle still rides along, since this same form is
        // what turns a policy off.
        const payload = this.entityId
            ? { items: [{
                id: this.entityId,
                update: {
                    name: { set: name },
                    externalId: { set: externalId },
                    description: { set: description },
                    deactivated: { set: this.deactivated },
                    metadata: { set: this.buildMetadata() }
                }
            }]}
            : { items: [{
                name,
                externalId,
                description,
                isDeactivated: this.deactivated,
                // Set only when creating from a dataset node.
                dataSetId: this.dataSetId,
                metadata: this.buildMetadata()
            }]};

        fetch(this.submitUrl, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json",
                [document.querySelector('meta[name="_csrf_header"]').content]:
                document.querySelector('meta[name="_csrf"]').content
            },
            body: JSON.stringify(payload)
        })
            .then(response => this.handleResponse(response))
            .catch(err => console.error("Policy save failed", err));
    }

    // DELETE
    delete() {
        const body = {
            items: [
                { id: this.entityId }
            ]
        };

        fetch(`/api/policies/delete`, {
            method: "DELETE",
            headers: {
                "Content-Type": "application/json",
                "Accept": "application/json",
                [document.querySelector('meta[name="_csrf_header"]').content]:
                document.querySelector('meta[name="_csrf"]').content
            },
            body: JSON.stringify(body)
        })
            .then(r => {
                if (r.status === 200 || r.status === 204) {
                    const cancelBtn = this.formElement.querySelector('.dh-btn.secondary');
                    cancelBtn?.click();
                    if (this.afterDeleteFn) this.afterDeleteFn(this);
                }
            });
    }


}
