import { springaimcp } from './gen/session';

/*
 * The wire contract of the rule-engine ecosystem. The classes in ./gen are generated from the very session.proto the Java
 * services use (`npm run proto`, part of every build and test run), so the contract has one owner. Generated static code needs
 * no `eval`, which is what lets the web server send a strict Content-Security-Policy (no 'unsafe-eval').
 */
const v1 = springaimcp.ruleengine.v1;

export type Role = 'ROLE_UNSPECIFIED' | 'ROLE_USER' | 'ROLE_ADMIN';

/** The signed-in user and the scope every later request runs in (proto message `Session`). */
export interface Session {
  userId: string;
  username: string;
  displayName: string;
  role: Role;
  tenantId: string;
  tenantName: string;
  /** Empty for a tenant-wide user. */
  organizationId: string;
  organizationName: string;
}

export interface LoginResponse {
  session: Session;
  accessToken: string;
  expiresAtEpochSeconds: number;
}

export interface ErrorResponse {
  code: string;
  message: string;
}

export const PROTOBUF = 'application/x-protobuf';

/** Plain objects with defaults filled, enums as names and 64-bit integers as numbers (epoch seconds fit). */
const OPTIONS = { longs: Number, enums: String, defaults: true } as const;

export function encodeLoginRequest(username: string, password: string): Uint8Array {
  return v1.LoginRequest.encode(v1.LoginRequest.create({ username, password })).finish();
}

export function encodeLoginResponse(response: LoginResponse): Uint8Array {
  const message = v1.LoginResponse.fromObject({ ...response });
  const problem = v1.LoginResponse.verify(message);
  if (problem) {
    throw new Error(`invalid LoginResponse: ${problem}`);
  }
  return v1.LoginResponse.encode(message).finish();
}

/** Decodes a login response; a body that is not a complete response (no session, no token) is rejected. */
export function decodeLoginResponse(bytes: Uint8Array): LoginResponse {
  const decoded = v1.LoginResponse.toObject(v1.LoginResponse.decode(bytes), OPTIONS) as unknown as LoginResponse;
  if (!decoded.session || !decoded.session.tenantId || !decoded.accessToken) {
    throw new Error('the login response is incomplete');
  }
  return decoded;
}

export function encodeErrorResponse(error: ErrorResponse): Uint8Array {
  return v1.ErrorResponse.encode(v1.ErrorResponse.fromObject({ ...error })).finish();
}

export function decodeErrorResponse(bytes: Uint8Array): ErrorResponse {
  return v1.ErrorResponse.toObject(v1.ErrorResponse.decode(bytes), OPTIONS) as unknown as ErrorResponse;
}
