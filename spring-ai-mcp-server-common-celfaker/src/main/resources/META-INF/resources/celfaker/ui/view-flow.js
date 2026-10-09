import { api as server } from './api.js';
import { clear, debounce, download, h, pickFile } from './dom.js';
import {
  addStep, ASSERT_OPS, autoWire, availableVariables, connect, disconnect, findStep, layout, normalizeWorkflow, removeStep, renameStep,
  suggestExtracts, toWorkflow, wouldCycle,
} from './flow-model.js';
import { apiById, notify, save, state } from './state.js';
import { invalidReasons, loadInvalidReasons, nodeStatus, runPanel, scenarioBar } from './view-run.js';

const NODE_W = 200;
const NODE_H = 64;
const problems = { list: [], error: '' };
const view = { edge: null };

const checkFlow = debounce(async (onDone) => {
  try {
    problems.list = (await server.validate(state.contract, toWorkflow(state.workflow))).problems;
    problems.error = '';
  } catch (e) {
    problems.error = String(e.message);
  }
  onDone();
}, 350);

export function render(root) {
  clear(root);
  const wf = state.workflow;
  const apis = state.contract.apis;
  const canvas = h('div', { class: 'canvas', tabindex: 0, 'aria-label': 'Workflow canvas. Drag APIs here; drag from a node\'s right dot to another node to connect; Delete removes the selection.' });
  const svgNs = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNs, 'svg');
  svg.setAttribute('class', 'edges');
  svg.setAttribute('width', '3200');
  svg.setAttribute('height', '2000');
  canvas.append(svg);
  const inspector = h('aside', { class: 'inspector', 'aria-live': 'polite' });
  const problemBox = h('div', { class: 'problems' });

  const palette = h('aside', { class: 'palette' }, h('h3', {}, 'APIs'),
    h('p', { class: 'muted' }, 'Drag onto the canvas, or press Add.'),
    apis.map((a) => h('div', { class: 'palette-item', draggable: 'true', ondragstart: (e) => e.dataTransfer.setData('text/plain', 'api:' + a.id) },
      h('span', { class: 'badge m-' + a.method }, a.method), ' ', h('b', {}, a.name || a.id), h('br', {}), h('code', {}, a.path),
      h('div', {}, h('button', { class: 'btn sm', onclick: () => { const s = addStep(wf, a.id, 40 + (wf.steps.length % 4) * 30, 40 + wf.steps.length * 20); select(s.id); } }, 'Add'))))
  );

  const centre = h('div', { class: 'flow-centre' }, scenarioBar(), h('div', { class: 'toolbar' },
    h('button', { class: 'btn', title: 'Chain actions and their validation APIs from the contract', onclick: async () => {
      if (wf.steps.length && !confirm('Replace the current workflow?')) return;
      replaceCurrent({ ...(await server.propose(state.contract)), name: state.workflow.name, load: state.workflow.load });
      state.ui.step = null;
      notify();
    } }, 'Propose from contract'),
    h('button', { class: 'btn', onclick: () => { layout(wf); notify(); } }, 'Auto layout'),
    h('button', { class: 'btn', onclick: () => download(wf.name + '-workflow.json', JSON.stringify(toWorkflow(wf), null, 2)) }, 'Export workflow'),
    h('button', { class: 'btn', title: 'All scenarios in one file', onclick: () => download(state.contract.name + '-scenarios.json', JSON.stringify(state.scenarios.map(toWorkflow), null, 2)) }, 'Export all'),
    h('button', { class: 'btn', onclick: async () => { const t = await pickFile(); if (t) { try { importScenarios(JSON.parse(t)); notify(); } catch (e) { alert('Not a workflow file: ' + e.message); } } } }, 'Import workflow'),
    h('button', { class: 'btn danger', onclick: () => { if (confirm('Remove all steps?')) { wf.steps = []; state.ui.step = null; notify(); } } }, 'Clear')),
    canvas, problemBox, runPanel());
  root.append(h('div', { class: 'flow' }, palette, centre, inspector));

  // ---- canvas ----------------------------------------------------------------------------------------------
  function select(id) {
    state.ui.step = id;
    view.edge = null;
    notify();
  }

  canvas.addEventListener('dragover', (e) => e.preventDefault());
  canvas.addEventListener('drop', (e) => {
    e.preventDefault();
    const data = e.dataTransfer.getData('text/plain');
    if (!data.startsWith('api:')) return;
    const r = canvas.getBoundingClientRect();
    const s = addStep(wf, data.slice(4), Math.max(0, e.clientX - r.left + canvas.scrollLeft - NODE_W / 2), Math.max(0, e.clientY - r.top + canvas.scrollTop - NODE_H / 2));
    select(s.id);
  });
  canvas.addEventListener('keydown', (e) => {
    if (e.key !== 'Delete' && e.key !== 'Backspace') return;
    if (view.edge) { disconnect(wf, view.edge.from, view.edge.to); view.edge = null; notify(); }
    else if (state.ui.step && findStep(wf, state.ui.step)) { removeStep(wf, state.ui.step); state.ui.step = null; notify(); }
  });
  canvas.addEventListener('click', (e) => { if (e.target === canvas || e.target === svg) { state.ui.step = null; view.edge = null; notify(); } });

  const nodeEls = new Map();
  function portOut(s) { return [s.x + NODE_W, s.y + NODE_H / 2]; }
  function portIn(s) { return [s.x, s.y + NODE_H / 2]; }
  function curve([x1, y1], [x2, y2]) {
    const dx = Math.max(40, Math.abs(x2 - x1) / 2);
    return `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`;
  }

  function drawEdges() {
    svg.replaceChildren();
    const defs = document.createElementNS(svgNs, 'defs');
    defs.innerHTML = '<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" class="arrow-head"/></marker>';
    svg.append(defs);
    for (const s of wf.steps) {
      for (const dep of s.dependsOn) {
        const d = findStep(wf, dep);
        if (!d) continue;
        const sel = view.edge && view.edge.from === dep && view.edge.to === s.id;
        const hit = document.createElementNS(svgNs, 'path');
        hit.setAttribute('d', curve(portOut(d), portIn(s)));
        hit.setAttribute('class', 'edge-hit');
        hit.addEventListener('click', (e) => { e.stopPropagation(); view.edge = { from: dep, to: s.id }; state.ui.step = null; notify(); canvas.focus(); });
        const p = document.createElementNS(svgNs, 'path');
        p.setAttribute('d', curve(portOut(d), portIn(s)));
        p.setAttribute('class', 'edge' + (sel ? ' selected' : ''));
        p.setAttribute('marker-end', 'url(#arrow)');
        svg.append(p, hit);
      }
    }
  }

  function startConnect(e, from) {
    e.preventDefault();
    e.stopPropagation();
    const temp = document.createElementNS(svgNs, 'path');
    temp.setAttribute('class', 'edge temp');
    svg.append(temp);
    const r = canvas.getBoundingClientRect();
    const start = portOut(from);
    const move = (ev) => temp.setAttribute('d', curve(start, [ev.clientX - r.left + canvas.scrollLeft, ev.clientY - r.top + canvas.scrollTop]));
    const up = (ev) => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      temp.remove();
      const hit = document.elementFromPoint(ev.clientX, ev.clientY)?.closest?.('.node');
      if (hit && hit.dataset.id !== from.id) {
        const res = connect(wf, from.id, hit.dataset.id);
        if (!res.ok) flash(res.reason);
        notify();
      }
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  function flash(msg) {
    problemBox.replaceChildren(h('p', { class: 'warn' }, msg));
    setTimeout(drawProblems, 2500);
  }

  function nodeEl(s) {
    const a = apiById(s.api);
    const selected = state.ui.step === s.id;
    const el = h('div', { class: 'node' + (selected ? ' selected' : '') + (a?.role === 'VALIDATION' ? ' validation' : '') + (nodeStatus(s.id) ? ' run-' + nodeStatus(s.id) : ''), dataset: { id: s.id }, style: `left:${s.x}px;top:${s.y}px;width:${NODE_W}px;height:${NODE_H}px`, tabindex: 0, role: 'button',
      'aria-label': 'Step ' + s.id + ' calls ' + s.api,
      onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); select(s.id); } } },
      h('div', { class: 'node-head' }, h('span', { class: 'badge m-' + (a?.method ?? 'GET') }, a?.method ?? '?'), ' ', h('b', {}, s.id)),
      h('div', { class: 'node-body mono' }, a?.path ?? 'unknown api'),
      h('span', { class: 'port in', title: 'incoming' }),
      h('span', { class: 'port out', title: 'drag to another step to run it after this one', onpointerdown: (e) => startConnect(e, s) }));
    el.addEventListener('pointerdown', (e) => {
      if (e.target.classList.contains('port') || e.button !== 0) return;
      const ox = e.clientX - s.x;
      const oy = e.clientY - s.y;
      let moved = false;
      const move = (ev) => {
        moved = true;
        s.x = Math.max(0, ev.clientX - ox);
        s.y = Math.max(0, ev.clientY - oy);
        el.style.left = s.x + 'px';
        el.style.top = s.y + 'px';
        drawEdges();
      };
      const up = () => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        if (moved) save();
        if (state.ui.step !== s.id) select(s.id);
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    });
    nodeEls.set(s.id, el);
    return el;
  }

  wf.steps.forEach((s) => canvas.append(nodeEl(s)));
  drawEdges();
  if (!wf.steps.length) canvas.append(h('p', { class: 'canvas-hint' }, 'Drag an API here, or press “Propose from contract”.'));

  function drawProblems() {
    problemBox.replaceChildren(...[
      problems.error ? h('p', { class: 'error' }, problems.error) : null,
      problems.list.length
        ? h('ul', { class: 'problem-list' }, problems.list.map((p) => h('li', { class: 'warn' }, p)))
        : wf.steps.length ? h('p', { class: 'ok' }, '✓ The workflow is runnable: ' + wf.steps.length + ' steps.') : null,
    ].filter(Boolean));
  }
  drawProblems();
  checkFlow(drawProblems);

  // ---- inspector -------------------------------------------------------------------------------------------
  const step = state.ui.step ? findStep(wf, state.ui.step) : null;
  if (view.edge) {
    inspector.append(h('h3', {}, 'Connection'), h('p', {}, h('code', {}, view.edge.to), ' runs after ', h('code', {}, view.edge.from), '.'),
      h('button', { class: 'btn danger', onclick: () => { disconnect(wf, view.edge.from, view.edge.to); view.edge = null; notify(); } }, 'Remove connection'));
  } else if (step) {
    inspectStep(inspector, step, select);
  } else {
    inspectWorkflow(inspector);
  }
}

function replaceCurrent(wf) {
  const i = state.scenarios.indexOf(state.workflow);
  state.scenarios[i] = normalizeWorkflow(wf);
  state.workflow = state.scenarios[i];
}

/** A file may hold one workflow or an array of scenarios. */
function importScenarios(json) {
  const list = (Array.isArray(json) ? json : [json]).map(normalizeWorkflow);
  if (!list.length) throw new Error('no workflow in the file');
  if (!state.workflow.steps.length && state.scenarios.length === 1) { state.scenarios.splice(0, 1, ...list); } else { state.scenarios.push(...list); }
  state.workflow = list[0];
  state.ui.step = null;
}

function field(label, control, hint) {
  return h('label', { class: 'field' }, h('span', { class: 'label' }, label), control, hint ? h('small', {}, hint) : null);
}

function inspectWorkflow(el) {
  const wf = state.workflow;
  const load = wf.load;
  el.append(h('h3', {}, 'Workflow'),
    field('Name', h('input', { value: wf.name, oninput: (e) => { wf.name = e.target.value; save(); } })),
    field('Load profile', h('select', { onchange: (e) => { load.profile = e.target.value; save(); } },
      ['smoke', 'load', 'stress', 'spike', 'custom'].map((p) => h('option', { value: p, selected: p === load.profile }, p))),
      'smoke = 1 iteration; load/stress/spike ramp to the VUs below'),
    field('Virtual users', h('input', { type: 'number', min: 1, value: load.vus, onchange: (e) => { load.vus = Number(e.target.value); save(); } })),
    field('Duration unit', h('input', { value: load.duration, onchange: (e) => { load.duration = e.target.value; save(); } }), 'k6 duration, e.g. 30s or 5m'),
    field('Max negative iterations', h('input', { type: 'number', min: 0, value: load.negatives, onchange: (e) => { load.negatives = Number(e.target.value); save(); } }), '0 turns the validation (negative) scenario off'),
    h('p', { class: 'muted' }, 'Select a step to map the values it passes on (extract) and receives (inject).'));
}

function inspectStep(el, step, select) {
  const wf = state.workflow;
  const spec = apiById(step.api);
  const vars = availableVariables(wf, step.id);
  const others = wf.steps.filter((s) => s.id !== step.id);
  const listId = 'vars-' + step.id;
  el.append(h('h3', {}, 'Step ', h('code', {}, step.id)),
    h('p', { class: 'muted' }, spec ? spec.method + ' ' + spec.path : 'unknown api ' + step.api),
    spec?.description ? h('p', { class: 'doc' }, spec.description) : null,
    field('Step id', h('input', { value: step.id, onchange: (e) => { const r = renameStep(wf, step.id, e.target.value); if (!r.ok) alert(r.reason); state.ui.step = r.ok ? e.target.value : step.id; notify(); } })),
    h('fieldset', {}, h('legend', {}, 'Runs after'),
      others.length ? others.map((o) => h('label', { class: 'inline' },
        h('input', { type: 'checkbox', checked: step.dependsOn.includes(o.id), disabled: !step.dependsOn.includes(o.id) && wouldCycle(wf, o.id, step.id),
          onchange: (e) => { e.target.checked ? connect(wf, o.id, step.id) : disconnect(wf, o.id, step.id); notify(); } }), ' ', o.id)) : h('span', { class: 'muted' }, 'no other steps')),
    h('datalist', { id: listId }, vars.map((v) => h('option', { value: v.ref })), ['{{iter}}', '{{vu}}', '{{uuid}}', '{{timestamp}}', '{{env.TOKEN}}'].map((v) => h('option', { value: v }))),
    rows('Extract — response → variable', step.extract, () => ({ name: '', from: 'body.id' }), (r, i) => [
      h('input', { value: r.name, placeholder: 'variable', 'aria-label': 'variable name', oninput: (e) => { r.name = e.target.value; save(); } }),
      h('input', { value: r.from, class: 'mono', placeholder: 'body.id | header.Location | status', 'aria-label': 'source', oninput: (e) => { r.from = e.target.value; save(); } })],
      suggestExtracts(spec).filter((s) => !step.extract.some((e) => e.from === s.from)).slice(0, 6).map((s) =>
        h('button', { class: 'chip toggle', title: 'Extract ' + s.from, onclick: () => { step.extract.push({ ...s }); notify(); } }, '+ ' + s.from))),
    rows('Inject — variable → request', step.inject, () => ({ target: 'path.id', value: vars[0]?.ref ?? '' }), (r) => [
      h('input', { value: r.target, class: 'mono', placeholder: 'path.id | query.x | header.X | body.a.b', 'aria-label': 'target', oninput: (e) => { r.target = e.target.value; save(); } }),
      h('input', { value: r.value, class: 'mono', list: listId, placeholder: '{{step.var}}', 'aria-label': 'value', oninput: (e) => { r.value = e.target.value; save(); } })],
      [h('button', { class: 'btn sm', onclick: () => { const n = autoWire(wf, state.contract.apis, step.id); if (!n) alert('Nothing to wire: no unmapped path parameter, or no variable extracted by an earlier step.'); notify(); } }, 'Auto-wire path parameters')]),
    bodySection(step, spec),
    rows('Assertions — check the response', step.assertions, () => ({ from: 'body.id', op: '==', value: '' }), (r) => [
      h('input', { value: r.from, class: 'mono', placeholder: 'status | body.a.b | header.X', 'aria-label': 'response value', oninput: (e) => { r.from = e.target.value; save(); } }),
      h('div', { class: 'row2-inner' },
        h('select', { 'aria-label': 'operator', onchange: (e) => { r.op = e.target.value; notify(); } }, ASSERT_OPS.map(([op, label]) => h('option', { value: op, selected: op === r.op }, label))),
        r.op === 'exists' || r.op === 'absent' ? null : h('input', { value: r.value, class: 'mono', list: listId, placeholder: 'expected, e.g. NEW or {{step.var}}', 'aria-label': 'expected value', oninput: (e) => { r.value = e.target.value; save(); } }))],
      [h('button', { class: 'chip toggle', title: 'The response status must be 2xx', onclick: () => { step.assertions.push({ from: 'status', op: '==', value: '201' }); notify(); } }, '+ status'),
        ...(spec?.responseExample && typeof spec.responseExample === 'object' ? suggestExtracts(spec).slice(0, 4).map((x) => h('button', { class: 'chip toggle', title: 'Assert ' + x.from + ' exists', onclick: () => { step.assertions.push({ from: x.from, op: 'exists', value: '' }); notify(); } }, '+ ' + x.from)) : [])]),
    field('Expected status (empty = API default)', h('input', { value: step.expectStatus.join(', '), onchange: (e) => { step.expectStatus = e.target.value.split(',').map((s) => parseInt(s.trim(), 10)).filter(Number.isFinite); save(); } })),
    field('Think time after (seconds)', h('input', { type: 'number', min: 0, step: '0.1', value: step.thinkTime, onchange: (e) => { step.thinkTime = Number(e.target.value); save(); } })),
    h('div', { class: 'toolbar' },
      h('button', { class: 'btn', onclick: () => { state.ui.step = null; notify(); } }, 'Done'),
      h('button', { class: 'btn danger', onclick: () => { removeStep(wf, step.id); state.ui.step = null; notify(); } }, 'Delete step')));
}

/** Which request body a step sends: generated valid data, a generated invalid case, or JSON typed here. */
function bodySection(step, spec) {
  if (!spec || !spec.requestExample || spec.role === 'VALIDATION' || typeof spec.requestExample !== 'object') return null;
  const mode = step.body ? 'custom' : step.invalidCase ? 'invalid' : 'valid';
  const set = (m) => {
    if (m === 'valid') { step.body = null; step.invalidCase = ''; }
    if (m === 'invalid') { step.body = null; step.invalidCase ||= invalidReasons(spec)[0] ?? ''; loadInvalidReasons(spec); }
    if (m === 'custom') { step.invalidCase = ''; step.body ??= JSON.parse(JSON.stringify(spec.requestExample)); }
    notify();
  };
  const err = h('small', { class: 'error' });
  return h('fieldset', {}, h('legend', {}, 'Request body'),
    h('div', { class: 'chips', role: 'radiogroup', 'aria-label': 'Request body source' }, [['valid', 'Generated (valid)'], ['invalid', 'Invalid case'], ['custom', 'Custom JSON']].map(([m, label]) =>
      h('button', { role: 'radio', 'aria-checked': mode === m, class: 'chip toggle' + (mode === m ? ' on' : ''), onclick: () => set(m) }, label))),
    mode === 'invalid' ? h('div', {},
      h('datalist', { id: 'inv-' + step.id }, invalidReasons(spec).map((r) => h('option', { value: r }))),
      h('input', { class: 'mono', list: 'inv-' + step.id, value: step.invalidCase, placeholder: 'e.g. customer.age:missing', 'aria-label': 'invalid case', oninput: (e) => { step.invalidCase = e.target.value; save(); } }),
      h('small', { class: 'muted' }, invalidReasons(spec).length ? invalidReasons(spec).length + ' generated cases — pick one, then set the expected status (e.g. 422) below.'
        : 'Loading the generated cases… (or type a reason). Set the expected status (e.g. 422) below.')) : null,
    mode === 'custom' ? h('div', {},
      h('textarea', { rows: 8, class: 'mono', spellcheck: 'false', 'aria-label': 'custom request body',
        oninput: (e) => { try { const v = JSON.parse(e.target.value); if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('must be a JSON object'); step.body = v; err.textContent = ''; save(); } catch (x) { err.textContent = x.message; } } },
        JSON.stringify(step.body, null, 2)), err,
      h('small', { class: 'muted' }, 'Values may use {{step.var}}, {{env.NAME}}, {{iter}}, {{uuid}}.')) : null);
}

function rows(title, list, make, cells, extras = []) {
  return h('fieldset', {}, h('legend', {}, title),
    list.map((r, i) => h('div', { class: 'row2' }, cells(r, i), h('button', { class: 'btn sm', 'aria-label': 'remove', onclick: () => { list.splice(i, 1); notify(); } }, '×'))),
    h('div', { class: 'chips' }, h('button', { class: 'btn sm', onclick: () => { list.push(make()); notify(); } }, '+ Add'), extras));
}
