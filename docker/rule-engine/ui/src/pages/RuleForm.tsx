import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { engine } from '../api/engine';
import { ApiError } from '../api/http';
import type { Action, CreateRuleRequest, Scope, Texts } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { TextsEditor, cleanTexts } from '../components/TextsEditor';
import { Async, Card, ErrorNotice, Field, PageHeader, useLoad } from '../components/ui';

const CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

/** Creates a rule. The expression is compiled against the parameter library by the service; its message is shown here. */
export function RuleFormPage() {
  const { session, isAdmin } = useAuth();
  const navigate = useNavigate();
  const modules = useLoad(engine.modules, []);
  const library = useLoad(engine.library, []);
  const [moduleCode, setModuleCode] = useState('');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [expression, setExpression] = useState('');
  const [trueAction, setTrueAction] = useState<Action>('ALLOW');
  const [falseAction, setFalseAction] = useState<Action>('BLOCK');
  const [trueMessage, setTrueMessage] = useState<Texts>({ en: '' });
  const [falseMessage, setFalseMessage] = useState<Texts>({ en: '' });
  const [status, setStatus] = useState<'ACTIVE' | 'DRAFT'>('ACTIVE');
  const [scope, setScope] = useState<Scope>(session?.organizationId ? 'ORGANIZATION' : 'TENANT');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const module = moduleCode || modules.data?.[0]?.code || '';

  async function submit(e: FormEvent) {
    e.preventDefault();
    const found: Record<string, string> = {};
    if (!CODE.test(code)) found.code = 'Use letters, digits, ".", "_" or "-" (1–64 characters).';
    if (!name.trim()) found.name = 'Give the rule a name.';
    if (!expression.trim()) found.expression = 'Write the CEL expression.';
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length) return;
    const request: CreateRuleRequest = {
      moduleCode: module,
      code,
      name: name.trim(),
      description: description.trim() || undefined,
      expression: expression.trim(),
      trueMessage: cleanTexts(trueMessage),
      falseMessage: cleanTexts(falseMessage),
      trueAction,
      falseAction,
      status,
      scope,
    };
    setBusy(true);
    try {
      await engine.createRule(request);
      navigate('/rules');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'invalid_expression') {
        setErrors({ expression: err.message }); // the compiler's message, shown on the field it is about
      } else if (err instanceof ApiError && err.code === 'duplicate') {
        setErrors({ code: 'A rule with this code already exists in this module and scope.' });
      } else {
        setFailure(err);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="New rule">
        The expression must be a true/false CEL expression over the parameters of the library, for example{' '}
        <code>customer.age &gt;= 18 &amp;&amp; loan.amount &lt;= 500000.0</code>.
      </PageHeader>
      <form onSubmit={submit} noValidate>
        <ErrorNotice error={failure} />
        <Card title="Identity">
          <div className="form-grid">
            <Field id="rule-module" label="Module">
              <select id="rule-module" value={module} onChange={(e) => setModuleCode(e.target.value)}>
                {modules.data?.map((m) => (
                  <option key={m.code} value={m.code}>
                    {m.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="rule-code" label="Code" hint="Unique inside the module. Used by groups to refer to the rule." error={errors.code}>
              <input id="rule-code" type="text" value={code} onChange={(e) => setCode(e.target.value)} aria-invalid={!!errors.code} />
            </Field>
            <Field id="rule-name" label="Name" error={errors.name}>
              <input id="rule-name" type="text" value={name} onChange={(e) => setName(e.target.value)} aria-invalid={!!errors.name} />
            </Field>
          </div>
          <Field id="rule-description" label="Description (optional)">
            <input id="rule-description" type="text" value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        </Card>
        <Card title="Expression" sub="Click a parameter to insert it.">
          <Field id="rule-expression" label="CEL expression" error={errors.expression}>
            <textarea
              id="rule-expression"
              className="code"
              value={expression}
              onChange={(e) => setExpression(e.target.value)}
              aria-invalid={!!errors.expression}
              spellCheck={false}
            />
          </Field>
          <Async load={library}>
            {(objects) => (
              <div className="chips" role="group" aria-label="Insert a parameter">
                {objects.flatMap((o) =>
                  o.attributes.map((a) => (
                    <button
                      key={a.celName}
                      type="button"
                      className="chip"
                      title={`${a.name} (${a.dataType})`}
                      onClick={() => setExpression((x) => (x ? x.replace(/\s*$/, ' ') : '') + a.celName)}
                    >
                      {a.celName}
                    </button>
                  )),
                )}
              </div>
            )}
          </Async>
        </Card>
        <Card title="Outcomes" sub="What the rule says and does when it is true or false. A blank message keeps that outcome silent.">
          <div className="form-grid">
            <Field id="true-action" label="If true">
              <select id="true-action" value={trueAction} onChange={(e) => setTrueAction(e.target.value as Action)}>
                <option>ALLOW</option>
                <option>WARN</option>
                <option>BLOCK</option>
              </select>
            </Field>
            <Field id="false-action" label="If false">
              <select id="false-action" value={falseAction} onChange={(e) => setFalseAction(e.target.value as Action)}>
                <option>ALLOW</option>
                <option>WARN</option>
                <option>BLOCK</option>
              </select>
            </Field>
          </div>
          <TextsEditor label="Message when true" value={trueMessage} onChange={setTrueMessage} />
          <TextsEditor label="Message when false" value={falseMessage} onChange={setFalseMessage} />
        </Card>
        <Card title="Visibility">
          <div className="form-grid">
            <Field
              id="rule-scope"
              label="Who can use this rule"
              hint={
                session?.organizationId && !isAdmin
                  ? 'Only an administrator can share a rule with the whole tenant.'
                  : 'Shared rules can be used by every organization of the tenant.'
              }
            >
              <select id="rule-scope" value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
                {session?.organizationId && <option value="ORGANIZATION">My organization ({session.organizationName})</option>}
                {(isAdmin || !session?.organizationId) && <option value="TENANT">The whole tenant</option>}
              </select>
            </Field>
            <Field id="rule-status" label="Start as">
              <select id="rule-status" value={status} onChange={(e) => setStatus(e.target.value as 'ACTIVE' | 'DRAFT')}>
                <option value="ACTIVE">Active — runs as soon as a group uses it</option>
                <option value="DRAFT">Draft — saved but never runs</option>
              </select>
            </Field>
          </div>
        </Card>
        <div className="actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? 'Saving…' : 'Save rule'}
          </button>
          <Link className="btn" to="/rules">
            Cancel
          </Link>
        </div>
      </form>
    </>
  );
}
