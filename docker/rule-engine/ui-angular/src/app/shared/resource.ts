import { signal, type Signal } from '@angular/core';
import { ApiError, toApiError } from '../core/api.service';

/** An asynchronous value for a template: `data()`, `error()`, `loading()`; `reload()` runs the loader again. */
export interface Loaded<T> {
  readonly data: Signal<T | null>;
  readonly error: Signal<ApiError | null>;
  readonly loading: Signal<boolean>;
  reload(): void;
}

/**
 * Runs {@code loader} now and on every {@code reload()}; an answer that arrives after a newer request started is ignored,
 * so a slow earlier call never overwrites a newer result.
 */
export function load<T>(loader: () => Promise<T>): Loaded<T> {
  const data = signal<T | null>(null);
  const error = signal<ApiError | null>(null);
  const loading = signal(true);
  let latest = 0;
  const run = () => {
    const mine = ++latest;
    loading.set(true);
    error.set(null);
    loader().then(
      (value) => {
        if (mine === latest) {
          data.set(value);
          loading.set(false);
        }
      },
      (e: unknown) => {
        if (mine === latest) {
          error.set(toApiError(e));
          loading.set(false);
        }
      },
    );
  };
  run();
  return { data: data.asReadonly(), error: error.asReadonly(), loading: loading.asReadonly(), reload: run };
}
