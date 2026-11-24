import * as C from "./crypto";
import { barChart, donut, lineChart } from "./charts";
import {
  ENTITY, Rates, balance, buildTransfer, evaluate, evaluateAmount, amountText, WALLET_ICONS, WALLET_COLORS, walletIconKey, investmentTotal, creditTotal, flow, formatMinor, live, monthRange, newId, pairKey, spendByCategory, toCsv, toMinor, totalBalance, dayStart,
  CURRENCIES, type ArchiveData, type Category, type EntryKind, type Frequency, type Ledger, type Recurring, type Tx, type Wallet, type WalletKind,
} from "./model";
import { session } from "./session";
import { confirmDialog, dialog, download, esc, field, icon, select, t, toast } from "./ui";
import { fetchRates } from "./rates";

export interface Ctx { ledger: Ledger; rates: Rates; currency: string; navigate: (page: string) => void; rerender: () => void }

const KIND: Partial<Record<WalletKind, [string, string]>> = { biasa: ["Biasa", "Regular"], investasi: ["Investasi", "Investment"], kartu: ["Kartu kredit", "Credit card"] };
const kindLabel = (k: WalletKind) => t(...(KIND[k] ?? KIND.biasa!));
/** Old rekening/tunai/ewallet wallets count as regular. */
const typeOf = (k: WalletKind): WalletKind => (k === "investasi" || k === "kartu" ? k : "biasa");
const EN_CAT: Record<string, string> = { food: "Food", shopping: "Shopping", bills: "Bills", transport: "Transport", fuel: "Fuel", fun: "Entertainment", home: "Home", health: "Health", salary: "Salary", other_income: "Income", transfer: "Transfer" };
const catName = (c?: Category) => !c ? t("Tanpa kategori", "Uncategorised") : document.documentElement.lang === "en" && c.key && EN_CAT[c.key] ? EN_CAT[c.key]! : c.name;
const dateLong = (ms: number) => new Intl.DateTimeFormat(document.documentElement.lang === "en" ? "en" : "id-ID", { weekday: "long", day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(ms);
const dateShort = (ms: number) => new Intl.DateTimeFormat(document.documentElement.lang === "en" ? "en" : "id-ID", { day: "numeric", month: "short", timeZone: "UTC" }).format(ms);
const monthShort = (ms: number) => new Intl.DateTimeFormat(document.documentElement.lang === "en" ? "en" : "id-ID", { month: "short", timeZone: "UTC" }).format(ms);
const monthLong = (ms: number) => new Intl.DateTimeFormat(document.documentElement.lang === "en" ? "en" : "id-ID", { month: "long", year: "numeric", timeZone: "UTC" }).format(ms);
const sign = (k: EntryKind) => (k === "income" ? "plus" : k === "expense" ? "minus" : "none") as "plus" | "minus" | "none";
const iconFor = (c?: Category) => (c?.key === "food" ? "food" : c?.key === "transfer" ? "swap" : c?.kind === "income" ? "wallet" : "cat");

export function txRow(tx: Tx, l: Ledger, edit = true): string {
  const w = l.wallets.find((x) => x.id === tx.walletId); const c = l.categories.find((x) => x.id === tx.categoryId);
  const cur = w?.currency ?? "IDR";
  const title = tx.note || (tx.kind === "transfer" ? t("Transfer", "Transfer") : catName(c));
  const to = tx.toWalletId ? l.wallets.find((x) => x.id === tx.toWalletId) : undefined;
  const sub = tx.kind === "transfer" ? `${w?.name ?? "?"} → ${to?.name ?? "?"} · ${dateShort(tx.occurredAt)}` : `${catName(c)} · ${w?.name ?? "?"} · ${dateShort(tx.occurredAt)}`;
  return `<${edit ? `button data-act="edit-tx" data-id="${tx.id}"` : "div"} class="row"><span class="icon">${icon(iconFor(c))}</span><span class="texts"><b>${esc(title)}</b><span class="muted small">${esc(sub)}</span></span><span class="amount ${tx.kind === "income" ? "in" : ""}">${esc(formatMinor(tx.amountMinor, cur, sign(tx.kind)))}</span></${edit ? "button" : "div"}>`;
}

function walletRow(w: Wallet, l: Ledger): string {
  const key = walletIconKey(w); const tint = w.color ? ` style="--wc:${esc(w.color)}"` : "";
  const meta = typeOf(w.kind) === "biasa" ? w.currency : `${kindLabel(w.kind)} · ${w.currency}`;
  return `<button class="row wallet" data-act="edit-wallet" data-id="${w.id}"><span class="icon${w.color ? " tinted" : ""}"${tint}>${icon(key === "wallet" ? "wallet" : "wi_" + key)}</span><span class="texts"><b>${esc(w.name)}</b><span class="muted small">${esc(meta)}</span></span><span class="amount">${esc(formatMinor(balance(w, l.txs), w.currency))}</span></button>`;
}

// --- Dashboard (W02) ----------------------------------------------------------------------------
function chartsBlock(c: Ctx): string {
  const { ledger: l, rates, currency } = c;
  const now = new Date(); const [ms, me] = monthRange(now.getUTCFullYear(), now.getUTCMonth());
  const total = totalBalance(l, currency, rates);
  const months = Array.from({ length: 6 }, (_, i) => { const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 5 + i, 1)); return monthRange(d.getUTCFullYear(), d.getUTCMonth()); });
  const bars = months.map(([a, b]) => { const x = flow(l, currency, rates, a, b); return { label: monthShort(a), a: x.income, b: x.expense }; });
  const fmt = (n: number) => formatMinor(n, currency);
  // Balance trend: today's balance minus the net flow of later months.
  let running = total ?? 0; const trend: { label: string; v: number }[] = [];
  for (let i = months.length - 1; i >= 0; i--) { trend.unshift({ label: monthShort(months[i]![0]), v: running }); const x = flow(l, currency, rates, months[i]![0], months[i]![1]); running -= x.income - x.expense; }
  const prev = trend.at(-2)?.v ?? 0; const growth = prev ? (((trend.at(-1)?.v ?? 0) - prev) * 100) / prev : 0;
  return `<div class="card"><div class="section-title">${t("Arus kas 6 bulan", "Cash flow, 6 months")}<span class="grow"></span><span class="legend"><span><i style="background:var(--primary)"></i>${t("Masuk", "Income")}</span><span><i style="background:var(--tertiary)"></i>${t("Keluar", "Expense")}</span></span></div>${barChart(bars, fmt)}</div>
  <div class="grid cols-2">${categoryCard(c, ms, me)}<div class="card"><div class="section-title">${t("Tren saldo", "Balance trend")}</div><p class="muted small">${t("Saldo", "Balance")} ${monthShort(ms)} ${esc(fmt(trend.at(-1)?.v ?? 0))} · ${growth >= 0 ? "+" : "−"}${Math.abs(growth).toFixed(1).replace(".", ",")}%</p>${lineChart(trend, fmt)}</div></div>
  ${budgetCard(c, ms, me)}`;
}

export function dashboard(c: Ctx): string {
  const { ledger: l, rates, currency } = c;
  const now = new Date(); const [ms, me] = monthRange(now.getUTCFullYear(), now.getUTCMonth());
  const total = totalBalance(l, currency, rates); const f = flow(l, currency, rates, ms, me);
  const wallets = live(l.wallets); const fmt = (n: number) => formatMinor(n, currency);
  const inv = investmentTotal(l, currency, rates), debt = creditTotal(l, currency, rates);
  const extras = [inv ? `${t("Investasi", "Investments")} ${fmt(inv)}` : "", debt ? `${t("Kartu kredit", "Credit cards")} ${fmt(debt)}` : ""].filter(Boolean).join(" · ");
  return `
  <div class="grid cols-top">
    <div class="hero"><div style="display:flex;justify-content:space-between"><span class="small">${t("Total saldo", "Total balance")}</span><span class="pill" style="background:var(--surfaceContainerLowest);color:var(--onSurface)"><i class="dot"></i>${session.online ? t("Tersinkron", "Synced") : t("Offline", "Offline")}</span></div>
      <div class="big">${total === null ? "—" : esc(fmt(total))}</div><span class="small">${t(`${wallets.length} dompet · total kas dalam ${currency}`, `${wallets.length} wallets · cash total in ${currency}`)}${extras ? `<br>${esc(extras)}` : ""}</span><hr>
      <div class="stats"><div><div class="small">${t("Masuk", "Income")}</div><div class="income">${esc(formatMinor(f.income, currency, "plus"))}</div></div><div><div class="small">${t("Keluar", "Expense")}</div><div class="expense">${esc(formatMinor(f.expense, currency, "minus"))}</div></div></div></div>
    <div class="card"><div class="section-title">${t("Bareng", "Shared")}<span class="grow"></span><button class="link" data-act="go" data-page="bareng">${t("Buka", "Open")}</button></div><p class="muted">${t("Wallet bareng ada di menu Bareng. Kunci grup disalin dari HP.", "Shared wallets live in the Shared menu. Group keys are mirrored from your phone.")}</p></div>
  </div>
  ${chartsBlock(c)}
  <div class="section-title">${t("Dompet", "Wallets")}<span class="grow"></span><button class="link" data-act="go" data-page="wallets">${t("Lihat semua", "See all")}</button></div>
  <div class="grid cols-3">${wallets.slice(0, 3).map((w) => walletRow(w, l)).join("") || `<p class="muted">${t("Belum ada dompet.", "No wallets yet.")}</p>`}</div>
  <div class="section-title">${t("Transaksi terbaru", "Recent transactions")}<span class="grow"></span><button class="link" data-act="go" data-page="transactions">${t("Lihat semua", "See all")}</button></div>
  <div class="list">${live(l.txs).slice(0, 5).map((x) => txRow(x, l)).join("") || `<p class="muted">${t("Belum ada transaksi.", "No transactions yet.")}</p>`}</div>`;
}

const PALETTE = ["var(--primary)", "var(--tertiary)", "#8B5000", "#6750A4", "#B3261E", "#00696E"];
function categoryCard(c: Ctx, from: number, until: number): string {
  const spend = spendByCategory(c.ledger, c.currency, c.rates, from, until);
  const total = spend.reduce((n, s) => n + s.minor, 0);
  const top = spend.slice(0, 5);
  const slices = top.map((s, i) => ({ label: catName(c.ledger.categories.find((x) => x.id === s.categoryId)), value: s.minor, color: PALETTE[i % PALETTE.length]! }));
  const legend = top.map((s, i) => `<div style="display:flex;gap:8px;align-items:center"><i style="width:10px;height:10px;border-radius:2px;background:${PALETTE[i % PALETTE.length]}"></i><span style="flex:1">${esc(catName(c.ledger.categories.find((x) => x.id === s.categoryId)))}</span><span>${esc(formatMinor(s.minor, c.currency))}</span><span class="muted small" style="width:36px;text-align:right">${total ? Math.round((s.minor * 100) / total) : 0}%</span></div>`).join("");
  return `<div class="card"><div class="section-title">${t("Pengeluaran per kategori", "Spending by category")}</div><p class="muted small">${esc(monthLong(from))} · total ${esc(formatMinor(total, c.currency))}</p>
    <div class="donut-wrap">${donut(slices, formatMinor(total, c.currency), t("Keluar", "Spent"))}<div style="display:grid;gap:8px">${legend || `<span class="muted">${t("Belum ada pengeluaran.", "No spending yet.")}</span>`}</div></div></div>`;
}

function budgetCard(c: Ctx, from: number, until: number): string {
  const spend = new Map(spendByCategory(c.ledger, c.currency, c.rates, from, until).map((s) => [s.categoryId, s.minor]));
  const lines = live(c.ledger.budgets);
  const rows = lines.map((b) => {
    const cat = c.ledger.categories.find((x) => x.id === b.categoryId); const spent = spend.get(b.categoryId) ?? 0; const over = spent > b.monthlyMinor;
    return `<div style="display:grid;gap:6px"><div style="display:flex;justify-content:space-between"><b>${esc(catName(cat))}</b><span class="muted small">${esc(formatMinor(spent, c.currency))} ${t("dari", "of")} ${esc(formatMinor(b.monthlyMinor, c.currency))}</span></div><div class="bar ${over ? "over" : ""}"><i style="width:${Math.min(100, (spent * 100) / b.monthlyMinor)}%"></i></div></div>`;
  }).join("");
  return `<div class="card"><div class="section-title">${t("Anggaran vs realisasi", "Budget vs actual")}<span class="grow"></span><button class="link" data-act="edit-budgets">${t("Atur", "Edit")}</button></div><p class="muted small">${esc(monthLong(from))}</p>${rows || `<p class="muted">${t("Belum ada anggaran. Atur di sini atau di HP (Pengaturan → Anggaran).", "No budgets yet. Set them here or on your phone (Settings → Budgets).")}</p>`}<p class="muted small">${t("Tagihan bersifat tetap — tidak dianggarkan.", "Bills are fixed — not budgeted.")}</p></div>`;
}

// --- Transactions -------------------------------------------------------------------------------
export function transactions(c: Ctx, state: { q: string; f: string }): string {
  const q = state.q.trim().toLowerCase(); const l = c.ledger;
  const items = live(l.txs).filter((x) => (state.f === "all" || (state.f === "income" && x.kind === "income") || (state.f === "expense" && x.kind === "expense")) && (!q || x.note.toLowerCase().includes(q) || l.wallets.find((w) => w.id === x.walletId)?.name.toLowerCase().includes(q) || catName(l.categories.find((k) => k.id === x.categoryId)).toLowerCase().includes(q)));
  const groups = new Map<number, Tx[]>(); for (const x of items) { const d = dayStart(x.occurredAt); groups.set(d, [...(groups.get(d) ?? []), x]); }
  const chip = (id: string, label: string) => `<button class="chip" data-act="filter" data-f="${id}" aria-pressed="${state.f === id}">${label}</button>`;
  return `<div class="search">${icon("search")}<input id="q" value="${esc(state.q)}" placeholder="${t("Cari transaksi, dompet…", "Search transactions, wallets…")}" autocomplete="off"></div>
  <div class="chips">${chip("all", t("Semua", "All"))}${chip("income", t("Pemasukan", "Income"))}${chip("expense", t("Pengeluaran", "Expenses"))}<span style="flex:1"></span><button class="btn" data-act="add-tx">${icon("add")}${t("Tambah transaksi", "Add transaction")}</button><button class="btn tonal" data-act="transfer">${icon("swap")}${t("Transfer", "Transfer")}</button></div>
  ${[...groups.entries()].sort((a, b) => b[0] - a[0]).map(([d, xs]) => `<p class="muted">${esc(dateLong(d))}</p><div class="list">${xs.map((x) => txRow(x, l)).join("")}</div>`).join("") || `<p class="muted">${t("Tidak ada transaksi.", "No transactions.")}</p>`}`;
}

export async function txDialog(c: Ctx, existing?: Tx) {
  const l = c.ledger; const wallets = live(l.wallets);
  if (!wallets.length) { toast(t("Tambah dompet dulu.", "Add a wallet first.")); return; }
  const kind = existing?.kind ?? "expense"; const w0 = existing ? l.wallets.find((w) => w.id === existing.walletId) : wallets[0]; const cur = w0?.currency ?? "IDR";
  const cats = live(l.categories).filter((k) => !k.hidden && k.kind !== "transfer");
  const body = `${select("kind", t("Jenis", "Type"), [["expense", t("Pengeluaran", "Expense")], ["income", t("Pemasukan", "Income")]], kind)}
    ${field("amount", t("Nominal", "Amount"), existing ? amountText(existing.amountMinor, cur) : "", { placeholder: cur === "IDR" ? "25k" : "", hint: cur === "IDR" ? t("Boleh “45k”, “12rb+20rb”.", "You can type “45k”, “12k+20k”.") : "", required: true, amount: cur })}
    ${select("category", t("Kategori", "Category"), cats.map((k) => [k.id, `${catName(k)} (${k.kind === "income" ? t("masuk", "in") : t("keluar", "out")})`]), existing?.categoryId ?? cats[0]?.id ?? "")}
    ${select("wallet", t("Dompet", "Wallet"), wallets.map((w) => [w.id, `${w.name} · ${w.currency}`]), existing?.walletId ?? wallets[0]!.id)}
    ${field("date", t("Tanggal", "Date"), new Date(existing?.occurredAt ?? Date.now()).toISOString().slice(0, 10), { type: "date" })}
    ${field("note", t("Catatan", "Note"), existing?.note ?? "")}
    ${existing ? `<label class="row" style="background:none;padding:0"><input type="checkbox" name="del" value="1"> <span class="error-text">${t("Hapus transaksi ini", "Delete this transaction")}</span></label>` : ""}`;
  const f = await dialog(existing ? t("Ubah transaksi", "Edit transaction") : t("Tambah transaksi", "Add transaction"), body);
  if (!f) return;
  if (existing && f.get("del") === "1") { await deleteTx(existing); return; }
  const w = wallets.find((x) => x.id === f.get("wallet")); const v = w ? evaluateAmount(String(f.get("amount") ?? ""), w.currency) : null;
  if (!w || v === null || v <= 0) { toast(t("Nominal tidak valid.", "Invalid amount.")); return; }
  session.put(ENTITY.tx, { ...(existing ?? { id: newId(), toWalletId: null, toAmountMinor: null, rate: null, recurringId: null, deletedAt: null, updatedAt: 0 }), walletId: w.id, categoryId: String(f.get("category")) || null, kind: String(f.get("kind")) as EntryKind, amountMinor: toMinor(v, w.currency), note: String(f.get("note") ?? "").trim(), occurredAt: Date.parse(String(f.get("date")) + "T00:00:00Z") || Date.now() });
  toast(t("Transaksi disimpan.", "Transaction saved."));
}

export async function deleteTx(tx: Tx) {
  if (await confirmDialog(t("Hapus transaksi ini?", "Delete this transaction?"), t("Saldo dompet dihitung ulang.", "The wallet balance is recalculated."), t("Hapus", "Delete"))) { session.put(ENTITY.tx, { ...tx, deletedAt: Date.now() }); toast(t("Dihapus.", "Deleted.")); }
}

export async function transferDialog(c: Ctx) {
  const wallets = live(c.ledger.wallets);
  if (wallets.length < 2) { toast(t("Butuh minimal dua dompet.", "You need at least two wallets.")); return; }
  const opts: [string, string][] = wallets.map((w) => [w.id, `${w.name} · ${w.currency}`]);
  const f = await dialog(t("Transfer antar dompet", "Transfer between wallets"), `${select("from", t("Dari", "From"), opts, wallets[0]!.id)}${select("to", t("Ke", "To"), opts, wallets[1]!.id)}${field("amount", t("Nominal", "Amount"), "", { required: true, amount: wallets[0]!.currency })}${field("rate", t("Kurs (opsional)", "Rate (optional)"), "", { hint: t("Untuk beda mata uang. Kosong = kurs API.", "For different currencies. Empty = API rate.") })}`, t("Transfer", "Transfer"));
  if (!f) return;
  const from = wallets.find((w) => w.id === f.get("from")), to = wallets.find((w) => w.id === f.get("to"));
  const v = from ? evaluateAmount(String(f.get("amount") ?? ""), from.currency) : null; const manual = evaluate(String(f.get("rate") ?? "").replace(/,/g, "."));
  if (!from || !to || v === null) return;
  const tx = buildTransfer(from, to, toMinor(v, from.currency), c.rates, manual, Date.now());
  if (!tx) { toast(t("Transfer gagal: periksa dompet dan kurs.", "Transfer failed: check wallets and rate.")); return; }
  session.put(ENTITY.tx, tx); toast(t("Transfer disimpan.", "Transfer saved."));
}

// --- Wallets ------------------------------------------------------------------------------------
export function wallets(c: Ctx): string {
  const ws = live(c.ledger.wallets);
  return `<div style="display:flex;gap:8px"><button class="btn" data-act="add-wallet">${icon("add")}${t("Tambah dompet", "Add wallet")}</button><button class="btn tonal" data-act="transfer">${icon("swap")}${t("Transfer", "Transfer")}</button></div>
  <div class="grid cols-2">${ws.map((w) => `<div style="display:flex;gap:8px;align-items:center">${walletRow(w, c.ledger)}<button class="btn text danger sm" data-act="del-wallet" data-id="${w.id}" aria-label="${t("Hapus", "Delete")}">${icon("del")}</button></div>`).join("") || `<p class="muted">${t("Belum ada dompet.", "No wallets yet.")}</p>`}</div>
  <p class="muted small">${t("Mata uang dompet tidak bisa diubah setelah dibuat.", "A wallet's currency can't be changed after it's created.")}</p>`;
}

function pickers(icon0: string, color0: string): string {
  const icons = WALLET_ICONS.map((k) => `<label class="pick"><input type="radio" name="icon" value="${k}" ${k === icon0 ? "checked" : ""}><span>${icon(k === "wallet" ? "wallet" : "wi_" + k)}</span></label>`).join("");
  const colors = [["", t("Bawaan", "Default")] as [string, string], ...WALLET_COLORS.map((x) => [x, x] as [string, string])].map(([v, l]) => `<label class="swatch" title="${esc(l)}"><input type="radio" name="color" value="${v}" ${v === color0 ? "checked" : ""}><span style="${v ? `background:${v}` : ""}">${v ? "" : "A"}</span></label>`).join("");
  return `<div class="field"><label>${t("Ikon", "Icon")}</label><div class="picks">${icons}</div></div><div class="field"><label>${t("Warna", "Colour")}</label><div class="picks">${colors}</div></div>`;
}

export async function walletDialog(c: Ctx, existing?: Wallet) {
  const body = `${field("name", t("Nama dompet", "Wallet name"), existing?.name ?? "", { required: true })}${select("kind", t("Tipe", "Type"), (["biasa", "investasi", "kartu"] as WalletKind[]).map((k) => [k, kindLabel(k)]), existing ? typeOf(existing.kind) : "biasa")}
    <p class="muted small">${t("Kartu kredit: saldo boleh minus, dihitung sebagai utang di luar total kas. Investasi: dihitung terpisah.", "Credit card: balance may go negative and counts as debt outside the cash total. Investment: counted apart.")}</p>
    ${pickers(existing ? walletIconKey(existing) : "wallet", existing?.color ?? "")}
    ${existing ? `<div class="field"><label>${t("Mata uang", "Currency")}</label><input value="${existing.currency}" disabled></div>` : select("currency", t("Mata uang", "Currency"), CURRENCIES.map((x) => [x.code, `${x.code} ${x.symbol}`]), c.currency)}
    ${existing ? "" : field("opening", t("Saldo awal (opsional)", "Opening balance (optional)"), "", { amount: c.currency })}
`;
  const f = await dialog(existing ? t("Ubah dompet", "Edit wallet") : t("Tambah dompet", "Add wallet"), body);
  if (!f) return;
  const currency = existing?.currency ?? String(f.get("currency"));
  const opening = existing ? existing.openingMinor : toMinor(evaluateAmount(String(f.get("opening") ?? ""), currency) ?? 0, currency);
  session.put(ENTITY.wallet, { ...(existing ?? { id: newId(), position: live(c.ledger.wallets).length, deletedAt: null, updatedAt: 0 }), name: String(f.get("name")).trim(), kind: String(f.get("kind")) as WalletKind, icon: String(f.get("icon") ?? "wallet"), color: String(f.get("color") ?? "") || null, currency, openingMinor: opening });
  toast(t("Dompet disimpan.", "Wallet saved."));
}

// --- Recurring ----------------------------------------------------------------------------------
const FREQ: Record<string, [string, string]> = { daily: ["Harian", "Daily"], weekly: ["Mingguan", "Weekly"], monthly: ["Bulanan", "Monthly"], yearly: ["Tahunan", "Yearly"] };
const WEEKDAYS: [string, string][] = [["1", "Senin|Monday"], ["2", "Selasa|Tuesday"], ["3", "Rabu|Wednesday"], ["4", "Kamis|Thursday"], ["5", "Jumat|Friday"], ["6", "Sabtu|Saturday"], ["7", "Minggu|Sunday"]];
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "Mei|May", "Jun", "Jul", "Agu|Aug", "Sep", "Okt|Oct", "Nov", "Des|Dec"];

export function recurringSection(c: Ctx): string {
  const items = live(c.ledger.recurring);
  const rows = items.map((r) => {
    const w = c.ledger.wallets.find((x) => x.id === r.walletId); const cur = w?.currency ?? "IDR";
    return `<div class="row"><button class="row" data-act="edit-recurring" data-id="${r.id}" style="flex:1"><span class="texts"><b>${esc(r.name)}</b><span class="muted small">${t(...FREQ[r.frequency]!)} · ${esc(formatMinor(r.amountMinor, cur))}${r.paused ? ` · ${t("dijeda", "paused")}` : ""}</span></span></button><button class="btn text sm" data-act="toggle-recurring" data-id="${r.id}">${r.paused ? t("Lanjut", "Resume") : t("Jeda", "Pause")}</button><button class="btn text sm danger" data-act="del-recurring" data-id="${r.id}" aria-label="${t("Hapus", "Delete")}">${icon("del")}</button></div>`;
  }).join("");
  return `<div class="card"><div class="section-title">${t("Transaksi berulang", "Recurring")}<span class="grow"></span><button class="btn tonal sm" data-act="add-recurring">${icon("add")}${t("Tambah", "Add")}</button></div>${rows || `<p class="muted">${t("Belum ada. Gaji, tagihan, langganan — catat sekali.", "None yet. Salary, bills, subscriptions — log once.")}</p>`}<p class="muted small">${t("Ditambahkan otomatis oleh HP saat harinya tiba.", "Added automatically by your phone when the day comes.")}</p></div>`;
}

const tt = (pair: string) => { const [a, b] = pair.split("|"); return t(a!, b ?? a!); };

export async function recurringDialog(c: Ctx, existing?: Recurring) {
  const wallets = live(c.ledger.wallets);
  if (!wallets.length) { toast(t("Tambah dompet dulu.", "Add a wallet first.")); return; }
  const w0 = wallets.find((w) => w.id === existing?.walletId) ?? wallets[0]!;
  const cats = live(c.ledger.categories).filter((k) => !k.hidden && k.kind !== "transfer");
  const today = new Date();
  const body = `${field("name", t("Nama", "Name"), existing?.name ?? "", { required: true, placeholder: t("Gaji, Listrik, Netflix", "Salary, Electricity, Netflix") })}
    ${select("kind", t("Jenis", "Type"), [["expense", t("Pengeluaran", "Expense")], ["income", t("Pemasukan", "Income")]], existing?.kind ?? "expense")}
    ${select("wallet", t("Dompet", "Wallet"), wallets.map((w) => [w.id, `${w.name} · ${w.currency}`]), w0.id)}
    ${field("amount", t("Nominal (dalam mata uang dompet)", "Amount (in the wallet's currency)"), existing ? amountText(existing.amountMinor, w0.currency) : "", { required: true, amount: w0.currency })}
    ${select("category", t("Kategori", "Category"), cats.map((k) => [k.id, `${catName(k)} (${k.kind === "income" ? t("masuk", "in") : t("keluar", "out")})`]), existing?.categoryId ?? cats[0]?.id ?? "")}
    ${select("frequency", t("Diulang", "Repeats"), Object.keys(FREQ).map((k) => [k, t(...FREQ[k]!)]), existing?.frequency ?? "monthly")}
    ${select("weekday", t("Hari (mingguan)", "Weekday (weekly)"), WEEKDAYS.map(([v, l]) => [v, tt(l)]), String(existing?.weekday ?? ((today.getUTCDay() + 6) % 7) + 1))}
    ${select("month", t("Bulan (tahunan)", "Month (yearly)"), MONTHS.map((m, i) => [String(i + 1), tt(m)]), String(existing?.month ?? today.getUTCMonth() + 1))}
    ${field("day", t("Tanggal 1–28 (bulanan/tahunan)", "Day 1–28 (monthly/yearly)"), String(Math.min(28, existing?.dayOfMonth ?? today.getUTCDate())), { type: "number" })}
    <label class="row" style="background:none;padding:0"><input type="checkbox" name="reminder" value="1" ${existing?.reminder ?? true ? "checked" : ""}> <span>${t("Ingatkan H-1 (notifikasi di HP)", "Remind the day before (phone notification)")}</span></label>`;
  const f = await dialog(existing ? t("Ubah transaksi berulang", "Edit recurring") : t("Tambah transaksi berulang", "Add recurring"), body);
  if (!f) return;
  const w = wallets.find((x) => x.id === f.get("wallet")) ?? w0;
  const v = evaluateAmount(String(f.get("amount") ?? ""), w.currency);
  const name = String(f.get("name") ?? "").trim(); const day = Number(f.get("day"));
  const frequency = String(f.get("frequency")) as Frequency;
  if (!name || v === null || v <= 0 || !String(f.get("category"))) { toast(t("Isi nama, nominal, dan kategori.", "Fill in name, amount and category.")); return; }
  if ((frequency === "monthly" || frequency === "yearly") && !(day >= 1 && day <= 28)) { toast(t("Tanggal harus 1–28.", "Day must be 1–28.")); return; }
  session.put(ENTITY.recurring, {
    ...(existing ?? { id: newId(), paused: false, lastRunDay: null, deletedAt: null, updatedAt: 0 }),
    name, walletId: w.id, categoryId: String(f.get("category")), kind: String(f.get("kind")) as EntryKind, amountMinor: toMinor(v, w.currency), frequency,
    dayOfMonth: frequency === "monthly" || frequency === "yearly" ? day : null, weekday: frequency === "weekly" ? Number(f.get("weekday")) : null,
    month: frequency === "yearly" ? Number(f.get("month")) : null, reminder: f.get("reminder") === "1",
  });
  toast(t("Transaksi berulang disimpan.", "Recurring saved."));
}

export async function deleteRecurring(r: Recurring) {
  if (!(await confirmDialog(t("Hapus transaksi berulang?", "Delete recurring?"), t("Transaksi yang sudah tercatat tidak ikut terhapus.", "Transactions already logged stay."), t("Hapus", "Delete")))) return;
  session.put(ENTITY.recurring, { ...r, deletedAt: Date.now() });
}

export async function deleteWallet(c: Ctx, w: Wallet) {
  const n = live(c.ledger.txs).filter((x) => x.walletId === w.id || x.toWalletId === w.id).length;
  if (!(await confirmDialog(t(`Hapus ${w.name}?`, `Delete ${w.name}?`), t(`Dompet dan ${n} transaksinya dihapus.`, `The wallet and its ${n} transactions are deleted.`), t("Hapus", "Delete")))) return;
  for (const x of live(c.ledger.txs)) if (x.walletId === w.id || x.toWalletId === w.id) session.put(ENTITY.tx, { ...x, deletedAt: Date.now() });
  session.put(ENTITY.wallet, { ...w, deletedAt: Date.now() });
}

// --- Reports ------------------------------------------------------------------------------------
export function reports(c: Ctx): string { return chartsBlock(c); }

export async function budgetsDialog(c: Ctx) {
  const cats = live(c.ledger.categories).filter((k) => k.kind === "expense" && !k.hidden && k.key !== "bills");
  const f = await dialog(t("Anggaran bulanan", "Monthly budgets"), cats.map((k) => field(`b_${k.id}`, `${catName(k)} (${c.currency})`, amountText(live(c.ledger.budgets).find((b) => b.categoryId === k.id)?.monthlyMinor ?? 0, c.currency), { hint: "", amount: c.currency })).join(""));
  if (!f) return;
  for (const k of cats) {
    const v = evaluateAmount(String(f.get(`b_${k.id}`) ?? ""), c.currency); const existing = c.ledger.budgets.find((b) => b.categoryId === k.id);
    if (v && v > 0) session.put(ENTITY.budget, { ...(existing ?? { id: `bud_${k.id}`, categoryId: k.id, updatedAt: 0 }), monthlyMinor: toMinor(v, c.currency), deletedAt: null });
    else if (existing && existing.deletedAt === null) session.put(ENTITY.budget, { ...existing, deletedAt: Date.now() });
  }
}

// --- Data & backup ------------------------------------------------------------------------------
export function dataPage(c: Ctx): string {
  const n = live(c.ledger.txs).length;
  return `<div class="grid cols-2"><div class="card"><div class="section-title">${t("Ekspor data", "Export data")}</div><p class="muted">${t(".arthatis dienkripsi dengan recovery key dari HP; atau CSV.", ".arthatis is encrypted with the recovery key from your phone; or CSV.")}</p><div class="chips"><button class="btn" data-act="export-arthatis">${t("Ekspor .arthatis", "Export .arthatis")}</button><button class="btn tonal" data-act="export-csv">CSV</button></div></div>
  <div class="card"><div class="section-title">${t("Impor data", "Import data")}</div><p class="muted">${t("Gabungkan file .arthatis atau CSV. Yang sama dilewati.", "Merge a .arthatis or CSV file. Identical entries are skipped.")}</p><label class="btn outlined" style="width:fit-content">${t("Pilih file", "Choose file")}<input type="file" id="import-file" accept=".arthatis,.csv,text/csv" hidden></label></div></div>
  <p class="muted small">${t(`${n} transaksi di salinan ini.`, `${n} transactions in this copy.`)}</p>`;
}

export async function exportArthatis(c: Ctx) {
  const f = await dialog("Recovery key", `<p class="muted">${t("Masukkan recovery key dari HP untuk mengenkripsi file.", "Enter the recovery key from your phone to encrypt the file.")}</p>${field("rk", "ABCD-EFGH-…", "", { required: true })}`, t("Ekspor", "Export"));
  if (!f) return;
  const rk = C.parseRecoveryKey(String(f.get("rk"))); if (!rk) { toast(t("Recovery key tidak valid.", "Invalid recovery key.")); return; }
  const l = c.ledger;
  const data: ArchiveData = { version: 1, createdAt: Date.now(), device: "Web", wallets: l.wallets, categories: l.categories, transactions: l.txs, recurring: l.recurring, rates: l.overrides, profile: l.profile, sharedWallets: [] };
  const magic = new TextEncoder().encode("ARTHATIS1\n"); const blob = C.wrapWithRecoveryKey(rk, new TextEncoder().encode(JSON.stringify(data)));
  const out = new Uint8Array(magic.length + blob.length); out.set(magic); out.set(blob, magic.length);
  download(`arthatis-${new Date().toISOString().slice(0, 10)}.arthatis`, out as BlobPart);
}

export function exportCsv(c: Ctx) { download(`arthatis-${new Date().toISOString().slice(0, 10)}.csv`, toCsv(c.ledger), "text/csv"); }

export async function importFile(c: Ctx, file: File) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  const magic = "ARTHATIS1\n";
  let data: ArchiveData | null = null;
  if (new TextDecoder().decode(bytes.slice(0, magic.length)) === magic) {
    const f = await dialog("Recovery key", field("rk", "ABCD-EFGH-…", "", { required: true }), t("Buka", "Unlock"));
    const rk = f && C.parseRecoveryKey(String(f.get("rk")));
    if (!rk) return;
    try { data = JSON.parse(new TextDecoder().decode(C.unwrapWithRecoveryKey(rk, bytes.slice(magic.length)))) as ArchiveData; } catch { toast(t("Recovery key salah.", "Wrong recovery key.")); return; }
  } else {
    data = csvToArchive(new TextDecoder().decode(bytes), c.ledger);
  }
  if (!data) { toast(t("File tidak dikenali.", "Unrecognised file.")); return; }
  let added = 0;
  const merge = <T extends { id: string; updatedAt: number }>(entity: string, incoming: T[], existing: T[]) => { const by = new Map(existing.map((x) => [x.id, x])); for (const x of incoming) { const cur = by.get(x.id); if (!cur || x.updatedAt > cur.updatedAt) { session.put(entity, x as never); added++; } } };
  merge(ENTITY.wallet, data.wallets, c.ledger.wallets); merge(ENTITY.category, data.categories, c.ledger.categories);
  merge(ENTITY.tx, data.transactions, c.ledger.txs); merge(ENTITY.recurring, data.recurring, c.ledger.recurring); merge(ENTITY.rate, data.rates, c.ledger.overrides);
  toast(t(`Impor selesai: ${added} data baru.`, `Import finished: ${added} new records.`));
}

function csvToArchive(csv: string, l: Ledger): ArchiveData | null {
  const rows = csv.split(/\r?\n/).filter(Boolean);
  if (rows[0]?.trim() !== "date,wallet,kind,category,amount,currency,to_wallet,to_amount,note") return null;
  const split = (line: string) => { const out: string[] = []; let cur = "", q = false; for (let i = 0; i < line.length; i++) { const ch = line[i]!; if (q && ch === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (ch === '"') q = !q; else if (ch === "," && !q) { out.push(cur); cur = ""; } else cur += ch; } out.push(cur); return out; };
  const wallets = new Map<string, Wallet>(l.wallets.map((w) => [w.name, w])); const cats = new Map<string, Category>(l.categories.map((k) => [k.name, k])); const txs: Tx[] = [];
  const now = Date.now(); const out: ArchiveData = { version: 1, createdAt: now, device: "CSV", wallets: [], categories: [], transactions: [], recurring: [], rates: [], profile: null, sharedWallets: [] };
  const hash = (s: string) => { let h = 5381; for (const ch of s) h = ((h << 5) + h + ch.charCodeAt(0)) | 0; return (h >>> 0).toString(36); };
  rows.slice(1).forEach((line, i) => {
    const f = split(line); if (f.length < 9) return;
    const cur = f[5] || "IDR"; const day = Date.parse(f[0]! + "T00:00:00Z"); const amount = parseFloat(f[4]!); if (!isFinite(day) || !isFinite(amount) || !["expense", "income", "transfer"].includes(f[2]!)) return;
    const wallet = (name: string) => { let w = wallets.get(name); if (!w) { w = { id: "w" + hash(name + cur), name, kind: "rekening", currency: cur, openingMinor: 0, position: wallets.size, updatedAt: now, deletedAt: null }; wallets.set(name, w); out.wallets.push(w); } return w; };
    const category = (name: string) => { let k = cats.get(name); if (!k) { k = { id: "c" + hash(name), key: null, name, kind: f[2] as EntryKind, color: "#717970", hidden: false, position: 100 + cats.size, updatedAt: now, deletedAt: null }; cats.set(name, k); out.categories.push(k); } return k; };
    const src = wallet(f[1]!); const dst = f[6] ? wallet(f[6]) : null;
    txs.push({ id: "t" + hash(line + i), walletId: src.id, toWalletId: dst?.id ?? null, categoryId: f[3] ? category(f[3]).id : null, kind: f[2] as EntryKind, amountMinor: toMinor(amount, cur), toAmountMinor: f[7] ? toMinor(parseFloat(f[7]), dst?.currency ?? cur) : null, rate: null, note: f[8] ?? "", occurredAt: day, recurringId: null, updatedAt: now, deletedAt: null });
  });
  out.transactions = txs; return out;
}

void pairKey; void fetchRates;
