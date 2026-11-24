import { buildReport, printReport } from "./report";
import { aiSettings, loadApiKey, reply, saveAiSettings, saveApiKey } from "./ai";
import { approveMember, contribute, createInvite, groupDetailPage, groupsPage, listGroups, loadGroup, vote, type GroupRow, type GroupView } from "./bareng";
import { ENTITY, CURRENCIES, buildTransfer, evaluate, formatMinor, groupAmount, newId, pairKey } from "./model";
import * as A from "./admin";
import * as P from "./pages";
import { buildRates, cachedRates, fetchRates } from "./rates";
import { session, onChange, type LinkState } from "./session";
import QRCode from "qrcode";
import pkg from "../../package.json";
import { bindAmountInputs, fitAmounts, $, applyTheme, confirmDialog, dialog, esc, field, icon, lang, select, setLang, setTheme, t, theme, toast } from "./ui";

type Page = "dashboard" | "transactions" | "wallets" | "bareng" | "reports" | "ai" | "data" | "settings" | "security" | "admin";
const NAV: [Page, string, string, string][] = [
  ["dashboard", "Dashboard", "Dashboard", "dashboard"], ["transactions", "Transaksi", "Transactions", "tx"], ["wallets", "Dompet", "Wallets", "wallet"],
  ["bareng", "Bareng", "Shared", "groups"], ["reports", "Laporan", "Reports", "chart"], ["ai", "AI & privasi", "AI & privacy", "ai"],
  ["data", "Data & backup", "Data & backup", "data"], ["settings", "Pengaturan", "Settings", "settings"],
];

const root = document.getElementById("root")!;
let page: Page = ([...NAV.map((n) => n[0]), "security", "admin"] as Page[]).find((id) => id === location.hash.slice(2)) ?? "dashboard";
let txState = { q: "", f: "all" };
let groups: GroupRow[] | null = null;
let groupsError = false;
let openGroup: GroupView | null = null;
let apiRates = cachedRates();
let chat: { me: boolean; html: string }[] = [];
let handlers: Record<string, (el: HTMLElement) => void> = {};
let sessionsList: { id: string; label: string; lastSeenAt: number }[] = [];
let sessionCount = 1;
let adminData: A.AdminData | null = null;
let adminFailed = false;
let link: LinkState | null = null;
let linkTimer = 0;

// One delegated listener for the whole app; `handlers` is replaced on every render.
root.addEventListener("click", (e) => {
  const el = (e.target as HTMLElement).closest<HTMLElement>("[data-act]");
  const h = el && handlers[el.dataset.act!];
  if (h) { e.preventDefault(); h(el!); }
});

const ctx = (): P.Ctx => {
  const ledger = session.ledger();
  return { ledger, rates: buildRates(apiRates, ledger.overrides), currency: ledger.profile?.primaryCurrency ?? "IDR", navigate: go, rerender: render };
};

function go(p: string) {
  page = p as Page; openGroup = null; location.hash = `/${page}`;
  if (p === "security") void loadSessions().then(render);
  if (p === "admin") void refreshAdmin();
  render();
  if (p === "bareng") void refreshGroups();
}

bindAmountInputs(groupAmount);

// --- Login (W03) --------------------------------------------------------------------------------
async function renderLogin() {
  applyTheme();
  root.innerHTML = `<div class="login"><div class="left"><img src="/design/wave.png" alt="Ollie"><h1>Arthatis Web</h1><p>${t("Mirror penuh dari ponselmu. Bisa lihat, tambah, ubah, dan hapus — sama seperti di aplikasi.", "A full mirror of your phone. View, add, edit and delete — just like in the app.")}</p><ul><li>✓ ${t("CRUD penuh di browser", "Full CRUD in the browser")}</li><li>✓ ${t("Wallet bareng & anggota", "Shared wallets & members")}</li><li>✓ ${t("E2EE: server tidak bisa membaca", "E2EE: the server can't read it")}</li><li>✓ ${t("Cabut sesi kapan saja", "Revoke sessions any time")}</li></ul><span class="small">TLS 1.3 · XChaCha20-Poly1305 · ML-KEM-768</span></div>
    <div class="right"><div class="panel"><h1 style="font-size:28px">${t("Masuk dengan QR", "Sign in with QR")}</h1><p class="muted">${t("Buka Arthatis di ponsel, lalu pilih Pengaturan › Keamanan › Tambah sesi web.", "Open Arthatis on your phone, then Settings › Security › Add web session.")}</p><canvas id="qr" class="qr" width="240" height="240"></canvas><div id="login-status" class="muted"></div><div class="code" id="code"></div><p class="muted small">${t("Setujui hanya jika kamu membuka halaman ini.", "Approve only if you opened this page.")}</p><p class="muted small">${t("Belum punya aplikasi? Unduh gratis untuk Android.", "No app yet? Download it free for Android.")}</p></div></div></div>`;
  const status = $("#login-status")!;
  try {
    link = await session.startLink();
    await QRCode.toCanvas($<HTMLCanvasElement>("#qr")!, link.qr, { width: 216, margin: 0 });
    $("#code")!.textContent = link.code;
    status.textContent = t("Berlaku 5 menit", "Valid 5 minutes");
    clearInterval(linkTimer);
    linkTimer = window.setInterval(async () => {
      if (!link) return;
      const r = await session.pollLink(link);
      if (r === "approved") { clearInterval(linkTimer); await session.sync(); await boot(); }
      else if (r === "expired") {
        clearInterval(linkTimer);
        status.innerHTML = `${t("QR kedaluwarsa.", "QR expired.")} <button class="link" id="reload-qr">${t("Muat ulang", "Reload")}</button>`;
        $("#reload-qr")?.addEventListener("click", () => void renderLogin());
      }
    }, 2000);
  } catch { status.textContent = t("Server tidak terjangkau.", "Server unreachable."); }
}

// --- Shell --------------------------------------------------------------------------------------
const navItems = () => (session.info?.isAdmin ? [...NAV, ["admin", "Admin", "Admin", "lock"] as [Page, string, string, string]] : NAV);

/** Banner shown while the server refuses this vault (hosted: not activated / expired; or suspended by the admin). */
function blockedBanner(): string {
  if (!session.blocked) return "";
  if (session.blocked === "suspended") return `<div class="card" style="background:var(--errorContainer);color:var(--onErrorContainer)"><b>${t("Akses ditangguhkan", "Access suspended")}</b><span class="small">${t("Admin server menangguhkan vault ini. Data di HP Anda tetap utuh.", "The server admin suspended this vault. Data on your phone is intact.")}</span></div>`;
  const pay = session.info?.payUrl ? `${session.info.payUrl}${session.info.payUrl.includes("?") ? "&" : "?"}v=${encodeURIComponent(session.vaultId)}` : null;
  return `<div class="card" style="background:var(--tertiaryContainer);color:var(--onTertiaryContainer)"><b>${t("Menunggu aktivasi", "Awaiting activation")}</b><span class="small">${t("Vault ini belum aktif atau sudah kedaluwarsa, jadi sinkron dijeda. ID vault:", "This vault isn't active or has expired, so sync is paused. Vault ID:")} <code>${esc(session.vaultId)}</code></span>${pay ? `<a class="btn sm" style="width:fit-content" href="${esc(pay)}" target="_blank" rel="noopener">${t("Buka halaman bayar", "Open payment page")}</a>` : ""}</div>`;
}

function titleOf(p: Page) {
  if (p === "security") return t("Keamanan & enkripsi", "Security & encryption");
  if (p === "admin") return t("Admin server", "Server admin");
  const n = NAV.find((x) => x[0] === p)!; return t(n[1], n[2]);
}

window.addEventListener("resize", () => fitAmounts());

function render() {
  if (!session.linked) return;
  const c = ctx();
  const active = page === "security" ? "settings" : page;
  root.innerHTML = `<div class="shell"><aside class="sidebar" aria-label="Menu"><div class="logo"><img src="/design/wave.png" alt="">Arthatis</div>${navItems().map(([id, a, b, ic]) => `<button class="nav" data-act="go" data-page="${id}" ${active === id ? 'aria-current="page"' : ""}>${icon(ic)}<span>${t(a, b)}</span></button>`).join("")}<span class="spacer"></span><div class="side-footer"><b class="small">${t("E2EE aktif", "E2EE on")}</b><span class="muted small">${t(`${sessionCount} sesi web · v${pkg.version}`, `${sessionCount} web sessions · v${pkg.version}`)}</span></div></aside>
    <div class="main"><header class="topbar"><h1>${esc(titleOf(page))}</h1><span class="pill ${session.online ? "" : "warn"}"><i class="dot"></i>${session.online ? t("Tersinkron", "Synced") : t("Offline", "Offline")}</span><button class="btn outlined sm" data-act="logout">${icon("logout")}${t("Putuskan", "Unlink")}</button></header><main class="content" id="page">${blockedBanner()}${pageBody(c)}</main></div></div>`;
  wire(c);
  fitAmounts();
}

function pageBody(c: P.Ctx): string {
  switch (page) {
    case "dashboard": return P.dashboard(c);
    case "transactions": return P.transactions(c, txState);
    case "wallets": return P.wallets(c);
    case "reports": return P.reports(c);
    case "bareng": return openGroup ? groupDetailPage(openGroup, session.vaultId) : groupsPage(groups, groupsError);
    case "ai": return aiPage(c);
    case "data": return P.dataPage(c);
    case "settings": return settingsPage(c);
    case "security": return securityPage();
    case "admin": return A.adminPage(adminData, adminFailed);
  }
}

async function refreshAdmin() {
  adminFailed = false;
  try { adminData = await A.loadAdmin(); } catch { adminFailed = true; }
  if (page === "admin") render();
}

async function refreshGroups() {
  groupsError = false;
  try { groups = await listGroups(); } catch { groupsError = true; }
  if (page === "bareng" && !openGroup) render();
}
async function openGroupById(id: string) {
  try { openGroup = await loadGroup(id); } catch { toast(t("Gagal memuat.", "Couldn't load.")); }
  render();
}

// --- AI page ------------------------------------------------------------------------------------
function aiPage(c: P.Ctx): string {
  const s = aiSettings();
  const msgs = chat.map((m) => `<div style="display:flex;justify-content:${m.me ? "flex-end" : "flex-start"}"><div style="max-width:70%;padding:12px;border-radius:16px;background:${m.me ? "var(--primaryContainer)" : "var(--surfaceContainerHigh)"};white-space:pre-wrap">${m.html}</div></div>`).join("");
  return `<div class="grid cols-2" style="align-items:start"><div class="card"><div class="section-title">Ollie · Chat AI</div><p class="muted small">${t("Ketik transaksi (“bakso 25k”) untuk membuat draf, atau tanya soal keuanganmu. Draf selalu menunggu konfirmasimu.", "Type an entry (“lunch 25k”) to make a draft, or ask about your money. Drafts always wait for your confirmation.")}</p><div style="display:grid;gap:8px;max-height:420px;overflow:auto">${msgs || `<p class="muted">${t("Halo! Aku Ollie.", "Hi! I'm Ollie.")}</p>`}</div><div class="search"><input id="chat-in" placeholder="${t("Tanya Ollie…", "Ask Ollie…")}" autocomplete="off"><button class="btn sm" data-act="chat-send" aria-label="${t("Kirim", "Send")}">${icon("send")}</button></div></div>
  <div class="card"><div class="section-title">${t("AI & privasi", "AI & privacy")}</div><p class="muted">${t("Di web, Gemini Nano tidak tersedia (hanya di HP). Pilih salah satu — keduanya mengirim teks pertanyaan keluar dari browser, tanpa E2EE.", "Gemini Nano isn't available on the web (phone only). Pick one — both send the question text out of the browser, without E2EE.")}</p>
    <label class="row"><span class="texts"><b>${t("AI di servermu (Ollama)", "AI on your server (Ollama)")}</b><span class="muted small">${t("Diatur lewat OLLAMA_URL di server.", "Configured with OLLAMA_URL on the server.")}</span></span><span class="switch"><input type="checkbox" id="ai-server" ${s.server ? "checked" : ""}></span></label>
    <label class="row"><span class="texts"><b>${t("API pribadi (kunci milikmu)", "Your own API key")}</b><span class="muted small">${t("Disimpan terenkripsi di browser ini.", "Stored encrypted in this browser.")}</span></span><span class="switch"><input type="checkbox" id="ai-cloud" ${s.cloud ? "checked" : ""}></span></label>
    <div id="ai-cloud-box" class="${s.cloud ? "" : "hidden"}" style="display:grid;gap:12px">${select("provider", t("Penyedia", "Provider"), [["anthropic", "Anthropic"], ["openai", "OpenAI"], ["deepseek", "DeepSeek"]], s.provider)}${field("apikey", t("Kunci API", "API key"), "", { type: "password", hint: t("Kosongkan untuk mempertahankan kunci tersimpan.", "Leave empty to keep the stored key.") })}${field("model", t("Nama model (opsional)", "Model name (optional)"), s.model)}</div>
    <button class="btn tonal" data-act="save-ai" style="width:fit-content">${t("Simpan", "Save")}</button>
    <p class="muted small">${t("Server menyimpan data hanya sebagai ciphertext. AI hanya melihat ringkasan yang dikirim browsermu sendiri.", "The server only stores ciphertext. The AI only sees the summary your own browser sends.")} (${esc(c.currency)})</p></div></div>`;
}

// --- Settings & security (W04) ------------------------------------------------------------------
function settingsPage(c: P.Ctx): string {
  const rate = (a: string) => { const r = c.rates.effective(a, "IDR"); return r ? `Rp${new Intl.NumberFormat("id-ID").format(r)}` : "—"; };
  return `<div class="grid cols-2" style="align-items:start"><div class="card"><div class="section-title">${t("Profil", "Profile")}</div>${field("name", t("Nama", "Name"), c.ledger.profile?.name ?? "")}<button class="btn tonal" data-act="save-name" style="width:fit-content">${t("Simpan nama", "Save name")}</button>
    <div class="section-title">${t("Mata uang utama", "Main currency")}</div><div class="chips">${CURRENCIES.map((x) => `<button class="chip" data-act="primary" data-cur="${x.code}" aria-pressed="${c.currency === x.code}">${x.code} ${x.symbol}</button>`).join("")}</div>
    <div class="section-title">${t("Tema & bahasa", "Theme & language")}</div><div class="chips">${["light", "dark", "system"].map((v) => `<button class="chip" data-act="theme" data-v="${v}" aria-pressed="${theme() === v}">${v === "light" ? t("Terang", "Light") : v === "dark" ? t("Gelap", "Dark") : t("Sistem", "System")}</button>`).join("")}</div><div class="chips"><button class="chip" data-act="lang" data-v="id" aria-pressed="${lang() === "id"}">Indonesia</button><button class="chip" data-act="lang" data-v="en" aria-pressed="${lang() === "en"}">English</button></div>
    <button class="btn outlined" data-act="go" data-page="security" style="width:fit-content">${icon("lock")}${t("Keamanan & enkripsi", "Security & encryption")}</button></div>
  <div class="card"><div class="section-title">${t("Mata uang & kurs", "Currency & rates")}</div>${["USD", "SGD", "MYR", "EUR"].map((a) => `<div class="row"><span class="texts"><b>${a}/IDR</b><span class="muted small">${c.rates.isManual(a, "IDR") ? t("Manual", "Manual") : t("Otomatis · API", "Automatic · API")}</span></span><button class="btn text sm" data-act="edit-rate" data-cur="${a}">${rate(a)}</button></div>`).join("")}<p class="muted small">${t("Kosongkan nilai untuk kembali ke kurs API.", "Clear a value to return to the API rate.")}</p><button class="btn tonal" data-act="refresh-rates" style="width:fit-content">${t("Perbarui kurs", "Refresh rates")}</button><p class="muted small">Arthatis Wallet v${pkg.version} · AGPL-3.0</p></div></div>
  <div style="margin-top:16px">${P.recurringSection(c)}</div>`;
}

function securityPage(): string {
  const gk = session.groupKeys();
  return `<div class="card"><div class="section-title">${icon("lock")} ${t("E2EE aktif", "E2EE on")}</div><p class="muted">${t("Server hanya menyimpan ciphertext, metadata minimal, dan kunci publik perangkat.", "The server only stores ciphertext, minimal metadata, and device public keys.")}</p><span class="small muted">TLS 1.3 · XChaCha20-Poly1305 · Argon2id · X25519 + ML-KEM-768</span></div>
  <div class="section-title">${t("Sesi web aktif", "Active web sessions")}</div><div class="list">${sessionsList.map((s) => `<div class="row"><span class="texts"><b>${esc(s.label)}${s.id === session.deviceId ? ` · ${t("sesi ini", "this session")}` : ""}</b><span class="muted small">${new Date(s.lastSeenAt).toLocaleString()}</span></span><button class="btn text sm danger" data-act="revoke" data-id="${s.id}">${t("Cabut", "Revoke")}</button></div>`).join("") || `<p class="muted">—</p>`}</div><button class="btn outlined" style="width:fit-content" data-act="revoke-all">${t("Cabut semua sesi web", "Revoke all web sessions")}</button>
  <div class="grid cols-2"><div class="card"><div class="section-title">${t("Kunci perangkat ini", "This device's key")}</div><p class="muted small">${t("Kunci vault dibuka dari HP lewat X25519 + ML-KEM-768 dan disimpan terenkripsi di browser ini.", "The vault key was opened from your phone via X25519 + ML-KEM-768 and is stored encrypted in this browser.")}</p></div><div class="card"><div class="section-title">${t("Kunci wallet bareng", "Shared wallet keys")}</div><p class="muted small">${t(`${gk.size} wallet · kunci disalin dari HP`, `${gk.size} wallets · keys mirrored from your phone`)}</p><p class="muted small">${t("Perubahan dari web dienkripsi di browser dan disinkronkan sebagai ciphertext.", "Changes from the web are encrypted in the browser and synced as ciphertext.")}</p></div></div>`;
}

async function loadSessions() {
  try { const r = await session.api.call<{ sessions: typeof sessionsList }>("GET", "/v1/sessions"); sessionsList = r.sessions; sessionCount = r.sessions.length; } catch { /* offline */ }
}

// --- Events -------------------------------------------------------------------------------------
function wire(c: P.Ctx) {
  $("#q")?.addEventListener("input", (e) => {
    txState.q = (e.target as HTMLInputElement).value; const pos = txState.q.length; render();
    const el = $<HTMLInputElement>("#q")!; el.focus(); el.setSelectionRange(pos, pos);
  });
  $("#import-file")?.addEventListener("change", async (e) => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) await P.importFile(c, f); });
  $("#ai-cloud")?.addEventListener("change", (e) => { $("#ai-cloud-box")!.classList.toggle("hidden", !(e.target as HTMLInputElement).checked); });
  $("#chat-in")?.addEventListener("keydown", (e) => { if ((e as KeyboardEvent).key === "Enter") void sendChat(c); });
  const failed = () => toast(t("Gagal.", "Failed."));
  handlers = {
    go: (el) => go(el.dataset.page!),
    logout: async () => {
      if (await confirmDialog(t("Putuskan tautan?", "Unlink this browser?"), t("Browser ini logout dan cache-nya dihapus. Salinan di server tetap ada.", "This browser is logged out and its cache cleared. The server copy stays."), t("Putuskan", "Unlink"))) { await session.logout(); await boot(); }
    },
    filter: (el) => { txState.f = el.dataset.f!; render(); },
    "add-tx": () => void P.txDialog(c),
    "edit-tx": (el) => { const x = c.ledger.txs.find((y) => y.id === el.dataset.id); if (x) void P.txDialog(c, x); },
    transfer: () => void P.transferDialog(c),
    "add-wallet": () => void P.walletDialog(c),
    "edit-wallet": (el) => { const w = c.ledger.wallets.find((y) => y.id === el.dataset.id); if (w) void P.walletDialog(c, w); },
    "del-wallet": (el) => { const w = c.ledger.wallets.find((y) => y.id === el.dataset.id); if (w) void P.deleteWallet(c, w); },
    "add-recurring": () => void P.recurringDialog(c),
    "edit-recurring": (el) => { const r = c.ledger.recurring.find((y) => y.id === el.dataset.id); if (r) void P.recurringDialog(c, r); },
    "toggle-recurring": (el) => { const r = c.ledger.recurring.find((y) => y.id === el.dataset.id); if (r) session.put(ENTITY.recurring, { ...r, paused: !r.paused }); },
    "del-recurring": (el) => { const r = c.ledger.recurring.find((y) => y.id === el.dataset.id); if (r) void P.deleteRecurring(r); },
    "edit-budgets": () => void P.budgetsDialog(c),
    "export-arthatis": () => void P.exportArthatis(c),
    "export-csv": () => P.exportCsv(c),
    "open-group": (el) => void openGroupById(el.dataset.id!),
    "back-groups": () => { openGroup = null; render(); },
    "reload-groups": () => void refreshGroups(),
    contribute: async () => { if (openGroup && (await contribute(openGroup, false))) await openGroupById(openGroup.row.id); },
    withdraw: async () => { if (openGroup && (await contribute(openGroup, true))) await openGroupById(openGroup.row.id); },
    invite: () => { if (openGroup) void createInvite(openGroup); },
    vote: async (el) => { if (openGroup) { await vote(openGroup, el.dataset.a!, el.dataset.vote === "approve").catch(failed); await openGroupById(openGroup.row.id); } },
    "approve-member": async (el) => { if (openGroup) { await approveMember(openGroup, el.dataset.v!).catch(failed); await openGroupById(openGroup.row.id); } },
    "admin-reload": () => void refreshAdmin(),
    "admin-mode": async (el) => { if (await A.setMode(el.dataset.mode as "open" | "email")) await refreshAdmin(); },
    "admin-add-emails": async () => { if (await A.addEmails()) await refreshAdmin(); },
    "admin-del-email": async (el) => { await A.removeEmail(el.dataset.email!).catch(failed); await refreshAdmin(); },
    "admin-suspend": async (el) => { if (await A.vaultAction("suspend", el.dataset.id!)) await refreshAdmin(); },
    "admin-resume": async (el) => { if (await A.vaultAction("resume", el.dataset.id!)) await refreshAdmin(); },
    "admin-delete": async (el) => { if (await A.vaultAction("delete", el.dataset.id!)) await refreshAdmin(); },
    "chat-send": () => void sendChat(c),
    "export-pdf": () => {
      const from = $<HTMLInputElement>("#rep-from")?.value ?? ""; const to = $<HTMLInputElement>("#rep-to")?.value ?? "";
      P.reportRange.from = from; P.reportRange.to = to;
      const a = Date.parse(`${from}T00:00:00Z`), b = Date.parse(`${to}T00:00:00Z`);
      if (!Number.isFinite(a) || !Number.isFinite(b) || a > b) { toast(t("Tanggal awal harus sebelum tanggal akhir.", "The start date must be before the end date.")); return; }
      const r = buildReport(c.ledger, c.currency, c.rates, a, b + 86_400_000);
      if (!r) { toast(t("Kurs belum tersedia untuk semua dompet.", "Rates are missing for some wallets.")); return; }
      if (!printReport(r, c.ledger)) toast(t("Izinkan pop-up untuk mencetak laporan.", "Allow pop-ups to print the report."));
    },
    "save-ai": async () => {
      const s = aiSettings(); s.server = $<HTMLInputElement>("#ai-server")!.checked; s.cloud = $<HTMLInputElement>("#ai-cloud")!.checked;
      s.provider = ($<HTMLSelectElement>("#f-provider")?.value ?? s.provider) as typeof s.provider; s.model = $<HTMLInputElement>("#f-model")?.value.trim() ?? s.model;
      saveAiSettings(s); const k = $<HTMLInputElement>("#f-apikey")?.value; if (k) await saveApiKey(k); toast(t("Tersimpan.", "Saved."));
    },
    "save-name": () => {
      const name = $<HTMLInputElement>("#f-name")!.value.trim();
      if (name) session.put(ENTITY.setting, { ...(c.ledger.profile ?? { id: "profile", primaryCurrency: "IDR", updatedAt: 0, deletedAt: null }), name });
      toast(t("Tersimpan.", "Saved."));
    },
    primary: (el) => { session.put(ENTITY.setting, { ...(c.ledger.profile ?? { id: "profile", name: "—", updatedAt: 0, deletedAt: null }), primaryCurrency: el.dataset.cur! }); },
    theme: (el) => { setTheme(el.dataset.v!); render(); },
    lang: (el) => { setLang(el.dataset.v as "id" | "en"); render(); },
    "edit-rate": async (el) => {
      const cur = el.dataset.cur!;
      const f = await dialog(`${cur}/IDR`, field("rate", `${cur}/IDR`, "", { hint: t("Kosongkan untuk kembali ke kurs API.", "Leave empty to use the API rate.") }));
      if (!f) return;
      const key = pairKey(cur, "IDR"); const existing = c.ledger.overrides.find((o) => o.id === key); const v = evaluate(String(f.get("rate") ?? "").replace(/\./g, ""));
      if (v && v > 0) session.put(ENTITY.rate, { ...(existing ?? { id: key, updatedAt: 0 }), rate: String(v), deletedAt: null });
      else if (existing) session.put(ENTITY.rate, { ...existing, deletedAt: Date.now() });
    },
    "refresh-rates": async () => { apiRates = await fetchRates(); render(); },
    revoke: async (el) => {
      await session.api.call("DELETE", `/v1/sessions/${el.dataset.id}`).catch(() => {});
      if (el.dataset.id === session.deviceId) { await session.logout(false); await boot(); return; }
      await loadSessions(); render();
    },
    "revoke-all": async () => { await session.api.call("POST", "/v1/sessions/revoke-all").catch(() => {}); await session.logout(false); await boot(); },
  };
}

async function sendChat(c: P.Ctx) {
  const input = $<HTMLInputElement>("#chat-in"); const text = input?.value.trim(); if (!text) return;
  chat.push({ me: true, html: esc(text) }); render();
  const r = await reply(text, c.ledger, c.rates, c.currency);
  if (r.type === "answer") chat.push({ me: false, html: esc(r.text) });
  else if (r.type === "draft") {
    const d = r.draft; const w = c.ledger.wallets.find((x) => x.id === d.walletId);
    const toW = d.toWalletId ? c.ledger.wallets.find((x) => x.id === d.toWalletId) : undefined;
    const ok = await confirmDialog(t("Simpan draf ini?", "Save this draft?"), d.kind === "transfer" ? `${t("Transfer", "Transfer")} ${formatMinor(d.amountMinor, w?.currency ?? "IDR")} · ${w?.name ?? "?"} → ${toW?.name ?? "?"}` : `${formatMinor(d.amountMinor, w?.currency ?? "IDR", d.kind === "income" ? "plus" : "minus")} · ${d.note}`, t("Simpan", "Save"));
    if (ok && d.kind === "transfer") {
      const tx = w && toW ? buildTransfer(w, toW, d.amountMinor, c.rates, null, Date.now()) : null;
      if (tx) { session.put(ENTITY.tx, { ...tx, note: d.note }); chat.push({ me: false, html: t("Tersimpan ✓", "Saved ✓") }); }
      else chat.push({ me: false, html: t("Kurs belum tersedia. Buat transfer dari menu Dompet dengan kurs manual.", "Rate unavailable. Make the transfer from Wallets with a manual rate.") });
    } else if (ok) {
      session.put(ENTITY.tx, { id: newId(), walletId: d.walletId, toWalletId: null, categoryId: d.categoryId, kind: d.kind, amountMinor: d.amountMinor, toAmountMinor: null, rate: null, note: d.note, occurredAt: Date.now(), recurringId: null, updatedAt: 0, deletedAt: null });
      chat.push({ me: false, html: t("Tersimpan ✓", "Saved ✓") });
    } else chat.push({ me: false, html: t("Draf dibuang.", "Draft discarded.") });
  } else chat.push({ me: false, html: t("Aku belum bisa menjawab itu. Coba tulis transaksi seperti “bakso 25k”, atau aktifkan AI server / API pribadi.", "I can't answer that yet. Try an entry like “lunch 25k”, or enable the server AI / your own API key.") });
  render(); $<HTMLInputElement>("#chat-in")?.focus();
}

// --- Boot ---------------------------------------------------------------------------------------
let subscribed = false;
async function boot() {
  applyTheme(); document.documentElement.lang = lang();
  if (!(await session.restore())) { await renderLogin(); return; }
  render();
  if (!subscribed) { subscribed = true; onChange(() => { if (session.linked) render(); }); setInterval(() => void session.sync(), 30_000); }
  void (async () => { apiRates = await fetchRates(); await session.loadInfo(); await loadSessions(); await session.sync(); render(); if (page === "bareng") void refreshGroups(); if (page === "admin") void refreshAdmin(); })();
}

window.addEventListener("hashchange", () => {
  const p = location.hash.slice(2) as Page;
  if (p && p !== page && (p === "security" || p === "admin" || NAV.some((n) => n[0] === p))) { page = p; openGroup = null; render(); if (p === "admin") void refreshAdmin(); }
});
void loadApiKey;
void boot();
