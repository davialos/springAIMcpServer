import { describe, expect, it } from 'vitest';
import { decodeErrorResponse, decodeLoginResponse, encodeErrorResponse, encodeLoginRequest, encodeLoginResponse, type LoginResponse } from './proto/codec';

const RESPONSE: LoginResponse = {
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
  accessToken: 'token',
  expiresAtEpochSeconds: 1_800_000_000,
};

describe('protobuf contract', () => {
  it('round-trips a login response with the tenant and organization ids', () => {
    const decoded = decodeLoginResponse(encodeLoginResponse(RESPONSE));
    expect(decoded.session.tenantId).toBe(RESPONSE.session.tenantId);
    expect(decoded.session.organizationId).toBe(RESPONSE.session.organizationId);
    expect(decoded.session.role).toBe('ROLE_ADMIN');
    expect(decoded.expiresAtEpochSeconds).toBe(1_800_000_000);
  });

  it('encodes the request as field 1 = username, field 2 = password', () => {
    // tag 0x0a len 5 "admin", tag 0x12 len 8 "admin123"
    expect([...encodeLoginRequest('admin', 'admin123')]).toEqual([0x0a, 5, ...'admin'.split('').map((c) => c.charCodeAt(0)), 0x12, 8, ...'admin123'.split('').map((c) => c.charCodeAt(0))]);
  });

  it('refuses an incomplete response and decodes errors', () => {
    expect(() => decodeLoginResponse(new Uint8Array())).toThrow(/incomplete/);
    expect(decodeErrorResponse(encodeErrorResponse({ code: 'locked', message: 'wait' }))).toEqual({ code: 'locked', message: 'wait' });
  });
});
