import { Injectable, computed, signal } from '@angular/core';
import type { LoginResponse, Session } from './proto/codec';

const STORAGE_KEY = 're.session';

/**
 * The signed-in user. The access token stays in sessionStorage (cleared when the tab closes) and is sent as a bearer
 * header; it is never put in a URL. Reads and writes of the storage never throw: without storage the session lives for
 * this page only.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly stored = signal<LoginResponse | null>(this.load());
  private timer: ReturnType<typeof setTimeout> | undefined;

  readonly session = computed<Session | null>(() => this.stored()?.session ?? null);
  readonly token = computed(() => this.stored()?.accessToken ?? null);
  readonly isAdmin = computed(() => this.stored()?.session.role === 'ROLE_ADMIN');
  readonly signedIn = computed(() => this.stored() !== null);

  constructor() {
    this.armExpiry();
  }

  /** Remembers a successful login and signs out when the token expires. */
  start(response: LoginResponse): void {
    this.stored.set(response);
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(response));
    } catch {
      // storage unavailable
    }
    this.armExpiry();
  }

  /** Forgets the session. */
  end(): void {
    this.stored.set(null);
    clearTimeout(this.timer);
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // storage unavailable
    }
  }

  private load(): LoginResponse | null {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      if (!raw) {
        return null;
      }
      const value = JSON.parse(raw) as LoginResponse;
      return value.expiresAtEpochSeconds * 1000 > Date.now() ? value : null;
    } catch {
      return null; // private mode or a damaged value: start signed out
    }
  }

  private armExpiry(): void {
    clearTimeout(this.timer);
    const s = this.stored();
    if (s) {
      // setTimeout caps at ~24.8 days; tokens live hours
      this.timer = setTimeout(() => this.end(), Math.max(0, Math.min(s.expiresAtEpochSeconds * 1000 - Date.now(), 2 ** 31 - 1)));
    }
  }
}
