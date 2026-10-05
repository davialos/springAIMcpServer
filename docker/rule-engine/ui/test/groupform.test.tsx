import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RuleView } from '../src/api/types';
import { ADMIN, USER, json, mockFetch, renderApp, signedIn } from './support';

afterEach(() => vi.unstubAllGlobals());

const rule = (code: string, name: string, scope: 'TENANT' | 'ORGANIZATION', status = 'ACTIVE'): RuleView => ({
  id: `id-${code}`, moduleCode: 'LOAN', code, name, description: null, expression: `customer.age >= ${code.length}`,
  status: status as RuleView['status'], scope, trueAction: 'ALLOW', falseAction: 'BLOCK', trueMessage: {}, falseMessage: {},
  parameters: ['customer.age'], groups: [], rowVersion: 0, updatedAt: '2026-10-05T10:00:00Z',
});

const RULES = [rule('ADULT', 'Customer is an adult', 'TENANT'), rule('KYC', 'KYC verified', 'TENANT'), rule('MINE', 'My private rule', 'ORGANIZATION'), rule('OLD', 'Draft rule', 'TENANT', 'DRAFT')];
const GROUP_VIEW = { id: 'g1', moduleCode: 'LOAN', code: 'new-group', name: 'New group', description: null, status: 'ACTIVE', scope: 'ORGANIZATION', policy: 'COMPOSITE', matchOn: 'TRUE', onError: 'BLOCK', compositeTrueAction: 'ALLOW', compositeFalseAction: 'BLOCK', compositeTrueMessage: {}, compositeFalseMessage: {}, rules: [], triggers: [], channelCount: 0, rowVersion: 0, updatedAt: '2026-10-05T10:00:00Z' };

function routes(extra: Record<string, () => Response> = {}) {
  return mockFetch({
    'GET /api/v1/modules': () => json([{ code: 'LOAN', name: 'Loan origination', description: null }]),
    'GET /api/v1/rules': () => json(RULES),
    'GET /api/v1/setup': () => json({ tenant: 'Acme Bank', organization: 'Retail', role: 'USER', modules: 1, objects: 1, parameters: 1, rules: 4, activeRules: 3, groups: 0, activeGroups: 0, triggers: 0, channels: 0, emailTemplates: 0, apiEndpoints: 0, messages: 0, policies: [], actions: [], languages: [] }),
    'GET /api/v1/rule-groups': () => json([GROUP_VIEW]),
    'POST /api/v1/rule-groups': () => json({ value: GROUP_VIEW, warnings: ['Rule OLD is DRAFT and will not run.'] }, 201),
    ...extra,
  });
}

const sentBody = (fetchMock: ReturnType<typeof routes>, method: string, url: string) => {
  const call = fetchMock.mock.calls.find((c) => (c[1] as RequestInit | undefined)?.method === method && String(c[0]) === url);
  return JSON.parse(String((call?.[1] as RequestInit).body)) as Record<string, unknown>;
};

describe('rule group form', () => {
  it('creates a composite group with ordered rules, messages and a trigger, and shows the server warnings', async () => {
    signedIn(USER);
    const fetchMock = routes();
    renderApp('/groups/new');
    const user = userEvent.setup();

    await screen.findByText('Customer is an adult');
    await user.type(screen.getByLabelText('Code'), 'new-group');
    await user.type(screen.getByLabelText('Name'), '  New group  ');
    const available = screen.getByText(/Available in/).parentElement as HTMLElement;
    await user.click(within(within(available).getByText('KYC verified').closest('li') as HTMLElement).getByRole('button', { name: 'Add' }));
    await user.click(within(within(available).getByText('Customer is an adult').closest('li') as HTMLElement).getByRole('button', { name: 'Add' }));
    await user.click(screen.getByRole('button', { name: 'Move ADULT up' })); // ADULT before KYC
    await user.type(screen.getByLabelText('Message when any rule is false: text (en)'), ' Something failed ');
    await user.click(screen.getByRole('button', { name: '+ Add a trigger point' }));
    await user.type(screen.getByLabelText('Application'), 'loan-portal');
    await user.type(screen.getByLabelText('Form'), 'LOAN_APPLICATION');
    await user.type(screen.getByLabelText('Action'), 'SUBMIT');
    await user.click(screen.getByRole('button', { name: 'Create rule group' }));

    await screen.findByText(/Saved “New group”/);
    expect(screen.getByText('Rule OLD is DRAFT and will not run.')).toBeInTheDocument();
    const body = sentBody(fetchMock, 'POST', '/api/v1/rule-groups');
    expect(body).toMatchObject({
      moduleCode: 'LOAN', code: 'new-group', name: 'New group', policy: 'COMPOSITE', onError: 'BLOCK', status: 'ACTIVE',
      scope: 'ORGANIZATION', compositeFalseAction: 'BLOCK', compositeFalseMessage: { en: 'Something failed' },
      rules: [{ ruleCode: 'ADULT', sequence: 10, enabled: true }, { ruleCode: 'KYC', sequence: 20, enabled: true }],
      triggers: [{ application: 'loan-portal', type: 'FORM_ACTION', formCode: 'LOAN_APPLICATION', actionCode: 'SUBMIT' }],
    });
    expect(body.compositeTrueMessage).toBeUndefined(); // the blank "true" message is dropped, not sent as empty text
    expect(body.expectedRowVersion).toBeUndefined();
  });

  it('shows the group messages only for the composite policy', async () => {
    signedIn(USER);
    routes();
    renderApp('/groups/new');
    const user = userEvent.setup();
    await screen.findByText('Customer is an adult');

    expect(screen.getByText('Group message and action')).toBeInTheDocument();
    await user.click(screen.getByLabelText(/First match/));

    expect(screen.queryByText('Group message and action')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Match on')).toBeInTheDocument();
    await user.click(screen.getByLabelText(/Evaluate all/));
    expect(screen.queryByLabelText('Match on')).not.toBeInTheDocument();
  });

  it('refuses a bad code before calling the service', async () => {
    signedIn(USER);
    const fetchMock = routes();
    renderApp('/groups/new');
    const user = userEvent.setup();
    await screen.findByText('Customer is an adult');

    await user.type(screen.getByLabelText('Code'), 'has space');
    await user.click(screen.getByRole('button', { name: 'Create rule group' }));

    expect(await screen.findByText(/Use letters, digits/)).toBeInTheDocument();
    expect(screen.getByText('Give the group a name.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some((c) => (c[1] as RequestInit | undefined)?.method === 'POST')).toBe(false);
  });

  it('keeps a private rule out of a group shared with the whole tenant', async () => {
    signedIn(ADMIN);
    routes();
    renderApp('/groups/new');
    const user = userEvent.setup();
    await screen.findByText('My private rule');

    await user.selectOptions(screen.getByLabelText('Who can use this group'), 'TENANT');

    await waitFor(() => expect(screen.queryByText('My private rule')).not.toBeInTheDocument());
    expect(screen.getByText('Customer is an adult')).toBeInTheDocument();
  });

  it('offers a plain user only their own organization', async () => {
    signedIn(USER);
    routes();
    renderApp('/groups/new');
    await screen.findByText('Customer is an adult');

    const scope = screen.getByLabelText('Who can use this group') as HTMLSelectElement;
    expect([...scope.options].map((o) => o.value)).toEqual(['ORGANIZATION']);
    expect(screen.getByText(/Only an administrator can share a group/)).toBeInTheDocument();
  });

  it('shows the service refusal on the form, and the stale-version conflict when editing', async () => {
    signedIn(USER);
    const fetchMock = routes({
      'GET /api/v1/rule-groups/g1': () => json({ ...GROUP_VIEW, rowVersion: 3, rules: [{ ruleCode: 'ADULT', ruleName: 'Customer is an adult', ruleStatus: 'ACTIVE', sequence: 10, enabled: true }] }),
      'PUT /api/v1/rule-groups/g1': () => json({ code: 'stale_version', detail: 'the group changed since you loaded it; reload and retry' }, 409),
    });
    renderApp('/groups/g1/edit');
    const user = userEvent.setup();

    await screen.findByRole('heading', { name: 'Edit New group' });
    expect(screen.getByLabelText('Code')).toHaveAttribute('readonly');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('changed since you loaded it');
    expect(sentBody(fetchMock, 'PUT', '/api/v1/rule-groups/g1').expectedRowVersion).toBe(3);
  });
});
