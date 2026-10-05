import { useId } from 'react';
import type { Texts } from '../api/types';

const COMMON: Array<[string, string]> = [
  ['en', 'English'],
  ['hi', 'Hindi'],
  ['th', 'Thai'],
  ['pt-BR', 'Portuguese (Brazil)'],
  ['es', 'Spanish'],
  ['fr', 'French'],
  ['de', 'German'],
];

/**
 * One message in several languages: a row per language (tag + text). Empty rows are dropped by {@link cleanTexts}; the
 * service validates tags and length again, this is only for the experience.
 */
export function TextsEditor(props: { label: string; value: Texts; onChange: (v: Texts) => void; hint?: string }) {
  const id = useId();
  const rows = Object.entries(props.value);
  const set = (next: Array<[string, string]>) => props.onChange(Object.fromEntries(next));
  const unused = COMMON.filter(([tag]) => !(tag in props.value));
  return (
    <fieldset className="field" style={{ border: 0, padding: 0, margin: '0 0 var(--space-4)' }}>
      <legend className="label" style={{ fontSize: 'var(--text-sm)', fontWeight: 'var(--weight-medium)' }}>
        {props.label}
      </legend>
      {rows.length === 0 && <p className="hint">No message: this outcome stays silent.</p>}
      {rows.map(([tag, text], i) => (
        <div key={tag} style={{ display: 'grid', gridTemplateColumns: '6rem 1fr auto', gap: 'var(--space-2)' }}>
          <input
            type="text"
            aria-label={`${props.label}: language ${i + 1}`}
            value={tag}
            onChange={(e) => {
              const copy: Array<[string, string]> = rows.map((r) => [r[0], r[1]]);
              copy[i] = [e.target.value.trim(), text];
              set(copy);
            }}
          />
          <input
            type="text"
            aria-label={`${props.label}: text (${tag})`}
            value={text}
            onChange={(e) => set(rows.map(([t, x], j) => (j === i ? [t, e.target.value] : [t, x])))}
          />
          <button
            type="button"
            className="btn btn-small btn-danger"
            aria-label={`Remove ${tag} message`}
            onClick={() => set(rows.filter((_, j) => j !== i))}
          >
            Remove
          </button>
        </div>
      ))}
      <div className="actions" style={{ marginTop: 'var(--space-2)' }}>
        {unused.slice(0, 3).map(([tag, name]) => (
          <button
            key={tag}
            type="button"
            className="btn btn-small"
            id={`${id}-${tag}`}
            onClick={() => set([...rows, [tag, '']])}
          >
            + {name}
          </button>
        ))}
      </div>
      {props.hint && <span className="hint">{props.hint}</span>}
    </fieldset>
  );
}

/** Drops blank rows (a language without text) before sending. */
export function cleanTexts(texts: Texts): Texts | undefined {
  const entries = Object.entries(texts)
    .map(([k, v]) => [k.trim(), v.trim()] as const)
    .filter(([k, v]) => k && v);
  return entries.length ? Object.fromEntries(entries) : undefined;
}
