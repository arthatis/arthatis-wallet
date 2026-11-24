// Chat AI on the web. Gemini Nano is phone-only; here the answer comes from the user's own server (Ollama) or their own API key.
// Both leave the browser, so both are opt-in and clearly labelled. Output is a text answer or a draft the user confirms.
import * as C from "./crypto";
import { Rates, balance, flow, formatMinor, live, monthRange, spendByCategory, toMinor, type Ledger } from "./model";
import { session } from "./session";
import { loadSecret, saveSecret } from "./storage";

export interface AiSettings { server: boolean; cloud: boolean; provider: "anthropic" | "openai" | "deepseek"; model: string }
const KEY = "arthatis.ai";
export const aiSettings = (): AiSettings => ({ server: false, cloud: false, provider: "anthropic", model: "", ...JSON.parse(localStorage.getItem(KEY) ?? "{}") });
export const saveAiSettings = (s: AiSettings) => localStorage.setItem(KEY, JSON.stringify(s));
export const saveApiKey = (key: string) => saveSecret("aiKey", new TextEncoder().encode(key));
export const loadApiKey = async () => { const b = await loadSecret("aiKey"); return b ? new TextDecoder().decode(b) : ""; };

export interface Draft { kind: "expense" | "income" | "transfer"; amountMinor: number; categoryId: string | null; walletId: string; toWalletId?: string; note: string }
export type Reply = { type: "draft"; draft: Draft } | { type: "answer"; text: string } | { type: "none" };

const WORDS: Record<string, string> = { makan: "food", lunch: "food", kopi: "food", bakso: "food", grab: "transport", gojek: "transport", bensin: "fuel", bbm: "fuel", belanja: "shopping", tokopedia: "shopping", listrik: "bills", pln: "bills", tagihan: "bills", sewa: "home", netflix: "fun", bioskop: "fun", obat: "health", dokter: "health", gaji: "salary" };

const squash = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
/** Wallet named in [text], ignoring case, spaces and punctuation; the longest name wins ("BCA Syariah" over "BCA"). */
export function matchWallet(text: string, l: Ledger) {
  const t = squash(text); if (!t) return undefined;
  return live(l.wallets).filter((w) => squash(w.name) && t.includes(squash(w.name))).sort((a, b) => squash(b.name).length - squash(a.name).length)[0];
}

/** "tf 50k dari BCA ke OVO", "transfer 1jt BCA ke GoPay": both wallets must be named (the web has no picker in the draft). */
function parseTransfer(text: string, l: Ledger, minor: (cur: string) => number): Draft | null {
  const lower = text.toLowerCase();
  const strong = /\b(transfer|trf|tf|pindah\w*|top\s?up|isi\s+saldo|tarik\s+tunai|setor\s+tunai)\b/.test(lower);
  if (!strong && !/\b(kirim|send|move)\b/.test(lower)) return null;
  const fromText = /\b(?:dari|from)\s+(.+?)(?=\s+(?:ke|to|kepada)\s|$)/.exec(lower)?.[1];
  const toText = /\b(?:ke|to|kepada)\s+(.+?)(?=\s+(?:dari|from)\s|$)/.exec(lower)?.[1];
  let from = fromText ? matchWallet(fromText, l) : undefined; let to = toText ? matchWallet(toText, l) : undefined;
  const named = live(l.wallets).filter((w) => squash(w.name) && squash(lower).includes(squash(w.name))).sort((a, b) => squash(lower).indexOf(squash(a.name)) - squash(lower).indexOf(squash(b.name)));
  if (!from) from = named.find((w) => w.id !== to?.id);
  if (!to) to = named.find((w) => w.id !== from?.id);
  if (!from || !to || from.id === to.id) return null;
  return { kind: "transfer", amountMinor: minor(from.currency), categoryId: null, walletId: from.id, toWalletId: to.id, note: text };
}

export function parseDraft(text: string, l: Ledger, currency: string): Draft | null {
  const m = /(\d+(?:[.,]\d+)?)\s*(jt|juta|rb|ribu|k)?/i.exec(text); if (!m) return null;
  const mult = { jt: 1e6, juta: 1e6, rb: 1e3, ribu: 1e3, k: 1e3 }[(m[2] ?? "").toLowerCase() as "k"] ?? 1;
  const transfer = parseTransfer(text, l, (cur) => toMinor(parseFloat(m[1]!.replace(",", ".")) * mult, cur || currency)); if (transfer) return transfer;
  const lower = text.toLowerCase(); const key = Object.entries(WORDS).find(([w]) => lower.includes(w))?.[1];
  const cat = live(l.categories).find((c) => c.key === key); if (!cat) return null;
  const wallet = matchWallet(lower, l) ?? live(l.wallets)[0]; if (!wallet) return null;
  return { kind: cat.kind === "income" ? "income" : "expense", amountMinor: toMinor(parseFloat(m[1]!.replace(",", ".")) * mult, wallet.currency || currency), categoryId: cat.id, walletId: wallet.id, note: text };
}

export function facts(l: Ledger, rates: Rates, currency: string): string {
  const now = new Date(); const [ms, me] = monthRange(now.getUTCFullYear(), now.getUTCMonth()); const [ps, pe] = monthRange(now.getUTCFullYear(), now.getUTCMonth() - 1);
  const a = flow(l, currency, rates, ms, me), b = flow(l, currency, rates, ps, pe); const f = (n: number) => formatMinor(n, currency);
  const top = spendByCategory(l, currency, rates, ms, me).slice(0, 5).map((s) => `${l.categories.find((c) => c.id === s.categoryId)?.name ?? "?"} ${f(s.minor)}`).join(", ");
  const budgets = live(l.budgets).map((bd) => { const spent = spendByCategory(l, currency, rates, ms, me).find((s) => s.categoryId === bd.categoryId)?.minor ?? 0; return `${l.categories.find((c) => c.id === bd.categoryId)?.name ?? "?"} spent ${f(spent)} of ${f(bd.monthlyMinor)}`; }).join("; ");
  const total = live(l.wallets).reduce((n, w) => n + (rates.convert(balance(w, l.txs), w.currency, currency) ?? 0), 0);
  const wallets = live(l.wallets).map((w) => `${w.name} (${w.kind}) ${formatMinor(balance(w, l.txs), w.currency)}`).join(", ");
  const history = [1, 2, 3, 4, 5, 6].map((back) => {
    const [s, e] = monthRange(now.getUTCFullYear(), now.getUTCMonth() - back); const fl = flow(l, currency, rates, s, e);
    if (!fl.income && !fl.expense) return "";
    const d = new Date(s); const tops = spendByCategory(l, currency, rates, s, e).slice(0, 3).map((x) => `${l.categories.find((c) => c.id === x.categoryId)?.name ?? "?"} ${f(x.minor)}`).join(", ");
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}: spent ${f(fl.expense)}, income ${f(fl.income)}${tops ? ` (top: ${tops})` : ""}`;
  }).filter(Boolean).join("\n");
  return `Currency: ${currency}\nNet worth (cash + investments − card debt): ${f(total)}\nWallet balances: ${wallets || "none"}\nThis month: spent ${f(a.expense)}, income ${f(a.income)}. Last month: spent ${f(b.expense)}, income ${f(b.income)}.\nTop categories this month: ${top || "none"}.\nBudgets: ${budgets || "none"}.${history ? `\nMonthly history:\n${history}` : ""}\nRecorded transactions: ${live(l.txs).length}.`;
}

/** Same brief as the phone: chat and general money advice are fine, but stay near recording, consultation and history. */
export const chatSystem = (factsText: string) =>
  `You are Ollie, the friendly assistant inside Arthatis Wallet, a private money-tracking app. Today is ${new Date().toISOString().slice(0, 10)}. ` +
  "Reply in the user's language, warm and concise (a few sentences unless asked for detail). You can make small talk, give general " +
  "personal-finance advice (budgeting, saving, debt, investing basics, spending habits) and analyse the user's history using the facts below. " +
  "If the user drifts far from money (e.g. coding, politics, homework), answer briefly if harmless, then gently steer back to their finances. " +
  "Never invent the user's own numbers: use only the facts; if they lack what is asked, say so and suggest what to record. Recording happens " +
  "in the app: tell the user to write e.g. 'bakso 25k' or 'transfer 100k dari BCA ke GoPay' to get a draft. You are not a licensed advisor; " +
  `say so for specific investment picks.\n\nFacts about the user's money:\n${factsText}`;

/** Earlier turns of this page's conversation, so the engine can follow along. */
const history: { role: "user" | "assistant"; content: string }[] = [];
const remember = (user: string, answer: string) => { history.push({ role: "user", content: user }, { role: "assistant", content: answer }); history.splice(0, Math.max(0, history.length - 10)); };

const timed = (ms: number) => { const c = new AbortController(); setTimeout(() => c.abort(), ms); return c.signal; };

export async function reply(text: string, l: Ledger, rates: Rates, currency: string): Promise<Reply> {
  const r = await answer(text, l, rates, currency);
  if (r.type === "answer") remember(text, r.text);
  return r;
}

async function answer(text: string, l: Ledger, rates: Rates, currency: string): Promise<Reply> {
  const draft = parseDraft(text, l, currency); if (draft) return { type: "draft", draft };
  const s = aiSettings(); const system = chatSystem(facts(l, rates, currency));
  const messages = [...history, { role: "user", content: text }];
  if (s.server) {
    try { const r = await session.api.call<{ reply: string }>("POST", "/v1/ai/chat", { messages: [{ role: "system", content: system }, ...messages] }); return { type: "answer", text: r.reply }; } catch { /* try the next engine */ }
  }
  if (s.cloud) {
    const key = await loadApiKey();
    if (key) {
      try {
        if (s.provider === "anthropic") {
          const r = await fetch("https://api.anthropic.com/v1/messages", { method: "POST", headers: { "content-type": "application/json", "x-api-key": key, "anthropic-version": "2023-06-01", "anthropic-dangerous-direct-browser-access": "true" }, body: JSON.stringify({ model: s.model || "claude-haiku-5-5", max_tokens: 800, system, messages }), signal: timed(35_000) });
          if (r.ok) return { type: "answer", text: (await r.json()).content[0].text };
        } else {
          const base = s.provider === "openai" ? "https://api.openai.com" : "https://api.deepseek.com";
          const r = await fetch(`${base}/v1/chat/completions`, { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${key}` }, body: JSON.stringify({ model: s.model || (s.provider === "openai" ? "gpt-5-mini" : "deepseek-chat"), messages: [{ role: "system", content: system }, ...messages] }), signal: timed(35_000) });
          if (r.ok) return { type: "answer", text: (await r.json()).choices[0].message.content };
        }
      } catch { /* fall through */ }
    }
  }
  if (/ringkasan|summary|bandingkan|compare|budget|anggaran/i.test(text)) return { type: "answer", text: facts(l, rates, currency) };
  const q = text.trim().toLowerCase();
  if (/^(halo|hai|hi|hello|hey|pagi|siang|sore|malam)\b/.test(q)) return { type: "answer", text: "Halo! Mau catat transaksi, lihat ringkasan, atau ngobrol soal keuangan? / Hi! Record something, see a summary, or talk money?" };
  if (/^(makasih|terima kasih|thanks|thank you|thx)\b/.test(q)) return { type: "answer", text: "Sama-sama! / You're welcome!" };
  return { type: "none" };
}

void C;
