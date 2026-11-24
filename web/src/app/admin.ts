// Server administration (web only; shown to the admin vault). Metadata only: contents are ciphertext the server cannot read.
import { session } from "./session";
import { confirmDialog, dialog, esc, t, toast } from "./ui";

export interface AdminVault {
  id: string; createdAt: number; email: string | null; isAdmin: boolean; suspendedAt: number | null;
  activeUntil: number | null; ref: string | null; lastSeenAt: number | null; devices: number;
}
export interface AdminData {
  settings: { registration: "open" | "email"; mail: boolean; activation: boolean };
  allowlist: string[];
  vaults: AdminVault[];
}

export async function loadAdmin(): Promise<AdminData> {
  const [settings, allow, vaults] = await Promise.all([
    session.api.call<AdminData["settings"]>("GET", "/v1/admin/settings"),
    session.api.call<{ emails: string[] }>("GET", "/v1/admin/allowlist"),
    session.api.call<{ vaults: AdminVault[] }>("GET", "/v1/admin/vaults"),
  ]);
  return { settings, allowlist: allow.emails, vaults: vaults.vaults };
}

const date = (ms: number | null) => (ms ? new Date(ms).toLocaleDateString(document.documentElement.lang === "en" ? "en" : "id-ID", { day: "numeric", month: "short", year: "numeric" }) : "—");

function status(v: AdminVault, activation: boolean): string {
  if (v.suspendedAt) return `<span class="badge" style="background:var(--errorContainer);color:var(--onErrorContainer)">${t("Ditangguhkan", "Suspended")}</span>`;
  if (v.isAdmin) return `<span class="badge">Admin</span>`;
  if (!activation) return `<span class="badge">${t("Aktif", "Active")}</span>`;
  if (v.activeUntil && v.activeUntil > Date.now()) return `<span class="badge">${t("Aktif s/d", "Active until")} ${esc(date(v.activeUntil))}</span>`;
  return `<span class="badge tertiary">${v.activeUntil ? t("Kedaluwarsa", "Expired") : t("Menunggu aktivasi", "Awaiting activation")}</span>`;
}

export function adminPage(d: AdminData | null, failed: boolean): string {
  if (failed) return `<div class="card"><b>${t("Tidak bisa memuat data admin.", "Couldn't load admin data.")}</b><button class="btn" data-act="admin-reload" style="width:fit-content">${t("Coba lagi", "Try again")}</button></div>`;
  if (!d) return `<p class="muted">${t("Memuat…", "Loading…")}</p>`;
  const chip = (id: string, label: string) => `<button class="chip" data-act="admin-mode" data-mode="${id}" aria-pressed="${d.settings.registration === id}">${label}</button>`;
  return `<p class="muted">${t("Fitur untuk pemilik server. Admin adalah vault pertama yang mendaftar. Isi data semua pengguna tetap terenkripsi dan tidak bisa dibaca dari sini.", "Server owner tools. The admin is the first vault that registered. Everyone's data stays encrypted and unreadable from here.")}</p>
  <div class="card"><div class="section-title">${t("Siapa yang boleh mendaftar", "Who may register")}</div>
    <div class="chips">${chip("open", t("Terbuka", "Open"))}${chip("email", t("Hanya email terdaftar", "Listed emails only"))}</div>
    <p class="muted small">${d.settings.registration === "open"
      ? t("Siapa pun yang tahu alamat server ini bisa mendaftar. Cocok untuk server pribadi di jaringan tertutup atau saat Anda sendiri yang membagikan alamatnya.", "Anyone who knows this server's address can register. Fine for a private server on a closed network, or when you share the address yourself.")
      : t("Hanya email di daftar di bawah yang bisa mendaftar, dan harus memasukkan kode yang dikirim ke emailnya.", "Only emails on the list below can register, and they must enter a code sent to that address.")}</p>
    ${d.settings.mail ? "" : `<p class="error-text small">${t("Server ini belum punya SMTP, jadi mode email tidak bisa diaktifkan. Isi SMTP_HOST di .env.", "This server has no SMTP, so email mode can't be enabled. Set SMTP_HOST in .env.")}</p>`}
    ${d.settings.activation ? `<p class="muted small">${t("Aktivasi wajib diaktifkan di server ini: vault baru menunggu diaktifkan oleh layanan pembayaran.", "Activation is required on this server: new vaults wait to be activated by the billing service.")}</p>` : ""}</div>
  <div class="card"><div class="section-title">${t("Daftar email yang boleh mendaftar", "Emails allowed to register")}</div>
    <div class="list">${d.allowlist.map((e) => `<div class="row"><span class="texts"><b>${esc(e)}</b></span><button class="btn text sm danger" data-act="admin-del-email" data-email="${esc(e)}">${t("Hapus", "Remove")}</button></div>`).join("") || `<p class="muted">${t("Belum ada.", "None yet.")}</p>`}</div>
    <button class="btn tonal" data-act="admin-add-emails" style="width:fit-content">${t("Tambah email", "Add emails")}</button></div>
  <div class="section-title">${t("Vault", "Vaults")} (${d.vaults.length})</div>
  <div class="list">${d.vaults.map((v) => `<div class="row"><span class="texts"><b>${esc(v.id.slice(0, 8))}…${v.email ? ` · ${esc(v.email)}` : ""}</b><span class="muted small">${t("Dibuat", "Created")} ${esc(date(v.createdAt))} · ${t("terakhir aktif", "last active")} ${esc(date(v.lastSeenAt))} · ${v.devices} ${t("perangkat", "devices")}</span></span>${status(v, d.settings.activation)}
    ${v.isAdmin ? "" : `<span style="display:flex;gap:4px">${v.suspendedAt ? `<button class="btn tonal sm" data-act="admin-resume" data-id="${v.id}">${t("Aktifkan", "Resume")}</button>` : `<button class="btn outlined sm" data-act="admin-suspend" data-id="${v.id}">${t("Tangguhkan", "Suspend")}</button>`}<button class="btn text sm danger" data-act="admin-delete" data-id="${v.id}">${t("Hapus", "Delete")}</button></span>`}</div>`).join("")}</div>`;
}

const errorText = (code: string | null) => code === "mail_not_configured" ? t("Server belum punya SMTP.", "This server has no SMTP.") : t("Gagal.", "Failed.");

export async function setMode(mode: "open" | "email"): Promise<boolean> {
  try { await session.api.call("PUT", "/v1/admin/settings", { registration: mode }); return true; }
  catch (e) { toast(errorText((e as { code?: string | null }).code ?? null)); return false; }
}

export async function addEmails(): Promise<boolean> {
  const f = await dialog(t("Tambah email", "Add emails"), `<div class="field"><label for="f-emails">${t("Satu email per baris", "One email per line")}</label><textarea id="f-emails" name="emails" placeholder="nama@contoh.com"></textarea></div>`);
  if (!f) return false;
  const emails = String(f.get("emails") ?? "").split(/[\s,;]+/).map((x) => x.trim()).filter((x) => /^\S+@\S+\.\S+$/.test(x));
  if (!emails.length) { toast(t("Tidak ada email yang valid.", "No valid emails.")); return false; }
  try { await session.api.call("POST", "/v1/admin/allowlist", { emails }); return true; } catch { toast(t("Gagal.", "Failed.")); return false; }
}

export async function removeEmail(email: string) { await session.api.call("DELETE", `/v1/admin/allowlist/${encodeURIComponent(email)}`); }

export async function vaultAction(kind: "suspend" | "resume" | "delete", id: string): Promise<boolean> {
  if (kind === "suspend" && !(await confirmDialog(t("Tangguhkan vault ini?", "Suspend this vault?"), t("Perangkatnya tidak bisa sinkron sampai Anda mengaktifkannya lagi. Data di HP pemilik tetap utuh.", "Its devices can't sync until you resume it. Data on the owner's phone stays intact."), t("Tangguhkan", "Suspend")))) return false;
  if (kind === "delete" && !(await confirmDialog(t("Hapus vault ini?", "Delete this vault?"), t("Semua data terenkripsi vault ini dihapus dari server dan tidak bisa dikembalikan. Data di HP pemilik tidak terpengaruh.", "All of this vault's encrypted data is deleted from the server for good. Data on the owner's phone is not affected."), t("Hapus", "Delete")))) return false;
  try {
    if (kind === "delete") await session.api.call("DELETE", `/v1/admin/vaults/${id}`); else await session.api.call("POST", `/v1/admin/vaults/${id}/${kind}`);
    return true;
  } catch { toast(t("Gagal.", "Failed.")); return false; }
}
