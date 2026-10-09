import test from 'node:test';
import assert from 'node:assert/strict';
import { runScenario } from '../../main/resources/META-INF/resources/celfaker/ui/scenario-runner.js';

const apis = {
  createOrder: { id: 'createOrder', method: 'POST', path: '/orders', role: 'ACTION', hasBody: true, headers: { Authorization: 'Bearer {{env.TOKEN}}' }, expectedStatus: [201], invalidStatus: [422] },
  getOrder: { id: 'getOrder', method: 'GET', path: '/orders/{id}', role: 'ACTION', hasBody: false, headers: {}, expectedStatus: [200] },
};
const step = (o) => ({ dependsOn: [], extract: [], inject: [], expectStatus: [], assertions: [], body: null, invalidCase: '', thinkTime: 0, ...o });
const workflow = { name: 's', load: { negatives: 0 }, steps: [
  step({ id: 'create', api: 'createOrder', extract: [{ name: 'id', from: 'body.id' }], assertions: [{ from: 'body.qty', op: '>=', value: '1' }] }),
  step({ id: 'bad', api: 'createOrder', dependsOn: ['create'], invalidCase: 'qty:missing', expectStatus: [422] }),
  step({ id: 'get', api: 'getOrder', dependsOn: ['bad'], inject: [{ target: 'path.id', value: '{{create.id}}' }], assertions: [{ from: 'body.id', op: '==', value: '{{create.id}}' }] }),
] };
const prepared = { problems: [], workflow, apis, baseUrl: 'http://svc/', valid: { createOrder: [{ qty: 3 }, { qty: 4 }] },
  invalid: { createOrder: [{ reason: 'qty:missing', field: 'order.qty', body: {}, expectedStatus: [422] }] } };

function service({ breakGet = false } = {}) {
  const sent = [];
  const send = async (req) => {
    sent.push(req);
    if (req.method === 'POST') return req.body && req.body.qty ? { status: 201, headers: {}, body: JSON.stringify({ id: 40 + req.body.qty, qty: req.body.qty }), millis: 2 } : { status: 422, headers: {}, body: '{"error":"qty"}', millis: 1 };
    return { status: 200, headers: {}, body: JSON.stringify({ id: breakGet ? 0 : Number(req.url.split('/').pop()) }), millis: 3 };
  };
  return { sent, send };
}

test('runs a scenario through the proxy: valid data, a rejected invalid case, a chained assertion', async () => {
  const { sent, send } = service();
  const events = [];
  const res = await runScenario({ contract: {}, workflow, prepare: async () => prepared, send, env: { TOKEN: 't' }, iterations: 2, onEvent: (e) => events.push(e) });
  assert.deepEqual([res.passed, res.failed], [2, 0]);
  assert.equal(sent[0].url, 'http://svc/orders');
  assert.equal(sent[0].headers.Authorization, 'Bearer t');
  assert.deepEqual(sent[0].body, { qty: 3 });
  assert.deepEqual(sent[1].body, {});          // the invalid case, sent as generated
  assert.equal(sent[2].url, 'http://svc/orders/43');
  const ends = events.filter((e) => e.phase === 'end');
  assert.deepEqual(ends.map((e) => [e.iter, e.step, e.ok]), [[0, 'create', true], [0, 'bad', true], [0, 'get', true], [1, 'create', true], [1, 'bad', true], [1, 'get', true]]);
  assert.deepEqual(events.filter((e) => e.phase === 'iteration').map((e) => e.ok), [true, true]);
});

test('a failing assertion stops the scenario and is reported per check', async () => {
  const { send } = service({ breakGet: true });
  const events = [];
  const res = await runScenario({ contract: {}, workflow, prepare: async () => prepared, send, onEvent: (e) => events.push(e) });
  assert.equal(res.failed, 1);
  const last = events.filter((e) => e.phase === 'end').at(-1);
  assert.equal(last.step, 'get');
  assert.deepEqual(last.checks.filter((c) => !c.ok).map((c) => c.name), ['get assert body.id ==']);
});

test('connection errors fail the step with the message', async () => {
  const events = [];
  const res = await runScenario({ contract: {}, workflow, prepare: async () => prepared, send: async () => ({ error: 'ConnectException: refused' }), onEvent: (e) => events.push(e) });
  assert.equal(res.failed, 1);
  assert.match(events.find((e) => e.error).error, /refused/);
});

test('workflow problems are returned and nothing is sent', async () => {
  const { sent, send } = service();
  const res = await runScenario({ contract: {}, workflow, prepare: async () => ({ problems: ['step x calls unknown api y'] }), send });
  assert.deepEqual(res.problems, ['step x calls unknown api y']);
  assert.equal(sent.length, 0);
});
