/*
 * Live datapoint stream client.
 *
 * Opens a WebSocket straight to the datahub-api host (NOT through the console proxy) and streams
 * live datapoints for a set of timeseries. Mirrors the recommend-value-type pattern in the
 * create-timeseries form: the api host comes from the <meta name="datahub-api-url"> tag and the
 * access token from the console's /token endpoint. A browser WebSocket can't send an Authorization
 * header, so the token rides in the ?token= query param (the api validates it on the handshake).
 *
 * Usage:
 *   const stream = new DatapointStream({
 *     onData: points => { ... },          // points: [{externalId, valueType, timestamp, value}]
 *     onStatus: state => { ... }          // state: 'connecting' | 'open' | 'closed'
 *   });
 *   stream.setInterest(['tsA', 'tsB']);   // which timeseries to receive
 *   stream.connect();
 *   ...
 *   stream.close();
 */
class DatapointStream {

	static RECONNECT_DELAY_MS = 2000;

	constructor(opts){
		opts = opts || {};
		this.onData = opts.onData || function(){};
		this.onStatus = opts.onStatus || function(){};
		// Resolve the api host the same way the create-timeseries form does.
		const apiUrlMeta = document.querySelector('meta[name="datahub-api-url"]');
		this.apiUrl = (apiUrlMeta ? apiUrlMeta.content : "").replace(/\/+$/, "");
		this.interest = [];
		this.ws = null;
		this.active = false;        // true between connect() and close()
		this._reconnectTimer = null;
	}

	// Turn the http(s) api base URL into a ws(s) WebSocket URL for the listen endpoint.
	_wsUrl(token){
		let base = this.apiUrl;
		if(base.startsWith("https://"))      base = "wss://"  + base.slice("https://".length);
		else if(base.startsWith("http://"))  base = "ws://"   + base.slice("http://".length);
		else {
			// Relative/empty base — fall back to the current page origin.
			const proto = window.location.protocol === "https:" ? "wss://" : "ws://";
			base = proto + window.location.host + base;
		}
		const ids = this.interest.length ? `&externalIds=${encodeURIComponent(this.interest.join(","))}` : "";
		return `${base}/timeseries/datapoints/listen?token=${encodeURIComponent(token)}${ids}`;
	}

	connect(){
		this.active = true;
		this._open();
	}

	_open(){
		this.onStatus('connecting');
		// Fetch a fresh token each (re)connect so an expired token doesn't wedge the stream.
		fetch('/token')
			.then(r => r.ok ? r.text() : Promise.reject('could not get access token'))
			.then(token => {
				if(!this.active) return;
				const ws = new WebSocket(this._wsUrl(token));
				this.ws = ws;
				ws.onopen = () => {
					this.onStatus('open');
					// (Re)send the interest set so a reconnect resumes the same series.
					this._sendInterest('set');
				};
				ws.onmessage = ev => {
					let msg;
					try { msg = JSON.parse(ev.data); } catch(e){ return; }
					if(msg && Array.isArray(msg.datapoints) && msg.datapoints.length){
						this.onData(msg.datapoints);
					}
				};
				ws.onclose = () => {
					this.onStatus('closed');
					if(this.active) this._scheduleReconnect();
				};
				ws.onerror = () => {
					// onclose fires right after; reconnect is handled there.
					try { ws.close(); } catch(e){ /* noop */ }
				};
			})
			.catch(e => {
				console.error('Datapoint stream connect failed', e);
				if(this.active) this._scheduleReconnect();
			});
	}

	_scheduleReconnect(){
		if(this._reconnectTimer) return;
		this._reconnectTimer = setTimeout(() => {
			this._reconnectTimer = null;
			if(this.active) this._open();
		}, DatapointStream.RECONNECT_DELAY_MS);
	}

	_sendInterest(action){
		if(this.ws && this.ws.readyState === WebSocket.OPEN){
			this.ws.send(JSON.stringify({ action: action || 'set', externalIds: this.interest }));
		}
	}

	// Replace the whole set of timeseries to receive. Safe to call before connect() (seeds the
	// initial handshake) or while open (sends an update frame).
	setInterest(externalIds){
		this.interest = Array.from(new Set((externalIds || []).map(String)));
		this._sendInterest('set');
	}

	subscribe(externalId){
		const id = String(externalId);
		if(!this.interest.includes(id)){
			this.interest.push(id);
			this._sendInterest('set');
		}
	}

	unsubscribe(externalId){
		const id = String(externalId);
		this.interest = this.interest.filter(x => x !== id);
		this._sendInterest('set');
	}

	close(){
		this.active = false;
		if(this._reconnectTimer){
			clearTimeout(this._reconnectTimer);
			this._reconnectTimer = null;
		}
		if(this.ws){
			try { this.ws.close(); } catch(e){ /* noop */ }
			this.ws = null;
		}
	}
}
