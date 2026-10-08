import { clear, h } from './dom.js';
import { restore, state, subscribe, notify } from './state.js';
import { refreshCandidates } from './view-apis.js';
import * as apis from './view-apis.js';
import * as params from './view-params.js';
import * as exprs from './view-exprs.js';
import * as values from './view-values.js';
import * as flow from './view-flow.js';
import * as generate from './view-generate.js';

const TABS = [
  ['apis', '1 · APIs', apis], ['params', '2 · Parameters', params], ['exprs', '3 · CEL expressions', exprs],
  ['values', '4 · Attribute map', values], ['flow', '5 · Flow designer', flow], ['generate', '6 · Generate', generate],
];

const nav = document.getElementById('tabs');
const main = document.getElementById('main');

function draw() {
  clear(nav);
  TABS.forEach(([id, label]) => nav.append(h('button', {
    role: 'tab', 'aria-selected': state.ui.tab === id, class: 'tab' + (state.ui.tab === id ? ' active' : ''),
    onclick: () => { state.ui.tab = id; notify(); } }, label)));
  const [, , view] = TABS.find(([id]) => id === state.ui.tab) ?? TABS[0];
  const y = main.scrollTop;
  clear(main);
  view.render(main);
  main.scrollTop = y;
}

restore();
state.ui = { tab: 'apis', api: state.contract.apis[0]?.id ?? null, step: null };
subscribe(draw);
Promise.all(state.contract.apis.map((a) => refreshCandidates(a))).then(draw, draw);
draw();
