// Load modes → k6 options. Profiles (executor, stages, VUs, thresholds) live in loadtest.config.json → modes;
// this file only turns a profile into scenarios. MODE env selects one of:
//   smoke | load | stress | spike | soak | breakpoint           each API on its own (sequential scenarios)
//   mixed-smoke | mixed-load | mixed-stress | mixed-spike | …    all APIs together, weighted traffic mix
//   journey-smoke | journey-load | journey-spike | …            replay the recorded browser flow (data/journey.json)
//   lifecycle-smoke | lifecycle-load | …                          walk each resource through create → read → update → status → delete (data/lifecycle.json)
//   session-load | …                                               walk sessions with the endpoint-to-endpoint transitions seen in production logs (data/traffic.json)
//   channels-smoke | channels-load | …                           WebSocket, STOMP and SSE endpoints (data/channels.json)
//   preview | journey-preview                                   build requests and print them, send nothing
// Scaling without editing config: VUS (base VUs), RATE (base arrival rate/s), ITERATIONS (per VU, smoke), DURATION_SCALE (e.g. 0.1),
// API=getUser,createOrder (restrict APIs), PER_API=parallel (per-API scenarios at once instead of in turn).
// MODEL=open turns closed (VU) profiles into arrival-rate ones (RATE = requests/s at multiplier 1); WARMUP=60s adds a warm-up
// phase (JIT, caches, pools) whose requests are tagged apart and excluded from thresholds and reports (WARMUP=off disables).

export function parseMode(raw) {
  const mode = (raw || 'smoke').toLowerCase();
  if (mode === 'preview') return { mode, profile: 'preview', mixed: false, journey: false };
  if (mode === 'journey-preview') return { mode, profile: 'preview', mixed: false, journey: true };
  if (mode === 'lifecycle-preview') return { mode, profile: 'preview', mixed: false, journey: false, lifecycle: true };
  const mixed = mode.startsWith('mixed-');
  const journey = mode.startsWith('journey-');
  const lifecycle = mode.startsWith('lifecycle-');
  const channels = mode.startsWith('channels-');
  const session = mode.startsWith('session-');
  return { mode, profile: mixed ? mode.slice(6) : journey ? mode.slice(8) : lifecycle ? mode.slice(10) : channels ? mode.slice(9) : session ? mode.slice(8) : mode, mixed,
    journey, lifecycle, channels, session };
}

const UNITS = { ms: 0.001, s: 1, m: 60, h: 3600, d: 86400 };

export function seconds(duration) {
  if (typeof duration === 'number') return duration;
  let total = 0;
  const re = /(\d+(?:\.\d+)?)(ms|s|m|h|d)/g;
  let m;
  while ((m = re.exec(String(duration))) !== null) total += parseFloat(m[1]) * UNITS[m[2]];
  return total;
}

export function formatDuration(totalSeconds) {
  const s = Math.max(1, Math.round(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return `${h ? h + 'h' : ''}${m ? m + 'm' : ''}${r || (!h && !m) ? r + 's' : ''}`;
}

function scaled(duration) {
  const factor = __ENV.DURATION_SCALE ? parseFloat(__ENV.DURATION_SCALE) : 1;
  return formatDuration(seconds(duration) * factor);
}

function envNumber(name, fallback) {
  const v = __ENV[name];
  return v !== undefined && v !== '' ? parseFloat(v) : fallback;
}

/** Whether closed (VU) profiles run as arrival-rate (open model) ones: MODEL=open, profile.model or config.model. */
function openModel(config, profile) {
  return (__ENV.MODEL || profile.model || config.model || 'closed') === 'open';
}

/**
 * The open-model form of a closed profile: stage targets stay multipliers, now of the base rate (requests/s) instead
 * of the base VUs. Users arrive on schedule whatever the response time — the way production traffic does — so a slow
 * service builds up in-flight requests (and drops iterations when the VUs run out) instead of slowing the load down.
 */
function asOpen(profile) {
  if (profile.executor === 'ramping-vus') {
    return Object.assign({}, profile, { executor: 'ramping-arrival-rate', baseRate: profile.baseRate || profile.baseVus || 10,
      preAllocatedVUs: profile.preAllocatedVUs || Math.max(10, 2 * (profile.baseVus || 10)), maxVUs: profile.maxVUs || 10 * (profile.baseVus || 10) + 50 });
  }
  if (profile.executor === 'constant-vus') {
    return Object.assign({}, profile, { executor: 'constant-arrival-rate', baseRate: profile.baseRate || profile.vus || 10,
      preAllocatedVUs: profile.preAllocatedVUs || Math.max(10, 2 * (profile.vus || 10)), maxVUs: profile.maxVUs || 10 * (profile.vus || 10) + 50 });
  }
  return profile;
}

/**
 * The warm-up phase of a profile — { seconds, fraction, readOnly } — or null. profile.warmup / config.warmup:
 * { duration: "60s", fraction: 0.3 (of base VUs / rate), readOnly: false }; WARMUP=<duration> | off overrides.
 */
export function warmupFor(config, profileName) {
  const profile = (config.modes || {})[profileName] || {};
  if (profile.executor === 'per-vu-iterations') return null; // smoke: a handful of iterations, nothing to warm
  const cfg = Object.assign({}, config.warmup || {}, profile.warmup || {});
  const raw = __ENV.WARMUP !== undefined ? __ENV.WARMUP : cfg.duration;
  if (!raw || raw === 'off' || raw === '0' || raw === 'false') return null;
  const secs = seconds(raw) * (__ENV.DURATION_SCALE ? parseFloat(__ENV.DURATION_SCALE) : 1);
  return secs > 0 ? { seconds: secs, fraction: cfg.fraction || 0.3, readOnly: cfg.readOnly === true } : null;
}

/** One scenario for a profile; `share` divides VUs/rate (parallel per-API runs), `multiplier` scales iterations. */
function scenario(profile, exec, startTime, multiplier) {
  const base = envNumber('VUS', profile.baseVus || profile.vus || 1);
  const rate = envNumber('RATE', profile.baseRate || 10);
  const s = { executor: profile.executor, exec };
  if (startTime) s.startTime = startTime;
  switch (profile.executor) {
    case 'per-vu-iterations':
      s.vus = Math.max(1, Math.round(base));
      s.iterations = envNumber('ITERATIONS', profile.iterations || 1) * (multiplier || 1); // per VU
      s.maxDuration = scaled(profile.maxDuration || '10m');
      break;
    case 'constant-vus':
      s.vus = Math.max(1, Math.round(base));
      s.duration = scaled(profile.duration || '1m');
      break;
    case 'ramping-vus':
      s.startVUs = profile.startVUs || 0;
      s.stages = profile.stages.map((st) => ({ duration: scaled(st.duration), target: Math.round(st.target * base) }));
      s.gracefulRampDown = profile.gracefulRampDown || '10s';
      break;
    case 'constant-arrival-rate':
      s.rate = Math.max(1, Math.round(rate));
      s.timeUnit = profile.timeUnit || '1s';
      s.duration = scaled(profile.duration || '1m');
      s.preAllocatedVUs = profile.preAllocatedVUs || 10;
      s.maxVUs = profile.maxVUs || 200;
      break;
    case 'ramping-arrival-rate':
      s.startRate = profile.startRate || 1;
      s.timeUnit = profile.timeUnit || '1s';
      s.stages = profile.stages.map((st) => ({ duration: scaled(st.duration), target: Math.max(1, Math.round(st.target * rate)) }));
      s.preAllocatedVUs = profile.preAllocatedVUs || 10;
      s.maxVUs = profile.maxVUs || 500;
      break;
    default:
      throw new Error(`unsupported executor ${profile.executor}`);
  }
  return s;
}

/** Wall-clock length of a scenario (for sequential per-API scheduling). */
function scenarioSeconds(s) {
  if (s.stages) return s.stages.reduce((t, st) => t + seconds(st.duration), 0) + seconds(s.gracefulRampDown || '0s');
  if (s.duration) return seconds(s.duration);
  return seconds(s.maxDuration || '1m');
}

function warmupScenario(profile, warm) {
  const base = envNumber('VUS', profile.baseVus || profile.vus || 1);
  const rate = envNumber('RATE', profile.baseRate || 10);
  const open = profile.executor.indexOf('arrival-rate') >= 0;
  const s = { exec: 'warmup', gracefulStop: '10s' };
  if (open) {
    Object.assign(s, { executor: 'constant-arrival-rate', rate: Math.max(1, Math.round(rate * warm.fraction)), timeUnit: profile.timeUnit || '1s',
      preAllocatedVUs: profile.preAllocatedVUs || 10, maxVUs: profile.maxVUs || 200 });
  } else {
    Object.assign(s, { executor: 'constant-vus', vus: Math.max(1, Math.round(base * warm.fraction)) });
  }
  s.duration = formatDuration(warm.seconds);
  return s;
}

function shiftStart(s, extraSeconds) {
  s.startTime = formatDuration(seconds(s.startTime || '0s') + extraSeconds);
  return s;
}

function thresholds(config, profile, runtime, mixed) {
  const t = {};
  const base = Object.assign({}, config.thresholds || {}, profile.thresholds || {});
  const baseKeys = [];
  for (const [metric, rules] of Object.entries(base)) {
    // with a warm-up, the run-wide latency and error thresholds only count requests tagged phase:measure
    const key = runtime.warmup && (metric === 'http_req_duration' || metric === 'http_req_failed') ? `${metric}{phase:measure}` : metric;
    t[key] = rules.slice();
    baseKeys.push(key);
  }
  const defaults = config.defaults || {};
  for (const api of runtime.apis) {
    const c = (config.apis || {})[api.id] || {};
    const p95 = profile.thresholds && profile.thresholds.http_req_duration ? null : c.p95Ms || defaults.p95Ms;
    t[`http_req_duration{api:${api.id}}`] = p95 ? [`p(95)<${p95}`] : ['max>=0'];
    const errorRate = profile.maxErrorRate !== undefined ? profile.maxErrorRate : c.maxErrorRate !== undefined ? c.maxErrorRate : defaults.maxErrorRate;
    t[`http_req_failed{api:${api.id}}`] = errorRate !== undefined ? [`rate<${errorRate}`] : ['rate>=0'];
    t[`http_reqs{api:${api.id}}`] = ['count>=0']; // keeps a per-API request count in the summary
  }
  const v = runtime.validation || {};
  if (v.mode === 'check') t.response_schema_violations = [`count<=${v.maxViolations || 0}`];
  if (v.readAfterWrite) t.read_after_write_mismatches = [`count<=${v.maxMismatches || 0}`];
  if (runtime.open && !profile.allowDropped) t.dropped_iterations = ['count<=0']; // the arrival rate could not be kept up
  if (profile.abortOnFail) {
    for (const metric of baseKeys) {
      t[metric] = t[metric].map((r) => ({ threshold: r, abortOnFail: true, delayAbortEval: profile.delayAbortEval || '30s' }));
    }
  }
  return t;
}

/** k6 options for the selected mode. */
export function buildOptions(config, runtime, journeySteps, lifecycleFlows, channelList, sessionModel) {
  const { mode, profile: profileName, mixed, journey, lifecycle, channels, session } = parseMode(__ENV.MODE);
  const common = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    insecureSkipTLSVerify: config.http && config.http.insecureSkipTLSVerify === true,
    userAgent: `k6-loadtest/${config.project || 'suite'}`,
  };
  if (profileName === 'preview') {
    const exec = lifecycle ? 'previewLifecycle' : journey ? 'previewJourney' : 'preview';
    return Object.assign(common, { scenarios: { preview: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec } } });
  }
  let profile = (config.modes || {})[profileName];
  if (profile && openModel(config, profile)) profile = asOpen(profile);
  if (runtime) runtime.open = !!profile && profile.executor.indexOf('arrival-rate') >= 0;
  if (!profile) {
    const known = Object.keys(config.modes || {});
    throw new Error(`MODE=${mode}: unknown profile "${profileName}". Use one of ${known.join(', ')} (or mixed-<profile>, preview)`);
  }
  if (channels) {
    if (!channelList) throw new Error(`MODE=${mode}: data/channels.json is empty — the project has no WebSocket, STOMP, SSE or Kafka endpoint`);
    return Object.assign(common, { scenarios: { [`channels_${profileName}`]: scenario(profile, 'channels', null, 1) },
      thresholds: { checks: ['rate>0.95'] } });
  }
  if (!runtime.apis.length) throw new Error('No API is enabled (check loadtest.config.json → apis, API and READ_ONLY)');
  const scenarios = {};
  if (lifecycle) {
    if (!lifecycleFlows) throw new Error(`MODE=${mode}: data/lifecycle.json has no flow — the project needs a create endpoint plus a read, update or delete endpoint of the same resource`);
    scenarios[`lifecycle_${profileName}`] = scenario(profile, 'lifecycle', null, 1);
  } else if (session) {
    if (!sessionModel) throw new Error(`MODE=${mode}: data/traffic.json has no sessions — import production access logs with: loadtest traffic --suite . --access-log <file>`);
    scenarios[`session_${profileName}`] = scenario(profile, 'session', null, 1);
  } else if (journey) {
    if (!journeySteps) throw new Error(`MODE=${mode}: data/journey.json is empty — generate with --har <recording.har>`);
    scenarios[`journey_${profileName}`] = scenario(profile, 'journey', null, 1);
  } else if (mixed) {
    scenarios[`mixed_${profileName}`] = scenario(profile, 'mixed', null, runtime.apis.length);
  } else if (profile.executor === 'per-vu-iterations') {
    // Iteration-based profiles (smoke): each iteration calls every API once, in order.
    scenarios[`${profileName}_all`] = scenario(profile, 'all', null, 1);
  } else {
    const parallel = (__ENV.PER_API || (config.perApi && config.perApi.schedule) || 'sequential') === 'parallel';
    const gap = seconds((config.perApi && config.perApi.gap) || '5s');
    let offset = 0;
    for (const api of runtime.apis) {
      const s = scenario(profile, `api_${api.id}`, parallel || offset === 0 ? null : formatDuration(offset), 1);
      scenarios[`${profileName}_${api.id}`] = s;
      offset += scenarioSeconds(s) + gap;
    }
  }
  const warm = runtime.warmup;
  if (warm) {
    for (const name of Object.keys(scenarios)) shiftStart(scenarios[name], warm.seconds + 5);
    scenarios.warmup = warmupScenario(profile, warm);
  }
  return Object.assign(common, { scenarios, thresholds: thresholds(config, profile, runtime, mixed) });
}

/** Weighted API choice for mixed traffic. */
export function pickWeighted(apis) {
  const total = apis.reduce((s, a) => s + a.weight, 0);
  let r = Math.random() * total;
  for (const a of apis) {
    r -= a.weight;
    if (r < 0) return a;
  }
  return apis[apis.length - 1];
}
