#!/usr/bin/env node
// Validates the normative JSON Schemas of LLD-16 and every example in the document against them.
//
//   npm i --no-save ajv@8 ajv-formats@3      (once, anywhere above this folder; nothing in the Maven build uses it)
//   node docs/schemas/validate.mjs [file.md ...]
//
// What it checks
//   1. dai-ui-1, dai-stream-2 and dai-tools-1 compile in ajv strict mode (JSON Schema 2020-12).
//   2. Every ```json block in the markdown is preceded by <!-- validate: KIND [name=NAME] --> and validates as KIND
//      (ui | node | interaction | event | batch | problem | askUser | renderUi | responseSchema); ```jsonc is illustrative.
//   3. Every ```sse block is a sequence of SSE frames whose data: payloads are valid events, whose event: equals data.type
//      and whose id: is <uuid>:<seq> with seq 0,1,2... "@name" as a whole data value stands for the named json block.
//   4. Every ```http block (one message each) has its JSON body validated: POST .../interactions -> interaction,
//      4xx/5xx -> problem, 200 with type state.snapshot -> event, 200 with events[] -> batch response.
//   5. The reference semantic rules (the executable spec of the Java UiTreeValidator, LLD-16 §5.5) hold for every surface.
//   6. The node table in §5.4 lists exactly the node types of the schema with the same authoring class.
//   7. negative-corpus.json: every entry must be REJECTED (by schema or by the semantic rules).
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const load = (name) => {
  for (const base of [here, resolve(here, '..'), resolve(here, '../..'), process.cwd()]) {
    try { return require(require.resolve(name, { paths: [base] })); } catch { /* try next */ }
  }
  throw new Error(`Cannot find ${name}; run: npm i --no-save ajv@8 ajv-formats@3`);
};
const Ajv2020 = load('ajv/dist/2020.js');
const addFormats = load('ajv-formats');

const ajv = new Ajv2020({ strict: true, allErrors: true, allowUnionTypes: true });
addFormats(ajv);
for (const k of ['x-class', 'x-container', 'x-server-only-props']) ajv.addKeyword(k);
const read = (f) => JSON.parse(readFileSync(resolve(here, f), 'utf8'));
const ui = read('dai-ui-1.schema.json');
const stream = read('dai-stream-2.schema.json');
const tools = read('dai-tools-1.schema.json');
[ui, stream, tools].forEach((s) => ajv.addSchema(s));
const REF = {
  ui: `${ui.$id}#/$defs/surface`, node: `${ui.$id}#/$defs/node`,
  interaction: `${stream.$id}#/$defs/interaction`, event: `${stream.$id}#/$defs/event`,
  batch: `${stream.$id}#/$defs/batchResponse`, problem: `${stream.$id}#/$defs/problem`,
  askUser: `${tools.$id}#/$defs/askUserInput`, renderUi: `${tools.$id}#/$defs/renderUiInput`,
  responseSchema: `${tools.$id}#/$defs/responseSchema`,
};
const nodeDefs = Object.fromEntries(Object.entries(ui.$defs).filter(([k]) => k.startsWith('node_') && k !== 'node'));
const nodeMeta = Object.fromEntries(Object.entries(nodeDefs).map(([k, v]) => [k.slice(5), v]));

const problems = [];
const fail = (where, msg) => problems.push(`${where}: ${msg}`);
const brief = (errors) => [...new Set(errors.filter((e) => !['oneOf', 'if', 'then'].includes(e.keyword))
  .map((e) => `${e.instancePath || '/'} ${e.keyword} ${e.message}`))].slice(0, 4).join(' | ');

// ---- reference semantic rules (LLD-16 §5.5) ----------------------------------------------------------------------
const LIMITS = { maxNodes: 400, maxDepth: 10, maxBytes: 262144 };
const BAD_CHARS = /[\u0000-\u0008\u000B-\u001F\u007F-\u009F‪-‮⁦-⁩]/;
const walk = (n, f, depth = 1) => { f(n, depth); (n.children ?? []).forEach((c) => walk(c, f, depth + 1)); };
const strings = (v, f) => { if (typeof v === 'string') f(v); else if (Array.isArray(v)) v.forEach((x) => strings(x, f)); else if (v && typeof v === 'object') Object.values(v).forEach((x) => strings(x, f)); };
const pointerGet = (doc, p) => { let cur = doc; for (const t of p.split('/').slice(1).map((x) => x.replace(/~1/g, '/').replace(/~0/g, '~'))) { if (cur === null || typeof cur !== 'object' || !(t in cur)) return { ok: false }; cur = cur[t]; } return { ok: true, value: cur }; };
const refsIn = (props) => {   // pointers read through {"$data": ...}
  const out = []; const go = (v) => { if (Array.isArray(v)) v.forEach(go); else if (v && typeof v === 'object') { if (typeof v.$data === 'string') out.push(v.$data); else Object.values(v).forEach(go); } }; go(props); return out;
};
const ACTION_PROPS = { button: ['action'], form: ['submit', 'cancel'], table: ['onSort', 'onPage'], suggestion_chips: [] };

function semanticSurface(s) {
  const v = []; const ids = new Set(); let count = 0, deep = 0;
  walk(s.tree, (n, d) => {
    count++; deep = Math.max(deep, d);
    if (ids.has(n.id)) v.push(`duplicate node id "${n.id}"`); ids.add(n.id);
    const props = n.props ?? {};
    for (const key of ACTION_PROPS[n.type] ?? []) if (props[key] !== undefined && !(props[key] in s.actions)) v.push(`node "${n.id}" references unknown action "${props[key]}"`);
    if (n.type === 'suggestion_chips') for (const it of props.items) if (!(it.action in s.actions)) v.push(`node "${n.id}" references unknown action "${it.action}"`);
    for (const p of refsIn(props)) if (!pointerGet(s.data ?? {}, p).ok) v.push(`node "${n.id}" binds missing data "${p}"`);
    if (props.bind) { const parent = props.bind.slice(0, props.bind.lastIndexOf('/')); if (parent && !pointerGet(s.data ?? {}, parent).ok) v.push(`node "${n.id}" binds under missing data "${parent}"`); }
    if (n.visibleWhen) { const paths = []; JSON.stringify(n.visibleWhen, (k, val) => { if (k === 'path') paths.push(val); return val; }); for (const p of paths) if (!pointerGet(s.data ?? {}, p).ok) v.push(`node "${n.id}" visibleWhen reads missing data "${p}"`); }
    if (n.type === 'change_review') for (const r of props.records) for (const f of r.fields) if (f.changed !== (f.before !== f.after)) v.push(`change_review field "${f.attr}": changed must equal (before !== after)`);
  });
  if (count > LIMITS.maxNodes) v.push(`${count} nodes > ${LIMITS.maxNodes}`);
  if (deep > LIMITS.maxDepth) v.push(`depth ${deep} > ${LIMITS.maxDepth}`);
  if (Buffer.byteLength(JSON.stringify(s)) > LIMITS.maxBytes) v.push('surface larger than max-surface-bytes');
  strings(s, (t) => { if (BAD_CHARS.test(t)) v.push('control or bidirectional-override character in a string'); });
  for (const [id, a] of Object.entries(s.actions)) {
    if (a.kind === 'respond' && !s.interruptId) v.push(`action "${id}" is respond but the surface has no interruptId`);
    if (a.kind === 'respond' && a.outcome === 'accept' && s.kind === 'proposal_review') {
      const review = s.tree && (() => { let r; walk(s.tree, (n) => { if (n.type === 'change_review') r = n; }); return r; })();
      if (!a.digest) v.push(`accept action "${id}" of a proposal review needs a digest`);
      else if (review && review.props.contentHash !== a.digest) v.push(`action "${id}" digest differs from change_review.contentHash`);
    }
  }
  if (s.kind === 'display' && Object.values(s.actions).some((a) => a.kind === 'respond')) v.push('display surfaces cannot declare respond actions');
  return v;
}
function semanticModelTree(input) {   // render_ui input: model authoring constraints
  const v = []; const ids = new Set();
  walk(input.tree, (n) => {
    const def = nodeMeta[n.type];
    if (ids.has(n.id)) v.push(`duplicate node id "${n.id}"`); ids.add(n.id);
    if (def['x-class'] !== 'model_safe') v.push(`node type "${n.type}" is server_only`);
    for (const p of def['x-server-only-props'] ?? []) if (n.props && p in n.props) v.push(`prop "${p}" of "${n.type}" is server-only`);
    for (const p of refsIn(n.props ?? {})) { const top = p.split('/')[1]; if (!(top in (input.data ?? {}))) v.push(`node "${n.id}" binds missing data "${p}"`); }
  });
  strings(input, (t) => { if (BAD_CHARS.test(t)) v.push('control or bidirectional-override character in a string'); });
  return v;
}

function check(kind, obj, where) {
  const validate = ajv.getSchema(REF[kind]);
  if (!validate) return fail(where, `unknown kind ${kind}`);
  if (!validate(obj)) return fail(where, `${kind}: ${brief(validate.errors)}`);
  if (kind === 'ui') semanticSurface(obj).forEach((m) => fail(where, m));
  if (kind === 'renderUi') semanticModelTree(obj).forEach((m) => fail(where, m));
  if (kind === 'event' && obj.type === 'ui.surface') semanticSurface(obj.surface).forEach((m) => fail(where, m));
  if (kind === 'event' && obj.type === 'state.snapshot') obj.surfaces.forEach((s, i) => semanticSurface(s).forEach((m) => fail(`${where} surfaces[${i}]`, m)));
  if (kind === 'batch') obj.events.forEach((e, i) => { if (e.type === 'ui.surface') semanticSurface(e.surface).forEach((m) => fail(`${where} events[${i}]`, m)); });
  return undefined;
}
const rejected = (kind, obj) => {
  const validate = ajv.getSchema(REF[kind]);
  if (!validate(obj)) return true;
  if (kind === 'ui') return semanticSurface(obj).length > 0;
  if (kind === 'renderUi') return semanticModelTree(obj).length > 0;
  return false;
};

// ---- markdown scan ------------------------------------------------------------------------------------------------
const files = process.argv.slice(2).length ? process.argv.slice(2) : [resolve(here, '../lld/16-chat-ui-protocol.md')];
let counts = { json: 0, sse: 0, http: 0, jsonc: 0, frames: 0 };
for (const file of files) {
  const lines = readFileSync(file, 'utf8').split('\n');
  const blocks = []; // {info, body, line, marker}
  for (let i = 0; i < lines.length; i++) {
    const m = /^```(\w+)\s*$/.exec(lines[i]);
    if (!m) continue;
    const start = i; let j = i + 1; const body = [];
    while (j < lines.length && !/^```\s*$/.test(lines[j])) body.push(lines[j++]);
    let k = start - 1; while (k >= 0 && lines[k].trim() === '') k--;
    blocks.push({ info: m[1], body: body.join('\n'), line: start + 1, marker: k >= 0 ? lines[k] : '' });
    i = j;
  }
  const named = {};
  for (const b of blocks.filter((x) => x.info === 'json')) {
    const mm = /<!--\s*validate:\s*(\w+)(?:\s+name=([\w-]+))?\s*-->/.exec(b.marker);
    b.kind = mm?.[1]; b.name = mm?.[2];
    if (b.name) { try { named[b.name] = JSON.parse(b.body); } catch { /* reported below */ } }
  }
  const resolveRefs = (o) => (typeof o === 'string' && o.startsWith('@') ? (named[o.slice(1)] ?? fail(file, `unknown @${o.slice(1)}`)) : o);
  for (const b of blocks) {
    const where = `${file}:${b.line}`;
    if (b.info === 'jsonc') { counts.jsonc++; continue; }
    if (b.info === 'json') {
      counts.json++;
      if (!b.kind) { fail(where, 'json block without a <!-- validate: KIND --> marker (use jsonc for illustrations)'); continue; }
      let obj; try { obj = JSON.parse(b.body); } catch (e) { fail(where, `invalid JSON: ${e.message}`); continue; }
      check(b.kind, obj, where);
    } else if (b.info === 'sse') {
      counts.sse++;
      const frames = b.body.split(/\n\s*\n/).map((f) => f.trim()).filter(Boolean);
      let expectSeq = 0; let prefix;
      for (const f of frames) {
        counts.frames++;
        const id = /^id: (.+)$/m.exec(f)?.[1]; const ev = /^event: (.+)$/m.exec(f)?.[1]; const data = /^data: (.+)$/m.exec(f)?.[1];
        if (!id || !ev || !data) { fail(where, `frame without id/event/data: ${f.slice(0, 60)}`); continue; }
        const im = /^([0-9a-f-]{36}):(\d+)$/.exec(id);
        if (!im) { fail(where, `bad SSE id ${id}`); continue; }
        prefix ??= im[1];
        if (im[1] !== prefix) fail(where, `id prefix changes inside one stream (${prefix} vs ${im[1]})`);
        if (Number(im[2]) !== expectSeq++) fail(where, `SSE ids must count 0,1,2...; got ${id}`);
        let obj; try { obj = JSON.parse(data); } catch (e) { fail(where, `bad data JSON (${ev}): ${e.message}`); continue; }
        if (obj.surface) obj.surface = resolveRefs(obj.surface);
        if (obj.type !== ev) fail(where, `event: ${ev} differs from data.type ${obj.type}`);
        check('event', obj, `${where} ${ev}`);
      }
      const last = frames.at(-1)?.match(/^event: (.+)$/m)?.[1];
      if (!['turn.end', 'interaction.end', 'error'].includes(last)) fail(where, `stream must end with turn.end, interaction.end or error (ends with ${last})`);
    } else if (b.info === 'http') {
      counts.http++;
      const [head, ...rest] = b.body.split(/\n\s*\n/); const first = head.split('\n')[0];
      const bodyText = rest.join('\n\n').trim();
      if (!bodyText) continue;
      let obj; try { obj = JSON.parse(bodyText); } catch (e) { fail(where, `bad body JSON: ${e.message}`); continue; }
      let kind;
      if (/^POST \S+\/interactions /.test(first)) kind = 'interaction';
      else if (/^HTTP\/1\.1 [45]\d\d/.test(first)) kind = 'problem';
      else if (/^HTTP\/1\.1 200/.test(first)) kind = obj.type === 'state.snapshot' ? 'event' : obj.events ? 'batch' : undefined;
      if (!kind) { fail(where, `cannot infer schema for: ${first}`); continue; }
      check(kind, obj, where);
    }
  }
  // §5.4 catalog table <-> schema
  const table = {};
  for (const l of lines) { const m = /^\| `([a-z_]+)` \| (model_safe|server_only) \|/.exec(l); if (m) table[m[1]] = m[2]; }
  if (Object.keys(table).length) {
    for (const [t, def] of Object.entries(nodeMeta)) { if (!(t in table)) fail(file, `catalog table lacks node type ${t}`); else if (table[t] !== def['x-class']) fail(file, `catalog table says ${t} is ${table[t]}, schema says ${def['x-class']}`); }
    for (const t of Object.keys(table)) if (!(t in nodeMeta)) fail(file, `catalog table lists unknown node type ${t}`);
  } else fail(file, 'no catalog table rows found (| `type` | model_safe|server_only | ...)');
}

// ---- negative corpus ----------------------------------------------------------------------------------------------
const corpus = read('negative-corpus.json');
let accepted = 0;
for (const c of corpus) { if (!rejected(c.kind, c.object)) { accepted++; fail('negative-corpus.json', `"${c.name}" was ACCEPTED but must be rejected`); } }

console.log(`blocks: ${counts.json} json, ${counts.sse} sse (${counts.frames} frames), ${counts.http} http, ${counts.jsonc} jsonc (skipped); negative corpus: ${corpus.length - accepted}/${corpus.length} rejected`);
if (problems.length) { console.error(`\n${problems.length} problem(s):\n - ${problems.join('\n - ')}`); process.exit(1); }
console.log('OK: schemas compile and all examples conform');
