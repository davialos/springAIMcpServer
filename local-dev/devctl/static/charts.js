// Small dependency-free SVG line charts for the dashboard: 2px lines, ~10% area wash for a single series,
// recessive hairline grid, end-value direct labels, legend for >= 2 series, crosshair + tooltip on hover.
// Colours come from CSS tokens (--s1, --s2, --ref) so light/dark are both validated palettes.
(function () {
  const NS = 'http://www.w3.org/2000/svg';
  const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const nice = v => { if (!(v > 0)) return 1; const p = Math.pow(10, Math.floor(Math.log10(v))); const f = v / p;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p; };
  const fmtNum = v => v == null ? '–' : Math.abs(v) >= 1000 ? v.toLocaleString(undefined, {maximumFractionDigits: 0})
    : Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1).replace(/\.0$/, '') : v.toFixed(2).replace(/\.?0+$/, '');
  const el = (tag, attrs, parent) => { const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; };
  const ago = s => s < 90 ? `${Math.round(s)}s` : `${Math.round(s / 60)}m`;

  // opts: {title, unit, series:[{key,label,color}], points:[{t,...}], ref:{value,label}, now}
  function lineChart(host, opts) {
    host.innerHTML = '';
    host.classList.add('chart');
    const head = document.createElement('div'); head.className = 'chart-head';
    const last = opts.points.length ? opts.points[opts.points.length - 1] : {};
    head.innerHTML = `<span class="chart-title">${opts.title}</span>` + (opts.series.length === 1
      ? `<span class="chart-val">${fmtNum(last[opts.series[0].key])}<small> ${opts.unit || ''}</small></span>` : '');
    host.appendChild(head);
    if (opts.series.length > 1) {
      const lg = document.createElement('div'); lg.className = 'legend';
      lg.innerHTML = opts.series.map(s => `<span><i style="background:${css(s.color)}"></i>${s.label}</span>`).join('');
      host.appendChild(lg);
    }
    const W = Math.max(260, host.clientWidth - 2), H = 150, L = 40, R = 46, T = 8, B = 20;
    const svg = el('svg', {viewBox: `0 0 ${W} ${H}`, width: '100%', height: H, role: 'img', 'aria-label': opts.title}, host);
    const pts = opts.points.filter(p => opts.series.some(s => p[s.key] != null));
    if (pts.length < 2) {
      el('text', {x: W / 2, y: H / 2, 'text-anchor': 'middle', class: 'axis'}, svg).textContent = 'waiting for data…';
      return;
    }
    const t0 = pts[0].t, t1 = opts.now || pts[pts.length - 1].t;
    let vmax = 0;
    for (const p of pts) for (const s of opts.series) if (p[s.key] != null) vmax = Math.max(vmax, p[s.key]);
    if (opts.ref && opts.ref.value && opts.ref.value > vmax * 3) opts = {...opts, ref: null};
    if (opts.ref && opts.ref.value) vmax = Math.max(vmax, opts.ref.value);
    const ymax = nice(vmax * 1.1 || 1);
    if (opts.ref === null && last[opts.series[0].key] != null && opts.refNote) head.querySelector('.chart-val small').textContent += opts.refNote;
    const x = t => L + (W - L - R) * ((t - t0) / Math.max(1, t1 - t0)), y = v => T + (H - T - B) * (1 - v / ymax);
    for (let i = 0; i <= 2; i++) {
      const v = ymax * i / 2;
      el('line', {x1: L, x2: W - R, y1: y(v), y2: y(v), class: 'grid'}, svg);
      el('text', {x: L - 6, y: y(v) + 4, 'text-anchor': 'end', class: 'axis'}, svg).textContent = fmtNum(v);
    }
    el('text', {x: L, y: H - 4, class: 'axis'}, svg).textContent = `-${ago(t1 - t0)}`;
    el('text', {x: W - R, y: H - 4, 'text-anchor': 'end', class: 'axis'}, svg).textContent = opts.nowLabel || 'now';
    if (opts.ref && opts.ref.value) {
      el('line', {x1: L, x2: W - R, y1: y(opts.ref.value), y2: y(opts.ref.value), class: 'ref'}, svg);
      el('text', {x: W - R + 4, y: y(opts.ref.value) + 4, class: 'axis'}, svg).textContent = opts.ref.label;
    }
    const labels = [];
    opts.series.forEach(s => {
      const col = css(s.color);
      const seg = pts.filter(p => p[s.key] != null);
      if (!seg.length) return;
      const d = seg.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)},${y(p[s.key]).toFixed(1)}`).join('');
      if (opts.series.length === 1)
        el('path', {d: `${d}L${x(seg[seg.length - 1].t).toFixed(1)},${y(0)}L${x(seg[0].t).toFixed(1)},${y(0)}Z`, fill: col, 'fill-opacity': .1}, svg);
      el('path', {d, fill: 'none', stroke: col, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'}, svg);
      const lp = seg[seg.length - 1];
      el('circle', {cx: x(lp.t), cy: y(lp[s.key]), r: 4, fill: col, stroke: css('--card'), 'stroke-width': 2}, svg);
      labels.push({y: y(lp[s.key]), text: fmtNum(lp[s.key])});
    });
    if (opts.series.length > 1) {  // direct end labels, nudged apart
      labels.sort((a, b) => a.y - b.y);
      for (let i = 1; i < labels.length; i++) if (labels[i].y - labels[i - 1].y < 12) labels[i].y = labels[i - 1].y + 12;
      labels.forEach(lb => { el('text', {x: W - R + 8, y: lb.y + 4, class: 'endlbl'}, svg).textContent = lb.text; });
    }
    // hover layer
    const cross = el('line', {y1: T, y2: H - B, class: 'cross', visibility: 'hidden'}, svg);
    const dots = opts.series.map(s => el('circle', {r: 4, fill: css(s.color), stroke: css('--card'), 'stroke-width': 2, visibility: 'hidden'}, svg));
    const tip = document.createElement('div'); tip.className = 'tip'; tip.hidden = true; host.appendChild(tip);
    const hit = el('rect', {x: L, y: 0, width: W - L - R, height: H, fill: 'transparent'}, svg);
    const move = ev => {
      const r = svg.getBoundingClientRect(), mx = (ev.clientX - r.left) * W / r.width;
      let best = pts[0];
      for (const p of pts) if (Math.abs(x(p.t) - mx) < Math.abs(x(best.t) - mx)) best = p;
      cross.setAttribute('x1', x(best.t)); cross.setAttribute('x2', x(best.t)); cross.setAttribute('visibility', 'visible');
      opts.series.forEach((s, i) => { const v = best[s.key];
        dots[i].setAttribute('visibility', v == null ? 'hidden' : 'visible');
        if (v != null) { dots[i].setAttribute('cx', x(best.t)); dots[i].setAttribute('cy', y(v)); } });
      tip.innerHTML = `<div class="mut">${new Date(best.t * 1000).toLocaleTimeString()}</div>` + opts.series.map(s =>
        `<div><i style="background:${css(s.color)}"></i>${s.label}: <b>${fmtNum(best[s.key])}</b> ${opts.unit || ''}</div>`).join('');
      tip.hidden = false;
      const px = (x(best.t) / W) * r.width;
      tip.style.left = Math.min(r.width - tip.offsetWidth - 4, Math.max(0, px + 12)) + 'px';
    };
    hit.addEventListener('mousemove', move);
    hit.addEventListener('mouseleave', () => { tip.hidden = true; cross.setAttribute('visibility', 'hidden'); dots.forEach(d => d.setAttribute('visibility', 'hidden')); });
  }
  window.Charts = {lineChart, fmtNum};
})();
