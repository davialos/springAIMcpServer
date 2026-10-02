// Realistic dummy values per field kind (the "dummy" data source).
// The generator decides each field's kind (FieldKind in Java); this file owns how a value of that kind looks.
// Add a kind or tweak one here; per-API overrides belong in apis/*.js or hooks.js.
import exec from 'k6/execution';
import * as R from './random.js';

let DICT = {};

export function setDictionaries(dictionaries) {
  DICT = dictionaries;
}

function d(name) {
  return DICT[name] || ['x'];
}

/** A short suffix unique per VU iteration, so unique columns (e-mail, username, code) do not collide. */
export function uniqueSuffix() {
  let vu = 0;
  let it = 0;
  try {
    vu = exec.vu.idInTest;
    it = exec.vu.iterationInInstance;
  } catch (e) {
    // outside a VU (init/setup): random only
  }
  return `${vu.toString(36)}${it.toString(36)}${R.chars('abcdefghijklmnopqrstuvwxyz0123456789', 4)}`;
}

function firstName() { return R.pick(d('firstNames')); }
function lastName() { return R.pick(d('lastNames')); }
function words(n) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(R.pick(d('words')));
  return out.join(' ');
}
function sentence(min, max) {
  const s = words(R.int(min, max));
  return s.charAt(0).toUpperCase() + s.slice(1) + '.';
}
function country() { return R.pick(d('countries')); }

const GENERATORS = {
  id: (s) => (s.type === 'string' ? (s.format === 'uuid' ? R.uuid() : String(R.int(1, 100000))) : R.int(1, 100000)),
  uuid: () => R.uuid(),
  email: () => `${firstName()}.${lastName()}.${uniqueSuffix()}`.toLowerCase() + '@' + R.pick(d('emailDomains')),
  username: () => `${firstName()}${lastName().charAt(0)}_${uniqueSuffix()}`.toLowerCase(),
  password: () => `Lt-${R.alnum(10)}9!`,
  firstName: () => firstName(),
  lastName: () => lastName(),
  fullName: () => `${firstName()} ${lastName()}`,
  name: (s, ctx, spec) => named(spec),
  phone: () => `+1${R.int(201, 989)}${R.int(200, 999)}${R.int(1000, 9999)}`,
  street: () => `${R.int(1, 9999)} ${R.pick(d('streetNames'))} ${R.pick(d('streetSuffixes'))}`,
  address: () => `${R.int(1, 9999)} ${R.pick(d('streetNames'))} ${R.pick(d('streetSuffixes'))}, ${R.pick(d('cities'))}`,
  city: () => R.pick(d('cities')),
  state: () => R.pick(d('states')),
  country: () => country()[0],
  countryCode: () => country()[1],
  postalCode: () => String(R.int(10000, 99999)),
  company: () => `${R.pick(d('companyWords'))} ${R.pick(d('companySuffixes'))}`,
  jobTitle: () => R.pick(d('jobTitles')),
  url: () => `https://www.${R.pick(d('webDomains'))}/${R.pick(d('words'))}/${R.alnum(6).toLowerCase()}`,
  ip: () => `10.${R.int(0, 255)}.${R.int(0, 255)}.${R.int(1, 254)}`,
  date: (s) => R.isoDate(R.date(s, 365, 365)),
  dateTime: (s) => R.isoDateTime(R.date(s, 365, 365)),
  time: () => `${String(R.int(0, 23)).padStart(2, '0')}:${String(R.int(0, 59)).padStart(2, '0')}:00`,
  birthDate: () => R.isoDate(new Date(Date.now() - R.int(18 * 365, 80 * 365) * 86400000)),
  age: () => R.int(18, 80),
  price: (s) => (s.type === 'integer' ? R.int(1, 5000) : R.float(1, 2000, 2)),
  currency: () => R.pick(d('currencies')),
  quantity: () => R.int(1, 10),
  percentage: (s) => (s.type === 'integer' ? R.int(0, 100) : R.float(0, 100, 1)),
  latitude: () => R.float(-85, 85, 6),
  longitude: () => R.float(-179, 179, 6),
  title: () => sentence(3, 6).replace(/\.$/, ''),
  description: () => sentence(8, 20),
  code: () => `${R.chars('ABCDEFGHJKLMNPQRSTUVWXYZ', 3)}-${uniqueSuffix().toUpperCase()}`,
  status: () => R.pick(d('statuses')),
  color: () => R.pick(d('colors')),
  gender: () => R.pick(d('genders')),
  language: () => R.pick(d('languages')),
  timezone: () => R.pick(d('timezones')),
  creditCard: () => R.pick(d('testCardNumbers')),
  token: () => R.alnum(32),
  slug: () => `${R.pick(d('words'))}-${R.pick(d('words'))}-${uniqueSuffix()}`,
  version: () => `${R.int(0, 5)}.${R.int(0, 20)}.${R.int(0, 50)}`,
  rating: (s) => (s.type === 'integer' ? R.int(1, 5) : R.float(1, 5, 1)),
  page: () => R.int(0, 3),
  pageSize: () => R.pick([10, 20, 50]),
  sort: () => 'id',
  search: () => R.pick(d('words')),
  enum: (s) => R.pick(s.enum),
  boolean: () => R.bool(),
  text: (s) => (s.example !== undefined ? s.example : words(R.int(2, 5))),
  integer: (s) => (s.example !== undefined ? Number(s.example) : R.int(1, 1000)),
  number: (s) => (s.example !== undefined ? Number(s.example) : R.float(1, 1000, 2)),
};

/** "name" fields get a value that reads like the thing they name: productName → "Smart Lamp". */
function named(spec) {
  const owner = `${spec.owner || ''}.${spec.key || ''}`.toLowerCase();
  if (/(user|customer|person|employee|contact|author|owner|member|patient|student)/.test(owner)) {
    return `${firstName()} ${lastName()}`;
  }
  if (/(company|organi[sz]ation|vendor|supplier|tenant|account)/.test(owner)) {
    return `${R.pick(d('companyWords'))} ${R.pick(d('companySuffixes'))}`;
  }
  if (/(city|location|place)/.test(owner)) return R.pick(d('cities'));
  return `${R.pick(d('productWords'))} ${R.pick(d('productNouns'))}`;
}

/**
 * A dummy value of the given kind, made valid for the schema (enum, type, length, range, pattern).
 * Falls back to a constraint-driven random value when the kind's value cannot satisfy the schema.
 */
export function generate(kind, schema, ctx, spec) {
  if (schema.enum && schema.enum.length) return R.pick(schema.enum);
  const g = GENERATORS[kind] || GENERATORS.text;
  let v = g(schema, ctx, spec || {});
  switch (schema.type) {
    case 'boolean':
      return typeof v === 'boolean' ? v : R.bool();
    case 'integer':
    case 'number':
      if (typeof v !== 'number') v = Number(v);
      if (!isFinite(v)) return R.scalar(schema);
      return R.fitNumber(v, schema);
    default: {
      v = String(v);
      // a unique column: never send the same value twice (kinds such as email already carry a suffix)
      if (schema.unique && ['email', 'username', 'code', 'slug', 'uuid', 'id'].indexOf(kind) < 0) {
        const suffix = `-${uniqueSuffix()}`;
        const max = schema.maxLength !== undefined ? schema.maxLength : Infinity;
        v = max <= suffix.length ? uniqueSuffix().slice(0, max) : v.slice(0, Math.max(0, max - suffix.length)) + suffix;
      }
      v = R.fitLength(v, schema);
      if (schema.pattern) {
        try {
          if (!new RegExp(schema.pattern).test(v)) {
            const p = R.fromPattern(schema.pattern);
            if (p !== undefined) return p;
          }
        } catch (e) {
          // pattern not valid in JS: keep the dummy value
        }
      }
      return v;
    }
  }
}
