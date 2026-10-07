import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';

/** Signed-out visitors go to the login page and come back to where they were going. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const location = useLocation();
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}

/**
 * Hides admin pages from users. This is for the experience only: the service refuses a non-admin with 403 on every admin
 * endpoint, whatever the browser shows.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { isAdmin } = useAuth();
  if (!isAdmin) {
    return (
      <section className="notice notice-warn" role="alert">
        <h1>Administrators only</h1>
        <p>Your role cannot open this page. Ask an administrator of your organization if you need the logs.</p>
      </section>
    );
  }
  return <>{children}</>;
}
