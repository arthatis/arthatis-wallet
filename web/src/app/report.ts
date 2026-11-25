// Financial report for a date range, printed to PDF by the browser (no library). Same numbers as the phone's report.
import { Rates, balance, formatMinor, live, totalBalance, type Ledger, type Tx, type Wallet } from "./model";
import { esc, lang, t } from "./ui";

export interface ReportCategory { categoryId: string | null; minor: number; count: number }
export interface ReportGroup { kind: Tx["kind"]; categoryId: string | null; total: number; txs: Tx[] }
/** opening = net worth at `from` (opening balances + earlier transactions): never income. netWorth = at `until`. */
export interface Report {
  from: number; until: number; currency: string; opening: number; netWorth: number; income: number; expense: number;
  expenses: ReportCategory[]; incomes: ReportCategory[];
  wallets: { wallet: Wallet; opening: number; in: number; out: number; closing: number }[]; txs: Tx[]; groups: ReportGroup[];
}

/** Transactions with from <= occurredAt < until. Null when a rate needed for [currency] is missing. */
export function buildReport(l: Ledger, currency: string, rates: Rates, from: number, until: number): Report | null {
  const wallets = live(l.wallets); const byId = new Map(wallets.map((w) => [w.id, w]));
  const inRange = l.txs.filter((x) => x.deletedAt === null && x.occurredAt >= from && x.occurredAt < until && byId.has(x.walletId));
  const upToEnd = l.txs.filter((x) => x.occurredAt < until);
  const beforeStart = l.txs.filter((x) => x.occurredAt < from);
  let income = 0, expense = 0;
  const byCat = { income: new Map<string | null, ReportCategory>(), expense: new Map<string | null, ReportCategory>() };
  for (const x of inRange) {
    if (x.kind === "transfer") continue;
    const v = rates.convert(x.amountMinor, byId.get(x.walletId)!.currency, currency); if (v === null) return null;
    if (x.kind === "income") income += v; else expense += v;
    const g = byCat[x.kind]; const k = x.categoryId ?? null; const cur = g.get(k) ?? { categoryId: k, minor: 0, count: 0 };
    g.set(k, { ...cur, minor: cur.minor + v, count: cur.count + 1 });
  }
  const netWorth = totalBalance({ ...l, txs: upToEnd }, currency, rates); if (netWorth === null) return null;
  const opening = totalBalance({ ...l, txs: beforeStart }, currency, rates); if (opening === null) return null;
  const newest = [...inRange].sort((a, b) => b.occurredAt - a.occurredAt);
  const value = (x: Tx) => rates.convert(x.amountMinor, byId.get(x.walletId)!.currency, currency) ?? 0;
  const groups: ReportGroup[] = (["expense", "income", "transfer"] as const).flatMap((kind) => {
    const m = new Map<string | null, Tx[]>();
    for (const x of newest.filter((y) => y.kind === kind)) { const k = x.categoryId ?? null; m.set(k, [...(m.get(k) ?? []), x]); }
    return [...m.entries()].map(([categoryId, txs]) => ({ kind, categoryId, total: txs.reduce((n, x) => n + value(x), 0), txs })).sort((a, b) => b.total - a.total);
  });
  const sorted = (m: Map<string | null, ReportCategory>) => [...m.values()].sort((a, b) => b.minor - a.minor);
  return {
    from, until, currency, opening, netWorth, income, expense, groups, expenses: sorted(byCat.expense), incomes: sorted(byCat.income),
    wallets: wallets.map((w) => {
      let inn = 0, out = 0;
      for (const x of inRange) {
        if (x.kind === "income" && x.walletId === w.id) inn += x.amountMinor;
        else if (x.kind === "expense" && x.walletId === w.id) out += x.amountMinor;
        else if (x.kind === "transfer") { if (x.walletId === w.id) out += x.amountMinor; if (x.toWalletId === w.id) inn += x.toAmountMinor ?? x.amountMinor; }
      }
      return { wallet: w, opening: balance(w, beforeStart), in: inn, out, closing: balance(w, upToEnd) };
    }),
    txs: newest,
  };
}

const day = (ms: number) => new Date(ms).toLocaleDateString(lang() === "en" ? "en-GB" : "id-ID", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

/** Opens the report in a print window; the browser's "Save as PDF" makes the file. */
export function printReport(r: Report, l: Ledger) {
  const cat = (id: string | null) => (id && l.categories.find((c) => c.id === id)?.name) || t("Tanpa kategori", "Uncategorised");
  const wName = (id: string | null) => (id && l.wallets.find((w) => w.id === id)?.name) || "?";
  const f = (n: number, sign: "none" | "plus" | "minus" = "none") => esc(formatMinor(n, r.currency, sign));
  const catRows = (xs: ReportCategory[], total: number) => xs.length ? xs.map((c) => `<tr><td>${esc(cat(c.categoryId))} <span class="m">(${c.count})${total ? ` · ${((c.minor * 100) / total).toFixed(1)}%` : ""}</span></td><td class="r">${f(c.minor)}</td></tr>`).join("") : `<tr><td class="m">${t("Tidak ada data.", "No data.")}</td><td></td></tr>`;
  const kindName = (k: Tx["kind"]) => (k === "expense" ? t("Pengeluaran", "Expense") : k === "income" ? t("Pemasukan", "Income") : t("Transfer", "Transfer"));
  const txRow = (x: Tx) => {
    const w = l.wallets.find((y) => y.id === x.walletId); const toW = l.wallets.find((y) => y.id === x.toWalletId);
    const desc = x.kind === "transfer" ? `${x.note || "Transfer"} → ${wName(x.toWalletId)}${x.feeMinor ? ` (fee ${formatMinor(x.feeMinor, toW?.currency ?? r.currency)})` : ""}` : x.note || cat(x.categoryId);
    return `<tr><td>${day(x.occurredAt)}</td><td>${esc(desc)}</td><td>${esc(w?.name ?? "?")}</td><td class="r">${esc(formatMinor(x.amountMinor, w?.currency ?? r.currency, x.kind === "income" ? "plus" : x.kind === "expense" ? "minus" : "none"))}</td></tr>`;
  };
  // Grouped by category: a header row with count and subtotal (report currency), then that category's transactions.
  const txRows = r.groups.map((g) => `<tr class="g"><td colspan="3">${esc(cat(g.categoryId))} · ${kindName(g.kind)} (${g.txs.length})</td><td class="r">${f(g.total)}</td></tr>${g.txs.map(txRow).join("")}`).join("");
  const html = `<!doctype html><html lang="${lang()}"><head><meta charset="utf-8"><title>${t("Laporan Keuangan", "Financial Report")} ${day(r.from)} – ${day(r.until - 86_400_000)}</title>
<style>@page{size:A4;margin:16mm}body{font:11px/1.45 system-ui,Roboto,sans-serif;color:#181D18;margin:0}h1{color:#1B6D35;font-size:22px;margin:0 0 4px}h2{font-size:13px;border-bottom:1px solid #C2C9BE;padding-bottom:4px;margin:20px 0 6px}
table{width:100%;border-collapse:collapse}td,th{padding:3px 4px;text-align:left;vertical-align:top}th{font-weight:600}.r{text-align:right;white-space:nowrap}.m{color:#5C5F5C}.b td{font-weight:600}tr{break-inside:avoid}thead{display:table-header-group}tr.g td{font-weight:600;background:#EEF3EB;padding-top:6px}</style></head><body>
<h1>${t("Laporan Keuangan", "Financial Report")}</h1><div>${day(r.from)} – ${day(r.until - 86_400_000)}</div><div class="m">${t("Dibuat", "Generated")} ${day(Date.now())} · Arthatis Wallet</div>
<h2>${t("Ringkasan", "Summary")}</h2><table>
<tr><td>${t("Saldo awal periode (bukan pemasukan)", "Opening balance (not income)")}</td><td class="r">${f(r.opening)}</td></tr>
<tr><td>${t("Pemasukan", "Income")}</td><td class="r">${f(r.income, "plus")}</td></tr><tr><td>${t("Pengeluaran", "Expenses")}</td><td class="r">${f(r.expense, "minus")}</td></tr>
<tr><td>${t("Selisih", "Net")}</td><td class="r">${f(r.income - r.expense, r.income >= r.expense ? "plus" : "minus")}</td></tr>
<tr class="b"><td>${t("Saldo akhir periode (kas + investasi − utang kartu)", "Closing balance (cash + investments − card debt)")}</td><td class="r">${f(r.netWorth)}</td></tr></table>
<h2>${t("Pengeluaran per kategori", "Expenses by category")} (${r.currency})</h2><table>${catRows(r.expenses, r.expense)}</table>
<h2>${t("Pemasukan per kategori", "Income by category")} (${r.currency})</h2><table>${catRows(r.incomes, 0)}</table>
<h2>${t("Per dompet", "By wallet")}</h2><table><thead><tr><th>${t("Dompet", "Wallet")}</th><th class="r">${t("Awal", "Opening")}</th><th class="r">${t("Masuk", "In")}</th><th class="r">${t("Keluar", "Out")}</th><th class="r">${t("Saldo akhir", "Closing")}</th></tr></thead>
${r.wallets.map((w) => `<tr><td>${esc(w.wallet.name)}</td><td class="r">${esc(formatMinor(w.opening, w.wallet.currency))}</td><td class="r">${esc(formatMinor(w.in, w.wallet.currency))}</td><td class="r">${esc(formatMinor(w.out, w.wallet.currency))}</td><td class="r">${esc(formatMinor(w.closing, w.wallet.currency))}</td></tr>`).join("")}</table>
<h2>${t("Transaksi per kategori", "Transactions by category")} (${r.txs.length})</h2><table><thead><tr><th>${t("Tanggal", "Date")}</th><th>${t("Keterangan", "Description")}</th><th>${t("Dompet", "Wallet")}</th><th class="r">${t("Nominal", "Amount")}</th></tr></thead>${txRows || `<tr><td class="m" colspan="4">${t("Tidak ada data.", "No data.")}</td></tr>`}</table>
<p class="m">${t("Nominal dalam mata uang masing-masing dompet kecuali disebut lain.", "Amounts are in each wallet's currency unless stated.")}</p></body></html>`;
  const w = window.open("", "_blank"); if (!w) return false;
  w.document.open(); w.document.write(html); w.document.close();
  w.addEventListener("load", () => w.print()); setTimeout(() => { try { w.focus(); w.print(); } catch { /* already printed */ } }, 400);
  return true;
}
