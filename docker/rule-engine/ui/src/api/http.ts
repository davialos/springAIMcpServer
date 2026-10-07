/** A refused request: the stable `code` and a message the service guarantees is safe to show. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type Unauthorized = () => void;

let bearer: string | null = null;
let onUnauthorized: Unauthorized = () => undefined;

/** The auth context installs the current token and what to do when the service says it is no longer valid. */
export function configureHttp(token: string | null, unauthorized: Unauthorized): void {
  bearer = token;
  onUnauthorized = unauthorized;
}

async function problem(res: Response): Promise<ApiError> {
  let code = 'http_' + res.status;
  let message = res.statusText || 'request failed';
  try {
    const body = (await res.json()) as { code?: string; detail?: string };
    code = body.code ?? code;
    message = body.detail ?? message;
  } catch {
    // not a problem document (a proxy error page, say): keep the status text
  }
  return new ApiError(res.status, code, message);
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (bearer) {
    headers.Authorization = `Bearer ${bearer}`;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'network', 'the server cannot be reached');
  }
  if (res.status === 401) {
    onUnauthorized(); // the token expired or was revoked: back to the login page
  }
  if (!res.ok) {
    throw await problem(res);
  }
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export const get = <T,>(path: string) => request<T>('GET', path);
export const post = <T,>(path: string, body: unknown) => request<T>('POST', path, body);
export const put = <T,>(path: string, body: unknown) => request<T>('PUT', path, body);
export const patch = <T,>(path: string, body: unknown) => request<T>('PATCH', path, body);

/** Query string from the defined, non-empty values only. */
export function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '' && v !== false) {
      q.set(k, String(v));
    }
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}
