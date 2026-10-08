import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyEvent, humanizeTool, newTurn, restoreHistory, stepsSummary } from '../../main/resources/META-INF/resources/dynamic-ai/ui/chat/turn.js';

test('a streamed turn: flags, steps, tool results, components, display, end', () => {
  const t = newTurn();
  [
    { type: 'turn.start', turnId: 'T', conversationId: 'C', ui: { steps: true, copy: true } },
    { type: 'step', stepId: 'check', title: 'Checked your request', status: 'done' },
    { type: 'tool.call', callId: 'tool-1', tool: 'find_orders', argsPreview: '{"status":"open"}' },
    { type: 'text.delta', text: 'Here ' },
    { type: 'tool.result', callId: 'tool-1', status: 'ok', summary: '2 Orders returned.' },
    { type: 'tool.call', callId: 'tool-2', tool: 'find_invoices', argsPreview: '{}' },
    { type: 'tool.result', callId: 'tool-2', status: 'not_permitted', summary: 'Not permitted.' },
    { type: 'ui.component', componentType: 'choice', componentId: 'choice-1', payload: '{"question":"Which?","options":[]}' },
    { type: 'ui.component', componentType: 'structured-response', payload: '{"version":1,"blocks":[]}' },
    { type: 'text.delta', text: 'you go.' },
    { type: 'unknown.future', x: 1 },
    { type: 'turn.end', finishReason: 'stop' },
  ].forEach((e) => applyEvent(t, e));
  assert.equal(t.turnId, 'T');
  assert.equal(t.text, 'Here you go.');
  assert.equal(t.status, 'done');
  assert.deepEqual(t.steps.map((s) => [s.title, s.status]), [['Checked your request', 'done'],
    ['Find orders', 'done'], ['Find invoices', 'error']]);
  assert.equal(t.components[0].payload.question, 'Which?');
  assert.deepEqual(t.display, { version: 1, blocks: [] });
  assert.equal(stepsSummary(t), 'Used 2 tools · 1 failed');
});

test('an error settles running steps and keeps the code', () => {
  const t = newTurn();
  applyEvent(t, { type: 'tool.call', callId: 'x', tool: 'slow_tool' });
  assert.equal(stepsSummary(t), 'Working…');
  applyEvent(t, { type: 'error', code: 'turn-timeout', title: 'Agent stream error', retryable: true });
  assert.equal(t.status, 'error');
  assert.equal(t.steps[0].status, 'error');
  assert.deepEqual(t.error, { code: 'turn-timeout', title: 'Agent stream error', retryable: true });
});

test('history restore attaches components and feedback to their turns', () => {
  const entries = restoreHistory([
    { role: 'USER', content: 'which order?' },
    { role: 'ASSISTANT', content: 'Pick one', turnId: 'T1' },
    { role: 'SYSTEM', content: 'ignored' },
  ], {
    components: [
      { turnId: 'T1', componentId: 'choice-1', componentType: 'choice', payload: { question: 'Which?' },
        answer: { values: ['o2'] } },
      { turnId: 'T9', componentId: 'choice-1', componentType: 'choice', payload: { question: 'Orphan?' }, answer: null },
    ],
    feedback: [{ turnId: 'T1', rating: 'down' }],
  });
  assert.equal(entries.length, 3);
  assert.equal(entries[1].turn.components[0].answer.values[0], 'o2');
  assert.equal(entries[1].feedback, 'down');
  assert.equal(entries[2].turn.components[0].payload.question, 'Orphan?');
  assert.equal(humanizeTool('runDataQuery'), 'Run data query');
});
