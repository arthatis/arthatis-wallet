// Port of the Kotlin `core` model/ledger. Field names match the JSON the phone writes.
export type WalletKind = "rekening" | "tunai" | "ewallet" | "investasi";
export type EntryKind = "expense" | "income" | "transfer";
export type Frequency = "daily" | "weekly" | "monthly" | "yearly";

interface Syncable { id: string; updatedAt: number; deletedAt: number | null }
export interface Wallet extends Syncable { name: string; kind: WalletKind; currency: string; openingMinor: number; position: number }
export interface Category extends Syncable { key: string | null; name: string; kind: EntryKind; color: string; hidden: boolean; position: number }
export interface Tx extends Syncable {
  walletId: string; toWalletId: string | null; categoryId: string | null; kind: EntryKind; amountMinor: number;
  toAmountMinor: number | null; rate: string | null; note: string; occurredAt: number; recurringId: string | null;
}
export interface Recurring extends Syncable {
  name: string; walletId: string; categoryId: string; kind: EntryKind; amountMinor: number; frequency: Frequency;
  dayOfMonth: number | null; weekday: number | null; month: number | null; reminder: boolean; paused: boolean; lastRunDay: number | null;
}
export interface RateOverride extends Syncable { rate: string }
export interface Budget extends Syncable { categoryId: string; monthlyMinor: number }
export interface Profile extends Syncable { name: string; primaryCurrency: string }

export const ENTITY = { wallet: "wallet", category: "category", tx: "transaction", recurring: "recurring", rate: "rate_override", setting: "setting", budget: "budget" } as const;

export interface Ledger {
  wallets: Wallet[]; categories: Category[]; txs: Tx[]; recurring: Recurring[]; overrides: RateOverride[]; budgets: Budget[]; profile: Profile | null;
}
export const emptyLedger = (): Ledger => ({ wallets: [], categories: [], txs: [], recurring: [], overrides: [], budgets: [], profile: null });

export const CURRENCIES = [{ code: "IDR", scale: 0, symbol: "Rp" }, { code: "USD", scale: 2, symbol: "$" }, { code: "SGD", scale: 2, symbol: "S$" }];
const info = (code: string) => CURRENCIES.find((c) => c.code === code) ?? { code, scale: 2, symbol: code };

export type Sign = "none" | "plus" | "minus";
/** `Rp14.125.000`, `$60,79`, `+Rp8.500.000`, `−Rp25.000` — same as the phone. */
export function formatMinor(minor: number, currency: string, sign: Sign = "none"): string {
  const { scale, symbol } = info(currency);
  const abs = Math.abs(minor);
  const whole = Math.floor(abs / 10 ** scale).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  const frac = scale ? "," + (abs % 10 ** scale).toString().padStart(scale, "0") : "";
  const prefix = sign === "plus" ? "+" : sign === "minus" ? "−" : minor < 0 ? "−" : "";
  return `${prefix}${symbol}${whole}${frac}`;
}
export const toMinor = (major: number, currency: string) => Math.round(major * 10 ** info(currency).scale);
export const toMajor = (minor: number, currency: string) => minor / 10 ** info(currency).scale;

/** Keypad-style input: `45k`, `12rb+20rb`, `1,5`. Null when invalid. */
export function evaluate(raw: string): number | null {
  const s = raw.trim().toLowerCase().replace(/rb/g, "k").replace(/,/g, ".").replace(/\s+/g, "");
  if (!s) return null;
  let i = 0;
  const peek = () => s[i];
  const num = (): number => {
    if (peek() === "-") { i++; return -num(); }
    const start = i;
    while (i < s.length && /[0-9.]/.test(s[i]!)) i++;
    if (start === i) throw new Error("number");
    let v = parseFloat(s.slice(start, i));
    if (peek() === "k") { i++; v *= 1000; }
    return v;
  };
  const term = (): number => { let v = num(); while (peek() === "*" || peek() === "/") { const op = s[i++]; const r = num(); if (op === "/" && r === 0) throw new Error("zero"); v = op === "*" ? v * r : v / r; } return v; };
  const expr = (): number => { let v = term(); while (peek() === "+" || peek() === "-") { const op = s[i++]; const r = term(); v = op === "+" ? v + r : v - r; } return v; };
  try { const v = expr(); return i === s.length && isFinite(v) ? v : null; } catch { return null; }
}

export const pairKey = (from: string, to: string) => (to === "IDR" ? `${from}/IDR` : from === "IDR" ? `${to}/IDR` : `${from}/${to}`);

export class Rates {
  constructor(private toIdr: Record<string, number> = {}, private manual: Record<string, number> = {}) {}
  private cross(base: string, quote: string): number | null {
    const b = base === "IDR" ? 1 : this.toIdr[base]; const q = quote === "IDR" ? 1 : this.toIdr[quote];
    return b && q ? b / q : null;
  }
  api(from: string, to: string) { const [b, q] = pairKey(from, to).split("/"); return this.cross(b!, q!); }
  effective(from: string, to: string) { const k = pairKey(from, to); return this.manual[k] ?? this.api(from, to); }
  isManual(from: string, to: string) { return pairKey(from, to) in this.manual; }
  convert(minor: number, from: string, to: string): number | null {
    if (from === to) return minor;
    const rate = this.effective(from, to);
    if (!rate) return null;
    const base = pairKey(from, to).split("/")[0];
    const major = toMajor(minor, from);
    return toMinor(base === from ? major * rate : major / rate, to);
  }
}

export const live = <T extends Syncable>(xs: T[]) => xs.filter((x) => x.deletedAt === null);

export function balance(w: Wallet, txs: Tx[]): number {
  let t = w.openingMinor;
  for (const x of txs) {
    if (x.deletedAt !== null) continue;
    if (x.kind === "income" && x.walletId === w.id) t += x.amountMinor;
    else if (x.kind === "expense" && x.walletId === w.id) t -= x.amountMinor;
    else if (x.kind === "transfer") { if (x.walletId === w.id) t -= x.amountMinor; if (x.toWalletId === w.id) t += x.toAmountMinor ?? x.amountMinor; }
  }
  return t;
}

export function totalBalance(l: Ledger, currency: string, rates: Rates): number | null {
  let t = 0;
  for (const w of live(l.wallets)) { const v = rates.convert(balance(w, l.txs), w.currency, currency); if (v === null) return null; t += v; }
  return t;
}

export const monthRange = (year: number, month0: number): [number, number] => [Date.UTC(year, month0, 1), Date.UTC(year, month0 + 1, 1)];

/** Income and expense in [currency] for [from, until). Transfers are neutral. */
export function flow(l: Ledger, currency: string, rates: Rates, from: number, until: number, categoryId?: string) {
  const byId = new Map(l.wallets.map((w) => [w.id, w]));
  let income = 0, expense = 0;
  for (const x of l.txs) {
    if (x.deletedAt !== null || x.occurredAt < from || x.occurredAt >= until || x.kind === "transfer") continue;
    if (categoryId && x.categoryId !== categoryId) continue;
    const w = byId.get(x.walletId); if (!w) continue;
    const v = rates.convert(x.amountMinor, w.currency, currency); if (v === null) continue;
    if (x.kind === "income") income += v; else expense += v;
  }
  return { income, expense };
}

export function spendByCategory(l: Ledger, currency: string, rates: Rates, from: number, until: number) {
  const byId = new Map(l.wallets.map((w) => [w.id, w]));
  const out = new Map<string, number>();
  for (const x of l.txs) {
    if (x.deletedAt !== null || x.kind !== "expense" || x.occurredAt < from || x.occurredAt >= until) continue;
    const w = byId.get(x.walletId); const v = w && rates.convert(x.amountMinor, w.currency, currency);
    if (v == null) continue;
    const k = x.categoryId ?? "";
    out.set(k, (out.get(k) ?? 0) + v);
  }
  return [...out.entries()].map(([categoryId, minor]) => ({ categoryId, minor })).sort((a, b) => b.minor - a.minor);
}

export const newId = () => crypto.randomUUID().replaceAll("-", "").slice(0, 20);
export const dayStart = (ms: number) => Math.floor(ms / 86_400_000) * 86_400_000;

export function buildTransfer(from: Wallet, to: Wallet, minor: number, rates: Rates, manualRate: number | null, at: number): Tx | null {
  if (from.id === to.id || minor <= 0) return null;
  const base = { id: newId(), walletId: from.id, toWalletId: to.id, categoryId: null, kind: "transfer" as const, amountMinor: minor, note: "", occurredAt: at, recurringId: null, updatedAt: 0, deletedAt: null };
  if (from.currency === to.currency) return { ...base, toAmountMinor: minor, rate: null };
  const rate = manualRate ?? rates.effective(from.currency, to.currency);
  if (!rate) return null;
  const converted = new Rates({}, { [pairKey(from.currency, to.currency)]: rate }).convert(minor, from.currency, to.currency);
  return converted === null ? null : { ...base, toAmountMinor: converted, rate: String(rate) };
}

// --- Archive (.arthatis JSON) and CSV: identical shapes to the phone's `ArchiveData` ------------
export interface ArchiveData {
  version: number; createdAt: number; device: string; wallets: Wallet[]; categories: Category[]; transactions: Tx[];
  recurring: Recurring[]; rates: RateOverride[]; profile: Profile | null; sharedWallets: string[];
}

export function toCsv(l: Ledger): string {
  const w = new Map(l.wallets.map((x) => [x.id, x])); const c = new Map(l.categories.map((x) => [x.id, x]));
  const q = (s: string) => `"${s.replaceAll('"', '""')}"`;
  const rows = ["date,wallet,kind,category,amount,currency,to_wallet,to_amount,note"];
  for (const t of [...live(l.txs)].sort((a, b) => a.occurredAt - b.occurredAt)) {
    const src = w.get(t.walletId); const cur = src?.currency ?? "IDR"; const dst = t.toWalletId ? w.get(t.toWalletId) : undefined;
    rows.push([new Date(t.occurredAt).toISOString().slice(0, 10), q(src?.name ?? ""), t.kind, q(c.get(t.categoryId ?? "")?.name ?? ""), toMajor(t.amountMinor, cur), src?.currency ?? "", q(dst?.name ?? ""), t.toAmountMinor != null && dst ? toMajor(t.toAmountMinor, dst.currency) : "", q(t.note)].join(","));
  }
  return rows.join("\n") + "\n";
}
