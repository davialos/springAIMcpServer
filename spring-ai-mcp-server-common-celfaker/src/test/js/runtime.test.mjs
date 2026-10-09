import test from 'node:test';
import assert from 'node:assert/strict';
import { createRuntime, getPath, setPath, subset, topo } from '../../main/resources/META-INF/resources/celfaker/ui/runtime.js';

const apis = {
  createOrder: { id: 'createOrder', method: 'POST', path: '/orders', role: 'ACTION', hasBody: true, headers: { Authorization: 'Bearer {{env.TOKEN}}' }, expectedStatus: [201], invalidStatus: [400] },
  validateOrder: { id: 'validateOrder', method: 'GET', path: '/orders/{id}/validate', role: 'VALIDATION', hasBody: false, headers: {}, expectedStatus: [200], expectBody: { valid: true } },
};
const workflow = {
  load: { negatives: 10 },
  steps: [
    { id: 'validate', api: 'validateOrder', dependsOn: ['create'], extract: [], inject: [{ target: 'path.id', value: '{{create.id}}' }, { target: 'header.X-Run', value: 'run-{{vu}}-{{iter}}' }], expectStatus: [], thinkTime: 0 },
    { id: 'create', api: 'createOrder', dependsOn: [], extract: [{ name: 'id', from: 'body.id' }, { name: 'loc', from: 'header.Location' }], inject: [], expectStatus: [], thinkTime: 0 },
  ],
};
const valid = { createOrder: [{ qty: 1 }, { qty: 2 }] };
const invalid = { createOrder: [{ reason: 'qty:missing', field: 'order.qty', body: {}, expectedStatus: [400] }, { reason: 'qty:invalid_string', field: 'order.qty', body: { qty: 'x' }, expectedStatus: [400, 422] }] };

/** In-memory service + k6-like http/check doubles. */
function harness({ breakValidation = false, acceptInvalid = false } = {}) {
  const calls = [];
  const http = {
    expectedStatuses: (...codes) => codes,
    request(method, url, body, params) {
      calls.push({ method, url, body: body && JSON.parse(body), params });
      const json = (status, obj, headers = {}) => ({ status, headers, body: JSON.stringify(obj), json: () => obj });
      if (method === 'POST' && url.endsWith('/orders')) {
        const b = JSON.parse(body);
        if (typeof b.qty !== 'number' && !acceptInvalid) return json(400, { error: 'bad' });
        return json(201, { id: 77 }, { location: '/orders/77' });
      }
      if (method === 'GET' && url.endsWith('/orders/77/validate')) return json(200, { valid: !breakValidation });
      return json(404, {});
    },
  };
  const checks = [];
  const check = (res, spec) => Object.entries(spec).every(([name, fn]) => { const ok = fn(res); checks.push([name, ok]); return ok; });
  const logs = [];
  const rt = createRuntime({ http, check, sleep() {}, workflow, apis, valid, invalid, baseUrl: 'http://svc', env: { TOKEN: 't0k' }, log: (m) => logs.push(m), metrics: {} });
  return { rt, calls, checks, logs };
}

test('topo puts dependencies first', () => {
  assert.deepEqual(topo(workflow.steps).map((s) => s.id), ['create', 'validate']);
  assert.throws(() => topo([{ id: 'a', dependsOn: ['b'] }, { id: 'b', dependsOn: ['a'] }]), /cycle/);
});

test('flow passes the extracted id into the validation path and renders headers', async () => {
  const { rt, calls } = harness();
  assert.equal(await rt.runFlow({ vu: 3, iter: 1 }), true);
  assert.equal(calls[0].url, 'http://svc/orders');
  assert.deepEqual(calls[0].body, { qty: 2 }); // iteration picks the data row
  assert.equal(calls[0].params.headers.Authorization, 'Bearer t0k');
  assert.equal(calls[1].url, 'http://svc/orders/77/validate');
  assert.equal(calls[1].params.headers['X-Run'], 'run-3-1');
  assert.deepEqual(calls[1].params.tags, { step: 'validate', api: 'validateOrder', kind: 'flow' });
});

test('flow fails when the validation api says the order is not valid', async () => {
  const { rt, checks } = harness({ breakValidation: true });
  assert.equal(await rt.runFlow({ vu: 1, iter: 0 }), false);
  assert.ok(checks.some(([n, ok]) => n === 'validate body matches' && !ok));
});

test('a step that fails stops the flow', async () => {
  const { rt, calls } = harness();
  valid.createOrder.push({ qty: 'broken' });
  assert.equal(await rt.runFlow({ vu: 1, iter: 2 }), false);
  assert.equal(calls.length, 1);
  valid.createOrder.pop();
});

test('negative cases are sent as-is and must be rejected', async () => {
  const { rt, calls } = harness();
  assert.equal(rt.negativeCount, 2);
  assert.equal(await rt.runNegative(0, { vu: 1, iter: 0 }), true);
  assert.deepEqual(calls[0].body, {});
  assert.equal(await rt.runNegative(1, { vu: 1, iter: 1 }), true);
  assert.deepEqual(calls[1].body, { qty: 'x' });
});

test('negative case fails when the api accepts invalid data', async () => {
  const { rt } = harness({ acceptInvalid: true });
  assert.equal(await rt.runNegative(0, { vu: 1, iter: 0 }), false);
});

test('unresolved variables are reported, not thrown', async () => {
  const broken = { ...workflow, steps: [{ ...workflow.steps[1], extract: [] }, workflow.steps[0]] };
  const logs = [];
  const rt = createRuntime({ http: { request() { return { status: 201, headers: {}, json: () => ({}) }; } }, check: () => true, sleep() {}, workflow: broken, apis, valid, invalid, baseUrl: 'http://x', log: (m) => logs.push(m) });
  assert.equal(await rt.runFlow({ vu: 1, iter: 0 }), false);
  assert.match(logs[0], /unresolved variable \{\{create\.id\}\}/);
});

test('path helpers', () => {
  const o = {};
  setPath(o, 'a.b[1].c', 5);
  assert.equal(o.a.b.length, 2);
  assert.equal(o.a.b[1].c, 5);
  assert.equal(getPath({ a: [{ x: 1 }] }, 'a[0].x'), 1);
  assert.equal(getPath({}, 'a.b'), undefined);
  assert.equal(subset({ a: 1, b: { c: [1] } }, { a: 1, b: { c: [1, 2], d: 3 }, z: 1 }), true);
  assert.equal(subset({ a: 2 }, { a: 1 }), false);
});

// ---- scenario features: assertions, custom / invalid bodies, trace, async http --------------------------------------------
import { compare } from '../../main/resources/META-INF/resources/celfaker/ui/runtime.js';

function scenario(steps, { http: custom } = {}) {
  const calls = [];
  const events = [];
  const http = custom ?? {
    async request(method, url, body, params) {
      await new Promise((r) => setTimeout(r, 1)); // genuinely asynchronous, like the browser adapter
      calls.push({ method, url, body: body && JSON.parse(body) });
      const json = (status, obj, headers = {}) => ({ status, headers, body: JSON.stringify(obj), json: () => obj });
      if (url.endsWith('/orders')) {
        const b = JSON.parse(body);
        return b.qty >= 1 ? json(201, { id: 9, status: 'NEW', qty: b.qty, tags: ['a', 'b'] }, { Location: '/orders/9' }) : json(422, { error: 'qty' });
      }
      return json(200, { valid: true });
    },
  };
  const check = (res, spec) => Object.values(spec).every((fn) => fn(res));
  const rt = createRuntime({ http, check, sleep: async () => {}, workflow: { load: { negatives: 0 }, steps }, apis, valid, invalid, baseUrl: 'http://svc', trace: (e) => events.push(e) });
  return { rt, calls, events };
}
const create = (extra = {}) => ({ id: 'create', api: 'createOrder', dependsOn: [], extract: [{ name: 'id', from: 'body.id' }], inject: [], expectStatus: [], thinkTime: 0, ...extra });

test('assertions check status, header and body values', async () => {
  const { rt, events } = scenario([create({ assertions: [
    { from: 'status', op: '==', value: '201' }, { from: 'body.status', op: '==', value: 'NEW' }, { from: 'body.qty', op: '>=', value: '1' },
    { from: 'body.tags[1]', op: '==', value: 'b' }, { from: 'header.Location', op: 'matches', value: '^/orders/\\d+$' },
    { from: 'body.missing', op: 'absent', value: '' }, { from: 'body.id', op: 'exists', value: '' }, { from: 'body.status', op: 'contains', value: 'EW' }] })]);
  assert.equal(await rt.runFlow({ vu: 1, iter: 0 }), true);
  const end = events.find((e) => e.phase === 'end');
  assert.equal(end.checks.filter((c) => c.name.includes(' assert ')).length, 8);
  assert.deepEqual(end.extracted, { id: 9 });
});

test('a failing assertion fails the step and is named in the trace', async () => {
  const { rt, events } = scenario([create({ assertions: [{ from: 'body.status', op: '==', value: 'PAID' }] })]);
  assert.equal(await rt.runFlow({ vu: 1, iter: 0 }), false);
  const end = events.find((e) => e.phase === 'end');
  assert.equal(end.ok, false);
  assert.deepEqual(end.checks.filter((c) => !c.ok).map((c) => c.name), ['create assert body.status ==']);
});

test('assertion values can reference variables of earlier steps', async () => {
  const steps = [create(), { id: 'check', api: 'validateOrder', dependsOn: ['create'], extract: [], expectStatus: [], thinkTime: 0,
    inject: [{ target: 'path.id', value: '{{create.id}}' }], assertions: [{ from: 'body.valid', op: '==', value: 'true' }] }];
  const { rt, calls } = scenario(steps);
  assert.equal(await rt.runFlow({ vu: 1, iter: 0 }), true);
  assert.equal(calls[1].url, 'http://svc/orders/9/validate');
});

test('a custom body replaces the generated one and may use variables', async () => {
  const { rt, calls } = scenario([create({ body: { qty: 0, note: 'run {{iter}}' }, expectStatus: [422], extract: [] })]);
  assert.equal(await rt.runFlow({ vu: 1, iter: 4 }), true); // 422 is what this step expects
  assert.deepEqual(calls[0].body, { qty: 0, note: 'run 4' });
});

test('a step can send a named generated invalid case and expect the rejection', async () => {
  const { rt, calls, events } = scenario([create({ invalidCase: 'qty:missing', expectStatus: [400, 422], extract: [] })]);
  invalid.createOrder[0].body = { qty: 0 };
  assert.equal(await rt.runFlow({ vu: 1, iter: 0 }), true);
  assert.deepEqual(calls[0].body, { qty: 0 });
  const { rt: rt2, events: ev2 } = scenario([create({ invalidCase: 'nope' })]);
  assert.equal(await rt2.runFlow({ vu: 1, iter: 0 }), false);
  assert.match(ev2.find((e) => e.phase === 'end').error, /no generated invalid case "nope"/);
  assert.equal(events.filter((e) => e.phase === 'start').length, 1);
});

test('trace reports request and response of every step', async () => {
  const { rt, events } = scenario([create()]);
  await rt.runFlow({ vu: 1, iter: 0 });
  const [start, end] = events;
  assert.equal(start.phase, 'start');
  assert.equal(start.request.url, 'http://svc/orders');
  assert.equal(end.response.status, 201);
  assert.match(end.response.body, /"id":9/);
});

test('compare operators', () => {
  assert.equal(compare('==', 5, '5'), true);
  assert.equal(compare('==', 'a', 'b'), false);
  assert.equal(compare('!=', undefined, 'x'), true);
  assert.equal(compare('>', 3, '2'), true);
  assert.equal(compare('<=', '2', '2'), true);
  assert.equal(compare('matches', 'abc', '^a.c$'), true);
  assert.equal(compare('matches', 'abc', '('), false);
  assert.equal(compare('exists', null, ''), false);
  assert.equal(compare('==', { a: 1 }, '{"a":1}'), true);
});
