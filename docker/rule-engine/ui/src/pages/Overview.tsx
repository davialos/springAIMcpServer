import { Link } from 'react-router-dom';
import { engine } from '../api/engine';
import { useAuth } from '../auth/AuthContext';
import { ActionBadge, Async, Card, PageHeader, Stat, StatusBadge, useLoad } from '../components/ui';

/** What is set up for the signed-in tenant and organization, at a glance. */
export function OverviewPage() {
  const { session, isAdmin } = useAuth();
  const setup = useLoad(engine.setup, []);
  const groups = useLoad(() => engine.groups(), []);
  return (
    <>
      <PageHeader
        title="Overview"
        actions={
          <>
            <Link className="btn" to="/test">
              Open test bench
            </Link>
            <Link className="btn btn-primary" to="/groups/new">
              New rule group
            </Link>
          </>
        }
      >
        The rules that decide for {session?.tenantName}
        {session?.organizationName ? ` / ${session.organizationName}` : ''}. You see what is shared with the whole tenant plus
        what belongs to your own organization.
      </PageHeader>
      <Async load={setup} what="Loading the setup">
        {(s) => (
          <div className="grid grid-stats" aria-label="Setup summary">
            <Stat label="Rule groups" value={s.groups} note={`${s.activeGroups} active`} />
            <Stat label="Rules" value={s.rules} note={`${s.activeRules} active`} />
            <Stat label="Parameters" value={s.parameters} note={`${s.objects} objects in the library`} />
            <Stat label="Trigger points" value={s.triggers} note="forms and actions bound to groups" />
            <Stat label="Channels" value={s.channels} note={`${s.emailTemplates} e-mail templates · ${s.apiEndpoints} APIs`} />
            <Stat label="Languages" value={s.languages.length} note={s.languages.join(', ') || 'none yet'} />
          </div>
        )}
      </Async>
      <Card title="Rule groups" sub="A group runs its rules by an evaluation policy and answers allow, warn or block.">
        <Async load={groups} what="Loading rule groups">
          {(list) =>
            list.length === 0 ? (
              <p className="empty">
                No rule group yet. <Link to="/groups/new">Create the first one.</Link>
              </p>
            ) : (
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Rule groups</caption>
                  <thead>
                    <tr>
                      <th>Group</th>
                      <th>Module</th>
                      <th>Policy</th>
                      <th className="num">Rules</th>
                      <th>Status</th>
                      <th>On error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((g) => (
                      <tr key={g.id}>
                        <td>
                          <Link to={`/groups?open=${g.id}`}>{g.name}</Link>
                          <div className="hint mono">{g.code}</div>
                        </td>
                        <td>{g.moduleCode}</td>
                        <td>{g.policy.replace('_', ' ').toLowerCase()}</td>
                        <td className="num">{g.rules.length}</td>
                        <td>
                          <StatusBadge value={g.status} />
                        </td>
                        <td>
                          <ActionBadge value={g.onError} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          }
        </Async>
      </Card>
      {isAdmin && (
        <Card title="Administration">
          <p>
            Read what the engine decided and who changed what in <Link to="/logs">Logs &amp; dashboard</Link>.
          </p>
        </Card>
      )}
    </>
  );
}
