import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthService, LoginError } from './auth.service';
import { SessionService } from './session.service';
import { decodeLoginResponse, encodeErrorResponse, encodeLoginRequest, encodeLoginResponse, PROTOBUF } from './proto/codec';

const RESPONSE = {
  session: { userId: 'u', username: 'admin', displayName: 'Ada', role: 'ROLE_ADMIN' as const, tenantId: 't', tenantName: 'Acme', organizationId: 'o', organizationName: 'Retail' },
  accessToken: 'tok',
  expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
};

/** An ArrayBuffer of the test realm (the testing backend checks the type with instanceof). */
function bytes(a: Uint8Array): ArrayBuffer {
  const out = new ArrayBuffer(a.byteLength);
  new Uint8Array(out).set(a);
  return out;
}

describe('AuthService', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let session: SessionService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    session = TestBed.inject(SessionService);
  });

  it('signs in with a protobuf body and keeps the session from the protobuf reply', async () => {
    const done = auth.login('admin', 'admin123');
    const req = http.expectOne('/auth/login');
    expect(req.request.headers.get('Content-Type')).toBe(PROTOBUF);
    expect(req.request.headers.get('Accept')).toBe(PROTOBUF);
    // the body is the protobuf message itself (field 1 username, field 2 password), not a JSON rendering of a typed array
    expect(req.request.body).toBeInstanceOf(ArrayBuffer);
    expect([...new Uint8Array(req.request.body as ArrayBuffer)]).toEqual([...encodeLoginRequest('admin', 'admin123')]);
    req.flush(bytes(encodeLoginResponse(RESPONSE)));
    const r = await done;

    expect(decodeLoginResponse(encodeLoginResponse(r)).session.tenantId).toBe('t');
    expect(session.signedIn()).toBe(true);
    expect(session.isAdmin()).toBe(true);
    expect(session.token()).toBe('tok');
    expect(session.session()?.organizationName).toBe('Retail');
  });

  it('reports the refusal code from the protobuf error and stays signed out', async () => {
    const done = auth.login('admin', 'nope');
    http.expectOne('/auth/login').flush(bytes(encodeErrorResponse({ code: 'bad_credentials', message: 'no' })), { status: 401, statusText: 'Unauthorized' });
    await expect(done).rejects.toMatchObject({ name: 'LoginError', status: 401, code: 'bad_credentials' });
    expect(session.signedIn()).toBe(false);
  });

  it('treats an unreachable service as a network error', async () => {
    const done = auth.login('a', 'b');
    http.expectOne('/auth/login').error(new ProgressEvent('error'));
    const e = (await done.catch((x: unknown) => x)) as LoginError;
    expect(e.code).toBe('network');
  });

  it('forgets the session on logout and does not restore an expired one', () => {
    session.start({ ...RESPONSE });
    auth.logout();
    expect(session.signedIn()).toBe(false);
    expect(sessionStorage.getItem('re.session')).toBeNull();
    sessionStorage.setItem('re.session', JSON.stringify({ ...RESPONSE, expiresAtEpochSeconds: 1 }));
    TestBed.resetTestingModule();
    expect(TestBed.inject(SessionService).signedIn()).toBe(false);
  });
});
