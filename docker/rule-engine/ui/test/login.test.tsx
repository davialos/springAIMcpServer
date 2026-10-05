import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PROTOBUF, encodeLoginRequest } from '../src/proto/codec';
import { ADMIN, USER, json, mockFetch, protobufError, protobufResponse, renderApp, signedIn } from './support';

afterEach(() => vi.unstubAllGlobals());

const hex = (b: Uint8Array | undefined) => Array.from(b ?? [], (x) => x.toString(16).padStart(2, '0')).join('');

async function fillAndSubmit(username: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Username'), username);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('login', () => {
  it('speaks protobuf both ways and lands on the overview with the tenant and organization', async () => {
    const fetchMock = mockFetch({
      'POST /auth/login': () => protobufResponse(ADMIN),
      'GET /api/v1/setup': () => json({ tenant: 'Acme Bank', organization: 'Retail', role: 'ADMIN', modules: 2, objects: 3, parameters: 9, rules: 5, activeRules: 5, groups: 4, activeGroups: 4, triggers: 3, channels: 3, emailTemplates: 1, apiEndpoints: 3, messages: 13, policies: [], actions: [], languages: ['en', 'hi'] }),
      'GET /api/v1/rule-groups': () => json([]),
    });
    renderApp('/login');

    await fillAndSubmit('admin', 'admin123');

    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/auth/login');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe(PROTOBUF);
    expect((init.headers as Record<string, string>).Accept).toBe(PROTOBUF);
    expect(hex(init.body as Uint8Array)).toBe(hex(encodeLoginRequest('admin', 'admin123')));
    // who and where, straight from the Session message
    expect(screen.getByText('Ada Admin')).toBeInTheDocument();
    expect(screen.getByText(/Acme Bank · Retail/)).toBeInTheDocument();
    expect(screen.getByText('Administrator')).toBeInTheDocument();
    // the token is sent to the API, never in a URL
    const apiCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/')) as unknown as [string, RequestInit];
    expect((apiCall[1].headers as Record<string, string>).Authorization).toBe('Bearer header.payload.signature');
    expect(apiCall[0]).not.toContain('header.payload');
  });

  it.each([
    ['bad_credentials', 401, 'Wrong username or password.'],
    ['locked', 429, 'Too many failed attempts. Wait a few minutes and try again.'],
  ])('shows a clear message for %s and clears the password', async (code, status, message) => {
    mockFetch({ 'POST /auth/login': () => protobufError(code, 'server wording', status) });
    renderApp('/login');

    await fillAndSubmit('admin', 'nope');

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled(); // nothing to submit until retyped
  });

  it('says so when the sign-in service cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('failed to fetch'); }));
    renderApp('/login');

    await fillAndSubmit('admin', 'x');

    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be reached');
  });

  it('refuses an answer that is not protobuf instead of trusting it', async () => {
    mockFetch({ 'POST /auth/login': () => json({ session: { tenantId: 't' }, accessToken: 'a' }) });
    renderApp('/login');

    await fillAndSubmit('admin', 'x');

    expect(await screen.findByRole('alert')).toHaveTextContent('unexpected format');
    expect(window.sessionStorage.getItem('re.session')).toBeNull();
  });

  it('keeps the session across a reload but not an expired one', async () => {
    signedIn({ ...ADMIN, expiresAtEpochSeconds: Math.floor(Date.now() / 1000) - 5 });
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'Rule engine console' })).toBeInTheDocument();
  });

  it('shows the local logins on the login page of the dev stack', () => {
    renderApp('/login');
    expect(screen.getAllByText('admin123')).toHaveLength(2); // admin and globex.admin
    expect(screen.getAllByText('user123', { selector: 'code' })).toHaveLength(2); // user and corp.user
  });
});

describe('roles', () => {
  const setup = () =>
    json({ tenant: 'Acme Bank', organization: 'Retail', role: 'X', modules: 0, objects: 0, parameters: 0, rules: 0, activeRules: 0, groups: 0, activeGroups: 0, triggers: 0, channels: 0, emailTemplates: 0, apiEndpoints: 0, messages: 0, policies: [], actions: [], languages: [] });

  it('sends a signed-out visitor to the login page', async () => {
    renderApp('/rules');
    expect(await screen.findByRole('heading', { name: 'Rule engine console' })).toBeInTheDocument();
  });

  it('gives a user no logs link and no logs page', async () => {
    signedIn(USER);
    mockFetch({ 'GET /api/v1/setup': setup, 'GET /api/v1/rule-groups': () => json([]) });
    renderApp('/logs');

    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Logs/ })).not.toBeInTheDocument();
    expect(screen.getByText('User')).toBeInTheDocument();
  });

  it('gives an administrator the logs', async () => {
    signedIn(ADMIN);
    mockFetch({
      'GET /api/v1/admin/logs/summary': () => json({ hours: 24, evaluations: 3, allow: 1, warn: 0, block: 2, withErrors: 1, p50Millis: 0.4, p95Millis: 1.2, auditEvents: 4, authoringChanges: 3, conversations: 1, series: [], topGroups: [], auditByAction: {} }),
    });
    renderApp('/logs');

    expect(await screen.findByText('67%')).toBeInTheDocument(); // 2 of 3 blocked
    expect(screen.getByRole('link', { name: 'Logs & dashboard' })).toBeInTheDocument();
  });

  it('signs the user out when the API answers 401', async () => {
    signedIn(ADMIN);
    mockFetch({
      'GET /api/v1/setup': () => json({ code: 'unauthenticated', detail: 'a valid access token is required' }, 401),
      'GET /api/v1/rule-groups': () => json({ code: 'unauthenticated', detail: 'x' }, 401),
    });
    renderApp('/');

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Rule engine console' })).toBeInTheDocument());
    expect(window.sessionStorage.getItem('re.session')).toBeNull();
  });
});
