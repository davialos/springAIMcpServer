import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, configureHttp, get, post, query } from '../src/api/http';
import { json } from './support';

afterEach(() => {
  vi.unstubAllGlobals();
  configureHttp(null, () => undefined);
});

describe('http layer', () => {
  it('sends the bearer token and a JSON body', async () => {
    const fetchMock = vi.fn(async () => json({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    configureHttp('tok', () => undefined);

    await post('/api/v1/rules', { a: 1 });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"a":1}');
  });

  it('signs the user out when the service says the token is no longer valid', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ code: 'unauthenticated', detail: 'a valid access token is required' }, 401)));
    const out = vi.fn();
    configureHttp('expired', out);

    await expect(get('/api/v1/setup')).rejects.toMatchObject({ status: 401, code: 'unauthenticated' });
    expect(out).toHaveBeenCalledOnce();
  });

  it('turns a problem document into an ApiError with its code and safe message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ code: 'invalid_expression', detail: 'unknown parameter customer.agee' }, 422)));

    const err = await get('/x').catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 422, code: 'invalid_expression', message: 'unknown parameter customer.agee' });
  });

  it('survives an answer that is not a problem document (a proxy error page)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' })));

    await expect(get('/x')).rejects.toMatchObject({ status: 502, code: 'http_502', message: 'Bad Gateway' });
  });

  it('reports an unreachable server as a network error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('failed to fetch'); }));

    await expect(get('/x')).rejects.toMatchObject({ status: 0, code: 'network' });
  });

  it('builds query strings from the values that are set', () => {
    expect(query({ a: 'x', b: '', c: undefined, d: false, e: true, f: 0, g: null })).toBe('?a=x&e=true&f=0');
    expect(query({})).toBe('');
    expect(query({ q: 'a b&c' })).toBe('?q=a+b%26c');
  });
});
