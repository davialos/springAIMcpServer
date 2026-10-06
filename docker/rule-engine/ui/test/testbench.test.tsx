import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Attribute } from '../src/api/types';
import { factValue } from '../src/pages/TestBench';
import { USER, json, mockFetch, renderApp, signedIn } from './support';

afterEach(() => vi.unstubAllGlobals());

const attr = (dataType: string): Attribute => ({ code: 'x', name: 'x', dataType, required: true, sampleValue: null, celName: 'o.x', usedByRules: 0 });

describe('factValue', () => {
  it('converts what was typed to the type of the parameter', () => {
    expect(factValue(attr('INT'), ' 34 ')).toBe(34);
    expect(factValue(attr('DOUBLE'), '250000.5')).toBe(250000.5);
    expect(factValue(attr('BOOL'), 'true')).toBe(true);
    expect(factValue(attr('BOOL'), 'false')).toBe(false);
    expect(factValue(attr('STRING'), 'IN')).toBe('IN');
    expect(factValue(attr('LIST_STRING'), '["a","b"]')).toEqual(['a', 'b']);
    expect(factValue(attr('MAP'), '{"k":1}')).toEqual({ k: 1 });
  });

  it('leaves a blank value out, so the engine reports the missing parameter', () => {
    expect(factValue(attr('INT'), '')).toBeUndefined();
    expect(factValue(attr('STRING'), '   ')).toBeUndefined();
  });

  it('passes a wrong-typed value through as text so the engine, not the browser, judges it', () => {
    expect(factValue(attr('INT'), 'thirty')).toBe('thirty');
    expect(factValue(attr('LIST_INT'), 'not json')).toBe('not json');
  });
});

describe('test bench', () => {
  it('asks for the parameters the group reads, evaluates, and shows the decision rule by rule', async () => {
    signedIn(USER);
    const group = { id: 'g1', moduleCode: 'LOAN', code: 'ELIG', name: 'Eligibility', description: null, status: 'ACTIVE', scope: 'TENANT', policy: 'COMPOSITE', matchOn: 'TRUE', onError: 'BLOCK', compositeTrueAction: 'ALLOW', compositeFalseAction: 'BLOCK', compositeTrueMessage: {}, compositeFalseMessage: {}, rules: [{ ruleCode: 'ADULT', ruleName: 'Adult', ruleStatus: 'ACTIVE', sequence: 10, enabled: true }], triggers: [], channelCount: 0, rowVersion: 0, updatedAt: 'x' };
    const fetchMock = mockFetch({
      'GET /api/v1/rule-groups': () => json([group]),
      'GET /api/v1/rules': () => json([{ id: 'r1', moduleCode: 'LOAN', code: 'ADULT', name: 'Adult', description: null, expression: 'customer.age >= 18', status: 'ACTIVE', scope: 'TENANT', trueAction: 'ALLOW', falseAction: 'BLOCK', trueMessage: {}, falseMessage: {}, parameters: ['customer.age'], groups: ['ELIG'], rowVersion: 0, updatedAt: 'x' }]),
      'GET /api/v1/library': () => json([{ code: 'customer', name: 'Customer', moduleCode: null, attributes: [{ code: 'age', name: 'Age in years', dataType: 'INT', required: true, sampleValue: '34', celName: 'customer.age', usedByRules: 1 }] }]),
      'GET /api/v1/setup': () => json({ tenant: 'T', organization: null, role: 'USER', modules: 1, objects: 1, parameters: 1, rules: 1, activeRules: 1, groups: 1, activeGroups: 1, triggers: 0, channels: 0, emailTemplates: 0, apiEndpoints: 0, messages: 0, policies: [], actions: [], languages: ['en', 'hi'] }),
      'POST /api/v1/evaluations': () => json({ moduleCode: 'LOAN', groupCode: 'ELIG', policy: 'COMPOSITE', decision: 'BLOCK', matched: true, primaryMessage: { source: 'GROUP', ruleCode: null, outcome: 'FALSE', action: 'BLOCK', language: 'hi', text: 'कुछ विफल हुआ' }, messages: [], results: [{ ruleCode: 'ADULT', ruleName: 'Adult', sequence: 10, outcome: 'FALSE', action: 'BLOCK', errorCode: null, errorDetail: null }], channels: [{ type: 'EMAIL', onResult: 'FALSE', ruleCode: null, recipientResolved: false, target: 'Declined (TPL-1)' }], durationMicros: 420 }),
    });
    renderApp('/test');
    const user = userEvent.setup();

    await user.selectOptions(await screen.findByLabelText('Rule group'), 'LOAN/ELIG');
    const age = await screen.findByLabelText('customer.age');
    await user.type(age, '16');
    await user.click(screen.getByLabelText('hi'));
    await user.click(screen.getByRole('button', { name: 'Evaluate' }));

    expect(await screen.findByText('कुछ विफल हुआ')).toBeInTheDocument();
    expect(screen.getAllByText('BLOCK').length).toBeGreaterThan(0);
    expect(screen.getByText(/Described only: nothing was sent/)).toBeInTheDocument();
    const call = fetchMock.mock.calls.find((c) => String(c[0]) === '/api/v1/evaluations') as unknown as [string, RequestInit];
    expect(JSON.parse(String(call[1].body))).toEqual({ moduleCode: 'LOAN', groupCode: 'ELIG', facts: { 'customer.age': 16 }, languages: ['en', 'hi'] });
  });
});
