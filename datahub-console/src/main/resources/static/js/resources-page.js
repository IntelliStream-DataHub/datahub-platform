/**
 * Resources page behaviour: the right-click context menu on the root-resource rows (export /
 * import). Export downloads the root's whole graph component as a binary file straight from
 * datahub-api (bearer token from /token, same pattern as the file upload); import posts such a
 * file back and reloads the page so new roots show up.
 *
 * Bundled via the 'resources' asset bundle (see resources.manifest.js / assets.gradle). Depends on
 * the app bundle (DhContextMenu, Flash, $L, renderSignedOutDialog), loaded before it.
 */
(function(){
	const IMPORT_FLASH_KEY = 'dh-graph-import-flash';

	// The import reloads the page so the new roots render; the summary flash is handed across the
	// reload through sessionStorage (read-once, same idea as dh-pending-view).
	document.addEventListener('DOMContentLoaded', () => {
		let pending = null;
		try { pending = JSON.parse(sessionStorage.getItem(IMPORT_FLASH_KEY) || 'null'); } catch (e) { /* ignore */ }
		if (pending) {
			try { sessionStorage.removeItem(IMPORT_FLASH_KEY); } catch (e) { /* ignore */ }
			Flash.info(pending.message, { details: pending.details });
		}

		const list = document.querySelector('[data-type="root-asset-list"]');
		if (!list || !window.DhContextMenu) return;

		list.addEventListener('contextmenu', e => {
			const row = e.target.closest('li[data-root-id]');
			if (!row) return;
			e.preventDefault();
			const id = row.getAttribute('data-root-id');
			const nameEl = row.querySelector('.dl-name');
			const name = (nameEl ? nameEl.textContent : row.textContent).trim();
			DhContextMenu.open(e.clientX, e.clientY, [
				{ label: $L('export.graph'), icon: 'fa-file-export', action: () => exportGraph(id, name) },
				{ label: $L('import.graph'), icon: 'fa-file-import', action: importGraph }
			]);
		});
	});

	const fmt = (key, ...args) => args.reduce((s, a, i) => s.replace('{' + i + '}', a), $L(key));

	const apiBase = () =>
		document.querySelector('meta[name="datahub-api-url"]').content.replace(/\/+$/, '');

	// Bearer token for browser-direct datahub-api calls, from the console /token endpoint. Resolves
	// to null (after rendering the signed-out dialog) when the session is gone.
	const fetchToken = () =>
		fetch('/token', { headers: { Accept: 'text/plain' }, credentials: 'same-origin' })
			.then(r => {
				if (r.status === 401){ if (window.renderSignedOutDialog) window.renderSignedOutDialog(); return null; }
				if (!r.ok) throw new Error('token');
				return r.text();
			});

	function exportGraph(id, name){
		fetchToken()
			.then(token => token === null ? null : fetch(apiBase() + '/resources/export/' + encodeURIComponent(id), {
				headers: { Accept: 'application/octet-stream', Authorization: 'Bearer ' + token }
			}))
			.then(r => {
				if (r === null) return;
				if (!r.ok) {
					// Surface the server's reason (e.g. the component is over the export limit).
					return r.json().catch(() => ({})).then(j => {
						throw new Error((j.error && j.error.message) || j.detail || ('export ' + r.status));
					});
				}
				const disposition = r.headers.get('Content-Disposition') || '';
				const match = disposition.match(/filename="([^"]+)"/);
				const fileName = match ? match[1] : (name.replace(/[^A-Za-z0-9._-]/g, '_') + '.dhgraph');
				return r.blob().then(blob => {
					const url = URL.createObjectURL(blob);
					const a = document.createElement('a');
					a.href = url;
					a.download = fileName;
					document.body.appendChild(a);
					a.click();
					a.remove();
					URL.revokeObjectURL(url);
				});
			})
			.catch(e => {
				console.log(e);
				const detail = (e && e.message && !/^export \d+$/.test(e.message)) ? ': ' + e.message : '';
				Flash.error($L('graph.export.failed') + detail);
			});
	}

	function importGraph(){
		const input = document.createElement('input');
		input.type = 'file';
		input.accept = '.dhgraph';
		input.addEventListener('change', () => {
			const file = input.files && input.files[0];
			if (file) uploadGraphFile(file);
		});
		input.click();
	}

	// Mirrors GraphFileCodec.MAX_COMPRESSED_BYTES on the api - keep the two in sync.
	const MAX_IMPORT_BYTES = 512 * 1024 * 1024;

	function uploadGraphFile(file){
		if (file.size > MAX_IMPORT_BYTES) {
			Flash.error($L('graph.import.too.large'));
			return;
		}
		fetchToken()
			.then(token => token === null ? null : fetch(apiBase() + '/resources/import', {
				method: 'POST',
				headers: { Accept: 'application/json', 'Content-Type': 'application/octet-stream', Authorization: 'Bearer ' + token },
				body: file
			}))
			.then(r => {
				if (r === null) return;
				if (!r.ok) {
					// Both error shapes the api produces carry a human message: the create-style
					// envelope ({error:{message}}) and RFC 9457 problem+json ({detail}).
					return r.json().catch(() => ({})).then(j => {
						throw new Error((j.error && j.error.message) || j.detail || ('import ' + r.status));
					});
				}
				return r.json().then(showImportResult);
			})
			.catch(e => {
				console.log(e);
				const detail = (e && e.message && !/^import \d+$/.test(e.message)) ? ': ' + e.message : '';
				Flash.error($L('graph.import.failed') + detail);
			});
	}

	function showImportResult(result){
		const skipped = result.nodesSkippedExisting + result.nodesSkippedTimeseries.length + result.relationsSkipped;
		const message = fmt('graph.import.done', result.nodesCreated, result.relationsCreated, skipped);
		const details = result.nodesSkippedTimeseries.length
			? [fmt('graph.import.skipped.timeseries', result.nodesSkippedTimeseries.join(', '))]
			: [];
		if (result.nodesCreated === 0 && result.relationsCreated === 0) {
			Flash.info(message, { details: details });
			return;
		}
		try { sessionStorage.setItem(IMPORT_FLASH_KEY, JSON.stringify({ message: message, details: details })); } catch (e) { /* ignore */ }
		location.reload();
	}
})();
