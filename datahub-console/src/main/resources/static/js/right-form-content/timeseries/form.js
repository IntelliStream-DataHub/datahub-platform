// The relations feeding a timeseries, from a node's `relatedResources`.
//
// That field replaced the inbound-only `relationsFrom`, and it lists both directions with a
// `direction` telling them apart — so filtering is what preserves the old "from" meaning. Entries
// written by this form on submit carry no direction (the server fills it only on read), and those
// are inbound by construction, so treat a missing direction as inbound.
const inboundRelations = relatedResources =>
	(relatedResources || []).filter(relation => relation.direction !== 'OUTBOUND');

class TimeseriesForm extends DatasetFormAbstract{

	constructor(obj) {
		super(obj);
		this.title = $L('create.timeseries');
		this.apiURL = "/api/timeseries";
		this.network = obj.network || null;
		this.removeRelResourceFn = function() {
			this.node.closest('li').remove();
		};
	}

	getFormFields(){
		return `
			${this.getEntityIdField()}
			<!-- No isRoot: a time series is never a navigation root. The field was posted as the
			     string "undefined" (this.isRoot is never assigned on this form), which the api
			     ignored while the property was unbindable and now rejects. -->

			<div data-type="relations" class="form-section">
				<label>${$L('from.relations')}</label>
				<div class="flex-container left-right">
					<ul class="node-container w100"></ul>
					<button type="button" class="dh-btn primary small" data-type="relation-list-btn">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			</div>
			
			${NamingHelp.label('timeseries')}
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
			
			<input class="${this.fieldError('unit')}" 
					type="hidden" name="unitExternalId"
					value="${this.getFormProperty('unitExternalId')}" />
			<input class="${this.fieldError('unit')}" 
					type="hidden" name="unit"
					value="${this.getFormProperty('unitExternalId')}"  tabindex="50"/>
					
			<div class="form-section">
				<label>${$L('main.unit')}</label>
				<div class="flex-container left-right">
					<div>
						<span data-type="unit-name" class="mright10"></span>
						<span data-type="unit-symbol"></span>
					</div>
					<button type="button" class="dh-btn primary small" style="margin-left: 10px" data-type="unit-list-btn"  tabindex="60">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			</div>
			
			<div class="form-section">
				<label>${$L('choose.dataset')}</label>
				<div class="flex-container left-right">
					<input type="hidden" name="dataSetId" value="${this.getFormProperty('dataSetId')}"/>
					<div>
						<span data-type="data-set" class="mright10"></span>
					</div>
					<button type="button" class="dh-btn primary small" style="margin-left: 10px" data-type="data-set-list-btn">
						<i class="fa fa-fw fa-search"></i>
					</button>
				</div>
			</div>
			
			<section data-type="value-type">
				<label>${$L('value.type')}
						<i class="fa fa-fw fa-circle-info pointer" data-type="value-type-help" title="${$L('value.type.help')}"></i>
					</label>
				<select class="${this.fieldError('valueType')}"
						type="text" name="valueType"
						 tabindex="80">
					<option value="bigint" title="${$L('value.type.help.bigint')}">${$L('bigint')}</option>
					<option value="float" title="${$L('value.type.help.float')}">${$L('float')}</option>
					<option value="float32" title="${$L('value.type.help.float32')}">${$L('float32')}</option>
					<option value="decimal32" title="${$L('value.type.help.decimal32')}">${$L('decimal32')}</option>
					<option value="numeric" title="${$L('value.type.help.numeric')}">${$L('datahub.numeric')}</option>
					<option value="text" title="${$L('value.type.help.text')}">${$L('text')}</option>
					<option value="mixed" title="${$L('value.type.help.mixed')}">${$L('mixed')}</option>
				</select>
			</section>
			
			<label class="checkbox-icons ${this.fieldError('isStep')}" for="isStep">
				<input type="checkbox" id="isStep" name="isStep" tabindex="90"
					${this.getFormProperty('isStep') === true ? "checked=''" : ""}/>
				<span>
					<img class="row-check" src="/static/img/row-check.svg" alt="row selected">
				</span>
				<span>${$L('is.step')}</span>
			</label>
		`;
	}

	render() {
		super.render();
		this.formElement.action = this.apiURL + "/save";

		this.wireExternalIdField();

		this.renderMetadataContent();
		this.createFromResourceRelation();

		// Per-type help lives on each <option> (hover) now; the info icon opens a full
		// description of the value-type field on click.
		const valueTypeHelp = this.formElement.querySelector('[data-type="value-type-help"]');
		if(valueTypeHelp){
			valueTypeHelp.addEventListener('click', () => this.showValueTypeHelp());
		}
	}

	submit(){
		this.formData = new FormData(this.formElement);
		const obj = Object.fromEntries(this.formData);
		obj.metadata = {};
		// `relatedResources`, not the old `relationsFrom`: that field is gone from the wire
		// contract, so anything sent under it is dropped and the timeseries is created with no
		// relations at all.
		obj.relatedResources = [];
		this.formElement.querySelectorAll('ul.node-container li').forEach( fromResource => {
			const fromResourceId = fromResource.querySelector('input[name="relationsFromNodeId"]').value;
			const relType = fromResource.querySelector('input[name="relationsFromRelType"]').value;
			// Keep the node id as a string: Number() rounds 64-bit ids above 2^53. The server
			// field is a Long and Jackson coerces the JSON string.
			// `name` is for re-populating this form after a failed validation, and is ignored
			// by the server.
			obj.relatedResources.push({id: fromResourceId, relationshipType: relType, name: fromResource.querySelector('span').textContent});
		});

		const metadataFields = this.formElement.querySelectorAll(`table.metadata tbody tr`);
		metadataFields.forEach( it => {
			const keyField = it.querySelector('[name$="key"]');
			const valueField = it.querySelector('[name$="value"]');
			if(keyField && valueField && keyField.value !== "" && valueField.value !== ""){
				obj.metadata[keyField.value] = valueField.value;
			}
		});

		this.jsonData = obj;

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

	addFromResourceAndRelType(data){
		const nodeContainer = this.formElement.querySelector('.node-container');
		const nodeColor = "#fff";
		data.forEach( rel => {
			const liEle = Object.assign(document.createElement('li'), {
				className: "flex-container left-c-right wrap",
				innerHTML: `
								<div class="resource-label" data-type="rel-type">
									<span></span>
									<input type="hidden" name="relationsFromRelType" value=""/>
								</div>`
			});
			liEle.querySelector('input[name="relationsFromRelType"]').value = rel.name;
			rel.nodes.forEach( node => {
				const nodeEle = Object.assign(document.createElement('div'), {
					className: "resource-label",
					style: "border-color: " + nodeColor,
					innerHTML: `
									<span></span>
									<input type="hidden" name="relationsFromNodeId" value="${node.id}"/>
								`
				});
				nodeEle.querySelector('span').textContent = node.name;
				liEle.prepend(nodeEle);
			})
			liEle.querySelector('[data-type="rel-type"] span').textContent = "-----> " + rel.name;
			nodeContainer.appendChild(liEle);
		});
	}

	createFromResourceRelation(){
		const fromNodeBtn = this.formElement.querySelector('[data-type="relation-list-btn"]');
		fromNodeBtn.addEventListener('click', e => {
			const rAndRForm = new ResourceAndRelationForm({
				network: this.network,
				afterSave: data => {
					this.addFromResourceAndRelType(data);
				}
			});
			rAndRForm.render();
		});
		this.findUnitBtn = this.formElement.querySelector('[data-type="unit-list-btn"]');
		this.findUnitBtn.addEventListener('click', e => {
			const unitList = new UnitList({});
			unitList.setConfirmCallback( selectedUnits => {
				this.setUnitAndExternalUnit(selectedUnits[0]);
			});
		});

        this.findDataSetBtn = this.formElement.querySelector('[data-type="data-set-list-btn"]');
        this.findDataSetBtn.addEventListener('click', e => {
            const unitList = new DataSetList({});
            unitList.setConfirmCallback( selectedUnits => {
                this.setDataSet(selectedUnits[0]);
            });
        });

		// If user submit validation fails, we can save the request to a object and repopulate
		// from resource entries when re-rendering
		if(this.jsonData && this.jsonData.relatedResources){
			inboundRelations(this.jsonData.relatedResources).forEach( it => {
				const data = [{name: it.relationshipType, nodes: [{id: it.id, name: it.name}]}];
				this.addFromResourceAndRelType(data);
			})
		}
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
			if(data.color !== undefined && data.color.background){
				return data.color.background;
			} else {
				return "#fff"
			}
		}
		const color = getColor(data);
		const label = Object.assign(document.createElement('div'), {
			className: "resource-label",
			style: "border-color: " + color,
			innerHTML: `
				<div>
					<span></span>
					<input type="hidden" name="${name}[${idx}]" value=""/>
				</div>
				<div class="remove pointer"><i class="fa fa-fw fa-xmark"></i></div>`
		});
		label.setAttribute("data-id", data.id);
		const hidden = label.querySelector('input');
		hidden.setAttribute("data-id", data.id);
		hidden.value = data.name;
		label.querySelector('span').textContent = data.name;
		label.querySelector('.remove').addEventListener('click', e=> {
			label.remove();
		});
		return label;
	}

	updateUnitField(data){
		if(data.unitExternalId !== null){
			fetch("/api/units/byids", {
				method: 'POST',
				headers: {
					'Accept': 'application/json',
					'Content-Type': 'application/json',
					[document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
				},
				body: JSON.stringify({
					"items": [{"externalId": data.unitExternalId}]
				})
			}).then(response => {
				if(response.status === 200){
					return response.json().then(json => {
						this.setUnitAndExternalUnit(json.items[0]);
					});
				}
			})
		} else {
			this.formElement.querySelector('[data-type="unit-name"]').textContent = data.unit;
			this.formElement.querySelector('[name="unit"]').value = data.unit;
		}
	}

	setUnitAndExternalUnit(unit){
		this.formElement.querySelector('[name="unit"]').value = unit.symbol;
		this.formElement.querySelector('[name="unitExternalId"]').value = unit.externalId;
		this.formElement.querySelector('[data-type="unit-name"]').textContent = unit.name;
		this.formElement.querySelector('[data-type="unit-symbol"]').textContent = `(${unit.symbol})`;
		this.recommendValueTypeForUnit(unit.externalId);
	}

	// Ask the datahub-api which value type compresses best for the chosen unit, then pre-select it and
	// surface the reason as the value-type tooltip. Called directly against the api (token from
	// /token) rather than through the console proxy. No-op on the edit form (value-type select removed).
	recommendValueTypeForUnit(unitExternalId){
		const select = this.formElement.querySelector('select[name="valueType"]');
		if(!select || !unitExternalId) return;

		const apiUrlMeta = document.querySelector('meta[name="datahub-api-url"]');
		const apiUrl = apiUrlMeta ? apiUrlMeta.content : "";

		fetch('/token')
			.then(r => r.ok ? r.text() : Promise.reject('could not get access token'))
			.then(token => fetch(`${apiUrl}/timeseries/recommend-value-type/${encodeURIComponent(unitExternalId)}`, {
				headers: { 'Accept': 'application/json', 'Authorization': 'Bearer ' + token },
				signal: AbortSignal.timeout(10000)
			}))
			.then(r => r.ok ? r.json() : Promise.reject('recommendation request failed'))
			.then(rec => {
				const option = select.querySelector(`option[value="${(rec.recommendedValueType || '').toLowerCase()}"]`);
				if(option){
					select.value = option.value;
				}
				const help = this.formElement.querySelector('[data-type="value-type-help"]');
				if(help && rec.reason){
					help.setAttribute('title', rec.reason);
				}
			})
			.catch(e => console.error('Value type recommendation failed', e));
	}

	// Open a dialog describing what the value-type field is and what each type is for.
	showValueTypeHelp(){
		const types = [
			['bigint', 'value.type.help.bigint'],
			['float', 'value.type.help.float'],
			['float32', 'value.type.help.float32'],
			['decimal32', 'value.type.help.decimal32'],
			['datahub.numeric', 'value.type.help.numeric'],
			['text', 'value.type.help.text'],
			['mixed', 'value.type.help.mixed']
		];
		const rows = types
			.map(([labelKey, helpKey]) => `<li><strong>${$L(labelKey)}</strong> — ${$L(helpKey)}</li>`)
			.join('');
		const dialog = Object.assign(document.createElement('dialog'), {
			className: "value-type-help-dialog",
			innerHTML: `
				<h3 style="margin-top:0">${$L('value.type')}</h3>
				<p>${$L('value.type.description')}</p>
				<ul style="padding-left:18px; line-height:1.5">${rows}</ul>
				<div style="text-align:right">
					<button type="button" class="dh-btn primary" data-type="close">${$L('close')}</button>
				</div>`
		});
		Object.assign(dialog.style, {
			maxWidth: "560px", padding: "20px", border: "none", borderRadius: "8px"
		});
		document.body.appendChild(dialog);
		dialog.showModal();
		const close = () => { dialog.close(); dialog.remove(); };
		dialog.querySelector('[data-type="close"]').addEventListener('click', close);
		dialog.addEventListener('click', e => { if(e.target === dialog) close(); });
	}

    setDataSet(dataSet){
        this.formElement.querySelector('[name="dataSetId"]').value = dataSet.id;
        this.formElement.querySelector('[data-type="data-set"]').textContent = dataSet.name;
    }
}

class TimeseriesEditForm extends TimeseriesForm{

	constructor(obj) {
		super(obj);
		this.title = $L('edit.timeseries') + " : " + obj.entityId;
		this.deleteUrl = this.apiURL + "/delete";
	}

	loadData(callback){
		if(this.entityId === null) return;
		fetch(this.apiURL + "/byids", {
			method: 'POST',
			headers: {
				'Accept': 'application/json',
				'Content-Type': 'application/json',
				[document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
			},
			body: JSON.stringify({
				"items": [
					{"id": this.entityId}
				]
			})
		})
			.then(response => {
				if(response.status === 200){
					return response.json().then(json => {
						const data = json.items[0]
						if(data){
							this.loadCompleteCallback(data);
							this.updateUnitField(data)
							if(callback instanceof Function){
								callback(data);
							}
						} else {
							console.error("No time series found with id: " + this.entityId);
						}
					});
				}
			})
			.catch( e => {
				console.error(e);
			});
	};

	render(){
		super.render();
		this.formElement.action = this.apiURL + "/update";
		if(!this.errors){
			this.loadData( json => {
				this.addRelationsFrom(inboundRelations(json.relatedResources));
				Object.entries(json.metadata).forEach(([key, value]) => {
					this.addMetadataRow(key, value);
				});
			});
		}
		this.submitButtonElement.firstElementChild.textContent = $L('update');
		// Todo: implement solution for allowing updating from resources
		this.formElement.querySelector('[data-type="relation-list-btn"]').remove();
		// Not allowed to change value type for time series so field is removed
		this.formElement.querySelector('section[data-type="value-type"]').remove();
	}

	addRelationsFrom(relations){
		const requestBody = {"items": []};
		relations.forEach(it =>  requestBody.items.push({id: it.id}));

		fetch("/api/resources/fetch-related-nodes", {
			method: 'POST',
			headers: {
				'Accept': 'application/json',
				'Content-Type': 'application/json',
				[document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
			},
			body: JSON.stringify({id: this.entityId, depth: 1})
		}).then(response => {
			if(response.status === 200){
				const nodeContainer = this.formElement.querySelector('.node-container');
				response.json().then(json => {
					const relationsFrom = [];
					for(let i = 0; i < json.edges.length; i++){
						const edge = json.edges[i];
						if( edge.end === `${this.entityId}`) {
							const node = json.nodes.find(it => it.id === edge.start);
							relationsFrom.push({node: node, edge: edge});
						}
					}

					relationsFrom.forEach( rel => {
						const nodeColor = this.network.getColor(rel.node);
						const liEle = Object.assign(document.createElement('li'), {
							className: "flex-container left-c-right",
							innerHTML: `
								<input type="hidden" name="id" value="${rel.edge.id}"/>
								<div class="resource-label" data-type="node-name" style="border-color:${nodeColor}">
									<span></span>
								</div>
								<div class="resource-label" data-type="rel-type">
									<span></span>
								</div>`
						});
						liEle.querySelector('[data-type="node-name"] span').textContent = rel.node.name;
						liEle.querySelector('[data-type="rel-type"] span').textContent = "-----> " + rel.edge.type;
						nodeContainer.appendChild(liEle);
					});
				});
			}
		}).catch( e => {
			console.error(e);
		});
	}

	submit(){
		const tsFields = new TimeseriesFields();
		const allowedUpdateFields = ["externalId", "name", "unit", "unitExternalId", "description", "valueType", "isStep", "dataSetId"];

		const metadataFields = this.formElement.querySelectorAll(`table.metadata tbody tr`);

		for (const [key, value] of new FormData(this.formElement).entries()) {
			if( value === "" || value === null) continue;
			if (allowedUpdateFields.includes(key)) {
				tsFields.set(key, value);
			}
		}
		if( metadataFields.length > 0 ) {
			tsFields["metadata"] = {"set": []};
			metadataFields.forEach( it => {
				const keyField = it.querySelector('[name$="key"]');
				const valueField = it.querySelector('[name$="value"]');
				tsFields["metadata"]["set"].push({key: keyField.value, value: valueField.value});
			});
		}
		const updateTimeseries = new UpdateTimeseries({id: this.entityId, update: tsFields});
		const dw = new DataWrapper();
		dw.items = [updateTimeseries];

		fetch(this.formElement.action, {
			method: this.formElement.method,
			headers: {
				'Accept': 'application/json',
				'Content-Type': 'application/json',
				[document.querySelector('meta[name="_csrf_header"]').content]: document.querySelector('meta[name="_csrf"]').content
			},
			signal: AbortSignal.timeout(10000),
			body: JSON.stringify(dw)
		}).then( response => {
			this.handleResponse(response);
		}).catch(() => {
			console.error("Update failed");
		});
	}
}

class UpdateTimeseries {
	constructor(obj) {
		this.id = obj.id || null;
		this.externalId = obj.externalId || null;
		this.update = obj.update || null;
	}
}
class TimeseriesFields extends UpdateFields{
	constructor() {
		super();
		this.name = undefined;
		this.externalId = undefined;
		this.description = undefined;
		this.metadata = undefined;
		this.unit = undefined;
		this.unitExternalId = undefined;
		this.isStep = undefined;
		this.securityCategories = undefined;
		this.dataSetId = undefined;
	}
}