// Daily FX rates straight from the browser to api.frankfurter.dev (v1); nothing passes through our server.
import { Rates, type RateOverride } from "./model";

const KEY = "arthatis.rates";
export async function fetchRates(): Promise<Record<string, number>> {
  const cached = JSON.parse(localStorage.getItem(KEY) ?? "{}") as Record<string, number>;
  const out = { ...cached };
  await Promise.all(["USD", "SGD"].map(async (base) => {
    try {
      const r = await fetch(`https://api.frankfurter.dev/v1/latest?base=${base}&symbols=IDR`);
      if (r.ok) { const j = await r.json(); if (j?.rates?.IDR) out[base] = j.rates.IDR as number; }
    } catch { /* keep the last good value */ }
  }));
  localStorage.setItem(KEY, JSON.stringify(out));
  return out;
}
export const cachedRates = () => JSON.parse(localStorage.getItem(KEY) ?? "{}") as Record<string, number>;
export const buildRates = (api: Record<string, number>, overrides: RateOverride[]) =>
  new Rates(api, Object.fromEntries(overrides.filter((o) => o.deletedAt === null).map((o) => [o.id, parseFloat(o.rate)]).filter(([, v]) => isFinite(v as number)) as [string, number][]));
