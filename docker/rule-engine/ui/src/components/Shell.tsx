import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** The signed-in frame: navigation by what the user may do, who and where they are, and the way out. */
export function Shell() {
  const { session, isAdmin, signOut } = useAuth();
  return (
    <div className="shell">
      <a className="skip-link" href="#content">
        Skip to content
      </a>
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            R
          </span>
          <span>Rule engine</span>
        </div>
        <nav aria-label="Main">
          <div className="nav-group-title">Setup</div>
          <div className="nav">
            <NavLink to="/" end>
              Overview
            </NavLink>
            <NavLink to="/library">Parameter library</NavLink>
            <NavLink to="/rules">Rules</NavLink>
            <NavLink to="/groups">Rule groups</NavLink>
            <NavLink to="/channels">Triggers &amp; channels</NavLink>
          </div>
          <div className="nav-group-title">Try</div>
          <div className="nav">
            <NavLink to="/test">Test bench</NavLink>
          </div>
          {isAdmin && (
            <>
              <div className="nav-group-title">Administration</div>
              <div className="nav">
                <NavLink to="/logs">Logs &amp; dashboard</NavLink>
              </div>
            </>
          )}
        </nav>
        <div className="sidebar-footer">
          <div className="user-chip">
            <strong>{session?.displayName || session?.username}</strong>
            <span>
              {session?.tenantName}
              {session?.organizationName ? ` · ${session.organizationName}` : ' · all organizations'}
            </span>
            <span>
              <span className={`badge ${isAdmin ? 'badge-info' : ''}`}>{isAdmin ? 'Administrator' : 'User'}</span>
            </span>
          </div>
          <button className="btn" onClick={signOut}>
            Sign out
          </button>
        </div>
      </aside>
      <main id="content" className="main" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}
