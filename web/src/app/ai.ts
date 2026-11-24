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

export interface Draft { kind: "expense" | "income"; amountMinor: number; categoryId: string; walletId: string; note: string }
export type Reply = { type: "draft"; draft: Draft } | { type: "answer"; text: string } | { type: "none" };

const WORDS: Record<string, string> = { makan: "food", lunch: "food", kopi: "food", bakso: "food", grab: "transport", gojek: "transport", bensin: "fuel", bbm: "fuel", belanja: "shopping", tokopedia: "shopping", listrik: "bills", pln: "bills", tagihan: "bills", sewa: "home", netflix: "fun", bioskop: "fun", obat: "health", dokter: "health", gaji: "salary" };

export function parseDraft(text: string, l: Ledger, currency: string): Draft | null {
  const m = /(\d+(?:[.,]\d+)?)\s*(jt|juta|rb|ribu|k)?/i.exec(text); if (!m) return null;
  const mult = { jt: 1e6, juta: 1e6, rb: 1e3, ribu: 1e3, k: 1e3 }[(m[2] ?? "").toLowerCase() as "k"] ?? 1;
  const lower = text.toLowerCase(); const key = Object.entries(WORDS).find(([w]) => lower.includes(w))?.[1];
  const cat = live(l.categories).find((c) => c.key === key); if (!cat) return null;
  const wallet = live(l.wallets).find((w) => lower.includes(w.name.toLowerCase())) ?? live(l.wallets)[0]; if (!wallet) return null;
  return { kind: cat.kind === "income" ? "income" : "expense", amountMinor: toMinor(parseFloat(m[1]!.replace(",", ".")) * mult, wallet.currency || currency), categoryId: cat.id, walletId: wallet.id, note: text };
}

export function facts(l: Ledger, rates: Rates, currency: string): string {
  const now = new Date(); const [ms, me] = monthRange(now.getUTCFullYear(), now.getUTCMonth()); const [ps, pe] = monthRange(now.getUTCFullYear(), now.getUTCMonth() - 1);
  const a = flow(l, currency, rates, ms, me), b = flow(l, currency, rates, ps, pe); const f = (n: number) => formatMinor(n, currency);
  const top = spendByCategory(l, currency, rates, ms, me).slice(0, 5).map((s) => `${l.categories.find((c) => c.id === s.categoryId)?.name ?? "?"} ${f(s.minor)}`).join(", ");
  const budgets = live(l.budgets).map((bd) => { const spent = spendByCategory(l, currency, rates, ms, me).find((s) => s.categoryId === bd.categoryId)?.minor ?? 0; return `${l.categories.find((c) => c.id === bd.categoryId)?.name ?? "?"} spent ${f(spent)} of ${f(bd.monthlyMinor)}`; }).join("; ");
  const total = live(l.wallets).reduce((n, w) => n + (rates.convert(balance(w, l.txs), w.currency, currency) ?? 0), 0);
  return `Currency: ${currency}\nTotal balance: ${f(total)}\nThis month: spent ${f(a.expense)}, income ${f(a.income)}. Last month: spent ${f(b.expense)}, income ${f(b.income)}.\nTop categories this month: ${top || "none"}.\nBudgets: ${budgets || "none"}.`;
}

export async function reply(text: string, l: Ledger, rates: Rates, currency: string): Promise<Reply> {
  const draft = parseDraft(text, l, currency); if (draft) return { type: "draft", draft };
  const s = aiSettings(); const system = `You are Ollie, a concise finance assistant in a private wallet app. Answer in the user's language using only these facts; if they do not contain the answer, say so. Never invent numbers.\n${facts(l, rates, currency)}`;
  if (s.server) {
    try { const r = await session.api.call<{ reply: string }>("POST", "/v1/ai/chat", { messages: [{ role: "system", content: system }, { role: "user", content: text }] }); return { type: "answer", text: r.reply }; } catch { /* try the next engine */ }
  }
  if (s.cloud) {
    const key = await loadApiKey();
    if (key) {
      try {
        if (s.provider === "anthropic") {
          const r = await fetch("https://api.anthropic.com/v1/messages", { method: "POST", headers: { "content-type": "application/json", "x-api-key": key, "anthropic-version": "2023-06-01", "anthropic-dangerous-direct-browser-access": "true" }, body: JSON.stringify({ model: s.model || "claude-haiku-5-5", max_tokens: 400, system, messages: [{ role: "user", content: text }] }) });
          if (r.ok) return { type: "answer", text: (await r.json()).content[0].text };
        } else {
          const base = s.provider === "openai" ? "https://api.openai.com" : "https://api.deepseek.com";
          const r = await fetch(`${base}/v1/chat/completions`, { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${key}` }, body: JSON.stringify({ model: s.model || (s.provider === "openai" ? "gpt-5-mini" : "deepseek-chat"), messages: [{ role: "system", content: system }, { role: "user", content: text }] }) });
          if (r.ok) return { type: "answer", text: (await r.json()).choices[0].message.content };
        }
      } catch { /* fall through */ }
    }
  }
  if (/ringkasan|summary|bandingkan|compare|budget|anggaran/i.test(text)) return { type: "answer", text: facts(l, rates, currency) };
  return { type: "none" };
}

void C;
