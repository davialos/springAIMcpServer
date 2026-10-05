import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/http';
import type { Action, Status } from '../api/types';

/** Decision / action badge with the meaning in text, not colour alone. */
export function ActionBadge({ value }: { value: Action | string }) {
  const kind = value === 'ALLOW' ? 'allow' : value === 'WARN' ? 'warn' : value === 'BLOCK' ? 'block' : 'info';
  return <span className={`badge badge-${kind}`}>{value}</span>;
}

export function StatusBadge({ value }: { value: Status | string }) {
  const kind = value === 'ACTIVE' ? 'allow' : value === 'DRAFT' ? 'warn' : 'info';
  return <span className={`badge badge-${kind}`}>{value}</span>;
}

export function OutcomeBadge({ value }: { value: 'TRUE' | 'FALSE' | 'ERROR' | string }) {
  const kind = value === 'TRUE' ? 'allow' : value === 'FALSE' ? 'warn' : 'block';
  return <span className={`badge badge-${kind}`}>{value}</span>;
}

export function ScopeBadge({ value }: { value: string }) {
  return <span className="badge">{value === 'TENANT' ? 'whole tenant' : 'my organization'}</span>;
}

export function PageHeader({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {children && <p>{children}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  );
}

export function Card({ title, sub, children }: { title?: string; sub?: string; children: ReactNode }) {
  return (
    <section className="card">
      {title && <h2>{title}</h2>}
      {sub && <p className="card-sub">{sub}</p>}
      {children}
    </section>
  );
}

export function Stat({ label, value, note }: { label: string; value: ReactNode; note?: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}

/** The message of a failed request, announced to assistive technology. */
export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) {
    return null;
  }
  const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : 'Something went wrong';
  const code = error instanceof ApiError ? error.code : null;
  return (
    <div className="notice notice-error" role="alert">
      {message}
      {code && code !== 'invalid_request' && <span className="hint"> ({code})</span>}
    </div>
  );
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return (
    <p role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" /> <span>{what}…</span>
    </p>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

export function Field(props: { id: string; label: string; hint?: string; error?: string | null; children: ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={props.id}>{props.label}</label>
      {props.children}
      {props.hint && <span className="hint">{props.hint}</span>}
      {props.error && (
        <span className="field-error" role="alert">
          {props.error}
        </span>
      )}
    </div>
  );
}

export interface Loaded<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

/** Runs a loader when its dependencies change; ignores answers that arrive after a newer request or an unmount. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]): Loaded<T> {
  const [state, setState] = useState<{ data: T | null; error: unknown; loading: boolean }>({
    data: null,
    error: null,
    loading: true,
  });
  const [tick, setTick] = useState(0);
  const latest = useRef(0);
  const run = useRef(loader);
  run.current = loader;

  useEffect(() => {
    const mine = ++latest.current;
    setState((s) => ({ ...s, loading: true, error: null }));
    run
      .current()
      .then((data) => latest.current === mine && setState({ data, error: null, loading: false }))
      .catch((error: unknown) => latest.current === mine && setState({ data: null, error, loading: false }));
    return () => {
      latest.current++;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ...state, reload };
}

/** Renders a loader's three states around its result. */
export function Async<T>({ load, what, children }: { load: Loaded<T>; what?: string; children: (data: T) => ReactNode }) {
  if (load.loading && !load.data) {
    return <Loading what={what} />;
  }
  if (load.error) {
    return <ErrorNotice error={load.error} />;
  }
  return load.data ? <>{children(load.data)}</> : null;
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
}

export function formatMillis(micros: number): string {
  const ms = micros / 1000;
  return ms < 10 ? ms.toFixed(2) + ' ms' : ms.toFixed(1) + ' ms';
}

export function Pager(props: { page: number; size: number; total: number; onPage: (page: number) => void }) {
  const last = Math.max(0, Math.ceil(props.total / props.size) - 1);
  return (
    <nav className="pager" aria-label="Pagination">
      <span>
        {props.total === 0 ? 'No results' : `Page ${props.page + 1} of ${last + 1} · ${props.total} results`}
      </span>
      <button className="btn btn-small" disabled={props.page <= 0} onClick={() => props.onPage(props.page - 1)}>
        Previous
      </button>
      <button className="btn btn-small" disabled={props.page >= last} onClick={() => props.onPage(props.page + 1)}>
        Next
      </button>
    </nav>
  );
}
