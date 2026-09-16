/**
 * DataHubProblem: one api refusal (an RFC 9457 body), read once and said in the user's language.
 *
 *   DataHubProblem.read(response).then(problem => problem.flash('update.failed'));
 *
 * The members stay on `body` rather than on the instance, so a body carrying `message` or `details`
 * cannot shadow the methods of the same name.
 */
class DataHubProblem {

	static get BASE(){ return 'https://intellistream.ai/errors/'; }

	constructor(status, body){
		this.body = (body && typeof body === 'object' && !Array.isArray(body)) ? body : {};
		this.status = typeof this.body.status === 'number' ? this.body.status : status;
	}

	/** Never rejects: an empty, HTML or unreadable body becomes a problem with only its status. */
	static read(response){
		return response.text().then(
			text => DataHubProblem.parse(response.status, text),
			() => new DataHubProblem(response.status, null));
	}

	/** For callers that already hold the body, as text (an XHR) or parsed (a thrown ApiError). */
	static parse(status, body){
		if(typeof body !== 'string') return new DataHubProblem(status, body);
		try {
			return new DataHubProblem(status, body ? JSON.parse(body) : null);
		} catch(ignored){
			return new DataHubProblem(status, null);
		}
	}

	get type(){ return this.body.type || null; }
	get title(){ return this.body.title || null; }
	get detail(){ return this.body.detail || null; }
	get retry(){ return this.body.retry || null; }
	get requestId(){ return this.body.requestId || null; }

	/** "optimistic-lock" for https://intellistream.ai/errors/optimistic-lock, null for a foreign type. */
	get slug(){
		const type = this.type;
		return typeof type === 'string' && type.startsWith(DataHubProblem.BASE) ? type.slice(DataHubProblem.BASE.length) : null;
	}

	/** Rejected fields as {field, message}, the shape the right-hand forms mark up. */
	fieldErrors(){
		const b = this.body;
		const out = [];
		if(Array.isArray(b.fields)){
			b.fields.forEach(f => out.push({ field: f.field, message: f.message }));
		}
		// The console's own validation answer; the api's `errors` entries carry a pointer instead.
		if(Array.isArray(b.errors)){
			b.errors.forEach(e => { if(e && e.field) out.push({ field: e.field, message: e.message }); });
		}
		if(Array.isArray(b.duplicated)){
			b.duplicated.forEach(entry => Object.keys(entry || {}).forEach(field =>
				out.push({ field: field, message: $L('error.problem.duplicate.value', null, [entry[field]]) })));
		}
		return out;
	}

	message(fallbackKey){
		const b = this.body;
		const fallback = $L(fallbackKey || 'error.problem.failed');
		const limit = window.LimitErrors && window.LimitErrors.message(b);
		if(limit) return limit;
		switch(this.slug){
			case 'dataset-forbidden':
				if(b.permission === 'manage') return $L('error.dataset.manage.forbidden');
				if(b.permission === 'read') return $L('error.dataset.read.forbidden');
				return $L('error.dataset.write.forbidden');
			case 'permissions-unavailable': return $L('error.permissions.unavailable');
			case 'unauthorized':
			case 'token-rejected': return $L('error.problem.unauthorized');
			case 'forbidden': return $L('error.problem.forbidden');
			case 'feature-disabled': return $L('error.problem.feature.disabled');
			case 'unknown-tenant': return $L('error.problem.unknown.tenant');
			case 'validation-failed':
			case 'constraint-violation': return $L('error.problem.invalid');
			case 'optimistic-lock': return $L('error.problem.optimistic.lock');
			case 'duplicate': return $L('error.problem.duplicate');
			case 'referenced': return $L('error.problem.referenced');
			case 'would-strand': return $L('error.problem.would.strand');
			case 'messaging-unavailable':
			case 'tenant-provisioning': return $L('error.problem.unavailable');
			case 'internal': return $L('error.problem.internal');
			// A datapoint write that skipped series names them in `missing`; its detail says the rest went in.
			case 'not-found': return b.missing ? (this.detail || fallback) : $L('error.problem.not.found');
		}
		// No sentence for this type, or no problem document at all: the status decides.
		if(this.status === 401) return $L('error.problem.unauthorized');
		if(this.status === 403) return $L('error.problem.forbidden');
		if(this.status === 404 && !this.detail) return $L('error.problem.not.found');
		if(this.status === 413) return $L('error.limit.too.large.plain');
		if(this.status === 429) return $L('error.limit.rate.plain');
		if(this.status >= 500){
			return $L(this.retry === 'same-request' || [502, 503, 504].includes(this.status)
				? 'error.problem.unavailable' : 'error.problem.internal');
		}
		return this.detail || fallback;
	}

	/** Lines naming what was involved, plus the request id when only an operator can help. */
	details(withoutFields){
		const b = this.body;
		const out = [];
		if(!withoutFields){
			this.fieldErrors().forEach(f => out.push(f.field ? f.field + ': ' + f.message : f.message));
		}
		if(Array.isArray(b.errors)){
			b.errors.forEach(e => { if(e && e.pointer) out.push($L('error.problem.unknown.field', null, [e.pointer])); });
		}
		if(Array.isArray(b.blockedBy)){
			b.blockedBy.forEach(x => out.push($L('error.problem.blocked.by', null,
				[x.externalId || x.subscriptionExternalId || x.id || ''])));
		}
		if(Array.isArray(b.missing)){
			b.missing.forEach(m => out.push($L('error.problem.missing', null, [m.externalId || m.id || ''])));
		}
		if(Array.isArray(b.violations)){
			b.violations.forEach(v => out.push((v.externalId || '') + ': ' + (v.reason || v.message || '')));
		}
		if(this.requestId && (this.retry === 'needs-operator' || this.status >= 500)){
			out.push($L('error.problem.reference', null, [this.requestId]));
		}
		return out;
	}

	flash(fallbackKey){
		return Flash.error(this.message(fallbackKey), { details: this.details() });
	}
}

window.DataHubProblem = DataHubProblem;
