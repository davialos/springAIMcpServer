/** An inline piece of a line: plain text, `code`, **bold** or _italic_. Rendered by the template, never as HTML. */
export interface Inline {
  kind: 'text' | 'code' | 'bold' | 'italic';
  text: string;
}

/** One line of an answer: a paragraph line, a bullet, or an indented continuation of the bullet above. */
export interface Line {
  kind: 'p' | 'bullet' | 'cont' | 'blank';
  inline: Inline[];
}

// italic needs _ at word edges, so identifiers such as LOAN_ALL_FAILURES or ANTHROPIC_API_KEY stay as they are
const TOKEN = /(`[^`]+`)|(\*\*[^*]+\*\*)|((?<![A-Za-z0-9_])_(?:[^_\n]|(?<=[A-Za-z0-9])_(?=[A-Za-z0-9]))+_(?![A-Za-z0-9_]))/g;

/** Splits a line into inline pieces. Unclosed markers stay plain text. */
export function inline(text: string): Inline[] {
  const out: Inline[] = [];
  let last = 0;
  for (const m of text.matchAll(TOKEN)) {
    const at = m.index ?? 0;
    if (at > last) {
      out.push({ kind: 'text', text: text.slice(last, at) });
    }
    const raw = m[0];
    if (m[1]) {
      out.push({ kind: 'code', text: raw.slice(1, -1) });
    } else if (m[2]) {
      out.push({ kind: 'bold', text: raw.slice(2, -2) });
    } else {
      out.push({ kind: 'italic', text: raw.slice(1, -1) });
    }
    last = at + raw.length;
  }
  if (last < text.length) {
    out.push({ kind: 'text', text: text.slice(last) });
  }
  return out;
}

/**
 * The small subset of Markdown the assistant uses (bullets, bold, italic, inline code), as data. Nothing is ever put in as
 * HTML, so an answer cannot inject markup or script into the console, whatever a model returns.
 */
export function lines(text: string): Line[] {
  return text.split('\n').map((raw): Line => {
    if (raw.trim() === '') {
      return { kind: 'blank', inline: [] };
    }
    if (/^\s{0,1}[-*] /.test(raw)) {
      return { kind: 'bullet', inline: inline(raw.replace(/^\s{0,1}[-*] /, '')) };
    }
    if (/^\s{2,}/.test(raw)) {
      return { kind: 'cont', inline: inline(raw.trim()) };
    }
    return { kind: 'p', inline: inline(raw) };
  });
}
