import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  PROTOBUF,
  decodeErrorResponse,
  decodeLoginResponse,
  encodeLoginRequest,
  type LoginResponse,
} from './proto/codec';
import { SessionService } from './session.service';

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

/** HttpClient sends an ArrayBuffer as it is, but would JSON-encode a typed array: hand it the buffer of exactly these bytes. */
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const out = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(out).set(bytes);
  return out;
}

/**
 * Signs in over Protocol Buffers: request and response are the `LoginRequest` / `LoginResponse` messages of the shared
 * contract, so the user, tenant and organization ids reach the browser exactly as the services define them.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);

  async login(username: string, password: string): Promise<LoginResponse> {
    let response: LoginResponse;
    try {
      const bytes = await firstValueFrom(
        this.http.post('/auth/login', toArrayBuffer(encodeLoginRequest(username, password)), {
          headers: { 'Content-Type': PROTOBUF, Accept: PROTOBUF },
          responseType: 'arraybuffer',
        }),
      );
      response = decodeLoginResponse(new Uint8Array(bytes));
    } catch (e) {
      throw this.refused(e);
    }
    this.session.start(response);
    return response;
  }

  logout(): void {
    this.session.end();
  }

  private refused(e: unknown): LoginError {
    if (e instanceof HttpErrorResponse) {
      if (e.status === 0) {
        return new LoginError(0, 'network', 'the sign-in service cannot be reached');
      }
      if (e.error instanceof ArrayBuffer && e.error.byteLength > 0) {
        try {
          const p = decodeErrorResponse(new Uint8Array(e.error));
          return new LoginError(e.status, p.code, p.message);
        } catch {
          // not a protobuf error: fall through
        }
      }
      return new LoginError(e.status, 'http_' + e.status, 'sign-in failed');
    }
    return new LoginError(0, 'bad_response', 'the sign-in service answered in an unexpected format');
  }
}
