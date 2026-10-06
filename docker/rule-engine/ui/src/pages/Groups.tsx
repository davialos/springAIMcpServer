import { useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { engine } from '../api/engine';
import type { GroupView } from '../api/types';
import { ActionBadge, Async, ErrorNotice, PageHeader, ScopeBadge, StatusBadge, useLoad } from '../components/ui';

/** Rule groups with their rules in running order, trigger points and messages. */
export function GroupsPage() {
  const groups = useLoad(() => engine.groups(), []);
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const flash = location.state as { warnings?: string[]; saved?: string } | null;
  const [open, setOpen] = useState<string | null>(params.get('open'));
  const [actionError, setActionError] = useState<unknown>(null);

  useEffect(() => {
    setOpen(params.get('open'));
  }, [params]);

  async function setStatus(g: GroupView, status: string) {
    setActionError(null);
    try {
      await engine.setGroupStatus(g.id, status);
      groups.reload();
    } catch (e) {
      setActionError(e);
    }
  }

  return (
    <>
      <PageHeader
        title="Rule groups"
        actions={
          <Link className="btn btn-primary" to="/groups/new">
            New rule group
          </Link>
        }
      >
        A group runs its rules by an evaluation policy and answers allow, warn or block. Applications call a group directly or
        through a trigger point.
      </PageHeader>
      {flash?.saved && (
        <div className="notice notice-ok" role="status">
          Saved “{flash.saved}”.
          {flash.warnings && flash.warnings.length > 0 && (
            <ul>
              {flash.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      <ErrorNotice error={actionError} />
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
                    <th>Policy</th>
                    <th className="num">Rules</th>
                    <th className="num">Triggers</th>
                    <th>Scope</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((g) => (
                    <GroupRow
                      key={g.id}
                      group={g}
                      open={open === g.id}
                      toggle={() => setParams(open === g.id ? {} : { open: g.id })}
                      setStatus={setStatus}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )
        }
      </Async>
    </>
  );
}

function GroupRow(props: { group: GroupView; open: boolean; toggle: () => void; setStatus: (g: GroupView, s: string) => void }) {
  const g = props.group;
  return (
    <>
      <tr className="clickable" onClick={props.toggle}>
        <td>
          <button className="btn-link" aria-expanded={props.open} onClick={(e) => { e.stopPropagation(); props.toggle(); }}>
            {g.name}
          </button>
          <div className="hint mono">
            {g.moduleCode} · {g.code}
          </div>
        </td>
        <td>{g.policy.replace('_', ' ').toLowerCase()}{g.policy === 'FIRST_MATCH' || g.policy === 'ALL_MATCH' ? ` (on ${g.matchOn.toLowerCase()})` : ''}</td>
        <td className="num">{g.rules.length}</td>
        <td className="num">{g.triggers.length}</td>
        <td>
          <ScopeBadge value={g.scope} />
        </td>
        <td>
          <StatusBadge value={g.status} />
        </td>
      </tr>
      {props.open && (
        <tr>
          <td colSpan={6}>
            <div className="panel">
              {g.description && <p>{g.description}</p>}
              <h3>Rules, in running order</h3>
              {g.rules.length === 0 ? (
                <p className="hint">None: this group always allows.</p>
              ) : (
                <ol>
                  {g.rules.map((r) => (
                    <li key={r.ruleCode}>
                      {r.ruleName} <span className="hint mono">{r.ruleCode}</span>{' '}
                      {r.ruleStatus !== 'ACTIVE' && <StatusBadge value={r.ruleStatus} />}
                      {!r.enabled && <span className="badge">switched off</span>}
                    </li>
                  ))}
                </ol>
              )}
              <dl className="kv">
                <dt>If a rule errors</dt>
                <dd>
                  <ActionBadge value={g.onError} />
                </dd>
                {g.policy === 'COMPOSITE' && (
                  <>
                    <dt>All true</dt>
                    <dd>
                      <ActionBadge value={g.compositeTrueAction} />{' '}
                      {Object.entries(g.compositeTrueMessage).map(([l, t]) => (
                        <div key={l}><span className="badge">{l}</span> {t}</div>
                      ))}
                    </dd>
                    <dt>Any false</dt>
                    <dd>
                      <ActionBadge value={g.compositeFalseAction} />{' '}
                      {Object.entries(g.compositeFalseMessage).map(([l, t]) => (
                        <div key={l}><span className="badge">{l}</span> {t}</div>
                      ))}
                    </dd>
                  </>
                )}
                <dt>Triggers</dt>
                <dd>
                  {g.triggers.length === 0
                    ? 'none: call the group directly'
                    : g.triggers.map((t) => (
                        <div key={t.id}>
                          <code>{t.application}</code> · {t.formCode} · {t.actionCode}
                          {t.fieldCode ? ` · field ${t.fieldCode}` : ''}
                        </div>
                      ))}
                </dd>
                <dt>Channels</dt>
                <dd>{g.channelCount} communication(s) bound to this group</dd>
              </dl>
              <div className="actions">
                <Link className="btn btn-small" to={`/test?module=${g.moduleCode}&group=${g.code}`}>
                  Test
                </Link>
                <Link className="btn btn-small" to={`/groups/${g.id}/edit`}>
                  Edit
                </Link>
                {g.status !== 'ACTIVE' && (
                  <button className="btn btn-small" onClick={() => props.setStatus(g, 'ACTIVE')}>
                    Activate
                  </button>
                )}
                {g.status === 'ACTIVE' && (
                  <button className="btn btn-small" onClick={() => props.setStatus(g, 'DRAFT')}>
                    Move to draft
                  </button>
                )}
                {g.status !== 'RETIRED' && (
                  <button className="btn btn-small btn-danger" onClick={() => props.setStatus(g, 'RETIRED')}>
                    Retire
                  </button>
                )}
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}
