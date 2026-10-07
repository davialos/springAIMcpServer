// Small, dependency-free syntax highlighter for code blocks in <saimcp-chat>. Produces escaped HTML with
// <span class="tok-…"> wrappers; unknown languages are escaped only. Covers the languages an enterprise assistant
// typically shows: CEL (Common Expression Language), JSON, Java/Kotlin/C-like, JavaScript/TypeScript, Python, SQL, YAML,
// shell, XML/HTML and CSS.

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const esc = (t) => t.replace(/[&<>"']/g, (c) => ESC[c]);

const words = (list) => new RegExp('\\b(?:' + list.split(/\s+/).filter(Boolean).join('|') + ')\\b');

const STRING_DQ = /"(?:[^"\\\n]|\\.)*"?/;
const STRING_SQ = /'(?:[^'\\\n]|\\.)*'?/;
const NUMBER = /\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[lLfFdDuU]?\b/;
const LINE_COMMENT = /\/\/[^\n]*/;
const BLOCK_COMMENT = /\/\*[\s\S]*?(?:\*\/|$)/;
const HASH_COMMENT = /#[^\n]*/;
const CALL = /\b[A-Za-z_$][\w$]*(?=\s*\()/;
const OPERATOR = /[-+*/%=!<>&|^~?:]+/;

/** Rules per language: [token class, regex]. Order matters: earlier rules win at the same position. */
const LANGS = {
  cel: [
    ['comment', LINE_COMMENT],
    // raw / bytes / triple-quoted strings first
    ['string', /[rRbB]{0,2}(?:"""[\s\S]*?(?:"""|$)|'''[\s\S]*?(?:'''|$))/],
    ['string', /[rRbB]{1,2}(?:"[^"\n]*"?|'[^'\n]*'?)/],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
    ['number', /\b(?:0[xX][0-9a-fA-F]+u?|\d+u|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b/],
    ['literal', words('true false null')],
    ['keyword', words('in as break const continue else for function if import let loop package namespace return var void while')],
    ['builtin', words('has all exists exists_one map filter size matches startsWith endsWith contains int uint double string bytes bool type duration timestamp dyn list getDate getDayOfMonth getDayOfWeek getDayOfYear getFullYear getHours getMilliseconds getMinutes getMonth getSeconds')],
    ['function', CALL],
    ['operator', /&&|\|\||==|!=|<=|>=|[-+*/%<>!?:]/],
  ],
  json: [
    ['property', /"(?:[^"\\\n]|\\.)*"(?=\s*:)/],
    ['string', STRING_DQ],
    ['number', /-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/],
    ['literal', words('true false null')],
  ],
  clike: [
    ['comment', BLOCK_COMMENT],
    ['comment', LINE_COMMENT],
    ['annotation', /@[A-Za-z_][\w.]*/],
    ['string', /"""[\s\S]*?(?:"""|$)/],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
    ['number', NUMBER],
    ['literal', words('true false null this super')],
    ['keyword', words('abstract assert boolean break byte case catch char class const continue default do double else enum exports extends final finally float for goto if implements import instanceof int interface long module native new non-sealed package permits private protected public record requires return sealed short static strictfp switch synchronized throw throws transient try var void volatile when while yield fun val object companion data override open internal lateinit suspend namespace using struct typedef template typename virtual')],
    ['type', /\b[A-Z][A-Za-z0-9_]*\b/],
    ['function', CALL],
    ['operator', OPERATOR],
  ],
  javascript: [
    ['comment', BLOCK_COMMENT],
    ['comment', LINE_COMMENT],
    ['string', /`(?:[^`\\]|\\.)*`?/],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
    ['number', NUMBER],
    ['literal', words('true false null undefined NaN Infinity this')],
    ['keyword', words('async await break case catch class const continue debugger default delete do else export extends finally for from function get if import in instanceof let new of return set static super switch throw try typeof var void while with yield type interface enum implements declare readonly as satisfies keyof namespace abstract private protected public')],
    ['type', /\b[A-Z][A-Za-z0-9_]*\b/],
    ['function', CALL],
    ['operator', OPERATOR],
  ],
  python: [
    ['comment', HASH_COMMENT],
    ['string', /[rRbBfFuU]{0,2}(?:"""[\s\S]*?(?:"""|$)|'''[\s\S]*?(?:'''|$))/],
    ['string', /[rRbBfFuU]{0,2}(?:"(?:[^"\\\n]|\\.)*"?|'(?:[^'\\\n]|\\.)*'?)/],
    ['annotation', /@[A-Za-z_][\w.]*/],
    ['number', NUMBER],
    ['literal', words('True False None self cls')],
    ['keyword', words('and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case')],
    ['function', CALL],
    ['operator', OPERATOR],
  ],
  sql: [
    ['comment', /--[^\n]*/],
    ['comment', BLOCK_COMMENT],
    ['string', /'(?:[^']|'')*'?/],
    ['property', /"(?:[^"]|"")*"?/],
    ['number', NUMBER],
    ['literal', /\b(?:TRUE|FALSE|NULL|true|false|null)\b/],
    ['keyword', new RegExp('\\b(?:' + ('select from where and or not in is like ilike between join inner left right full outer cross on '
      + 'group by order having limit offset fetch first next rows only insert into values update set delete create table view index '
      + 'alter drop add column primary key foreign references unique check default constraint as distinct union all except intersect '
      + 'case when then else end exists returning with recursive over partition window asc desc nulls last cast coalesce count sum '
      + 'avg min max begin commit rollback grant revoke schema sequence trigger function procedure language returns if replace')
      .split(' ').join('|') + ')\\b', 'i')],
    ['function', CALL],
    ['operator', /[-+*/%=<>!|:]+/],
  ],
  yaml: [
    ['comment', HASH_COMMENT],
    ['property', /^[ \t]*-?[ \t]*[\w.$-]+(?=[ \t]*:(?:\s|$))/m],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
    ['number', /\b\d+(?:\.\d+)?\b/],
    ['literal', words('true false null yes no on off')],
    ['keyword', /^---$|^\.\.\.$/m],
    ['operator', /[|>][-+]?(?=\s*$)/m],
  ],
  bash: [
    ['comment', HASH_COMMENT],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
    ['variable', /\$(?:\{[^}\n]*\}?|[A-Za-z_][\w]*|[0-9@#?*$!-])/],
    ['keyword', words('if then else elif fi for while until do done case esac in function return local export readonly set unset exit source alias')],
    ['builtin', words('echo cd ls cat grep sed awk curl wget git mvn gradle java npm node docker kubectl chmod chown mkdir rm cp mv printf read test sudo')],
    ['number', /\b\d+\b/],
    ['operator', /&&|\|\||[|;&<>]/],
  ],
  xml: [
    ['comment', /<!--[\s\S]*?(?:-->|$)/],
    ['keyword', /<!\[CDATA\[[\s\S]*?(?:\]\]>|$)|<![A-Za-z][^>]*>?/],
    ['tag', /<\/?[A-Za-z][\w:.-]*|\/?>/],
    ['property', /\b[A-Za-z_:][\w:.-]*(?==)/],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
  ],
  css: [
    ['comment', BLOCK_COMMENT],
    ['string', STRING_DQ],
    ['string', STRING_SQ],
    ['keyword', /@[\w-]+/],
    ['property', /[\w-]+(?=\s*:)/],
    ['number', /-?\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms|deg)?\b/],
    ['literal', /#[0-9a-fA-F]{3,8}\b/],
    ['type', /[.#][\w-]+/],
  ],
};

const ALIASES = {
  cel: 'cel', 'google-cel': 'cel',
  json: 'json', jsonc: 'json', json5: 'json',
  java: 'clike', kotlin: 'clike', kt: 'clike', groovy: 'clike', scala: 'clike', c: 'clike', cpp: 'clike',
  'c++': 'clike', cs: 'clike', csharp: 'clike', go: 'clike', rust: 'clike', swift: 'clike', dart: 'clike',
  js: 'javascript', javascript: 'javascript', mjs: 'javascript', jsx: 'javascript', ts: 'javascript',
  typescript: 'javascript', tsx: 'javascript',
  py: 'python', python: 'python',
  sql: 'sql', postgres: 'sql', postgresql: 'sql', plsql: 'sql', mysql: 'sql',
  yaml: 'yaml', yml: 'yaml',
  sh: 'bash', bash: 'bash', shell: 'bash', zsh: 'bash', console: 'bash',
  xml: 'xml', html: 'xml', svg: 'xml', xhtml: 'xml',
  css: 'css', scss: 'css',
};

const LABELS = {
  cel: 'CEL', json: 'JSON', java: 'Java', kotlin: 'Kotlin', js: 'JavaScript', javascript: 'JavaScript',
  ts: 'TypeScript', typescript: 'TypeScript', py: 'Python', python: 'Python', sql: 'SQL', yaml: 'YAML', yml: 'YAML',
  sh: 'Shell', bash: 'Bash', shell: 'Shell', xml: 'XML', html: 'HTML', css: 'CSS',
};

/** Display name of a fence language ("" → "Text"). */
export function languageLabel(lang) {
  if (!lang) return 'Text';
  return LABELS[lang] ?? lang;
}

/** Whether a language has a grammar. */
export function supports(lang) {
  return Boolean(ALIASES[String(lang).toLowerCase()]);
}

const COMPILED = new Map();

function compiled(lang) {
  let c = COMPILED.get(lang);
  if (!c) {
    // sticky copies keep each rule's own i/m flags; rules use non-capturing groups only
    c = LANGS[lang].map(([cls, r]) => [cls, new RegExp(r.source, r.flags.replace(/[gy]/g, '') + 'y')]);
    COMPILED.set(lang, c);
  }
  return c;
}

/**
 * Highlights code; returns escaped HTML.
 *
 * @param {string} code source code
 * @param {string} lang fence language
 * @returns {string}
 */
export function highlight(code, lang) {
  const key = ALIASES[String(lang || '').toLowerCase()];
  if (!key) {
    return esc(code);
  }
  const rules = compiled(key);
  let out = '';
  let plain = '';
  let pos = 0;
  const flush = () => {
    if (plain) {
      out += esc(plain);
      plain = '';
    }
  };
  while (pos < code.length) {
    let matched = false;
    for (const [cls, re] of rules) {
      re.lastIndex = pos;
      const m = re.exec(code);
      if (m && m[0].length > 0) {
        flush();
        out += `<span class="tok-${cls}">${esc(m[0])}</span>`;
        pos += m[0].length;
        matched = true;
        break;
      }
    }
    if (!matched) {
      // unmatched identifiers are consumed whole, so no rule ever starts inside a word ("index" vs "in")
      const word = /[A-Za-z_$][\w$]*|\s+|./y;
      word.lastIndex = pos;
      const w = word.exec(code)[0];
      plain += w;
      pos += w.length;
    }
  }
  flush();
  return out;
}
