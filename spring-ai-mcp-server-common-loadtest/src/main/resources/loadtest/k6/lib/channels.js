// WebSocket, STOMP-over-WebSocket and Server-Sent Events endpoints (data/channels.json, tunables in
// loadtest.config.json → channels). One iteration of MODE=channels-<profile> exercises every enabled channel once:
// ws/stomp: connect, send `messages` messages `intervalMs` apart, listen, close after `hold`; sse: read the stream for
// `hold` and count events. Kafka needs the xk6-kafka extension and runs from kafka.js (see README).
import http from 'k6/http';
import { check } from 'k6';
import { seconds } from './modes.js';
import { sessionFor } from './auth.js';

// k6 ships WebSockets as k6/websockets (newer releases) or k6/experimental/websockets; the suite must still load on a k6
// that has neither (every other mode works), so the module is looked up here, in the init context, without failing.
let WebSocket;
for (const name of ['k6/websockets', 'k6/experimental/websockets']) {
  try {
    WebSocket = require(name).WebSocket;
    break;
  } catch (e) {
    // try the next name
  }
}

function requireWebSocket() {
  if (!WebSocket) throw new Error('this k6 has no WebSocket module (k6/websockets); use k6 v0.52 or newer');
}

function wsUrl(runtime, path) {
  return `${runtime.baseUrl.replace(/^http/i, 'ws')}${path}`;
}

function holdMs(cfg, fallback) {
  return Math.round(seconds(cfg.hold || fallback) * 1000);
}

function payload(cfg) {
  const m = cfg.message !== undefined ? cfg.message : 'ping';
  return typeof m === 'string' ? m : JSON.stringify(m);
}

function session(runtime, auth) {
  return sessionFor(runtime, { authRole: undefined }, auth);
}

/** A plain WebSocket: open, send, count what comes back, close. */
function webSocket(runtime, channel, cfg, auth) {
  requireWebSocket();
  const s = session(runtime, auth);
  const tags = { api: `ch_${channel.id}`, name: `WS ${channel.path}` };
  const state = { opened: false, received: 0, error: undefined };
  const socket = new WebSocket(wsUrl(runtime, channel.path), null, { headers: s.headers || {}, tags });
  socket.onopen = () => {
    state.opened = true;
    const n = cfg.messages !== undefined ? cfg.messages : 3;
    for (let i = 0; i < n; i++) setTimeout(() => socket.send(payload(cfg)), i * (cfg.intervalMs || 200));
    setTimeout(() => socket.close(), holdMs(cfg, '3s'));
  };
  socket.onmessage = () => { state.received++; };
  socket.onerror = (e) => { state.error = e && e.error ? e.error : 'error'; };
  return new Promise((resolve) => {
    socket.onclose = () => resolve(state);
  }).then((st) => {
    check(null, { [`ws ${channel.id} connected`]: () => st.opened }, { api: `ch_${channel.id}` });
    if (cfg.expectReply) check(null, { [`ws ${channel.id} replied`]: () => st.received > 0 }, { api: `ch_${channel.id}` });
  });
}

function frame(command, headers, body) {
  const lines = [command];
  for (const [k, v] of Object.entries(headers || {})) lines.push(`${k}:${v}`);
  return `${lines.join('\n')}\n\n${body || ''}\0`;
}

/** STOMP over WebSocket: CONNECT, SUBSCRIBE to the broker destinations, SEND to the application destinations. */
function stomp(runtime, channel, cfg, auth) {
  requireWebSocket();
  const s = session(runtime, auth);
  const tags = { api: `ch_${channel.id}`, name: `STOMP ${channel.path}` };
  const state = { connected: false, messages: 0, receipts: 0 };
  const socket = new WebSocket(wsUrl(runtime, channel.path), null, { headers: s.headers || {}, tags });
  socket.onopen = () => {
    socket.send(frame('CONNECT', Object.assign({ 'accept-version': '1.2', host: runtime.baseUrl.replace(/^https?:\/\//, '').split('/')[0] }, s.headers || {})));
  };
  socket.onmessage = (event) => {
    const text = String(event.data);
    if (text.startsWith('CONNECTED')) {
      state.connected = true;
      (channel.subscribe || []).forEach((dest, i) => socket.send(frame('SUBSCRIBE', { id: `sub-${i}`, destination: dest })));
      const n = cfg.messages !== undefined ? cfg.messages : 3;
      for (let i = 0; i < n; i++) {
        for (const dest of channel.send || []) {
          setTimeout(() => socket.send(frame('SEND', { destination: dest, 'content-type': 'application/json' }, payload(cfg))), i * (cfg.intervalMs || 200));
        }
      }
      setTimeout(() => {
        socket.send(frame('DISCONNECT', {}));
        socket.close();
      }, holdMs(cfg, '3s'));
    } else if (text.startsWith('MESSAGE')) {
      state.messages++;
    }
  };
  return new Promise((resolve) => {
    socket.onclose = () => resolve(state);
  }).then((st) => {
    check(null, { [`stomp ${channel.id} connected`]: () => st.connected }, { api: `ch_${channel.id}` });
    if (cfg.expectReply) check(null, { [`stomp ${channel.id} received a message`]: () => st.messages > 0 }, { api: `ch_${channel.id}` });
  });
}

/** Server-Sent Events: read the stream until `hold` runs out (a timed-out read keeps what arrived) and count events. */
function sse(runtime, channel, cfg, auth) {
  const s = session(runtime, auth);
  const res = http.get(runtime.baseUrl + channel.path, {
    headers: Object.assign({ Accept: 'text/event-stream' }, s.headers || {}),
    timeout: `${holdMs(cfg, '5s')}ms`,
    responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 0), // 0: the read timed out, as intended
    tags: { api: `ch_${channel.id}`, name: `SSE ${channel.path}` },
  });
  const events = String(res.body || '').split(/\n\n+/).filter((chunk) => /(^|\n)(data|event|id):/.test(chunk)).length;
  const ok = (res.status >= 200 && res.status < 300) || (res.status === 0 && res.error_code === 1050 && events > 0);
  check(res, { [`sse ${channel.id} stream opened`]: () => ok, [`sse ${channel.id} sent events`]: () => events >= (cfg.minEvents || 1) },
    { api: `ch_${channel.id}` });
}

/** One iteration: every enabled channel (CHANNEL=<id,…> narrows; Kafka runs from kafka.js). */
export async function runChannels(runtime, channels, auth) {
  const wanted = __ENV.CHANNEL ? __ENV.CHANNEL.split(',').map((x) => x.trim()) : null;
  for (const channel of channels) {
    const cfg = (runtime.config.channels || {})[channel.id] || {};
    if (cfg.enabled === false || (wanted && wanted.indexOf(channel.id) < 0)) continue;
    if (channel.kind === 'ws') await webSocket(runtime, channel, cfg, auth);
    else if (channel.kind === 'stomp') await stomp(runtime, channel, cfg, auth);
    else if (channel.kind === 'sse') sse(runtime, channel, cfg, auth);
  }
}
