/**
 * DhContextMenu — the shared right-click context menu (extracted from files-page.js so the
 * resources page can reuse it). One menu at a time; closes on click-away, scroll, resize and
 * Escape. Styling lives in all.css (.dh-context-menu / .dh-context-item).
 *
 * Usage:
 *   DhContextMenu.open(e.clientX, e.clientY, [
 *     { label: 'Rename', icon: 'fa-pen', action: () => {...} },
 *     { label: 'Delete', icon: 'fa-trash', danger: true, action: () => {...} }
 *   ]);
 */
window.DhContextMenu = (function(){
	let el = null;
	function close(){ if (el){ el.remove(); el = null; } }
	function open(x, y, items){
		close();
		el = document.createElement('div');
		el.className = 'dh-context-menu';
		items.forEach(item => {
			const btn = document.createElement('button');
			btn.type = 'button';
			btn.className = 'dh-context-item' + (item.danger ? ' danger' : '');
			btn.innerHTML = '<i class="fa fa-fw ' + item.icon + '"></i><span></span>';
			btn.querySelector('span').textContent = item.label;
			btn.addEventListener('click', () => { close(); item.action(); });
			el.appendChild(btn);
		});
		document.body.appendChild(el);
		el.style.left = Math.min(x, window.innerWidth - el.offsetWidth - 8) + 'px';
		el.style.top = Math.min(y, window.innerHeight - el.offsetHeight - 8) + 'px';
	}
	document.addEventListener('click', close);
	document.addEventListener('scroll', close, true);
	window.addEventListener('resize', close);
	document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
	return { open: open, close: close };
})();
