import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { engine } from '../api/engine';
import { ApiError } from '../api/http';
import type { Action, GroupRequest, GroupView, Policy, RuleView, Scope, Texts, TriggerSpec } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { TextsEditor, cleanTexts } from '../components/TextsEditor';
import { Async, Card, ErrorNotice, Field, PageHeader, ScopeBadge, StatusBadge, useLoad } from '../components/ui';

const CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

const POLICIES: Array<{ value: Policy; title: string; text: string }> = [
  { value: 'COMPOSITE', title: 'Composite', text: 'Every rule must be true. One message and one action for the whole group.' },
  { value: 'FIRST_MATCH', title: 'First match', text: 'Stops at the first rule whose result matches the setting below.' },
  { value: 'ALL_MATCH', title: 'All matches', text: 'Reports every rule whose result matches the setting below.' },
  { value: 'EVALUATE_ALL', title: 'Evaluate all', text: 'Runs every rule and reports each result, true and false.' },
];

interface Member {
  ruleCode: string;
  enabled: boolean;
}

interface TriggerRow extends TriggerSpec {
  key: number;
}

/**
 * Creates a rule group, or edits one when the route has an id: policy, which rules run in what order, the messages of a
 * composite group, and the trigger points that make an application call it. The service re-validates everything; this form
 * explains the choices and keeps the author from sending an obviously wrong request.
 */
export function GroupFormPage() {
  const { id } = useParams();
  const editing = id !== undefined;
  const existing = useLoad(() => (editing ? engine.group(id) : Promise.resolve(null)), [id]);
  if (editing && existing.loading && !existing.data) {
    return <p role="status">Loading the group…</p>;
  }
  if (editing && existing.error) {
    return <ErrorNotice error={existing.error} />;
  }
  return <GroupEditor key={id ?? 'new'} group={existing.data} />;
}

function GroupEditor({ group }: { group: GroupView | null }) {
  const { session, isAdmin } = useAuth();
  const navigate = useNavigate();
  const editing = group !== null;
  const modules = useLoad(engine.modules, []);
  const [moduleCode, setModuleCode] = useState(group?.moduleCode ?? '');
  const [code, setCode] = useState(group?.code ?? '');
  const [name, setName] = useState(group?.name ?? '');
  const [description, setDescription] = useState(group?.description ?? '');
  const [policy, setPolicy] = useState<Policy>(group?.policy ?? 'COMPOSITE');
  const [matchOn, setMatchOn] = useState<'TRUE' | 'FALSE'>(group?.matchOn ?? 'FALSE');
  const [onError, setOnError] = useState<Action>(group?.onError ?? 'BLOCK');
  const [trueAction, setTrueAction] = useState<Action>(group?.compositeTrueAction ?? 'ALLOW');
  const [falseAction, setFalseAction] = useState<Action>(group?.compositeFalseAction ?? 'BLOCK');
  const [trueMessage, setTrueMessage] = useState<Texts>(group?.compositeTrueMessage ?? { en: '' });
  const [falseMessage, setFalseMessage] = useState<Texts>(group?.compositeFalseMessage ?? { en: '' });
  const [members, setMembers] = useState<Member[]>(
    group?.rules.map((r) => ({ ruleCode: r.ruleCode, enabled: r.enabled })) ?? [],
  );
  const [triggers, setTriggers] = useState<TriggerRow[]>(
    group?.triggers.map((t, i) => ({
      key: i,
      application: t.application,
      type: t.type,
      formCode: t.formCode,
      actionCode: t.actionCode,
      fieldCode: t.fieldCode ?? undefined,
    })) ?? [],
  );
  const [status, setStatus] = useState<'ACTIVE' | 'DRAFT'>(group?.status === 'DRAFT' ? 'DRAFT' : 'ACTIVE');
  const [scope, setScope] = useState<Scope>(group?.scope ?? (session?.organizationId ? 'ORGANIZATION' : 'TENANT'));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const module = moduleCode || group?.moduleCode || modules.data?.[0]?.code || '';
  const rules = useLoad(() => (module ? engine.rules(module) : Promise.resolve([] as RuleView[])), [module]);
  const byCode = useMemo(() => new Map((rules.data ?? []).map((r) => [r.code, r])), [rules.data]);
  // a rule of this organization cannot be part of a group shared by the whole tenant
  const candidates = (rules.data ?? []).filter(
    (r) => !members.some((m) => m.ruleCode === r.code) && (scope === 'ORGANIZATION' || r.scope === 'TENANT'),
  );

  useEffect(() => {
    if (scope === 'TENANT') {
      setMembers((m) => m.filter((x) => byCode.get(x.ruleCode)?.scope !== 'ORGANIZATION'));
    }
  }, [scope, byCode]);

  const move = (i: number, by: number) =>
    setMembers((list) => {
      const next = [...list];
      const j = i + by;
      if (j < 0 || j >= next.length) return list;
      const a = next[i];
      const b = next[j];
      if (!a || !b) return list;
      next[i] = b;
      next[j] = a;
      return next;
    });

  async function submit(e: FormEvent) {
    e.preventDefault();
    const found: Record<string, string> = {};
    if (!CODE.test(code)) found.code = 'Use letters, digits, ".", "_" or "-" (1–64 characters).';
    if (!name.trim()) found.name = 'Give the group a name.';
    triggers.forEach((t, i) => {
      if (!t.application || !t.formCode || !t.actionCode) found[`trigger${i}`] = 'A trigger needs an application, a form and an action.';
      if (t.type === 'FORM_FIELD' && !t.fieldCode) found[`trigger${i}`] = 'A field trigger needs the field code.';
    });
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    const request: GroupRequest = {
      moduleCode: module,
      code,
      name: name.trim(),
      description: description.trim() || undefined,
      policy,
      matchOn: policy === 'FIRST_MATCH' || policy === 'ALL_MATCH' ? matchOn : 'TRUE',
      onError,
      compositeTrueAction: trueAction,
      compositeFalseAction: falseAction,
      compositeTrueMessage: policy === 'COMPOSITE' ? cleanTexts(trueMessage) : undefined,
      compositeFalseMessage: policy === 'COMPOSITE' ? cleanTexts(falseMessage) : undefined,
      rules: members.map((m, i) => ({ ruleCode: m.ruleCode, sequence: (i + 1) * 10, enabled: m.enabled })),
      triggers: triggers.map(({ key: _key, ...t }) => ({ ...t, fieldCode: t.type === 'FORM_FIELD' ? t.fieldCode : undefined })),
      status,
      scope,
      expectedRowVersion: group?.rowVersion,
    };
    setBusy(true);
    try {
      const written = group ? await engine.replaceGroup(group.id, request) : await engine.createGroup(request);
      navigate(`/groups?open=${written.value.id}`, { state: { warnings: written.warnings, saved: written.value.name } });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'duplicate') {
        setErrors({ code: 'A group with this code already exists in this module and scope.' });
      } else {
        setFailure(err);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title={editing ? `Edit ${group.name}` : 'New rule group'}>
        A rule group bundles rules, says how their results combine, and which application events call it.
      </PageHeader>
      <form onSubmit={submit} noValidate>
        <ErrorNotice error={failure} />
        <Card title="Basics">
          <div className="form-grid">
            <Field id="group-module" label="Module" hint={editing ? 'A group keeps its module.' : undefined}>
              <select id="group-module" value={module} disabled={editing} onChange={(e) => { setModuleCode(e.target.value); setMembers([]); }}>
                {modules.data?.map((m) => (
                  <option key={m.code} value={m.code}>
                    {m.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="group-code" label="Code" hint={editing ? 'A group keeps its code.' : 'Applications refer to the group by module and code.'} error={errors.code}>
              <input id="group-code" type="text" value={code} readOnly={editing} onChange={(e) => setCode(e.target.value)} aria-invalid={!!errors.code} />
            </Field>
            <Field id="group-name" label="Name" error={errors.name}>
              <input id="group-name" type="text" value={name} onChange={(e) => setName(e.target.value)} aria-invalid={!!errors.name} />
            </Field>
          </div>
          <Field id="group-description" label="Description (optional)">
            <input id="group-description" type="text" value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        </Card>

        <Card title="How the rules combine">
          <div className="choice-grid" role="radiogroup" aria-label="Evaluation policy">
            {POLICIES.map((p) => (
              <label key={p.value} className="choice">
                <span className="check">
                  <input type="radio" name="policy" value={p.value} checked={policy === p.value} onChange={() => setPolicy(p.value)} />
                  <strong>{p.title}</strong>
                </span>
                <span>{p.text}</span>
              </label>
            ))}
          </div>
          <div className="form-grid" style={{ marginTop: 'var(--space-4)' }}>
            {(policy === 'FIRST_MATCH' || policy === 'ALL_MATCH') && (
              <Field id="group-match" label="Match on" hint="Which result counts as a match.">
                <select id="group-match" value={matchOn} onChange={(e) => setMatchOn(e.target.value as 'TRUE' | 'FALSE')}>
                  <option value="FALSE">A rule that fails (false)</option>
                  <option value="TRUE">A rule that holds (true)</option>
                </select>
              </Field>
            )}
            <Field id="group-on-error" label="If a rule cannot be evaluated" hint="A missing or invalid value is never silently skipped.">
              <select id="group-on-error" value={onError} onChange={(e) => setOnError(e.target.value as Action)}>
                <option value="BLOCK">Block (recommended)</option>
                <option value="WARN">Warn</option>
                <option value="ALLOW">Allow</option>
              </select>
            </Field>
          </div>
        </Card>

        <Card title="Rules" sub="They run in the order shown. Use the arrows to reorder.">
          <h3>In this group</h3>
          {members.length === 0 && <p className="hint">No rule yet: a group without rules always allows.</p>}
          <ol className="list-reset" aria-label="Rules in this group">
            {members.map((m, i) => {
              const r = byCode.get(m.ruleCode);
              return (
                <li key={m.ruleCode} className="rule-row">
                  <span className="badge">{(i + 1) * 10}</span>
                  <span>
                    <strong>{r?.name ?? m.ruleCode}</strong> <span className="hint mono">{m.ruleCode}</span>
                    {r && r.status !== 'ACTIVE' && <> <StatusBadge value={r.status} /></>}
                    {r && <div className="hint"><code>{r.expression}</code></div>}
                  </span>
                  <label className="check">
                    <input type="checkbox" checked={m.enabled} onChange={(e) => setMembers((l) => l.map((x, j) => (j === i ? { ...x, enabled: e.target.checked } : x)))} />
                    runs
                  </label>
                  <span className="actions">
                    <button type="button" className="btn btn-small" aria-label={`Move ${m.ruleCode} up`} disabled={i === 0} onClick={() => move(i, -1)}>↑</button>
                    <button type="button" className="btn btn-small" aria-label={`Move ${m.ruleCode} down`} disabled={i === members.length - 1} onClick={() => move(i, 1)}>↓</button>
                    <button type="button" className="btn btn-small btn-danger" aria-label={`Remove ${m.ruleCode}`} onClick={() => setMembers((l) => l.filter((_, j) => j !== i))}>Remove</button>
                  </span>
                </li>
              );
            })}
          </ol>
          <h3 style={{ marginTop: 'var(--space-4)' }}>Available in {module || 'the module'}</h3>
          <Async load={rules} what="Loading rules">
            {() =>
              candidates.length === 0 ? (
                <p className="hint">
                  {members.length > 0 ? 'Every available rule is already in the group.' : 'No rule available yet.'}{' '}
                  <Link to="/rules/new">Create a rule</Link>
                </p>
              ) : (
                <ul className="list-reset">
                  {candidates.map((r) => (
                    <li key={r.id} className="rule-row" style={{ gridTemplateColumns: '1fr auto auto auto' }}>
                      <span>
                        <strong>{r.name}</strong> <span className="hint mono">{r.code}</span>
                        <div className="hint"><code>{r.expression}</code></div>
                      </span>
                      <StatusBadge value={r.status} />
                      <ScopeBadge value={r.scope} />
                      <button type="button" className="btn btn-small" onClick={() => setMembers((l) => [...l, { ruleCode: r.code, enabled: true }])}>
                        Add
                      </button>
                    </li>
                  ))}
                </ul>
              )
            }
          </Async>
        </Card>

        {policy === 'COMPOSITE' && (
          <Card title="Group message and action" sub="One answer for the whole group, in as many languages as you like.">
            <div className="form-grid">
              <Field id="group-true-action" label="When every rule is true">
                <select id="group-true-action" value={trueAction} onChange={(e) => setTrueAction(e.target.value as Action)}>
                  <option>ALLOW</option>
                  <option>WARN</option>
                  <option>BLOCK</option>
                </select>
              </Field>
              <Field id="group-false-action" label="When any rule is false">
                <select id="group-false-action" value={falseAction} onChange={(e) => setFalseAction(e.target.value as Action)}>
                  <option>BLOCK</option>
                  <option>WARN</option>
                  <option>ALLOW</option>
                </select>
              </Field>
            </div>
            <TextsEditor label="Message when every rule is true" value={trueMessage} onChange={setTrueMessage} />
            <TextsEditor label="Message when any rule is false" value={falseMessage} onChange={setFalseMessage} />
          </Card>
        )}

        <Card title="Trigger points" sub="Optional. They make an application form action (or a field change) call this group.">
          {triggers.map((t, i) => (
            <div key={t.key} className="panel" style={{ marginTop: 0, marginBottom: 'var(--space-3)' }}>
              <div className="form-grid">
                <Field id={`t${i}-app`} label="Application">
                  <input id={`t${i}-app`} type="text" value={t.application} onChange={(e) => setTriggers((l) => l.map((x) => (x.key === t.key ? { ...x, application: e.target.value } : x)))} />
                </Field>
                <Field id={`t${i}-type`} label="Fires on">
                  <select id={`t${i}-type`} value={t.type} onChange={(e) => setTriggers((l) => l.map((x) => (x.key === t.key ? { ...x, type: e.target.value as TriggerSpec['type'] } : x)))}>
                    <option value="FORM_ACTION">A form action (submit, approve …)</option>
                    <option value="FORM_FIELD">A field change</option>
                  </select>
                </Field>
                <Field id={`t${i}-form`} label="Form">
                  <input id={`t${i}-form`} type="text" value={t.formCode} onChange={(e) => setTriggers((l) => l.map((x) => (x.key === t.key ? { ...x, formCode: e.target.value } : x)))} />
                </Field>
                <Field id={`t${i}-action`} label="Action">
                  <input id={`t${i}-action`} type="text" value={t.actionCode} onChange={(e) => setTriggers((l) => l.map((x) => (x.key === t.key ? { ...x, actionCode: e.target.value } : x)))} />
                </Field>
                {t.type === 'FORM_FIELD' && (
                  <Field id={`t${i}-field`} label="Field">
                    <input id={`t${i}-field`} type="text" value={t.fieldCode ?? ''} onChange={(e) => setTriggers((l) => l.map((x) => (x.key === t.key ? { ...x, fieldCode: e.target.value } : x)))} />
                  </Field>
                )}
              </div>
              {errors[`trigger${i}`] && <p className="field-error" role="alert">{errors[`trigger${i}`]}</p>}
              <button type="button" className="btn btn-small btn-danger" onClick={() => setTriggers((l) => l.filter((x) => x.key !== t.key))}>
                Remove trigger
              </button>
            </div>
          ))}
          <button type="button" className="btn" onClick={() => setTriggers((l) => [...l, { key: Date.now(), application: '', type: 'FORM_ACTION', formCode: '', actionCode: '' }])}>
            + Add a trigger point
          </button>
        </Card>

        <Card title="Visibility and status">
          <div className="form-grid">
            <Field id="group-scope" label="Who can use this group" hint={session?.organizationId && !isAdmin ? 'Only an administrator can share a group with the whole tenant.' : undefined}>
              <select id="group-scope" value={scope} disabled={editing} onChange={(e) => setScope(e.target.value as Scope)}>
                {session?.organizationId && <option value="ORGANIZATION">My organization ({session.organizationName})</option>}
                {(isAdmin || !session?.organizationId) && <option value="TENANT">The whole tenant</option>}
              </select>
            </Field>
            <Field id="group-status" label="Status">
              <select id="group-status" value={status} onChange={(e) => setStatus(e.target.value as 'ACTIVE' | 'DRAFT')}>
                <option value="ACTIVE">Active — applications get its decisions</option>
                <option value="DRAFT">Draft — saved, never evaluated</option>
              </select>
            </Field>
          </div>
        </Card>

        <div className="actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? 'Saving…' : editing ? 'Save changes' : 'Create rule group'}
          </button>
          <Link className="btn" to="/groups">
            Cancel
          </Link>
        </div>
      </form>
    </>
  );
}
