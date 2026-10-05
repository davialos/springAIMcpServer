import {
  PROTOBUF,
  decodeErrorResponse,
  decodeLoginResponse,
  encodeLoginRequest,
  type LoginResponse,
} from '../proto/codec';

/** A refused login: the service's stable code ("bad_credentials", "locked" …) and message. */
export class LoginError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'LoginError';
  }
}

/**
 * Signs in over Protocol Buffers: the request and the response are the `LoginRequest` / `LoginResponse` messages of the
 * shared contract, so the user, tenant and organization ids reach the browser exactly as the services define them.
 */
export async function login(username: string, password: string): Promise<LoginResponse> {
  let res: Response;
  try {
    res = await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': PROTOBUF, Accept: PROTOBUF },
      body: encodeLoginRequest(username, password) as BodyInit,
    });
  } catch {
    throw new LoginError(0, 'network', 'the sign-in service cannot be reached');
  }
  const bytes = new Uint8Array(await res.arrayBuffer());
  const protobuf = (res.headers.get('Content-Type') ?? '').includes('protobuf');
  if (!res.ok) {
    if (protobuf && bytes.length > 0) {
      const e = decodeErrorResponse(bytes);
      throw new LoginError(res.status, e.code, e.message);
    }
    throw new LoginError(res.status, 'http_' + res.status, 'sign-in failed');
  }
  if (!protobuf) {
    throw new LoginError(res.status, 'bad_response', 'the sign-in service answered in an unexpected format');
  }
  return decodeLoginResponse(bytes);
}
