import { useState } from 'react';
import { Link } from 'react-router-dom';
import { engine } from '../api/engine';
import type { RuleView } from '../api/types';
import { ActionBadge, Async, ErrorNotice, Field, PageHeader, ScopeBadge, StatusBadge, useLoad } from '../components/ui';

/** Every rule the caller may see, with its expression, messages, the parameters it reads and the groups that use it. */
export function RulesPage() {
  const [module, setModule] = useState('');
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const modules = useLoad(engine.modules, []);
  const rules = useLoad(() => engine.rules(module || undefined, status || undefined), [module, status]);

  async function change(rule: RuleView, next: string) {
    setActionError(null);
    try {
      await engine.setRuleStatus(rule.id, next);
      rules.reload();
    } catch (e) {
      setActionError(e);
    }
  }

  return (
    <>
      <PageHeader
        title="Rules"
        actions={
          <Link className="btn btn-primary" to="/rules/new">
            New rule
          </Link>
        }
      >
        A rule is one CEL expression with what to say and do when it is true or false. Rules are checked against the parameter
        library when they are saved.
      </PageHeader>
      <div className="toolbar">
        <Field id="rule-module" label="Module">
          <select id="rule-module" value={module} onChange={(e) => setModule(e.target.value)}>
            <option value="">All modules</option>
            {modules.data?.map((m) => (
              <option key={m.code} value={m.code}>
                {m.name}
              </option>
            ))}
          </select>
        </Field>
        <Field id="rule-status" label="Status">
          <select id="rule-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any</option>
            <option value="ACTIVE">Active</option>
            <option value="DRAFT">Draft</option>
            <option value="RETIRED">Retired</option>
          </select>
        </Field>
      </div>
      <ErrorNotice error={actionError} />
      <Async load={rules} what="Loading rules">
        {(list) =>
          list.length === 0 ? (
            <p className="empty">
              No rule matches. <Link to="/rules/new">Create one.</Link>
            </p>
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="visually-hidden">Rules</caption>
                <thead>
                  <tr>
                    <th>Rule</th>
                    <th>Expression</th>
                    <th>If true</th>
                    <th>If false</th>
                    <th>Scope</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((r) => (
                    <RuleRows key={r.id} rule={r} open={open === r.id} toggle={() => setOpen(open === r.id ? null : r.id)} change={change} />
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

function RuleRows(props: { rule: RuleView; open: boolean; toggle: () => void; change: (r: RuleView, status: string) => void }) {
  const r = props.rule;
  return (
    <>
      <tr className="clickable" onClick={props.toggle}>
        <td>
          <button className="btn-link" aria-expanded={props.open} onClick={(e) => { e.stopPropagation(); props.toggle(); }}>
            {r.name}
          </button>
          <div className="hint mono">
            {r.moduleCode} · {r.code}
          </div>
        </td>
        <td>
          <code>{r.expression}</code>
        </td>
        <td>
          <ActionBadge value={r.trueAction} />
        </td>
        <td>
          <ActionBadge value={r.falseAction} />
        </td>
        <td>
          <ScopeBadge value={r.scope} />
        </td>
        <td>
          <StatusBadge value={r.status} />
        </td>
      </tr>
      {props.open && (
        <tr>
          <td colSpan={6}>
            <div className="panel">
              <dl className="kv">
                <dt>Reads</dt>
                <dd>{r.parameters.length ? r.parameters.map((p) => <code key={p}>{p} </code>) : '—'}</dd>
                <dt>Used in groups</dt>
                <dd>{r.groups.length ? r.groups.join(', ') : 'none yet'}</dd>
                <dt>Message if true</dt>
                <dd>{Object.keys(r.trueMessage).length ? Object.entries(r.trueMessage).map(([l, t]) => <div key={l}><span className="badge">{l}</span> {t}</div>) : 'silent'}</dd>
                <dt>Message if false</dt>
                <dd>{Object.keys(r.falseMessage).length ? Object.entries(r.falseMessage).map(([l, t]) => <div key={l}><span className="badge">{l}</span> {t}</div>) : 'silent'}</dd>
              </dl>
              <div className="actions">
                {r.status !== 'ACTIVE' && (
                  <button className="btn btn-small" onClick={() => props.change(r, 'ACTIVE')}>
                    Activate
                  </button>
                )}
                {r.status === 'ACTIVE' && (
                  <button className="btn btn-small" onClick={() => props.change(r, 'DRAFT')}>
                    Move to draft
                  </button>
                )}
                {r.status !== 'RETIRED' && (
                  <button className="btn btn-small btn-danger" onClick={() => props.change(r, 'RETIRED')}>
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
