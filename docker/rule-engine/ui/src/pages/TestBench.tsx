import { useMemo, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { engine } from '../api/engine';
import type { Attribute, Decision } from '../api/types';
import {
  ActionBadge,
  Async,
  Card,
  ErrorNotice,
  Field,
  OutcomeBadge,
  PageHeader,
  formatMillis,
  useLoad,
} from '../components/ui';

/** Converts what was typed into the JSON value of the parameter's type; blank means "not supplied". */
export function factValue(attribute: Attribute | undefined, text: string): unknown {
  const t = text.trim();
  if (t === '') {
    return undefined;
  }
  switch (attribute?.dataType) {
    case 'INT':
    case 'DOUBLE': {
      const n = Number(t);
      return Number.isNaN(n) ? t : n; // a non-number is sent as text and reported by the engine as INVALID_PARAMETER
    }
    case 'BOOL':
      return t === 'true';
    case 'LIST_STRING':
    case 'LIST_INT':
    case 'LIST_DOUBLE':
    case 'MAP':
    case 'ANY':
      try {
        return JSON.parse(t) as unknown;
      } catch {
        return t;
      }
    default:
      return t;
  }
}

/**
 * The test bench: pick a group, fill in the parameters its rules read, see what the engine decides. Nothing is sent to
 * anyone, and the values you type are neither stored nor logged (the log keeps decisions and counts only).
 */
export function TestBenchPage() {
  const [params, setParams] = useSearchParams();
  const groups = useLoad(() => engine.groups(undefined, 'ACTIVE'), []);
  const rules = useLoad(() => engine.rules(), []);
  const library = useLoad(engine.library, []);
  const setup = useLoad(engine.setup, []);
  const selected = params.get('group') ? `${params.get('module')}/${params.get('group')}` : '';
  const [values, setValues] = useState<Record<string, string>>({});
  const [languages, setLanguages] = useState<string[]>(['en']);
  const [result, setResult] = useState<Decision | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const group = groups.data?.find((g) => `${g.moduleCode}/${g.code}` === selected);
  const attributes = useMemo(() => {
    const map = new Map<string, Attribute>();
    library.data?.forEach((o) => o.attributes.forEach((a) => map.set(a.celName, a)));
    return map;
  }, [library.data]);
  const needed = useMemo(() => {
    if (!group || !rules.data) return [] as string[];
    const names = new Set<string>();
    for (const member of group.rules) {
      rules.data.find((r) => r.code === member.ruleCode && r.moduleCode === group.moduleCode)?.parameters.forEach((p) => names.add(p));
    }
    return [...names].sort();
  }, [group, rules.data]);

  function choose(value: string) {
    const [module, code] = value.split('/');
    setResult(null);
    setError(null);
    setParams(module && code ? { module, group: code } : {});
  }

  function fillSamples() {
    const next: Record<string, string> = {};
    needed.forEach((n) => {
      const sample = attributes.get(n)?.sampleValue;
      if (sample) next[n] = sample;
    });
    setValues(next);
  }

  async function run(e: FormEvent) {
    e.preventDefault();
    if (!group) return;
    const facts: Record<string, unknown> = {};
    needed.forEach((n) => {
      const v = factValue(attributes.get(n), values[n] ?? '');
      if (v !== undefined) facts[n] = v;
    });
    setBusy(true);
    setError(null);
    try {
      setResult(await engine.evaluate(group.moduleCode, group.code, facts, languages.length ? languages : ['en']));
    } catch (err) {
      setResult(null);
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Test bench">
        Ask the engine what it decides for values you supply. Nothing is e-mailed or called; the values you type are not stored.
      </PageHeader>
      <Async load={groups} what="Loading rule groups">
        {(list) => (
          <form onSubmit={run}>
            <Card title="1. Choose a group" sub="Only active groups can be evaluated.">
              <Field id="bench-group" label="Rule group">
                <select id="bench-group" value={selected} onChange={(e) => choose(e.target.value)}>
                  <option value="">Select…</option>
                  {list.map((g) => (
                    <option key={g.id} value={`${g.moduleCode}/${g.code}`}>
                      {g.moduleCode} · {g.name}
                    </option>
                  ))}
                </select>
              </Field>
            </Card>
            {group && (
              <Card title="2. Supply values" sub={`${group.name} reads ${needed.length} parameter(s). Leave one blank to see what a missing value does.`}>
                <div className="form-grid">
                  {needed.map((n) => {
                    const a = attributes.get(n);
                    return (
                      <Field key={n} id={`fact-${n}`} label={n} hint={a ? `${a.name} · ${a.dataType}` : undefined}>
                        {a?.dataType === 'BOOL' ? (
                          <select id={`fact-${n}`} value={values[n] ?? ''} onChange={(e) => setValues({ ...values, [n]: e.target.value })}>
                            <option value="">(not supplied)</option>
                            <option value="true">true</option>
                            <option value="false">false</option>
                          </select>
                        ) : (
                          <input
                            id={`fact-${n}`}
                            type="text"
                            inputMode={a?.dataType === 'INT' || a?.dataType === 'DOUBLE' ? 'decimal' : 'text'}
                            value={values[n] ?? ''}
                            onChange={(e) => setValues({ ...values, [n]: e.target.value })}
                          />
                        )}
                      </Field>
                    );
                  })}
                </div>
                <fieldset className="field" style={{ border: 0, padding: 0 }}>
                  <legend className="label">Answer in</legend>
                  <div className="actions">
                    {(setup.data?.languages ?? ['en']).map((l) => (
                      <label key={l} className="check">
                        <input
                          type="checkbox"
                          checked={languages.includes(l)}
                          onChange={(e) => setLanguages((cur) => (e.target.checked ? [...cur, l] : cur.filter((x) => x !== l)))}
                        />
                        {l}
                      </label>
                    ))}
                  </div>
                  <span className="hint">The first language with a text wins; the others are fallbacks, in this order.</span>
                </fieldset>
                <div className="actions">
                  <button className="btn btn-primary" type="submit" disabled={busy}>
                    {busy ? 'Evaluating…' : 'Evaluate'}
                  </button>
                  <button className="btn" type="button" onClick={fillSamples}>
                    Fill sample values
                  </button>
                </div>
              </Card>
            )}
          </form>
        )}
      </Async>
      <ErrorNotice error={error} />
      {result && <ResultPanel result={result} />}
    </>
  );
}

function ResultPanel({ result }: { result: Decision }) {
  return (
    <Card title="3. Decision">
      <div aria-live="polite">
        <p style={{ fontSize: 'var(--text-lg)' }}>
          <ActionBadge value={result.decision} />{' '}
          <span className="hint">
            {result.policy.replace('_', ' ').toLowerCase()} · {formatMillis(result.durationMicros)}
          </span>
        </p>
        {result.primaryMessage && (
          <div className={`notice ${result.decision === 'BLOCK' ? 'notice-error' : result.decision === 'WARN' ? 'notice-warn' : 'notice-ok'}`}>
            {result.primaryMessage.text} <span className="badge">{result.primaryMessage.language}</span>
          </div>
        )}
      </div>
      {result.messages.length > 0 && (
        <>
          <h3>Messages</h3>
          <ul>
            {result.messages.map((m, i) => (
              <li key={i}>
                <ActionBadge value={m.action} /> {m.text} <span className="badge">{m.language}</span>
                {m.ruleCode && <span className="hint mono"> {m.ruleCode}</span>}
              </li>
            ))}
          </ul>
        </>
      )}
      <h3>Rule by rule</h3>
      <div className="table-wrap">
        <table>
          <caption className="visually-hidden">Result of each rule</caption>
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
            {result.results.map((r) => (
              <tr key={r.ruleCode}>
                <td className="num">{r.sequence}</td>
                <td>
                  {r.ruleName} <span className="hint mono">{r.ruleCode}</span>
                </td>
                <td>
                  <OutcomeBadge value={r.outcome} />
                </td>
                <td>
                  <ActionBadge value={r.action} />
                </td>
                <td>{r.errorCode ? `${r.errorCode}${r.errorDetail ? ` — ${r.errorDetail}` : ''}` : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {result.channels.length > 0 && (
        <>
          <h3>Communications this decision would trigger</h3>
          <p className="hint">Described only: nothing was sent, and recipients are never shown.</p>
          <ul>
            {result.channels.map((c, i) => (
              <li key={i}>
                <span className="badge badge-info">{c.type}</span> when {c.onResult.toLowerCase()}
                {c.target ? ` → ${c.target}` : ''}
                {c.recipientResolved ? ' (recipient resolved from the values)' : ' (no recipient)'}
              </li>
            ))}
          </ul>
        </>
      )}
    </Card>
  );
}
