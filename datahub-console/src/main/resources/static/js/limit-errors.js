/**
 * The api's limit refusals, said in the user's own language.
 *
 * Four things can now come back that are not "your input was wrong": a per-minute rate limit, a
 * daily ingest quota, a lifetime tenant ceiling, and a request body that is too large. The api's own
 * `detail` is written for whoever operates the platform, mentions no UI at all, and is never
 * translated, so the console rewrites each one and adds the piece that actually helps: how long to
 * wait, or that the limit is raised by asking.
 *
 * These arrive as RFC 9457 problem documents, but a couple of call sites (the raw-body file upload)
 * only have a status code and a string, so `fromStatus` covers those without them having to parse.
 */
/**
 * The api's per-field caps, mirrored so a form can stop an over-long value where it is typed rather
 * than after a round trip that refuses the whole submission.
 *
 * These duplicate `FieldLimits` in datahub-api-model. The browser copy is a courtesy, not the
 * enforcement: the api validates every one of these itself, and a stale value here costs a clearer
 * error message, never a hole in the limits.
 */
window.FieldLimits = Object.freeze({
	DESCRIPTION_MAX: 10000,
	METADATA_MAX_ENTRIES: 256,
	METADATA_KEY_MAX: 128,
	METADATA_VALUE_MAX: 1024,
	LABELS_MAX: 64,
	LABEL_LENGTH_MAX: 512,
	RELATED_RESOURCES_MAX: 100,
	DATAPOINT_VALUE_MAX: 64
});

window.LimitErrors = (function () {

	const TYPE = {
		RATE: 'https://intellistream.ai/errors/rate-limit-exceeded',
		QUOTA: 'https://intellistream.ai/errors/ingest-quota-exceeded',
		TENANT: 'https://intellistream.ai/errors/tenant-limit-reached',
		TOO_LARGE: 'https://intellistream.ai/errors/request-too-large'
	};

	/** "in about 2 minutes" reads better than "in 118 seconds", and the number is approximate anyway. */
	function waitFor(seconds) {
		const s = Number(seconds);
		if (!Number.isFinite(s) || s <= 0) return $L('error.limit.wait.moment');
		if (s < 90) return $L('error.limit.wait.seconds', null, [Math.ceil(s)]);
		if (s < 5400) return $L('error.limit.wait.minutes', null, [Math.round(s / 60)]);
		return $L('error.limit.wait.hours', null, [Math.round(s / 3600)]);
	}

	function bytes(n) {
		const b = Number(n);
		if (!Number.isFinite(b) || b <= 0) return null;
		return window.getByteSize ? window.getByteSize(b) : b + ' B';
	}

	/**
	 * The api names the thing it counted in English, for whoever reads a log. Translate the ones we
	 * know and fall back to the server's own word for anything else, so a metric added later still
	 * produces a sentence rather than a gap.
	 */
	const METRIC_KEYS = {
		'events': 'error.limit.metric.events',
		'nodes': 'error.limit.metric.nodes',
		'relationships': 'error.limit.metric.relationships',
		'data points': 'error.limit.metric.datapoints',
		'text data points': 'error.limit.metric.text.datapoints',
		'ingested bytes': 'error.limit.metric.bytes'
	};

	function metricName(metric) {
		if (!metric) return null;
		const key = METRIC_KEYS[metric];
		return key ? $L(key) : metric;
	}

	function isLimit(json) {
		return !!(json && typeof json === 'object' && json.type
			&& Object.values(TYPE).indexOf(json.type) !== -1);
	}

	/** The localized message for a problem document, or null if it is not one of ours. */
	function message(json) {
		if (!isLimit(json)) return null;
		switch (json.type) {
			case TYPE.RATE:
				return $L('error.limit.rate', null, [waitFor(json.retryAfter)]);
			case TYPE.QUOTA:
				return $L('error.limit.quota', null, [waitFor(json.retryAfter)]);
			case TYPE.TENANT: {
				const what = metricName(json.metric);
				return what
					? $L('error.limit.tenant', null, [json.limit, what])
					: $L('error.limit.tenant.plain');
			}
			case TYPE.TOO_LARGE: {
				const max = bytes(json.limitBytes);
				return max ? $L('error.limit.too.large', null, [max]) : $L('error.limit.too.large.plain');
			}
			default:
				return null;
		}
	}

	/**
	 * A message from a status code alone, for the places that never parse the body: the raw-body file
	 * upload reads `xhr.responseText`, which without this shows the caller a JSON document.
	 *
	 * `body` is the raw text, parsed here when it happens to be a problem document so the numbers in
	 * it are still used; otherwise the status carries the meaning on its own.
	 */
	function fromStatus(status, body) {
		if (body) {
			try {
				const parsed = JSON.parse(body);
				const parsedMessage = message(parsed);
				if (parsedMessage) return parsedMessage;
			} catch (ignored) {
				// Not JSON, so there is nothing in it to read. The status below still says enough.
			}
		}
		if (status === 429) return $L('error.limit.rate.plain');
		if (status === 413) return $L('error.limit.too.large.plain');
		if (status === 403) return $L('error.limit.tenant.plain');
		return null;
	}

	return { TYPE: TYPE, isLimit: isLimit, message: message, fromStatus: fromStatus };
})();
