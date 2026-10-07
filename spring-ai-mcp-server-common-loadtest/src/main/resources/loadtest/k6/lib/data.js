// Data-source selection: every generated field calls field(ctx, spec) and this module decides where the value
// comes from — user-supplied values, real values sampled from the database/API, dummy values or random values —
// according to DATA_MODE (env) or config.data.mode:
//   auto    user > real > dummy                (default: realistic, hits existing rows)
//   dummy   realistic fake values
//   random  constraint-driven random values    (fuzz-like; 4xx accepted, see config.data.acceptClientErrorsIn)
//   real    real values > user > dummy
//   user    user values > dummy
//   mixed   weighted pick per field per request among the sources available (config.data.mix)
// Which real value is used (config.data.pick / PICK env):
//   random     any sampled value (default)
//   partition  each VU walks its own slice of the pool (VU n takes n-1, n-1+K, n-1+2K, …; K = config.data.partitions
//              or the VUS env, default 100): no two VUs hit the same row, so updates/deletes do not contend or 404
//   sequence   every VU walks the pool in order from its own start, wrapping around
// Real values of one table that were sampled together (spec.group) come from the same row: one request that carries
// an order id and its customer id sends a pair that exists, not two unrelated rows.
// In every mode except user/real, identifier fields (ids, foreign keys) keep using real values when
// config.data.realIdentifiersInAllModes is true (default), so lookups hit existing rows instead of 404-ing.
import * as R from './random.js';
import * as D from './dummy.js';

const MODES = ['auto', 'dummy', 'random', 'real', 'user', 'mixed'];

let STATE = {
  config: { data: {} },
  real: {},
  userFields: {},
  userPayloads: {},
  mode: 'auto',
};

/** Called once from main.js init code with the shared pools. */
export function init(state) {
  const mode = (__ENV.DATA_MODE || (state.config.data && state.config.data.mode) || 'auto').toLowerCase();
  if (MODES.indexOf(mode) < 0) throw new Error(`DATA_MODE must be one of ${MODES.join(', ')} (got ${mode})`);
  STATE = Object.assign({}, state, { mode });
  D.setDictionaries(state.dictionaries || {});
  D.setFileSize(((state.config.data || {}).files || {}).sizeKb);
}

export function dataMode() {
  return STATE.mode;
}

/**
 * A fresh per-request context. `complete`: send every optional field too (seeding creates whole rows); `write`: the
 * request changes data (POST/PUT/PATCH/DELETE), which per-VU partitioning (data.partition) keeps off other VUs' rows.
 */
export function context(apiId, seeded, complete, write) {
  return { api: apiId, mode: STATE.mode, depth: 0, sources: {}, rows: {}, seeded: seeded || {},
    complete: complete === true, write: write === true };
}

function cfg() {
  return STATE.config.data || {};
}

function userValues(spec) {
  const keys = [spec.key, `${spec.owner}.${spec.name}`, `${spec.api || ''}.${spec.name}`, spec.name];
  for (const k of keys) {
    const v = STATE.userFields[k];
    if (v && v.length) return v;
  }
  return null;
}

/**
 * Real values for a field: rows created by this run first (seeded in setup or created by earlier requests of
 * this VU — they certainly exist), then values sampled from the database or harvested from the API.
 */
function realValues(spec, ctx) {
  if (!spec.real) return null;
  const seeded = ctx && ctx.seeded && ctx.seeded[spec.real];
  const created = CREATED[spec.real];
  if ((seeded && seeded.length) || (created && created.length)) {
    return (seeded || []).concat(created || []);
  }
  const v = STATE.real[spec.real];
  return v && v.length ? v : null;
}

/**
 * One real value. The pool is first narrowed to this VU's slice for writes (data.partition, see below). Then:
 *  - a composite foreign key's pool holds tuples (one per parent row): the first of its fields in a request picks the
 *    tuple, the others take their component from the same tuple;
 *  - values of one `group` (table) share the row chosen first in this request, provided the pools have the same length
 *    (pools sampled together do; seeded/harvested ones usually do not and are picked independently);
 *  - otherwise one value: by popularity skew (data.skew) with the default `random` pick, or by the `partition` /
 *    `sequence` walk of data.pick.
 */
function pickReal(spec, ctx, values) {
  const mine = partitioned(values, ctx);
  if (spec.component !== undefined) {
    ctx.tuples = ctx.tuples || {};
    let t = ctx.tuples[spec.real];
    if (t === undefined) {
      t = mine[pickPosition(spec, mine.length)];
      ctx.tuples[spec.real] = t;
    }
    return Array.isArray(t) ? t[spec.component] : t;
  }
  if (!spec.group) return mine[pickPosition(spec, mine.length)];
  const slot = `${spec.group}:${mine.length}`;
  if (ctx.rows[slot] === undefined) ctx.rows[slot] = pickPosition(spec, mine.length);
  return mine[ctx.rows[slot]];
}

/** A position in a pool of `len` values: skewed random for the default strategy, else the pick-strategy walk. */
function pickPosition(spec, len) {
  return pickStrategy() === 'random' ? R.indexSkewed(len, skew()) : pickIndex(spec, len);
}

/** Popularity skew of real values: data.skew (uniform | zipf | hot), overridden by SKEW / SKEW_S in the environment. */
function skew() {
  const c = cfg().skew || {};
  const mode = __ENV.SKEW || c.mode || 'uniform';
  return mode === 'uniform' ? null : Object.assign({}, c, { mode, s: __ENV.SKEW_S ? parseFloat(__ENV.SKEW_S) : c.s });
}

/**
 * Writes with data.partition.mode = "vu": every VU takes its own slice of a pool (values i with i % slots == VU's slot),
 * so concurrent updates and deletes never fight over the same row. Reads still see the whole pool. Fewer values than
 * slots: the slice count shrinks to the pool size (VUs then share rows, as they must).
 */
function partitioned(values, ctx) {
  const p = cfg().partition || {};
  const mode = __ENV.PARTITION || p.mode;
  if (mode !== 'vu' || !ctx.write || !values || values.length < 2) return values;
  const slots = Math.min(p.slots || 64, values.length);
  const slot = ((typeof __VU === 'number' && __VU > 0 ? __VU : 1) - 1) % slots;
  const mine = [];
  for (let i = slot; i < values.length; i += slots) mine.push(values[i]);
  return mine.length ? mine : values;
}

/** Ids created by this VU's own create requests during the run (bounded), by pool key. */
const CREATED = {};
const MAX_CREATED = 500;

/** Remembers an id the server returned for a created row, so later requests of this VU can use it. */
export function remember(pool, id) {
  if (!pool || id === undefined || id === null || typeof id === 'object') return;
  const list = CREATED[pool] || (CREATED[pool] = []);
  if (list.length >= MAX_CREATED) list.shift();
  list.push(id);
}

const PICKS = ['random', 'partition', 'sequence'];
const CURSOR = {};

function pickStrategy() {
  const p = (__ENV.PICK || cfg().pick || 'random').toLowerCase();
  if (PICKS.indexOf(p) < 0) throw new Error(`PICK must be one of ${PICKS.join(', ')} (got ${p})`);
  return p;
}

/** Index into a pool of `len` values according to the pick strategy. */
function pickIndex(spec, len) {
  const strategy = pickStrategy();
  if (strategy === 'random' || len <= 1) return R.int(0, len - 1);
  const slot = `${spec.group || spec.real || spec.key}:${len}`;
  const n = CURSOR[slot] || 0;
  CURSOR[slot] = n + 1;
  const vu = (typeof __VU === 'number' && __VU > 0 ? __VU : 1) - 1;
  if (strategy === 'sequence') return (vu + n) % len;
  const k = Math.max(1, Number(cfg().partitions || __ENV.VUS || 100));
  return ((vu % k) + k * n) % len;
}

function choose(ctx, spec, user, real) {
  const identifier = spec.kind === 'id' || spec.kind === 'uuid';
  const realIds = cfg().realIdentifiersInAllModes !== false;
  switch (ctx.mode) {
    case 'dummy':
      return identifier && realIds && real ? 'real' : 'dummy';
    case 'random':
      return identifier && realIds && real ? 'real' : 'random';
    case 'real':
      return real ? 'real' : user ? 'user' : 'dummy';
    case 'user':
      return user ? 'user' : identifier && real ? 'real' : 'dummy';
    case 'mixed': {
      if (identifier && realIds && real && !user) return 'real';
      const mix = cfg().mix || { user: 20, real: 40, dummy: 30, random: 10 };
      const options = [];
      if (user) options.push(['user', mix.user || 0]);
      if (real) options.push(['real', mix.real || 0]);
      options.push(['dummy', mix.dummy || 0]);
      options.push(['random', mix.random || 0]);
      return weighted(options) || 'dummy';
    }
    default:
      return user ? 'user' : real ? 'real' : 'dummy';
  }
}

function weighted(options) {
  const total = options.reduce((s, o) => s + o[1], 0);
  if (total <= 0) return undefined;
  let r = Math.random() * total;
  for (const [name, w] of options) {
    r -= w;
    if (r < 0) return name;
  }
  return options[options.length - 1][0];
}

/**
 * The value of one scalar field.
 * spec: { key, name, owner, kind, schema: {type, format, enum, minLength, …}, real: 'table.column' | 'sql:<id>' | null,
 *         group: 'table' | undefined }
 */
export function field(ctx, spec) {
  const user = userValues(Object.assign({ api: ctx.api }, spec));
  const real = realValues(spec, ctx);
  const tuple = spec.component !== undefined && spec.real;
  if (tuple) ctx.tupleSources = ctx.tupleSources || {};
  // the columns of a composite foreign key share one source and one parent row per request
  const source = tuple && ctx.tupleSources[spec.real] ? ctx.tupleSources[spec.real] : choose(ctx, spec, user, real);
  if (tuple) ctx.tupleSources[spec.real] = source;
  ctx.sources[spec.key] = source;
  let value;
  switch (source) {
    case 'user':
      value = R.pick(user);
      break;
    case 'real':
      value = pickReal(spec, ctx, real);
      break;
    case 'random':
      value = R.scalar(spec.schema);
      break;
    default:
      value = D.generate(spec.kind, spec.schema, ctx, spec);
  }
  if (spec.schema.format === 'data-rest-link') {
    // Spring Data REST association: the URI of the target resource (collection prefix + id)
    const id = source === 'real' || source === 'user' ? value : R.int(1, 1000);
    return `${baseUrl()}${spec.schema.example}${id}`;
  }
  return value;
}

function baseUrl() {
  return (__ENV.BASE_URL || STATE.config.baseUrl || '').replace(/\/+$/, '');
}

/**
 * An object from [name, required, thunk] entries; optional entries are included at config.data.optionalFieldRate,
 * or always while seeding (ctx.complete), so seeded rows carry every column later requests may look them up by.
 */
export function obj(ctx, entries) {
  const rate = cfg().optionalFieldRate !== undefined ? cfg().optionalFieldRate : 0.7;
  const out = {};
  for (const [name, required, thunk] of entries) {
    if (required || (ctx && ctx.complete) || Math.random() < rate) {
      const v = thunk();
      if (v !== undefined) out[name] = v;
    }
  }
  return out;
}

/** An array of thunk() values sized within [minItems, maxItems] (capped by config.data.maxArrayItems). */
export function arr(ctx, bounds, thunk) {
  const cap = cfg().maxArrayItems || 3;
  const min = bounds.minItems !== undefined ? bounds.minItems : 1;
  const max = Math.max(min, bounds.maxItems !== undefined ? Math.min(bounds.maxItems, Math.max(cap, min)) : Math.max(cap, min));
  const n = R.int(min, max);
  const out = [];
  for (let i = 0; i < n; i++) out.push(thunk());
  return out;
}

/** A nested object, bounded by config.data.maxDepth so recursive DTOs terminate. */
export function nested(ctx, thunk) {
  const maxDepth = cfg().maxDepth || 4;
  if (ctx.depth >= maxDepth) return undefined;
  ctx.depth++;
  try {
    return thunk();
  } finally {
    ctx.depth--;
  }
}

/** A free-form object (Map, JsonNode, Object): nothing is known about it, so it stays small. */
export function free(ctx) {
  return {};
}

/**
 * A complete user-supplied body for the API (data/user.json → payloads), when the data mode uses user data:
 * always in auto/user mode, at the mix's user weight in mixed mode; never in dummy/random/real mode.
 */
export function userPayload(ctx) {
  const list = STATE.userPayloads[ctx.api];
  if (!list || !list.length) return undefined;
  // Shared-array items are frozen: hand out a copy so hooks may modify it.
  if (ctx.mode === 'auto' || ctx.mode === 'user') return copy(R.pick(list));
  if (ctx.mode === 'mixed') {
    const mix = cfg().mix || {};
    const total = (mix.user || 0) + (mix.real || 0) + (mix.dummy || 0) + (mix.random || 0);
    if (total > 0 && Math.random() < (mix.user || 0) / total) return copy(R.pick(list));
  }
  return undefined;
}

function copy(v) {
  return v === undefined ? v : JSON.parse(JSON.stringify(v));
}
