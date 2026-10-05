// Constraint-aware random values (the "random" data source).
// Owned by spring-ai-mcp-server-common-loadtest; copied into every generated suite. Edit in the suite if needed.

export function int(min, max) {
  const lo = Math.ceil(min);
  const hi = Math.floor(max);
  return hi < lo ? lo : Math.floor(Math.random() * (hi - lo + 1)) + lo;
}

export function float(min, max, decimals = 2) {
  const f = Math.pow(10, decimals);
  const v = min + Math.random() * (max - min);
  return Math.round(v * f) / f;
}

export function pick(list) {
  return list && list.length ? list[Math.floor(Math.random() * list.length)] : undefined;
}

const ZIPF_CACHE = {};

/**
 * A list element with a skewed popularity, like real traffic: a few hot rows take most requests.
 *   zipf: rank r (list order) has weight 1 / r^s (default s = 1.1)
 *   hot:  `hotFraction` of the list (default 5%) takes `hotShare` of the requests (default 80%)
 * Anything else (or a one-element list) is a uniform pick.
 */
export function pickSkewed(list, skew) {
  const n = list ? list.length : 0;
  if (n < 2 || !skew || !skew.mode || skew.mode === 'uniform') return pick(list);
  if (skew.mode === 'hot') {
    const hot = Math.max(1, Math.round(n * (skew.hotFraction !== undefined ? skew.hotFraction : 0.05)));
    const share = skew.hotShare !== undefined ? skew.hotShare : 0.8;
    if (hot >= n || Math.random() < share) return list[Math.floor(Math.random() * Math.min(hot, n))];
    return list[hot + Math.floor(Math.random() * (n - hot))];
  }
  if (skew.mode === 'zipf') {
    const s = skew.s !== undefined ? skew.s : 1.1;
    const key = `${n}|${s}`;
    let cumulative = ZIPF_CACHE[key];
    if (!cumulative) {
      cumulative = new Array(n);
      let total = 0;
      for (let i = 0; i < n; i++) {
        total += 1 / Math.pow(i + 1, s);
        cumulative[i] = total;
      }
      ZIPF_CACHE[key] = cumulative;
    }
    const r = Math.random() * cumulative[n - 1];
    let lo = 0;
    let hi = n - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (cumulative[mid] < r) lo = mid + 1;
      else hi = mid;
    }
    return list[lo];
  }
  return pick(list);
}

export function bool(p = 0.5) {
  return Math.random() < p;
}

const LOWER = 'abcdefghijklmnopqrstuvwxyz';
const UPPER = LOWER.toUpperCase();
const DIGITS = '0123456789';
const ALNUM = LOWER + UPPER + DIGITS;

export function chars(set, length) {
  let s = '';
  for (let i = 0; i < length; i++) s += set[Math.floor(Math.random() * set.length)];
  return s;
}

export function alnum(length) {
  return chars(ALNUM, length);
}

export function uuid() {
  const h = chars('0123456789abcdef', 32).split('');
  h[12] = '4';
  h[16] = '89ab'[Math.floor(Math.random() * 4)];
  const s = h.join('');
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}

/**
 * A Date within [-pastDays, +futureDays] of today, honouring a PAST/FUTURE constraint. Days count from UTC
 * midnight and keep a two-day margin, so a PAST/FUTURE value is still past/future as a calendar date
 * (LocalDate) whatever the time of day and the server's time zone.
 */
export function date(schema = {}, pastDays = 3650, futureDays = 365) {
  let from = -pastDays;
  let to = futureDays;
  if (schema.temporal === 'PAST') to = -2;
  if (schema.temporal === 'FUTURE') from = 2;
  const midnight = Math.floor(Date.now() / 86400000) * 86400000;
  return new Date(midnight + int(from, to) * 86400000 + int(0, 86399) * 1000);
}

export function isoDate(d) {
  return d.toISOString().slice(0, 10);
}

export function isoDateTime(d) {
  return d.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

// ── Regex-driven strings ────────────────────────────────────────────────────────────────────────────
// Supports literals, escapes (\d \w \s \. …), classes ([a-z0-9_-], [^…]), groups with alternation,
// non-capturing groups, quantifiers (? * + {n} {n,} {n,m}, lazy forms) and anchors. Unbounded repeats are
// capped. Unsupported constructs (look-arounds, back-references) make generation fail → caller falls back.

const PRINTABLE = (() => {
  let s = '';
  for (let c = 32; c < 127; c++) s += String.fromCharCode(c);
  return s;
})();
const CLASS_ESCAPES = {
  d: DIGITS,
  w: ALNUM + '_',
  s: ' ',
  D: LOWER + UPPER + '_-',
  W: ' -.,!',
  S: ALNUM,
};

function parseRegex(src) {
  let i = 0;
  function peek() { return src[i]; }
  function eat() { return src[i++]; }

  function parseAlternation() {
    const branches = [parseSequence()];
    while (peek() === '|') {
      eat();
      branches.push(parseSequence());
    }
    return { t: 'alt', branches };
  }

  function parseSequence() {
    const items = [];
    while (i < src.length && peek() !== '|' && peek() !== ')') {
      let atom = parseAtom();
      if (atom === null) continue;
      atom = parseQuantifier(atom);
      items.push(atom);
    }
    return { t: 'seq', items };
  }

  function parseAtom() {
    const c = eat();
    if (c === '^' || c === '$') return null;
    if (c === '(') {
      if (peek() === '?') {
        eat();
        const k = eat();
        if (k !== ':') throw new Error('unsupported group (?' + k);
      }
      const inner = parseAlternation();
      if (eat() !== ')') throw new Error('unbalanced group');
      return inner;
    }
    if (c === '[') return parseClass();
    if (c === '.') return { t: 'set', chars: ALNUM };
    if (c === '\\') {
      const e = eat();
      if (CLASS_ESCAPES[e]) return { t: 'set', chars: CLASS_ESCAPES[e] };
      if (/[1-9bBk]/.test(e)) throw new Error('unsupported escape \\' + e);
      if (e === 'n') return { t: 'lit', c: '\n' };
      if (e === 't') return { t: 'lit', c: '\t' };
      return { t: 'lit', c: e };
    }
    return { t: 'lit', c };
  }

  function parseClass() {
    let negate = false;
    if (peek() === '^') { eat(); negate = true; }
    let set = '';
    let first = true;
    while (i < src.length && (peek() !== ']' || first)) {
      first = false;
      let c = eat();
      if (c === '\\') {
        const e = eat();
        if (CLASS_ESCAPES[e]) { set += CLASS_ESCAPES[e]; continue; }
        c = e === 'n' ? '\n' : e === 't' ? '\t' : e;
      }
      if (peek() === '-' && src[i + 1] !== ']' && src[i + 1] !== undefined) {
        eat();
        let end = eat();
        if (end === '\\') end = eat();
        for (let x = c.charCodeAt(0); x <= end.charCodeAt(0); x++) set += String.fromCharCode(x);
      } else {
        set += c;
      }
    }
    eat(); // ]
    if (negate) set = PRINTABLE.split('').filter((ch) => set.indexOf(ch) < 0).join('');
    return { t: 'set', chars: set || 'x' };
  }

  function parseQuantifier(atom) {
    let min = 1;
    let max = 1;
    const c = peek();
    if (c === '?') { eat(); min = 0; max = 1; }
    else if (c === '*') { eat(); min = 0; max = 4; }
    else if (c === '+') { eat(); min = 1; max = 5; }
    else if (c === '{') {
      const m = /^\{(\d*)(,?)(\d*)\}/.exec(src.slice(i));
      if (!m) return atom;
      i += m[0].length;
      min = m[1] === '' ? 0 : parseInt(m[1], 10);
      max = m[2] === '' ? min : m[3] === '' ? min + 4 : parseInt(m[3], 10);
    } else {
      return atom;
    }
    if (peek() === '?' || peek() === '+') eat(); // lazy / possessive
    return { t: 'rep', atom, min, max };
  }

  const ast = parseAlternation();
  if (i < src.length) throw new Error('unexpected ' + src[i]);
  return ast;
}

function gen(node) {
  switch (node.t) {
    case 'alt': return gen(pick(node.branches));
    case 'seq': return node.items.map(gen).join('');
    case 'lit': return node.c;
    case 'set': return node.chars[Math.floor(Math.random() * node.chars.length)];
    case 'rep': {
      const n = int(node.min, node.max);
      let s = '';
      for (let k = 0; k < n; k++) s += gen(node.atom);
      return s;
    }
    default: return '';
  }
}

const REGEX_CACHE = {};

/** A string matching `pattern`, or undefined when the pattern is unsupported or no match was produced. */
export function fromPattern(pattern) {
  let entry = REGEX_CACHE[pattern];
  if (entry === undefined) {
    try {
      entry = { ast: parseRegex(pattern), re: new RegExp(pattern) };
    } catch (e) {
      entry = null;
    }
    REGEX_CACHE[pattern] = entry;
  }
  if (!entry) return undefined;
  for (let attempt = 0; attempt < 8; attempt++) {
    const s = gen(entry.ast);
    if (entry.re.test(s)) return s;
  }
  return undefined;
}

/** Fits a string into [minLength, maxLength]. */
export function fitLength(s, schema) {
  let out = String(s);
  if (schema.maxLength !== undefined && out.length > schema.maxLength) out = out.slice(0, schema.maxLength);
  if (schema.minLength !== undefined && out.length < schema.minLength) out += alnum(schema.minLength - out.length);
  return out;
}

/** Fits a number into [minimum, maximum] and the integer type. */
export function fitNumber(n, schema) {
  let v = Number(n);
  if (!isFinite(v)) v = 0;
  if (schema.minimum !== undefined && v < schema.minimum) v = schema.minimum;
  if (schema.maximum !== undefined && v > schema.maximum) v = schema.maximum;
  return schema.type === 'integer' ? Math.round(v) : v;
}

/** A random value valid for a scalar schema: {type, format, enum, minLength, maxLength, minimum, maximum, pattern, temporal}. */
export function scalar(schema) {
  if (schema.enum && schema.enum.length) return pick(schema.enum);
  switch (schema.type) {
    case 'boolean':
      return bool();
    case 'integer': {
      const min = schema.minimum !== undefined ? schema.minimum : 0;
      const max = schema.maximum !== undefined ? schema.maximum : min + 100000;
      return int(min, max);
    }
    case 'number': {
      const min = schema.minimum !== undefined ? schema.minimum : 0;
      const max = schema.maximum !== undefined ? schema.maximum : min + 10000;
      return float(min, max);
    }
    default:
      return randomString(schema);
  }
}

function randomString(schema) {
  switch (schema.format) {
    case 'date': return isoDate(date(schema));
    case 'date-time': return isoDateTime(date(schema));
    case 'time': return isoDateTime(date(schema)).slice(11, 19);
    case 'uuid': return uuid();
    case 'email': return `${alnum(int(5, 12)).toLowerCase()}@${pick(['example.com', 'example.org'])}`;
    case 'uri': return `https://example.com/${alnum(8).toLowerCase()}`;
    case 'ipv4': return `${int(1, 223)}.${int(0, 255)}.${int(0, 255)}.${int(1, 254)}`;
    case 'byte': return encodeBase64(alnum(int(4, 24)));
    default:
      break;
  }
  if (schema.pattern) {
    const s = fromPattern(schema.pattern);
    if (s !== undefined) return s;
  }
  const min = schema.minLength !== undefined ? schema.minLength : 1;
  const max = schema.maxLength !== undefined ? Math.min(schema.maxLength, Math.max(min, 64)) : Math.max(min, 24);
  return alnum(int(min, max));
}

function encodeBase64(s) {
  const table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let out = '';
  for (let i = 0; i < s.length; i += 3) {
    const a = s.charCodeAt(i);
    const b = s.charCodeAt(i + 1);
    const c = s.charCodeAt(i + 2);
    out += table[a >> 2] + table[((a & 3) << 4) | (b >> 4 || 0)];
    out += isNaN(b) ? '=' : table[((b & 15) << 2) | (c >> 6 || 0)];
    out += isNaN(c) ? '=' : table[c & 63];
  }
  return out;
}
