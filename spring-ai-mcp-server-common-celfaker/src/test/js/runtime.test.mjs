import test from 'node:test';
import assert from 'node:assert/strict';
import { createRuntime, getPath, setPath, subset, topo } from '../../main/resources/celfaker/k6/runtime.js';

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

test('flow passes the extracted id into the validation path and renders headers', () => {
  const { rt, calls } = harness();
  assert.equal(rt.runFlow({ vu: 3, iter: 1 }), true);
  assert.equal(calls[0].url, 'http://svc/orders');
  assert.deepEqual(calls[0].body, { qty: 2 }); // iteration picks the data row
  assert.equal(calls[0].params.headers.Authorization, 'Bearer t0k');
  assert.equal(calls[1].url, 'http://svc/orders/77/validate');
  assert.equal(calls[1].params.headers['X-Run'], 'run-3-1');
  assert.deepEqual(calls[1].params.tags, { step: 'validate', api: 'validateOrder', kind: 'flow' });
});

test('flow fails when the validation api says the order is not valid', () => {
  const { rt, checks } = harness({ breakValidation: true });
  assert.equal(rt.runFlow({ vu: 1, iter: 0 }), false);
  assert.ok(checks.some(([n, ok]) => n === 'validate body matches' && !ok));
});

test('a step that fails stops the flow', () => {
  const { rt, calls } = harness();
  valid.createOrder.push({ qty: 'broken' });
  assert.equal(rt.runFlow({ vu: 1, iter: 2 }), false);
  assert.equal(calls.length, 1);
  valid.createOrder.pop();
});

test('negative cases are sent as-is and must be rejected', () => {
  const { rt, calls } = harness();
  assert.equal(rt.negativeCount, 2);
  assert.equal(rt.runNegative(0, { vu: 1, iter: 0 }), true);
  assert.deepEqual(calls[0].body, {});
  assert.equal(rt.runNegative(1, { vu: 1, iter: 1 }), true);
  assert.deepEqual(calls[1].body, { qty: 'x' });
});

test('negative case fails when the api accepts invalid data', () => {
  const { rt } = harness({ acceptInvalid: true });
  assert.equal(rt.runNegative(0, { vu: 1, iter: 0 }), false);
});

test('unresolved variables are reported, not thrown', () => {
  const broken = { ...workflow, steps: [{ ...workflow.steps[1], extract: [] }, workflow.steps[0]] };
  const logs = [];
  const rt = createRuntime({ http: { request() { return { status: 201, headers: {}, json: () => ({}) }; } }, check: () => true, sleep() {}, workflow: broken, apis, valid, invalid, baseUrl: 'http://x', log: (m) => logs.push(m) });
  assert.equal(rt.runFlow({ vu: 1, iter: 0 }), false);
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
