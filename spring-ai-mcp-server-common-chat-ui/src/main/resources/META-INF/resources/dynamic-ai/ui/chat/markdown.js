// Streaming-tolerant Markdown renderer for <saimcp-chat> (LLD-13 §6).
//
// Safe by construction: every piece of source text is HTML-escaped before it is placed in the output, raw HTML is
// never passed through, links are limited to http(s)/mailto, and images become click-to-load placeholders unless their
// host is allow-listed. It re-parses the whole text on every call, so an unfinished construct (an open code fence, a
// half-written table) renders sensibly while an answer is still streaming.
//
// Supported: ATX headings, paragraphs, line breaks, emphasis, strong, strikethrough, inline code, links, autolinks,
// images, block quotes (nested), ordered/unordered/task lists (nested), tables with column alignment, fenced code
// blocks with syntax highlighting and a copy button, Mermaid fences (rendered by the component), thematic breaks.

import { highlight, languageLabel } from './highlight.js';

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

/** HTML-escapes text for element content and attribute values. */
export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ESC[c]);
}

/** Only these URL schemes become links; anything else stays text. */
export function safeUrl(url) {
  const u = String(url).trim();
  if (/^(https?:|mailto:)/i.test(u)) {
    return u;
  }
  return null;
}

const FENCE = /^ {0,3}(`{3,}|~{3,})\s*([\w+#.-]*)[^\n`]*$/;
const HEADING = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$/;
const HR = /^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$/;
const QUOTE = /^ {0,3}> ?(.*)$/;
const LIST_ITEM = /^( {0,12})([-*+]|\d{1,9}[.)])([ \t]+|$)(.*)$/;
/** A link destination: no spaces or angle brackets, one level of balanced parentheses (GFM). */
const URL_IN_PARENS = '(?:[^()\\s<>]|\\([^()\\s<>]*\\))+';
const TABLE_DELIM = /^ {0,3}\|?(?:[ \t]*:?-{1,}:?[ \t]*\|)+(?:[ \t]*:?-{1,}:?[ \t]*)?\|?[ \t]*$/;

/**
 * Renders Markdown to an HTML string.
 *
 * @param {string} source Markdown text (possibly incomplete while streaming)
 * @param {{imageHosts?: string[], breaks?: boolean}} [options]
 * @returns {string} HTML
 */
export function renderMarkdown(source, options = {}) {
  const opts = { imageHosts: [], breaks: true, ...options };
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n');
  return renderBlocks(lines, opts);
}

function renderBlocks(lines, opts) {
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^\s*$/.test(line)) {
      i++;
      continue;
    }
    let m;
    if ((m = FENCE.exec(line))) {
      const marker = m[1];
      const lang = m[2] || '';
      const body = [];
      let closed = false;
      i++;
      while (i < lines.length) {
        const l = lines[i];
        if (new RegExp('^ {0,3}' + marker[0] + '{' + marker.length + ',}\\s*$').test(l)) {
          closed = true;
          i++;
          break;
        }
        body.push(l);
        i++;
      }
      out.push(codeBlock(body.join('\n'), lang, closed));
      continue;
    }
    if ((m = HEADING.exec(line))) {
      const level = m[1].length;
      out.push(`<h${level}>${inline(m[2] || '', opts)}</h${level}>`);
      i++;
      continue;
    }
    if (HR.test(line)) {
      out.push('<hr>');
      i++;
      continue;
    }
    if (QUOTE.test(line)) {
      const body = [];
      while (i < lines.length && (m = QUOTE.exec(lines[i]))) {
        body.push(m[1]);
        i++;
      }
      out.push(`<blockquote>${renderBlocks(body, opts)}</blockquote>`);
      continue;
    }
    if (LIST_ITEM.test(line) && !HR.test(line)) {
      const consumed = renderList(lines, i, opts);
      out.push(consumed.html);
      i = consumed.next;
      continue;
    }
    if (line.includes('|') && i + 1 < lines.length && TABLE_DELIM.test(lines[i + 1])) {
      const consumed = renderTable(lines, i, opts);
      out.push(consumed.html);
      i = consumed.next;
      continue;
    }
    // paragraph: until a blank line or the start of another block
    const para = [line];
    i++;
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !startsBlock(lines, i)) {
      para.push(lines[i]);
      i++;
    }
    out.push(`<p>${inlineLines(para, opts)}</p>`);
  }
  return out.join('');
}

function startsBlock(lines, i) {
  const l = lines[i];
  return FENCE.test(l) || HEADING.test(l) || HR.test(l) || QUOTE.test(l) || LIST_ITEM.test(l)
    || (l.includes('|') && i + 1 < lines.length && TABLE_DELIM.test(lines[i + 1]));
}

function inlineLines(lines, opts) {
  return lines.map((l, idx) => {
    const last = idx === lines.length - 1;
    const hard = / {2,}$/.test(l) || /\\$/.test(l);
    const text = inline(l.replace(/ {2,}$/, '').replace(/\\$/, '').trim(), opts);
    if (last) {
      return text;
    }
    return text + (hard || opts.breaks ? '<br>' : ' ');
  }).join('');
}

// ─── Lists ──────────────────────────────────────────────────────────────────────────────────────────

function renderList(lines, start, opts) {
  const first = LIST_ITEM.exec(lines[start]);
  const baseIndent = first[1].length;
  const ordered = /\d/.test(first[2]);
  const startNum = ordered ? parseInt(first[2], 10) : 1;
  const items = [];
  let i = start;
  let current = null;
  while (i < lines.length) {
    const line = lines[i];
    const m = LIST_ITEM.exec(line);
    if (m && m[1].length === baseIndent && /\d/.test(m[2]) === ordered) {
      current = { lines: [m[4]], contentIndent: baseIndent + m[2].length + Math.max(1, m[3].length) };
      items.push(current);
      i++;
      continue;
    }
    if (/^\s*$/.test(line)) {
      // a blank line ends the list unless the next line continues it (indented, or another item)
      const next = lines[i + 1];
      const nextItem = next === undefined ? null : LIST_ITEM.exec(next);
      if (next !== undefined && current && (indentOf(next) >= current.contentIndent
        || (nextItem && nextItem[1].length === baseIndent && /\d/.test(nextItem[2]) === ordered))) {
        current.lines.push('');
        i++;
        continue;
      }
      break;
    }
    if (current && (indentOf(line) > baseIndent || (!LIST_ITEM.test(line) && !startsBlock(lines, i)))) {
      // continuation: nested list, indented paragraph, or lazy continuation of the paragraph
      current.lines.push(line.slice(Math.min(indentOf(line), current.contentIndent)));
      i++;
      continue;
    }
    break;
  }
  const tag = ordered ? 'ol' : 'ul';
  const startAttr = ordered && startNum !== 1 ? ` start="${startNum}"` : '';
  const body = items.map((item) => {
    let content = item.lines;
    while (content.length > 1 && /^\s*$/.test(content[content.length - 1])) {
      content = content.slice(0, -1);
    }
    let task = '';
    const t = /^\[([ xX])\][ \t]+(.*)$/.exec(content[0]);
    if (t) {
      task = `<input type="checkbox" disabled${t[1] === ' ' ? '' : ' checked'}> `;
      content = [t[2], ...content.slice(1)];
    }
    const tight = !content.some((l) => /^\s*$/.test(l));
    let html = renderBlocks(content, opts);
    if (tight) {
      html = html.replace(/^<p>([\s\S]*?)<\/p>/, '$1');
    }
    return `<li${task ? ' class="task"' : ''}>${task}${html}</li>`;
  }).join('');
  return { html: `<${tag}${startAttr}>${body}</${tag}>`, next: i };
}

function indentOf(line) {
  const m = /^[ \t]*/.exec(line);
  return m ? m[0].replace(/\t/g, '    ').length : 0;
}

// ─── Tables ─────────────────────────────────────────────────────────────────────────────────────────

function splitRow(line) {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  const cells = [];
  let cell = '';
  let code = false;
  for (let k = 0; k < s.length; k++) {
    const c = s[k];
    if (c === '\\' && s[k + 1] === '|') {
      cell += '|';
      k++;
    } else if (c === '`') {
      code = !code;
      cell += c;
    } else if (c === '|' && !code) {
      cells.push(cell.trim());
      cell = '';
    } else {
      cell += c;
    }
  }
  cells.push(cell.trim());
  return cells;
}

function renderTable(lines, start, opts) {
  const header = splitRow(lines[start]);
  const aligns = splitRow(lines[start + 1]).map((d) => {
    const left = d.startsWith(':');
    const right = d.endsWith(':');
    return left && right ? 'center' : right ? 'right' : left ? 'left' : '';
  });
  let i = start + 2;
  const rows = [];
  while (i < lines.length && lines[i].includes('|') && !/^\s*$/.test(lines[i])) {
    rows.push(splitRow(lines[i]));
    i++;
  }
  const cell = (tag, text, idx) => {
    const a = aligns[idx] ? ` style="text-align:${aligns[idx]}"` : '';
    return `<${tag}${a}>${inline(text ?? '', opts)}</${tag}>`;
  };
  const head = `<thead><tr>${header.map((h, idx) => cell('th', h, idx)).join('')}</tr></thead>`;
  const body = rows.length
    ? `<tbody>${rows.map((r) => `<tr>${header.map((_, idx) => cell('td', r[idx], idx)).join('')}</tr>`).join('')}</tbody>`
    : '';
  return { html: `<div class="table-wrap"><table>${head}${body}</table></div>`, next: i };
}

// ─── Code ───────────────────────────────────────────────────────────────────────────────────────────

function codeBlock(code, lang, closed) {
  const l = lang.toLowerCase();
  if (l === 'mermaid') {
    return `<div class="mermaid-block" data-closed="${closed}"><pre class="mermaid-source"><code>${escapeHtml(code)}`
      + '</code></pre></div>';
  }
  const label = languageLabel(l);
  return `<div class="code-block" data-lang="${escapeHtml(l)}" data-closed="${closed}">`
    + `<div class="code-bar"><span class="code-lang">${escapeHtml(label)}</span>`
    + '<button type="button" class="copy-code" aria-label="Copy code">Copy</button></div>'
    + `<pre><code>${highlight(code, l)}</code></pre></div>`;
}

// ─── Inline ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * Renders inline Markdown (code spans, links, images, autolinks, emphasis) to HTML.
 *
 * @param {string} text one line or cell
 * @param {{imageHosts?: string[]}} opts
 * @returns {string}
 */
export function inline(text, opts = {}) {
  const slots = [];
  const keep = (html) => {
    slots.push(html);
    return `\u0000${slots.length - 1}\u0000`;
  };
  let s = String(text);
  // code spans first: their content is literal
  s = s.replace(/(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)/g, (_, __, code) => keep(`<code>${escapeHtml(code.trim())}</code>`));
  // backslash escapes
  s = s.replace(/\\([\\`*_{}[\]()#+\-.!|~<>])/g, (_, c) => keep(escapeHtml(c)));
  // images: never auto-loaded unless the host is allow-listed (exfiltration guard, LLD-13 §6)
  s = s.replace(new RegExp(`!\\[([^\\]]*)\\]\\(\\s*<?(${URL_IN_PARENS})>?(?:\\s+"[^"]*")?\\s*\\)`, 'g'),
    (_, alt, url) => keep(image(alt, url, opts)));
  // links
  s = s.replace(new RegExp(`\\[([^\\]]+)\\]\\(\\s*<?(${URL_IN_PARENS})>?(?:\\s+"[^"]*")?\\s*\\)`, 'g'), (_, label, url) => {
    const safe = safeUrl(url);
    if (!safe) {
      return keep(escapeHtml(label));
    }
    return keep(link(safe, inline(label, opts)));
  });
  // autolinks <https://…> and bare URLs
  s = s.replace(/<((?:https?:\/\/|mailto:)[^>\s]+)>/g, (_, url) => keep(link(url, escapeHtml(url))));
  s = s.replace(/(^|[\s(])((?:https?:\/\/)[^\s<]*[^\s<.,:;"')\]])/g, (_, pre, url) => pre + keep(link(url, escapeHtml(url))));
  s = escapeHtml(s);
  s = s.replace(/(\*\*|__)(?=\S)([\s\S]*?\S)\1/g, '<strong>$2</strong>');
  s = s.replace(/(^|[^*\w])\*(?=\S)([^*]*?\S)\*(?!\*)/g, '$1<em>$2</em>');
  s = s.replace(/(^|[^_\w])_(?=\S)([^_]*?\S)_(?![_\w])/g, '$1<em>$2</em>');
  s = s.replace(/~~(?=\S)([\s\S]*?\S)~~/g, '<del>$1</del>');
  // restore slots (they may nest: a link label can contain a code span)
  for (let pass = 0; pass < 3 && s.includes('\u0000'); pass++) {
    s = s.replace(/\u0000(\d+)\u0000/g, (_, n) => slots[Number(n)]);
  }
  return s;
}

function link(url, labelHtml) {
  return `<a href="${escapeHtml(url)}" title="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer nofollow">`
    + `${labelHtml}</a>`;
}

function image(alt, url, opts) {
  const safe = /^https?:/i.test(url) ? url : null;
  if (!safe) {
    return escapeHtml(alt || 'image');
  }
  let host = '';
  try {
    host = new URL(safe).host;
  } catch {
    return escapeHtml(alt || 'image');
  }
  if ((opts.imageHosts || []).includes(host)) {
    return `<img src="${escapeHtml(safe)}" alt="${escapeHtml(alt)}" loading="lazy" referrerpolicy="no-referrer">`;
  }
  return `<button type="button" class="image-placeholder" data-src="${escapeHtml(safe)}" data-alt="${escapeHtml(alt)}"`
    + ` title="Load image from ${escapeHtml(host)}">Image: ${escapeHtml(alt || host)} (${escapeHtml(host)}) – click to load</button>`;
}
