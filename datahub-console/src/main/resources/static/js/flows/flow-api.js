/*
 * FlowApi — browser-direct access to datahub-flow (intellistream-flow).
 *
 * The Flows section calls the flow service itself, as the Analyze tab calls datahub-analysis
 * (CONSTRAINTS.md, frontend #2): the base URL from <meta name="datahub-flow-url">, a Bearer token
 * from the console's /token endpoint, the token fetched again once on a 401. A refusal rejects with
 * a DataHubProblem, so every page says it in the user's language the same way.
 *
 *   FlowApi.get('/flows').then(page => ...)
 *   FlowApi.post('/flows/validate', definition).then(answer => ...)
 *
 * FlowRunStream follows a run's live view, GET /runs/{id}/events: newline-delimited JSON over
 * fetch, because EventSource cannot send a bearer token. Each line is one object; the stream ends
 * with {"type":"end"}. A connection that drops before that is reopened after a pause, like the
 * live datapoint stream, and the service replays from the run's state, so nothing is lost.
 */
(function(){
	"use strict";

	var cachedToken = null;

	function base(){
		var m = document.querySelector('meta[name="datahub-flow-url"]');
		return (m ? m.content : "").replace(/\/+$/, "");
	}

	function token(force){
		if(cachedToken && !force) return Promise.resolve(cachedToken);
		return fetch("/token", { headers: { Accept: "text/plain" }, credentials: "same-origin" })
			.then(function(r){
				if(r.status === 401 && window.renderSignedOutDialog) window.renderSignedOutDialog();
				return r.ok ? r.text() : Promise.reject(new DataHubProblem(r.status, null));
			})
			.then(function(t){ cachedToken = t.trim(); return cachedToken; });
	}

	// One call, resolved with the parsed body (null for a 204), rejected with a DataHubProblem.
	// `opts.base` sends it to another service (datahub-api, for the Needs-attention events).
	function request(path, opts){
		opts = opts || {};
		var hasBody = opts.body !== undefined && opts.body !== null;
		var send = function(t){
			return fetch((opts.base || base()) + path, {
				method: opts.method || "GET",
				headers: Object.assign({ Accept: "application/json", Authorization: "Bearer " + t },
					hasBody ? { "Content-Type": "application/json" } : {}),
				body: hasBody ? (typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body)) : undefined,
				signal: AbortSignal.timeout(opts.timeout || 30000)
			});
		};
		return token(false)
			.then(send)
			.then(function(r){ return r.status === 401 ? token(true).then(send) : r; })
			.then(function(r){
				if(!r.ok) return DataHubProblem.read(r).then(function(p){ return Promise.reject(p); });
				return r.status === 204 ? null : r.json();
			});
	}

	function apiBase(){
		var m = document.querySelector('meta[name="datahub-api-url"]');
		return (m ? m.content : "").replace(/\/+$/, "");
	}

	/** A message for anything a call rejected with: a problem, or a network failure as it came. */
	function reason(err, fallbackKey){
		return err instanceof DataHubProblem ? err.message(fallbackKey) : String(err);
	}

	function flashError(err, fallbackKey){
		if(err instanceof DataHubProblem) return err.flash(fallbackKey);
		return Flash.error(String(err));
	}

	/** "/flows/a%20b" — every path segment the user typed goes through here. */
	function seg(value){ return encodeURIComponent(value); }

	window.FlowApi = {
		base: base,
		apiBase: apiBase,
		token: token,
		request: request,
		get:  function(path, opts){ return request(path, Object.assign({}, opts, { method: "GET" })); },
		post: function(path, body, opts){ return request(path, Object.assign({}, opts, { method: "POST", body: body })); },
		put:  function(path, body, opts){ return request(path, Object.assign({}, opts, { method: "PUT", body: body })); },
		del:  function(path, opts){ return request(path, Object.assign({}, opts, { method: "DELETE" })); },
		/** A call to datahub-api rather than the flow service, with the same token handling. */
		api: function(path, body){
			return request(path, { method: body === undefined ? "GET" : "POST", body: body, base: apiBase() });
		},
		reason: reason,
		flashError: flashError,
		seg: seg
	};

	/*
	 * new FlowRunStream({ runId, onEvent: obj => ..., onStatus: state => ... }).open() / .close()
	 * state: 'connecting' | 'open' | 'reconnecting' | 'ended' | 'failed'
	 */
	function FlowRunStream(opts){
		this.runId = opts.runId;
		this.onEvent = opts.onEvent || function(){};
		this.onStatus = opts.onStatus || function(){};
		this.controller = null;
		this.active = false;
		this.attempts = 0;
	}
	FlowRunStream.RECONNECT_DELAY_MS = 2000;
	FlowRunStream.MAX_ATTEMPTS = 10;

	FlowRunStream.prototype.open = function(){
		this.active = true;
		this._connect(false);
		return this;
	};

	FlowRunStream.prototype.close = function(){
		this.active = false;
		if(this.controller) this.controller.abort();
	};

	FlowRunStream.prototype._connect = function(forceToken){
		var self = this;
		if(!self.active) return;
		self.onStatus(self.attempts === 0 ? "connecting" : "reconnecting");
		self.controller = new AbortController();
		var ended = false;
		var handedOver = false;   // a 401 started a fresh connection; this one is done
		token(forceToken)
			.then(function(t){
				return fetch(base() + "/runs/" + seg(self.runId) + "/events", {
					headers: { Accept: "application/x-ndjson", Authorization: "Bearer " + t },
					signal: self.controller.signal
				});
			})
			.then(function(r){
				if(r.status === 401 && !forceToken){
					handedOver = true;
					self._connect(true);
					return;
				}
				if(!r.ok){
					return DataHubProblem.read(r).then(function(p){ self.active = false; self.onStatus("failed", p); });
				}
				self.attempts = 0;
				self.onStatus("open");
				var reader = r.body.pipeThrough(new TextDecoderStream()).getReader();
				var buffer = "";
				var pump = function(){
					return reader.read().then(function(chunk){
						if(chunk.done) return;
						buffer += chunk.value;
						var lines = buffer.split("\n");
						buffer = lines.pop();
						lines.forEach(function(line){
							if(!line.trim()) return;
							var obj;
							try { obj = JSON.parse(line); } catch(e){ return; }
							if(obj.type === "end") ended = true;
							self.onEvent(obj);
						});
						return pump();
					});
				};
				return pump();
			})
			.catch(function(){ /* aborted, or the connection dropped: decided below */ })
			.then(function(){
				if(!self.active || handedOver) return;
				if(ended){ self.active = false; self.onStatus("ended"); return; }
				if(++self.attempts > FlowRunStream.MAX_ATTEMPTS){ self.active = false; self.onStatus("failed"); return; }
				setTimeout(function(){ self._connect(false); }, FlowRunStream.RECONNECT_DELAY_MS);
			});
	};

	window.FlowRunStream = FlowRunStream;
})();
