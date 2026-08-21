/*
 * Naming help — the info icon that sits next to the Name field on the create/edit forms for
 * assets, time series, functions and data sets, plus the dialog it opens.
 *
 * DataHub's graph is an ontology in the RDF sense, and the three identity fields line up with
 * it one for one: the label is the class (rdf:type), the external id is the stable identifier
 * (the local part of an IRI) and the name is the human readable label (rdfs:label). Anyone
 * coming from an oil and gas facility already has a tagging standard covering the same ground,
 * so the dialog maps the two together and ends with examples for the kind being created.
 *
 * Usage from a form: emit NamingHelp.label(kind) where the Name <label> would go. The base
 * form's render() calls NamingHelp.bind(this.formElement), so there is no per-form wiring.
 */
window.NamingHelp = (function () {

	// Per-kind example rows: [name, external id]. Plant tags read the same in every locale
	// (tag descriptions on the Norwegian shelf are written in English too), so the rows are not
	// translated; only the caption above them is.
	//
	// The asset and time-series rows mirror the plant tag verbatim, because that is what the
	// platform now stores: external ids are kept exactly as sent within the character set, so
	// 21-P-101A stays 21-P-101A rather than being rewritten to 21_p_101a. These examples used to
	// show the rewritten form, which taught a convention the platform no longer imposes.
	//
	// The function and data set rows stay snake_case, and that is not an oversight: those things
	// have no plant tag to mirror, so their ids come from the form's suggestion, which derives a
	// lowercase underscored id from the typed name.
	const EXAMPLES = {
		asset: [
			['21-P-101A Crude export pump A', '21-P-101A'],
			['20-V-201 First stage separator', '20-V-201'],
			['21-E-140 Gas cooler', '21-E-140']
		],
		timeseries: [
			['21-PT-1234 Separator inlet pressure', '21-PT-1234'],
			['21-TT-1235 Pump discharge temperature', '21-TT-1235'],
			['21-FT-1240 Export gas flow', '21-FT-1240']
		],
		function: [
			['Separator pressure 1 h mean', 'separator_pressure_1h_mean'],
			['Export pump efficiency', 'export_pump_efficiency'],
			['Choke position anomaly score', 'choke_position_anomaly_score']
		],
		dataset: [
			['Platform A process historian', 'platform_a_process_historian'],
			['Area 21 SCADA tags', 'area_21_scada_tags'],
			['Maintenance work orders', 'maintenance_work_orders']
		]
	};

	// The same asset written as RDF, so the mapping from the three form fields to a triple is
	// concrete. Turtle local names may start with a digit and may contain hyphens, so the plant
	// tag carries over unchanged — which is the point: the external id is the IRI's local part,
	// and IRI local parts are case-sensitive.
	const RDF_EXAMPLE =
		'ex:21-P-101A  a  ex:CentrifugalPump ;\n'
		+ '    rdfs:label  "21-P-101A Crude export pump A" ;\n'
		+ '    ex:isPartOf ex:20-V-201 .';

	const RULE_KEYS = [
		'naming.help.rules.tag',
		'naming.help.rules.stable',
		'naming.help.rules.unique',
		'naming.help.rules.nounits',
		'naming.help.rules.singular',
		'naming.help.rules.abbrev'
	];

	const STANDARD_KEYS = [
		'naming.help.standards.isa',
		'naming.help.standards.iso81346',
		'naming.help.standards.norsok',
		'naming.help.standards.cfihos',
		'naming.help.standards.iso15926'
	];

	function listOf(keys) {
		return keys.map(key => `<li>${window.escapeHtml($L(key))}</li>`).join('');
	}

	/** The Name field's <label>, with the clickable help icon after the caption. */
	function label(kind) {
		return `
			<label>${window.escapeHtml($L('name'))}
				<i class="fa fa-fw fa-circle-question pointer field-help-icon"
						data-type="naming-help" data-kind="${window.escapeHtml(kind)}"
						role="button" tabindex="0"
						title="${window.escapeHtml($L('naming.help.icon.title'))}"></i>
			</label>`;
	}

	function open(kind) {
		const examples = EXAMPLES[kind] || EXAMPLES.asset;
		const rows = examples.map(([name, externalId]) => `
			<tr>
				<td>${window.escapeHtml(name)}</td>
				<td class="nh-id">${window.escapeHtml(externalId)}</td>
			</tr>`).join('');

		const dialog = Object.assign(document.createElement('dialog'), {
			className: "naming-help-dialog",
			innerHTML: `
				<div class="nh-head">
					<h3>${window.escapeHtml($L('naming.help.dialog.title'))}</h3>
					<i class="fa fa-fw fa-close pointer" data-type="close"
							role="button" tabindex="0"
							aria-label="${window.escapeHtml($L('close'))}"></i>
				</div>
				<div class="nh-body">
					<p>${window.escapeHtml($L('naming.help.intro'))}</p>

					<h4>${window.escapeHtml($L('naming.help.fields.title'))}</h4>
					<ul>
						<li>${window.escapeHtml($L('naming.help.fields.name'))}</li>
						<li>${window.escapeHtml($L('naming.help.fields.externalid'))}</li>
						<li>${window.escapeHtml($L('naming.help.fields.label'))}</li>
					</ul>
					<p class="nh-caption">${window.escapeHtml($L('naming.help.rdf.caption'))}</p>
					<pre>${window.escapeHtml(RDF_EXAMPLE)}</pre>

					<h4>${window.escapeHtml($L('naming.help.rules.title'))}</h4>
					<ul>${listOf(RULE_KEYS)}</ul>

					<h4>${window.escapeHtml($L('naming.help.examples.title'))}</h4>
					<p class="nh-caption">${window.escapeHtml($L('naming.help.examples.' + kind))}</p>
					<table>
						<thead>
							<tr>
								<th>${window.escapeHtml($L('name'))}</th>
								<th>${window.escapeHtml($L('external.id'))}</th>
							</tr>
						</thead>
						<tbody>${rows}</tbody>
					</table>

					<h4>${window.escapeHtml($L('naming.help.standards.title'))}</h4>
					<p>${window.escapeHtml($L('naming.help.standards.intro'))}</p>
					<ul>${listOf(STANDARD_KEYS)}</ul>
				</div>
				<footer class="nh-foot">
					<button type="button" class="dh-btn primary" data-type="close-btn">
						<span>${window.escapeHtml($L('close'))}</span>
					</button>
				</footer>`
		});

		document.body.appendChild(dialog);
		dialog.showModal();

		const close = () => dialog.close();
		dialog.querySelectorAll('[data-type="close"], [data-type="close-btn"]')
			.forEach(el => el.addEventListener('click', close));
		// A click that lands on the dialog element itself is a backdrop click: the head/body/foot
		// fill it edge to edge, so nothing else can be the target.
		dialog.addEventListener('click', e => {
			if (e.target === dialog) close();
		});
		// Covers the Escape key too, which closes the dialog without going through close().
		dialog.addEventListener('close', () => dialog.remove());
	}

	/** Wires every help icon under root. Safe to call again after a re-render. */
	function bind(root) {
		if (!root) return;
		root.querySelectorAll('[data-type="naming-help"]').forEach(icon => {
			if (icon.dataset.helpBound === "true") return;
			icon.dataset.helpBound = "true";
			const show = () => open(icon.getAttribute('data-kind'));
			icon.addEventListener('click', show);
			icon.addEventListener('keydown', e => {
				if (e.key === 'Enter' || e.key === ' ') {
					e.preventDefault();
					show();
				}
			});
		});
	}

	return { label, bind, open };
})();
