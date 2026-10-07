// Response validation (data/response-schemas.json) and read-after-write comparison.
//
// Schemas are a lenient JSON-Schema subset written by the generator: type (a name or a list), properties, required,
// items, enum, nullable; {} accepts anything. Extra members are fine — only a declared type or a declared required
// member can fail, so a violation points at a contract change, not at the generator's guesswork.
import { Counter } from 'k6/metrics';

/** Responses that did not match their schema (tagged api); thresholds: validation.maxViolations. */
export const violations = new Counter('response_schema_violations');
/** Reads that returned something else than the write before them sent (lifecycle flows); validation.readAfterWrite. */
export const readMismatches = new Counter('read_after_write_mismatches');

const MAX_ERRORS = 5;
const MAX_ITEMS = 20; // array elements checked per array: bounded cost on large pages
const SENSITIVE = /pass(word|wd)?|secret|token|api[-_]?key|credential|pin|cvv|ssn|iban/i;

function typeOf(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'number') return Number.isInteger(v) ? 'integer' : 'number';
  return typeof v; // string, boolean, object
}

function accepts(types, actual) {
  for (const t of types) {
    if (t === actual || (t === 'number' && actual === 'integer')) return true;
  }
  return false;
}

function isEmpty(schema) {
  return !schema || Object.keys(schema).length === 0;
}

function walk(schema, value, path, errors, required) {
  if (errors.length >= MAX_ERRORS || isEmpty(schema)) return;
  if (value === null || value === undefined) {
    if (schema.nullable !== true && required) errors.push(`${path}: null where a value is required`);
    return;
  }
  const actual = typeOf(value);
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : null;
  if (types && !accepts(types, actual)) {
    errors.push(`${path}: expected ${types.join('|')}, got ${actual}`);
    return;
  }
  if (schema.enum && actual !== 'object' && actual !== 'array' && schema.enum.indexOf(String(value)) < 0) {
    errors.push(`${path}: "${String(value).slice(0, 40)}" is not one of ${schema.enum.slice(0, 8).join(', ')}`);
    return;
  }
  if (actual === 'object') {
    const requiredNames = schema.required || [];
    for (const name of requiredNames) {
      if (value[name] === undefined) errors.push(`${path}.${name}: required member is missing`);
    }
    for (const name of Object.keys(schema.properties || {})) {
      if (value[name] !== undefined) walk(schema.properties[name], value[name], `${path}.${name}`, errors, requiredNames.indexOf(name) >= 0);
    }
  } else if (actual === 'array' && schema.items) {
    const n = Math.min(value.length, MAX_ITEMS);
    for (let i = 0; i < n; i++) walk(schema.items, value[i], `${path}[${i}]`, errors, true);
  }
}

/**
 * Checks a parsed response body against a schema.
 * @returns {string[]} what differs (at most five lines, each starting with the JSON path); empty when it matches
 */
export function validate(schema, body) {
  const errors = [];
  walk(schema, body, '$', errors, true);
  return errors.slice(0, MAX_ERRORS);
}

// ── read-after-write ────────────────────────────────────────────────────────────────────────────────────

const DATE_LIKE = /^\d{4}-\d{2}-\d{2}/;

function same(written, read) {
  if (typeof written === 'number' || typeof read === 'number') {
    const a = Number(written);
    const b = Number(read);
    if (Number.isFinite(a) && Number.isFinite(b)) return Math.abs(a - b) < 1e-9 * Math.max(1, Math.abs(a));
  }
  if (typeof written === 'boolean' || typeof read === 'boolean') return String(written) === String(read);
  // case and surrounding blanks are often normalised by the server (emails, codes): not a lost write
  return String(written).trim().toLowerCase() === String(read).trim().toLowerCase();
}

/** The object whose members the write's fields should be found in: the body itself, or its single wrapper. */
function record(body, written) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return undefined;
  const keys = Object.keys(written);
  if (keys.some((k) => body[k] !== undefined)) return body;
  const nested = Object.values(body).filter((v) => v && typeof v === 'object' && !Array.isArray(v));
  return nested.length === 1 ? nested[0] : body; // {"data": {...}}
}

/**
 * Compares what a write sent with what a following read returned: scalar members present in both. Skipped — never a
 * mismatch: members the read does not return (a leaner response DTO), secrets, dates and timestamps (the server may
 * normalise them), nested objects. Numbers compare by value, text ignoring case and surrounding blanks.
 * @returns {string[]} one line per member that differs
 */
export function mismatches(written, readBody) {
  if (!written || typeof written !== 'object' || Array.isArray(written)) return [];
  const found = record(readBody, written);
  if (!found) return [];
  const out = [];
  for (const [name, sent] of Object.entries(written)) {
    if (sent === null || typeof sent === 'object' || SENSITIVE.test(name)) continue;
    if (typeof sent === 'string' && DATE_LIKE.test(sent)) continue;
    const got = found[name];
    if (got === undefined || got === null || typeof got === 'object') continue;
    if (!same(sent, got) && out.length < MAX_ERRORS) {
      out.push(`${name}: wrote ${JSON.stringify(sent).slice(0, 40)}, read ${JSON.stringify(got).slice(0, 40)}`);
    }
  }
  return out;
}
