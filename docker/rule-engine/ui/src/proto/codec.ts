import protobuf from 'protobufjs';
// The contract is imported from where the Java code owns it; there is no second copy to drift.
import contract from '../../../contract/src/main/protobuf/springaimcp/ruleengine/v1/session.proto?raw';

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

const root = protobuf.parse(contract).root;
const LoginRequest = root.lookupType('springaimcp.ruleengine.v1.LoginRequest');
const LoginResponseType = root.lookupType('springaimcp.ruleengine.v1.LoginResponse');
const ErrorResponseType = root.lookupType('springaimcp.ruleengine.v1.ErrorResponse');

/** Plain objects with defaults filled, enums as names and 64-bit integers as numbers (epoch seconds fit). */
const OPTIONS = { longs: Number, enums: String, defaults: true } as const;

function verified(type: protobuf.Type, payload: Record<string, unknown>): Uint8Array {
  const message = type.fromObject(payload); // accepts enum names; rejects unknown ones
  const problem = type.verify(message);
  if (problem) {
    throw new Error(`invalid ${type.name}: ${problem}`);
  }
  return type.encode(message).finish();
}

export function encodeLoginRequest(username: string, password: string): Uint8Array {
  return verified(LoginRequest, { username, password });
}

export function encodeLoginResponse(response: LoginResponse): Uint8Array {
  return verified(LoginResponseType, { ...response });
}

/** Decodes a login response; a body that is not a complete response (no session, no token) is rejected. */
export function decodeLoginResponse(bytes: Uint8Array): LoginResponse {
  const decoded = LoginResponseType.toObject(LoginResponseType.decode(bytes), OPTIONS) as LoginResponse;
  if (!decoded.session || !decoded.session.tenantId || !decoded.accessToken) {
    throw new Error('the login response is incomplete');
  }
  return decoded;
}

export function encodeErrorResponse(error: ErrorResponse): Uint8Array {
  return verified(ErrorResponseType, { ...error });
}

export function decodeErrorResponse(bytes: Uint8Array): ErrorResponse {
  return ErrorResponseType.toObject(ErrorResponseType.decode(bytes), OPTIONS) as ErrorResponse;
}
