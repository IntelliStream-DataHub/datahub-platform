/**
 * Minimal, dependency-free Markdown -> safe HTML renderer for assistant replies.
 *
 * Why hand-rolled: the console ships to airgapped deployments, so pulling marked/DOMPurify from a
 * CDN is not an option, and a strict CSP would block them anyway. Safety comes from escaping ALL
 * HTML up front and only ever emitting tags this file constructs itself — the model's text can never
 * introduce a tag or attribute. Link hrefs are additionally protocol-checked so `javascript:` and
 * friends cannot slip through.
 *
 * Supported: headings, bold/italic, inline + fenced code, unordered/ordered lists, GitHub-style
 * pipe tables, blockquotes, horizontal rules, links, and hard line breaks. Deliberately no nested
 * lists or raw HTML — the answers this renders don't need them, and every feature is attack surface.
 *
 * Exposed as window.dhChatMarkdown(text) -> html string.
 */
(function () {
	'use strict';

	const NUL = '\u0000';

	function escapeHtml(s) {
		return s.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;');
	}

	// Only these protocols may appear in a link. Relative links (starting with / or #) are allowed.
	// Everything the check rejects (javascript:, data:, vbscript:, …) renders as inert text.
	function safeHref(raw) {
		const url = raw.trim();
		if (/^(https?:|mailto:)/i.test(url)) return url;
		if (/^[/#]/.test(url)) return url;
		return null;
	}

	/**
	 * Inline formatting on already-HTML-escaped text. Code spans are pulled out first so that *, _
	 * and [ ] inside them are left untouched, then reinserted verbatim.
	 */
	function inline(text) {
		const codes = [];
		let s = text.replace(/`([^`]+)`/g, (_, code) => {
			codes.push(code);
			return NUL + 'C' + (codes.length - 1) + NUL;
		});

		// Links: [label](href). Label gets inline formatting; href is protocol-checked.
		s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (whole, label, href) => {
			const safe = safeHref(href);
			if (!safe) return whole;
			return '<a href="' + safe + '" target="_blank" rel="noopener noreferrer">' + label + '</a>';
		});

		s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
		s = s.replace(/__([^_]+)__/g, '<strong>$1</strong>');
		s = s.replace(/\*([^*]+)\*/g, '<em>$1</em>');
		s = s.replace(/(^|[^\w])_([^_]+)_(?=[^\w]|$)/g, '$1<em>$2</em>');

		s = s.replace(new RegExp(NUL + 'C(\\d+)' + NUL, 'g'),
			(_, i) => '<code>' + codes[Number(i)] + '</code>');
		return s;
	}

	function splitTableRow(line) {
		let s = line.trim();
		if (s.startsWith('|')) s = s.slice(1);
		if (s.endsWith('|')) s = s.slice(0, -1);
		return s.split('|').map((c) => c.trim());
	}

	function isTableSeparator(line) {
		return /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/.test(line);
	}

	function alignmentsFrom(sepCells) {
		return sepCells.map((c) => {
			const left = c.startsWith(':');
			const right = c.endsWith(':');
			if (left && right) return 'center';
			if (right) return 'right';
			if (left) return 'left';
			return '';
		});
	}

	function renderTable(header, sep, rows) {
		const align = alignmentsFrom(sep);
		const cell = (tag, text, i) => {
			const a = align[i] ? ' style="text-align:' + align[i] + '"' : '';
			return '<' + tag + a + '>' + inline(text) + '</' + tag + '>';
		};
		let html = '<div class="chat-table"><table><thead><tr>';
		header.forEach((h, i) => (html += cell('th', h, i)));
		html += '</tr></thead><tbody>';
		rows.forEach((r) => {
			html += '<tr>';
			header.forEach((_, i) => (html += cell('td', r[i] === undefined ? '' : r[i], i)));
			html += '</tr>';
		});
		return html + '</tbody></table></div>';
	}

	function render(text) {
		if (text === null || text === undefined) return '';
		let src = String(text).replace(/\r\n?/g, '\n');

		// Pull fenced code blocks out before anything else so their contents are never parsed.
		const blocks = [];
		src = src.replace(/```[^\n]*\n([\s\S]*?)```/g, (_, code) => {
			blocks.push('<pre><code>' + escapeHtml(code.replace(/\n$/, '')) + '</code></pre>');
			return NUL + 'B' + (blocks.length - 1) + NUL;
		});

		src = escapeHtml(src);

		const lines = src.split('\n');
		const out = [];
		let i = 0;
		let para = [];

		const flushPara = () => {
			if (para.length) {
				out.push('<p>' + para.map(inline).join('<br>') + '</p>');
				para = [];
			}
		};

		while (i < lines.length) {
			const line = lines[i];
			const fenced = line.match(new RegExp('^' + NUL + 'B(\\d+)' + NUL + '$'));

			if (fenced) {
				flushPara();
				out.push(blocks[Number(fenced[1])]);
				i++;
			} else if (line.trim() === '') {
				flushPara();
				i++;
			} else if (/^#{1,6}\s+/.test(line)) {
				flushPara();
				const level = line.match(/^#+/)[0].length;
				out.push('<h' + level + '>' + inline(line.replace(/^#{1,6}\s+/, '')) + '</h' + level + '>');
				i++;
			} else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
				flushPara();
				out.push('<hr>');
				i++;
			} else if (/^\s*>/.test(line)) {
				flushPara();
				const quote = [];
				while (i < lines.length && /^\s*>/.test(lines[i])) {
					quote.push(lines[i].replace(/^\s*>\s?/, ''));
					i++;
				}
				out.push('<blockquote>' + quote.map(inline).join('<br>') + '</blockquote>');
			} else if (i + 1 < lines.length && line.includes('|') && isTableSeparator(lines[i + 1])) {
				flushPara();
				const header = splitTableRow(line);
				const sep = splitTableRow(lines[i + 1]);
				i += 2;
				const rows = [];
				while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
					rows.push(splitTableRow(lines[i]));
					i++;
				}
				out.push(renderTable(header, sep, rows));
			} else if (/^\s*[-*+]\s+/.test(line)) {
				flushPara();
				const items = [];
				while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
					items.push('<li>' + inline(lines[i].replace(/^\s*[-*+]\s+/, '')) + '</li>');
					i++;
				}
				out.push('<ul>' + items.join('') + '</ul>');
			} else if (/^\s*\d+[.)]\s+/.test(line)) {
				flushPara();
				const items = [];
				while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
					items.push('<li>' + inline(lines[i].replace(/^\s*\d+[.)]\s+/, '')) + '</li>');
					i++;
				}
				out.push('<ol>' + items.join('') + '</ol>');
			} else {
				para.push(line);
				i++;
			}
		}
		flushPara();
		return out.join('\n');
	}

	window.dhChatMarkdown = render;
})();
