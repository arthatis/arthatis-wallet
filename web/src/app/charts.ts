// Hand-drawn SVG charts (no chart library). Colours come from CSS tokens so light/dark follow the theme.
import { esc } from "./ui";

export interface BarDatum { label: string; a: number; b: number }

/** Grouped bars: income (primary) vs expense (tertiary) per month. */
export function barChart(data: BarDatum[], fmt: (n: number) => string): string {
  const W = 560, H = 220, pad = { l: 8, r: 8, t: 12, b: 28 };
  const max = Math.max(1, ...data.flatMap((d) => [d.a, d.b]));
  const bw = (W - pad.l - pad.r) / data.length;
  const y = (v: number) => pad.t + (H - pad.t - pad.b) * (1 - v / max);
  const bars = data.map((d, i) => {
    const x = pad.l + i * bw; const w = bw * 0.28;
    return `<rect x="${x + bw * 0.16}" y="${y(d.a)}" width="${w}" height="${H - pad.b - y(d.a)}" rx="3" fill="var(--primary)"><title>${esc(d.label)} ${esc(fmt(d.a))}</title></rect>
      <rect x="${x + bw * 0.16 + w + 4}" y="${y(d.b)}" width="${w}" height="${H - pad.b - y(d.b)}" rx="3" fill="var(--tertiary)"><title>${esc(d.label)} ${esc(fmt(d.b))}</title></rect>
      <text x="${x + bw / 2}" y="${H - 8}" text-anchor="middle">${esc(d.label)}</text>`;
  }).join("");
  return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" width="100%"><line class="grid-line" x1="0" x2="${W}" y1="${H - pad.b}" y2="${H - pad.b}"/>${bars}</svg>`;
}

export interface Slice { label: string; value: number; color: string }

export function donut(slices: Slice[], centerTop: string, centerBottom: string): string {
  const total = slices.reduce((n, s) => n + s.value, 0) || 1;
  const r = 52, c = 2 * Math.PI * r; let offset = 0;
  const arcs = slices.map((s) => {
    const len = (s.value / total) * c;
    const el = `<circle r="${r}" cx="80" cy="80" fill="none" stroke="${s.color}" stroke-width="22" stroke-dasharray="${len} ${c - len}" stroke-dashoffset="${-offset}" transform="rotate(-90 80 80)"><title>${esc(s.label)}</title></circle>`;
    offset += len; return el;
  }).join("");
  return `<svg class="chart" viewBox="0 0 160 160" role="img" width="160" height="160"><circle r="${r}" cx="80" cy="80" fill="none" stroke="var(--surfaceContainerHighest)" stroke-width="22"/>${arcs}
    <text x="80" y="78" text-anchor="middle" style="font-size:11px">${esc(centerBottom)}</text><text x="80" y="96" text-anchor="middle" style="font-size:13px;font-weight:600;fill:var(--onSurface)">${esc(centerTop)}</text></svg>`;
}

/** Balance trend: a line with a soft area. */
export function lineChart(points: { label: string; v: number }[], fmt: (n: number) => string): string {
  const W = 560, H = 200, pad = { l: 8, r: 8, t: 14, b: 26 };
  if (points.length < 2) return `<svg class="chart" viewBox="0 0 ${W} ${H}" width="100%"></svg>`;
  const lo = Math.min(...points.map((p) => p.v)), hi = Math.max(...points.map((p) => p.v));
  const span = hi - lo || 1;
  const x = (i: number) => pad.l + ((W - pad.l - pad.r) * i) / (points.length - 1);
  const y = (v: number) => pad.t + (H - pad.t - pad.b) * (1 - (v - lo) / span);
  const line = points.map((p, i) => `${i ? "L" : "M"}${x(i)} ${y(p.v)}`).join(" ");
  const area = `${line} L${x(points.length - 1)} ${H - pad.b} L${x(0)} ${H - pad.b} Z`;
  const labels = points.map((p, i) => `<text x="${x(i)}" y="${H - 8}" text-anchor="middle">${esc(p.label)}</text>`).join("");
  const dots = points.map((p, i) => `<circle cx="${x(i)}" cy="${y(p.v)}" r="3.5" fill="var(--primary)"><title>${esc(p.label)} ${esc(fmt(p.v))}</title></circle>`).join("");
  return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" width="100%"><path d="${area}" fill="var(--primary)" opacity=".12"/><path d="${line}" fill="none" stroke="var(--primary)" stroke-width="2.5" stroke-linejoin="round"/>${dots}${labels}</svg>`;
}
