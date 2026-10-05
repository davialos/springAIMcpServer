import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { App } from '../src/App';
import { AuthProvider } from '../src/auth/AuthContext';
import { PROTOBUF, encodeErrorResponse, encodeLoginResponse, type LoginResponse } from '../src/proto/codec';

export const ADMIN: LoginResponse = {
  session: {
    userId: '33333333-0000-0000-0000-000000000001',
    username: 'admin',
    displayName: 'Ada Admin',
    role: 'ROLE_ADMIN',
    tenantId: '11111111-1111-1111-1111-111111111111',
    tenantName: 'Acme Bank',
    organizationId: '22222222-2222-2222-2222-222222222222',
    organizationName: 'Retail',
  },
  accessToken: 'header.payload.signature',
  expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
};

export const USER: LoginResponse = {
  ...ADMIN,
  session: { ...ADMIN.session, username: 'user', displayName: 'Uma User', role: 'ROLE_USER' },
};

/** A signed-in browser tab: the session the AuthProvider restores from sessionStorage. */
export function signedIn(r: LoginResponse): void {
  window.sessionStorage.setItem('re.session', JSON.stringify(r));
}

export function protobufResponse(r: LoginResponse, status = 200): Response {
  return new Response(encodeLoginResponse(r) as BodyInit, { status, headers: { 'Content-Type': PROTOBUF } });
}

export function protobufError(code: string, message: string, status: number): Response {
  return new Response(encodeErrorResponse({ code, message }) as BodyInit, { status, headers: { 'Content-Type': PROTOBUF } });
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

export function renderApp(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

/** Routes fetch by "METHOD path" to canned responses; anything unexpected fails the test loudly. */
export function mockFetch(routes: Record<string, () => Response | Promise<Response>>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`;
    const handler = routes[key] ?? routes[key.split('?')[0] ?? ''];
    if (!handler) {
      throw new Error(`unexpected request: ${key}`);
    }
    return handler();
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}
