import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { LoginError } from '../api/auth';
import { useAuth } from '../auth/AuthContext';

const SHOW_DEV_HINT = import.meta.env.VITE_DEV_LOGIN_HINT !== 'false';

const FRIENDLY: Record<string, string> = {
  bad_credentials: 'Wrong username or password.',
  locked: 'Too many failed attempts. Wait a few minutes and try again.',
  network: 'The sign-in service cannot be reached. Is the stack running?',
  invalid_request: 'Enter your username and password.',
};

/** Signs in with a protobuf LoginRequest; the session (user, tenant, organization) comes back as a protobuf message. */
export function LoginPage() {
  const { session, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (session) {
    return <Navigate to={from} replace />;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(username, password);
      navigate(from, { replace: true });
    } catch (err) {
      const code = err instanceof LoginError ? err.code : 'unknown';
      setError(FRIENDLY[code] ?? (err instanceof Error ? err.message : 'Sign-in failed.'));
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <main className="login-card">
        <h1>Rule engine console</h1>
        <p className="hint">Sign in to view the rule setup, author rule groups and, as an administrator, read the logs.</p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="username">Username</label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              autoFocus
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>
          {error && (
            <div className="notice notice-error" role="alert">
              {error}
            </div>
          )}
          <button className="btn btn-primary" type="submit" disabled={busy || !username || !password}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        {SHOW_DEV_HINT && (
          <aside className="dev-hint" aria-label="Local development logins">
            <strong>Local stack logins</strong>
            <table>
              <thead>
                <tr>
                  <th>User</th>
                  <th>Password</th>
                  <th>Role · tenant / organization</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>
                    <code>admin</code>
                  </td>
                  <td>
                    <code>admin123</code>
                  </td>
                  <td>Admin · Acme Bank / Retail</td>
                </tr>
                <tr>
                  <td>
                    <code>user</code>
                  </td>
                  <td>
                    <code>user123</code>
                  </td>
                  <td>User · Acme Bank / Retail</td>
                </tr>
                <tr>
                  <td>
                    <code>corp.user</code>
                  </td>
                  <td>
                    <code>user123</code>
                  </td>
                  <td>User · Acme Bank / Corporate</td>
                </tr>
                <tr>
                  <td>
                    <code>globex.admin</code>
                  </td>
                  <td>
                    <code>admin123</code>
                  </td>
                  <td>Admin · Globex (another tenant)</td>
                </tr>
              </tbody>
            </table>
          </aside>
        )}
      </main>
    </div>
  );
}
