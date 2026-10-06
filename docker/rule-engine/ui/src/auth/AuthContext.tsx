import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { login as loginRequest } from '../api/auth';
import { configureHttp } from '../api/http';
import type { LoginResponse, Session } from '../proto/codec';

const STORAGE_KEY = 're.session';

interface Stored {
  session: Session;
  accessToken: string;
  expiresAtEpochSeconds: number;
}

interface AuthState {
  session: Session | null;
  isAdmin: boolean;
  signIn: (username: string, password: string) => Promise<Session>;
  signOut: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function load(): Stored | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const stored = JSON.parse(raw) as Stored;
    return stored.expiresAtEpochSeconds * 1000 > Date.now() ? stored : null;
  } catch {
    return null; // private mode or a damaged value: start signed out
  }
}

function save(r: LoginResponse | null): void {
  try {
    if (r) {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(r));
    } else {
      window.sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // storage unavailable: the session lives for this page only
  }
}

/**
 * Holds the signed-in user. The access token stays in sessionStorage (cleared when the tab closes) and is sent as a bearer
 * header; it is never put in a URL. Any 401 from the API signs the user out.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [stored, setStored] = useState<Stored | null>(load);

  const signOut = useCallback(() => {
    save(null);
    setStored(null);
  }, []);

  // keep the HTTP layer in step before any child effect runs
  configureHttp(stored?.accessToken ?? null, signOut);

  useEffect(() => {
    if (!stored) {
      return undefined;
    }
    const left = stored.expiresAtEpochSeconds * 1000 - Date.now();
    const timer = window.setTimeout(signOut, Math.max(left, 0));
    return () => window.clearTimeout(timer);
  }, [stored, signOut]);

  const signIn = useCallback(async (username: string, password: string) => {
    const response = await loginRequest(username, password);
    save(response);
    setStored(response);
    return response.session;
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      session: stored?.session ?? null,
      isAdmin: stored?.session.role === 'ROLE_ADMIN',
      signIn,
      signOut,
    }),
    [stored, signIn, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth needs an AuthProvider');
  }
  return ctx;
}
