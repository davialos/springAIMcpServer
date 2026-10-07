import type { Bucket } from '../api/types';

/**
 * Evaluations over time as stacked bars (allow, warn, block). Drawn as SVG with a text alternative: the chart has an
 * accessible summary and the numbers are also available as a table, so nothing depends on colour or on seeing the bars.
 */
export function BarChart({ series, hours }: { series: Bucket[]; hours: number }) {
  if (series.length === 0) {
    return <p className="empty">No evaluations in the last {hours} hours.</p>;
  }
  const width = 720;
  const height = 180;
  const pad = { l: 36, r: 20, t: 8, b: 24 };
  const max = Math.max(...series.map((b) => b.total), 1);
  const slot = (width - pad.l - pad.r) / series.length;
  const bar = Math.max(2, slot * 0.72);
  const y = (n: number) => (n / max) * (height - pad.t - pad.b);
  const label = (iso: string) => {
    const d = new Date(iso);
    return hours <= 48
      ? d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
      : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  };
  const every = Math.ceil(series.length / 8);
  const total = series.reduce((s, b) => s + b.total, 0);
  return (
    <figure style={{ margin: 0 }}>
      <svg
        className="chart"
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`Evaluations per ${hours <= 48 ? 'hour' : 'day'}: ${total} in total over ${series.length} periods, peak ${max}`}
      >
        <line x1={pad.l} x2={width - pad.r} y1={height - pad.b} y2={height - pad.b} stroke="var(--border)" />
        <text x={pad.l - 6} y={pad.t + 8} textAnchor="end">
          {max}
        </text>
        <text x={pad.l - 6} y={height - pad.b} textAnchor="end">
          0
        </text>
        {series.map((b, i) => {
          const x = pad.l + i * slot + (slot - bar) / 2;
          let top = height - pad.b;
          const part = (n: number, colour: string, name: string) => {
            if (n === 0) {
              return null;
            }
            const h = y(n);
            top -= h;
            return (
              <rect key={name} x={x} y={top} width={bar} height={h} fill={colour} rx={1}>
                <title>{`${label(b.at)} · ${name}: ${n}`}</title>
              </rect>
            );
          };
          return (
            <g key={b.at}>
              {part(b.allow, 'var(--chart-allow)', 'allow')}
              {part(b.warn, 'var(--chart-warn)', 'warn')}
              {part(b.block, 'var(--chart-block)', 'block')}
              {i % every === 0 && (
                // the last label would run past the right edge: end-align it
                <text x={i === series.length - 1 ? width - pad.r + bar / 2 : x + bar / 2} y={height - 8}
                  textAnchor={i === series.length - 1 ? 'end' : 'middle'}>
                  {label(b.at)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      <figcaption className="legend">
        <span>
          <i style={{ background: 'var(--chart-allow)' }} />
          Allow
        </span>
        <span>
          <i style={{ background: 'var(--chart-warn)' }} />
          Warn
        </span>
        <span>
          <i style={{ background: 'var(--chart-block)' }} />
          Block
        </span>
      </figcaption>
      <details>
        <summary className="hint">Show the numbers</summary>
        <div className="table-wrap" style={{ marginTop: 'var(--space-2)' }}>
          <table>
            <thead>
              <tr>
                <th>Period</th>
                <th className="num">Total</th>
                <th className="num">Allow</th>
                <th className="num">Warn</th>
                <th className="num">Block</th>
              </tr>
            </thead>
            <tbody>
              {series.map((b) => (
                <tr key={b.at}>
                  <td>{label(b.at)}</td>
                  <td className="num">{b.total}</td>
                  <td className="num">{b.allow}</td>
                  <td className="num">{b.warn}</td>
                  <td className="num">{b.block}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}
