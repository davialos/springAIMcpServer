// Runs a scenario from the dashboard with the very same flow runtime the generated k6 suite uses (../runtime.js).
// DOM-free: the dashboard passes `prepare` and `send` (both talk to the local server), tests pass fakes.
import { createRuntime } from './runtime.js';

/** k6-like `http` over the dashboard's /api/send proxy: browsers cannot call the service directly (CORS). */
export function httpAdapter(send) {
  return {
    async request(method, url, body, params) {
      const r = await send({ method, url, headers: params?.headers ?? {}, body: body === null || body === undefined ? undefined : JSON.parse(body) });
      if (r.error) throw new Error(r.error);
      const text = r.body ?? '';
      return { status: r.status, headers: r.headers ?? {}, body: text, millis: r.millis, json: () => JSON.parse(text) };
    },
  };
}

/**
 * @param {object} o
 * @param {object} o.contract    the API contract
 * @param {object} o.workflow    the scenario (Workflow JSON)
 * @param {Function} o.prepare   (contract, workflow, seed, count) => { problems, workflow, apis, valid, invalid, baseUrl }
 * @param {Function} o.send      request proxy: ({method,url,headers,body}) => { status, headers, body, millis } | { error }
 * @param {object} [o.env]       values for {{env.NAME}}
 * @param {number} [o.iterations] how many times to run the whole scenario (each uses the next generated data row)
 * @param {number} [o.seed]
 * @param {Function} [o.onEvent] trace events of the runtime, plus { phase: 'iteration', iter, ok }
 * @returns {Promise<{problems: string[], iterations: number, passed: number, failed: number}>}
 */
export async function runScenario({ contract, workflow, prepare, send, env = {}, iterations = 1, seed = 42, onEvent = () => {} }) {
  const prepared = await prepare(contract, workflow, seed, Math.max(20, iterations));
  if (prepared.problems?.length) return { problems: prepared.problems, iterations: 0, passed: 0, failed: 0 };
  const runtime = createRuntime({
    http: httpAdapter(send),
    check: (res, spec) => Object.values(spec).every((fn) => { try { return Boolean(fn(res)); } catch (e) { return false; } }),
    sleep: (s) => new Promise((r) => setTimeout(r, Math.min(s * 1000, 300))),
    workflow: prepared.workflow, apis: prepared.apis, valid: prepared.valid, invalid: prepared.invalid,
    baseUrl: String(prepared.baseUrl).replace(/\/$/, ''), env, log: () => {}, trace: (e) => onEvent({ ...e, iter: current }),
  });
  let passed = 0;
  let current = 0;
  for (let i = 0; i < iterations; i++) {
    current = i;
    const ok = await runtime.runFlow({ vu: 1, iter: i });
    onEvent({ phase: 'iteration', iter: i, ok });
    if (ok) passed++;
  }
  return { problems: [], iterations, passed, failed: iterations - passed };
}
