import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { engine } from '../api/engine';
import type { AuditEntry, EvaluationLog } from '../api/types';
import { BarChart } from '../components/BarChart';
import {
  ActionBadge,
  Async,
  Card,
  Empty,
  ErrorNotice,
  Field,
  OutcomeBadge,
  PageHeader,
  Pager,
  Stat,
  formatMillis,
  formatTime,
  useLoad,
} from '../components/ui';

/** Admin area: the tabs shared by the dashboard and the three logs. */
export function LogsLayout() {
  return (
    <>
      <PageHeader title="Logs &amp; dashboard">
        What the engine decided, who changed what, and the AI chat conversations of your tenant. No input value is ever recorded:
        the logs keep decisions, counts and codes.
      </PageHeader>
      <nav className="tabs" aria-label="Log sections">
        <NavLink to="/logs" end>
          Dashboard
        </NavLink>
        <NavLink to="/logs/evaluations">Evaluations</NavLink>
        <NavLink to="/logs/audit">Audit trail</NavLink>
        <NavLink to="/logs/chat">Chat</NavLink>
      </nav>
      <Outlet />
    </>
  );
}

const WINDOWS: Array<[number, string]> = [
  [24, 'Last 24 hours'],
  [168, 'Last 7 days'],
  [720, 'Last 30 days'],
];

export function DashboardPage() {
  const [hours, setHours] = useState(24);
  const summary = useLoad(() => engine.logs.summary(hours), [hours]);
  return (
    <>
      <div className="toolbar">
        <Field id="window" label="Period">
          <select id="window" value={hours} onChange={(e) => setHours(Number(e.target.value))}>
            {WINDOWS.map(([h, label]) => (
              <option key={h} value={h}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <button className="btn" onClick={summary.reload}>
          Refresh
        </button>
      </div>
      <Async load={summary} what="Loading the dashboard">
        {(s) => {
          const blockRate = s.evaluations ? Math.round((s.block / s.evaluations) * 100) : 0;
          return (
            <>
              <div className="grid grid-stats" aria-label="Key numbers">
                <Stat label="Evaluations" value={s.evaluations} note={`${s.allow} allow · ${s.warn} warn · ${s.block} block`} />
                <Stat label="Blocked" value={`${blockRate}%`} note={`${s.block} of ${s.evaluations}`} />
                <Stat label="With errors" value={s.withErrors} note="a rule could not be evaluated" />
                <Stat label="Median / p95" value={`${s.p50Millis.toFixed(1)} / ${s.p95Millis.toFixed(1)}`} note="milliseconds to decide" />
                <Stat label="Changes made" value={s.authoringChanges} note={`${s.auditEvents} audit events`} />
                <Stat label="Chat conversations" value={s.conversations} note="active in this period" />
              </div>
              <Card title="Evaluations over time">
                <BarChart series={s.series} hours={s.hours} />
              </Card>
              <div className="grid grid-2">
                <Card title="Busiest groups">
                  {s.topGroups.length === 0 ? (
                    <Empty>Nothing evaluated in this period.</Empty>
                  ) : (
                    <div className="table-wrap">
                      <table>
                        <caption className="visually-hidden">Busiest groups</caption>
                        <thead>
                          <tr>
                            <th>Group</th>
                            <th className="num">Runs</th>
                            <th className="num">Blocked</th>
                            <th className="num">Errors</th>
                            <th className="num">Avg</th>
                          </tr>
                        </thead>
                        <tbody>
                          {s.topGroups.map((g) => (
                            <tr key={`${g.moduleCode}/${g.groupCode}`}>
                              <td>
                                <span className="mono">{g.groupCode}</span>
                                <div className="hint">{g.moduleCode}</div>
                              </td>
                              <td className="num">{g.evaluations}</td>
                              <td className="num">{g.blocked}</td>
                              <td className="num">{g.errors}</td>
                              <td className="num">{g.avgMillis.toFixed(1)} ms</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </Card>
                <Card title="Audit events by kind">
                  {Object.keys(s.auditByAction).length === 0 ? (
                    <Empty>No audit event in this period.</Empty>
                  ) : (
                    <div className="table-wrap">
                      <table>
                        <caption className="visually-hidden">Audit events by kind</caption>
                        <tbody>
                          {Object.entries(s.auditByAction).map(([action, n]) => (
                            <tr key={action}>
                              <td className="mono">{action}</td>
                              <td className="num">{n}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </Card>
              </div>
            </>
          );
        }}
      </Async>
    </>
  );
}

export function EvaluationsPage() {
  const [decision, setDecision] = useState('');
  const [group, setGroup] = useState('');
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const size = 25;
  const log = useLoad(
    () => engine.logs.evaluations({ decision, group, errorsOnly, page, size }),
    [decision, group, errorsOnly, page],
  );
  const reset = () => setPage(0);
  return (
    <>
      <div className="toolbar">
        <Field id="ev-decision" label="Decision">
          <select id="ev-decision" value={decision} onChange={(e) => { setDecision(e.target.value); reset(); }}>
            <option value="">Any</option>
            <option>ALLOW</option>
            <option>WARN</option>
            <option>BLOCK</option>
          </select>
        </Field>
        <Field id="ev-group" label="Group code">
          <input id="ev-group" type="text" value={group} onChange={(e) => { setGroup(e.target.value); reset(); }} />
        </Field>
        <label className="check">
          <input type="checkbox" checked={errorsOnly} onChange={(e) => { setErrorsOnly(e.target.checked); reset(); }} />
          Only evaluations with errors
        </label>
        <button className="btn" onClick={log.reload}>
          Refresh
        </button>
      </div>
      <Async load={log} what="Loading evaluations">
        {(p) =>
          p.items.length === 0 ? (
            <Empty>No evaluation matches.</Empty>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Evaluation log</caption>
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Group</th>
                      <th>Decision</th>
                      <th className="num">True</th>
                      <th className="num">False</th>
                      <th className="num">Error</th>
                      <th className="num">Time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {p.items.map((e) => (
                      <EvaluationRow key={e.id} e={e} open={open === e.id} toggle={() => setOpen(open === e.id ? null : e.id)} />
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
            </>
          )
        }
      </Async>
    </>
  );
}

function EvaluationRow({ e, open, toggle }: { e: EvaluationLog; open: boolean; toggle: () => void }) {
  return (
    <>
      <tr className="clickable" onClick={toggle}>
        <td>
          <button className="btn-link" aria-expanded={open} onClick={(ev) => { ev.stopPropagation(); toggle(); }}>
            {formatTime(e.at)}
          </button>
        </td>
        <td>
          <span className="mono">{e.groupCode ?? '(deleted group)'}</span>
          <div className="hint">
            {e.moduleCode} · {e.policy.replace('_', ' ').toLowerCase()}
            {e.viaTrigger ? ' · via trigger' : ''}
          </div>
        </td>
        <td>
          <ActionBadge value={e.decision} />
        </td>
        <td className="num">{e.rulesTrue}</td>
        <td className="num">{e.rulesFalse}</td>
        <td className="num">{e.rulesError}</td>
        <td className="num">{formatMillis(e.durationMicros)}</td>
      </tr>
      {open && (
        <tr>
          <td colSpan={7}>
            <EvaluationDetail id={e.id} />
          </td>
        </tr>
      )}
    </>
  );
}

function EvaluationDetail({ id }: { id: string }) {
  const detail = useLoad(() => engine.logs.evaluation(id), [id]);
  return (
    <div className="panel">
      <Async load={detail} what="Loading the rules">
        {(d) => (
          <div className="table-wrap">
            <table>
              <caption className="visually-hidden">Rules of this evaluation</caption>
              <thead>
                <tr>
                  <th className="num">#</th>
                  <th>Rule</th>
                  <th>Result</th>
                  <th>Action</th>
                  <th>Problem</th>
                </tr>
              </thead>
              <tbody>
                {d.results.map((r) => (
                  <tr key={r.ruleCode + r.sequence}>
                    <td className="num">{r.sequence}</td>
                    <td className="mono">{r.ruleCode}</td>
                    <td>
                      <OutcomeBadge value={r.outcome} />
                    </td>
                    <td>
                      <ActionBadge value={r.action} />
                    </td>
                    <td>{r.errorCode ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Async>
      <p className="hint">Input values are not stored, so only the outcome of each rule can be shown.</p>
    </div>
  );
}

const ACTIONS = [
  'RULE_CREATED',
  'RULE_STATUS_CHANGED',
  'RULE_GROUP_CREATED',
  'RULE_GROUP_UPDATED',
  'RULE_GROUP_STATUS_CHANGED',
  'RULE_GROUP_EVALUATED',
];

export function AuditPage() {
  const [action, setAction] = useState('');
  const [actor, setActor] = useState('');
  const [page, setPage] = useState(0);
  const size = 25;
  const log = useLoad(() => engine.logs.audit({ action, actor, page, size }), [action, actor, page]);
  return (
    <>
      <div className="toolbar">
        <Field id="au-action" label="What happened">
          <select id="au-action" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }}>
            <option value="">Anything</option>
            {ACTIONS.map((a) => (
              <option key={a}>{a}</option>
            ))}
          </select>
        </Field>
        <Field id="au-actor" label="Who (name contains)">
          <input id="au-actor" type="text" value={actor} onChange={(e) => { setActor(e.target.value); setPage(0); }} />
        </Field>
        <button className="btn" onClick={log.reload}>
          Refresh
        </button>
      </div>
      <Async load={log} what="Loading the audit trail">
        {(p) =>
          p.items.length === 0 ? (
            <Empty>No audit entry matches.</Empty>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Audit trail</caption>
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Who</th>
                      <th>What</th>
                      <th>Summary</th>
                    </tr>
                  </thead>
                  <tbody>
                    {p.items.map((a) => (
                      <AuditRow key={a.id} a={a} />
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
            </>
          )
        }
      </Async>
    </>
  );
}

function AuditRow({ a }: { a: AuditEntry }) {
  const pretty = (() => {
    try {
      return a.details ? JSON.stringify(JSON.parse(a.details), null, 2) : null;
    } catch {
      return a.details;
    }
  })();
  return (
    <tr>
      <td>{formatTime(a.at)}</td>
      <td>
        {a.actorName ?? '—'}
        <div className="hint">{a.actorRole?.toLowerCase()}</div>
      </td>
      <td>
        <span className="badge badge-info">{a.action}</span>
        <div className="hint mono">{a.entityCode}</div>
      </td>
      <td>
        {a.summary}
        {pretty && (
          <details>
            <summary className="hint">Details</summary>
            <pre>{pretty}</pre>
          </details>
        )}
      </td>
    </tr>
  );
}

export function ChatLogPage() {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const list = useLoad(() => engine.logs.conversations(page, 15), [page]);
  const detail = useLoad(() => (selected ? engine.logs.conversation(selected) : Promise.resolve(null)), [selected]);
  return (
    <div className="grid grid-2">
      <Card title="Conversations" sub="AI chat sessions of your tenant. Messages are shown as stored: the platform redacts them before storing.">
        <Async load={list} what="Loading conversations">
          {(p) =>
            p.items.length === 0 ? (
              <Empty>No chat conversation yet.</Empty>
            ) : (
              <>
                <ul className="list-reset">
                  {p.items.map((c) => (
                    <li key={c.id}>
                      <button
                        className="btn"
                        style={{ width: '100%', justifyContent: 'space-between', marginBottom: 'var(--space-2)' }}
                        aria-pressed={selected === c.id}
                        onClick={() => setSelected(c.id)}
                      >
                        <span>{c.title ?? '(untitled)'}</span>
                        <span className="hint">
                          {c.messages} msg · {formatTime(c.lastActivityAt)}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
                <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
              </>
            )
          }
        </Async>
      </Card>
      <Card title="Transcript">
        <ErrorNotice error={detail.error} />
        {!selected && <Empty>Select a conversation.</Empty>}
        {detail.data && (
          <div className="transcript" aria-label="Conversation transcript">
            {detail.data.messages.map((m) => (
              <div key={m.seq} className={`bubble ${m.role === 'USER' ? 'user' : ''}`}>
                <small>
                  {m.role.toLowerCase()} · {formatTime(m.at)}
                  {m.redacted ? ' · redacted' : ''}
                </small>
                {m.content}
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
