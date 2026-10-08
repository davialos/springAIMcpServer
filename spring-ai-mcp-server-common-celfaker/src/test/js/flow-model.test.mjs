import test from 'node:test';
import assert from 'node:assert/strict';
import {
  addStep, ancestors, autoWire, availableVariables, connect, disconnect, layout, newWorkflow, pathParams, removeStep,
  renameStep, suggestExtracts, toWorkflow, wouldCycle, prune,
} from '../../main/resources/META-INF/resources/celfaker/ui/flow-model.js';

const apis = [
  { id: 'createOrder', method: 'POST', path: '/orders', responseExample: { id: 5, status: 'NEW', links: { self: 'x' } } },
  { id: 'validateOrder', method: 'GET', path: '/orders/{id}/validate' },
];

function flow() {
  const wf = newWorkflow('t');
  const a = addStep(wf, 'createOrder');
  const b = addStep(wf, 'validateOrder');
  return { wf, a, b };
}

test('step ids stay unique', () => {
  const wf = newWorkflow();
  assert.deepEqual([addStep(wf, 'a-b').id, addStep(wf, 'a-b').id, addStep(wf, 'a-b').id], ['a_b', 'a_b2', 'a_b3']);
});

test('connect refuses cycles, self loops and duplicates', () => {
  const { wf, a, b } = flow();
  assert.equal(connect(wf, a.id, b.id).ok, true);
  assert.equal(connect(wf, a.id, b.id).ok, false);
  assert.equal(connect(wf, b.id, a.id).ok, false);
  assert.equal(connect(wf, a.id, a.id).ok, false);
  assert.equal(wouldCycle(wf, b.id, a.id), true);
  disconnect(wf, a.id, b.id);
  assert.equal(connect(wf, b.id, a.id).ok, true);
});

test('ancestors are transitive', () => {
  const wf = newWorkflow();
  const [a, b, c] = ['x', 'y', 'z'].map((n) => addStep(wf, n));
  connect(wf, a.id, b.id); connect(wf, b.id, c.id);
  assert.deepEqual([...ancestors(wf, c.id)].sort(), ['x', 'y']);
});

test('variables come from ancestors only and autoWire fills path parameters', () => {
  const { wf, a, b } = flow();
  a.extract.push({ name: 'id', from: 'body.id' });
  assert.deepEqual(availableVariables(wf, b.id), []);
  connect(wf, a.id, b.id);
  assert.deepEqual(availableVariables(wf, b.id).map((v) => v.ref), ['{{createOrder.id}}']);
  assert.equal(autoWire(wf, apis, b.id), 1);
  assert.deepEqual(b.inject, [{ target: 'path.id', value: '{{createOrder.id}}' }]);
  assert.equal(autoWire(wf, apis, b.id), 0);
  assert.deepEqual(pathParams(apis[1]), ['id']);
});

test('rename rewrites edges and references', () => {
  const { wf, a, b } = flow();
  a.extract.push({ name: 'id', from: 'body.id' });
  connect(wf, a.id, b.id);
  b.inject.push({ target: 'path.id', value: '{{createOrder.id}}' });
  assert.equal(renameStep(wf, 'createOrder', 'make').ok, true);
  assert.deepEqual(b.dependsOn, ['make']);
  assert.equal(b.inject[0].value, '{{make.id}}');
  assert.equal(renameStep(wf, 'make', 'validateOrder').ok, false);
  assert.equal(renameStep(wf, 'make', '1bad').ok, false);
});

test('removing a step drops edges and injections that used it', () => {
  const { wf, a, b } = flow();
  a.extract.push({ name: 'id', from: 'body.id' });
  connect(wf, a.id, b.id);
  b.inject.push({ target: 'path.id', value: '{{createOrder.id}}' }, { target: 'query.x', value: '1' });
  removeStep(wf, a.id);
  assert.deepEqual(b.dependsOn, []);
  assert.deepEqual(b.inject, [{ target: 'query.x', value: '1' }]);
});

test('suggestExtracts lists scalar leaves', () => {
  assert.deepEqual(suggestExtracts(apis[0]).map((s) => s.from), ['body.id', 'body.status', 'body.links.self']);
});

test('layout orders by depth', () => {
  const { wf, a, b } = flow();
  connect(wf, a.id, b.id);
  layout(wf);
  assert.ok(b.x > a.x);
});

test('toWorkflow normalises numbers and drops empty rows', () => {
  const { wf, a } = flow();
  a.extract.push({ name: '', from: '' }, { name: 'id', from: 'body.id' });
  a.expectStatus = ['201', 'x'];
  a.thinkTime = '0.5';
  const out = toWorkflow(wf);
  assert.deepEqual(out.steps[0].extract, [{ name: 'id', from: 'body.id' }]);
  assert.deepEqual(out.steps[0].expectStatus, [201]);
  assert.equal(out.steps[0].thinkTime, 0.5);
});

test('prune removes steps of deleted apis', () => {
  const { wf, b } = flow();
  connect(wf, 'createOrder', b.id);
  prune(wf, ['validateOrder']);
  assert.deepEqual(wf.steps.map((s) => s.id), ['validateOrder']);
  assert.deepEqual(wf.steps[0].dependsOn, []);
});

test('autoWire adds the obvious extract to the upstream step when nothing is extracted yet', () => {
  const { wf, a, b } = flow();
  connect(wf, a.id, b.id);
  assert.equal(autoWire(wf, apis, b.id), 1);
  assert.deepEqual(a.extract, [{ name: 'id', from: 'body.id' }]);
  assert.deepEqual(b.inject, [{ target: 'path.id', value: '{{createOrder.id}}' }]);
});
