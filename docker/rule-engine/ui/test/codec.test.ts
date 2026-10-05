// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  decodeErrorResponse,
  decodeLoginResponse,
  encodeLoginRequest,
  encodeLoginResponse,
  type LoginResponse,
} from '../src/proto/codec';

/**
 * The same golden bytes the Java ContractGoldenTest reads (contract/testdata/login-response.hex), written by a hand-rolled
 * encoder that is neither implementation: if both languages decode and re-encode them identically, they agree on the wire.
 */
const GOLDEN = readFileSync(new URL('../../contract/testdata/login-response.hex', import.meta.url), 'utf8').trim();
const bytes = (hex: string) => Uint8Array.from(hex.match(/../g)?.map((h) => parseInt(h, 16)) ?? []);
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

const EXPECTED: LoginResponse = {
  session: {
    userId: '33333333-0000-0000-0000-000000000001',
    username: 'admin',
    displayName: 'Anaïs Müller',
    role: 'ROLE_ADMIN',
    tenantId: '11111111-1111-1111-1111-111111111111',
    tenantName: 'Acme Bank',
    organizationId: '22222222-2222-2222-2222-222222222222',
    organizationName: 'Retail',
  },
  accessToken: 'golden.access.token',
  expiresAtEpochSeconds: 1_893_456_000,
};

describe('protobuf contract (shared with the Java services)', () => {
  it('decodes the golden bytes, tenant and organization ids included', () => {
    expect(decodeLoginResponse(bytes(GOLDEN))).toEqual(EXPECTED);
  });

  it('encodes to exactly the golden bytes', () => {
    expect(hex(encodeLoginResponse(EXPECTED))).toBe(GOLDEN);
  });

  it('tolerates a field added by a newer peer', () => {
    const future = hex(bytes(GOLDEN)) + '9a06026869'; // field 99, string "hi"
    expect(decodeLoginResponse(bytes(future)).session.tenantId).toBe(EXPECTED.session.tenantId);
  });

  it('rejects truncated and incomplete bodies instead of returning half a session', () => {
    expect(() => decodeLoginResponse(bytes(GOLDEN.slice(0, 40)))).toThrow();
    expect(() => decodeLoginResponse(new Uint8Array())).toThrow(/incomplete/);
  });

  it('keeps a tenant-wide user (no organization) as empty strings', () => {
    const wide = encodeLoginResponse({
      ...EXPECTED,
      session: { ...EXPECTED.session, organizationId: '', organizationName: '' },
    });
    expect(decodeLoginResponse(wide).session.organizationId).toBe('');
  });

  it('encodes a login request the Java side can read (field 1 username, field 2 password)', () => {
    // 0a 05 "admin" 12 06 "secret"
    expect(hex(encodeLoginRequest('admin', 'secret'))).toBe('0a0561646d696e1206736563726574');
    expect(hex(encodeLoginRequest('', ''))).toBe(''); // proto3 omits defaults, as Java does
  });

  it('decodes an error response', () => {
    // 0a 0f "bad_credentials" 12 05 "wrong"
    const b = bytes('0a0f6261645f63726564656e7469616c73120577726f6e67');
    expect(decodeErrorResponse(b)).toEqual({ code: 'bad_credentials', message: 'wrong' });
  });
});
