// Shared wallets on the web. Group keys come from the phone (mirrored into the vault as `groupkey` records);
// everything here is encrypted client-side exactly like the phone does (docs/protocol.md).
import * as C from "./crypto";
import * as G from "./groupcrypto";
import { formatMinor, toMajor, toMinor, evaluateAmount, newId } from "./model";
import { session } from "./session";
import { dialog, esc, field, select, t, toast, icon } from "./ui";

interface Meta { name: string; kind: string; currency: string; targetMinor?: number | null; targetDay?: number | null }
export interface GroupRow { id: string; role: string; status: string; epoch: number; memberCount: number; meta: Meta | null }
interface Contribution { id: string; vaultId: string; kind: "contribution" | "withdrawal"; amountMinor: number; note: string; at: number; updatedAt: number; deletedAt: number | null }
interface Member { vaultId: string; role: string; status: string; name: string; boxPub: Uint8Array; kemPub: Uint8Array }
interface Approval { id: string; status: string; required: number; approvals: number; amountMinor: number; note: string }

const keyFor = (id: string, epoch: number) => session.groupKeys().get(id)?.get(epoch) ?? null;
/** Decrypts meta/profile/activity/approval JSON with the newest key that works (older keys survive a rotation). */
const decAny = (groupId: string, text: string, aad: string) => {
  const json = G.openWithAnyKey(session.groupKeys().get(groupId), text, aad);
  if (json === null) return null;
  try { return JSON.parse(json); } catch { return null; }
};

export async function listGroups(): Promise<GroupRow[]> {
  const res = await session.api.call<{ groups: any[] }>("GET", "/v1/groups");
  return res.groups.map((g) => {
    const key = keyFor(g.id, g.epoch);
    return { id: g.id, role: g.role, status: g.status, epoch: g.epoch, memberCount: g.memberCount, meta: key && g.meta ? (decAny(g.id, g.meta, G.metaAad(g.id)) as Meta | null) : null };
  });
}

export interface GroupView { row: GroupRow; members: Member[]; contributions: Contribution[]; approvals: Approval[]; activity: { text: string; at: number }[]; locked: boolean }

export async function loadGroup(id: string): Promise<GroupView> {
  const d = await session.api.call<any>("GET", `/v1/groups/${id}`);
  const key = keyFor(id, d.epoch);
  const row: GroupRow = { id, role: d.role, status: "active", epoch: d.epoch, memberCount: d.members.filter((m: any) => m.status === "active").length, meta: key ? decAny(id, d.meta, G.metaAad(id)) : null };
  if (!key) return { row, members: [], contributions: [], approvals: [], activity: [], locked: true };
  const members: Member[] = d.members.map((m: any) => ({ vaultId: m.vaultId, role: m.role, status: m.status, name: decAny(id, m.profile, G.profileAad(id))?.name ?? "—", boxPub: C.unb64(m.boxPub), kemPub: C.unb64(m.kemPub) }));
  const sync = await session.api.call<{ records: any[] }>("POST", `/v1/groups/${id}/sync`, { epoch: d.epoch, cursor: 0, records: [] }).catch(() => ({ records: [] }));
  const contributions: Contribution[] = sync.records.filter((r) => r.entity === "contribution" && r.deletedAt === null).map((r) => { const j = G.openRecord(key, id, d.epoch, "contribution", r.id, r.payload); try { return j ? JSON.parse(j) : null; } catch { return null; } }).filter(Boolean).sort((a: Contribution, b: Contribution) => b.at - a.at);
  const ap = await session.api.call<{ approvals: any[] }>("GET", `/v1/groups/${id}/approvals`).catch(() => ({ approvals: [] }));
  const approvals: Approval[] = ap.approvals.map((a) => { const p = decAny(id, a.payload, G.approvalAad(id)); return p ? { id: a.id, status: a.status, required: a.required, approvals: a.approvals, amountMinor: p.amountMinor, note: p.note } : null; }).filter(Boolean) as Approval[];
  const act = await session.api.call<{ activity: any[] }>("GET", `/v1/groups/${id}/activity`).catch(() => ({ activity: [] }));
  const activity = act.activity.map((a) => decAny(id, a.payload, G.activityAad(id))).filter(Boolean).reverse();
  return { row, members, contributions, approvals, activity, locked: false };
}

export function groupsPage(groups: GroupRow[] | null, error: boolean): string {
  if (error) return `<div class="card"><b>${t("Tidak bisa memuat wallet bareng.", "Couldn't load shared wallets.")}</b><p class="muted">${t("Periksa koneksi lalu coba lagi.", "Check your connection and try again.")}</p><button class="btn" data-act="reload-groups">${t("Coba lagi", "Try again")}</button></div>`;
  if (!groups) return `<p class="muted">${t("Memuat…", "Loading…")}</p>`;
  const cards = groups.filter((g) => g.status === "active").map((g) => {
    const m = g.meta;
    return `<button class="card" style="text-align:left;border:0" data-act="open-group" data-id="${g.id}"><div class="section-title"><span class="grow">${esc(m?.name ?? t("Terkunci", "Locked"))}</span><span class="badge tertiary">${m?.kind === "investasi" ? t("Investasi", "Investment") : t("Tabungan", "Savings")}</span></div><span class="muted small">${t(`${g.memberCount} anggota`, `${g.memberCount} members`)}${m ? "" : " · " + t("kunci belum disalin dari HP", "key not mirrored from the phone yet")}</span><span class="link">${t("Lihat detail", "View details")} →</span></button>`;
  }).join("");
  return `<p class="muted">${t("Membuat grup dan mengeluarkan anggota dilakukan di HP (kunci grup diputar di sana). Di web kamu bisa melihat, menambah kontribusi, memberi suara, dan menyetujui anggota.", "Creating groups and removing members happens on your phone (the group key is rotated there). On the web you can view, add contributions, vote, and approve members.")}</p><div class="grid cols-2">${cards || `<p class="muted">${t("Belum ada wallet bareng.", "No shared wallets yet.")}</p>`}</div>`;
}

export function groupDetailPage(v: GroupView, me: string): string {
  if (v.locked) return `<div class="card"><b>${t("Kunci grup belum tersedia", "Group key not available yet")}</b><p class="muted">${t("Buka wallet ini sekali di HP agar kuncinya disalin ke web, lalu muat ulang.", "Open this wallet once on your phone so its key is mirrored to the web, then reload.")}</p></div>`;
  const m = v.row.meta!; const collected = v.contributions.reduce((n, c) => n + (c.kind === "withdrawal" ? -c.amountMinor : c.amountMinor), 0);
  const manager = v.row.role === "owner" || v.row.role === "admin"; const writer = v.row.role !== "viewer";
  const roleLabel = (r: string) => r === "owner" ? "Owner" : r === "admin" ? "Admin" : r === "member" ? t("Anggota", "Member") : t("Hanya lihat", "View only");
  const pct = m.targetMinor ? Math.round((collected * 100) / m.targetMinor) : null;
  return `<button class="link" data-act="back-groups">← ${t("Semua wallet bareng", "All shared wallets")}</button>
  <div class="card"><div class="section-title">${esc(m.name)}</div><div style="font-size:28px;font-weight:600">${esc(formatMinor(collected, m.currency))}</div>
    ${m.targetMinor ? `<span class="muted small">${t("dari", "of")} ${esc(formatMinor(m.targetMinor, m.currency))} · ${pct}%</span><div class="bar"><i style="width:${Math.min(100, pct ?? 0)}%"></i></div>` : `<span class="muted small">${t("tanpa target", "no target")}</span>`}
    ${writer ? `<div class="chips"><button class="btn" data-act="contribute" data-id="${v.row.id}">${icon("add")}${t("Tambah kontribusi", "Add contribution")}</button><button class="btn tonal" data-act="withdraw" data-id="${v.row.id}">${t("Tarik dana", "Withdraw")}</button>${manager ? `<button class="btn outlined" data-act="invite" data-id="${v.row.id}">${t("Undang", "Invite")}</button>` : ""}</div>` : ""}</div>
  <div class="section-title">${t("Anggota", "Members")}</div><div class="list">${v.members.filter((x) => x.status === "active").map((x) => `<div class="row"><span class="avatar">${esc(x.name.slice(0, 1).toUpperCase())}</span><span class="texts"><b>${esc(x.name)}${x.vaultId === me ? ` (${t("kamu", "you")})` : ""}</b></span><span class="badge">${roleLabel(x.role)}</span></div>`).join("")}
    ${v.members.filter((x) => x.status === "pending").map((x) => `<div class="row"><span class="texts"><b>${t("Permintaan bergabung", "Join request")}</b><span class="muted small">${t("Menunggu persetujuan", "Awaiting approval")}</span></span>${manager ? `<button class="btn sm" data-act="approve-member" data-id="${v.row.id}" data-v="${x.vaultId}">${t("Setujui", "Approve")}</button>` : ""}</div>`).join("")}</div>
  ${v.approvals.filter((a) => a.status === "pending").length ? `<div class="section-title">${t("Persetujuan", "Approvals")}</div>${v.approvals.filter((a) => a.status === "pending").map((a) => `<div class="card"><b>${t("Penarikan", "Withdrawal")} ${esc(formatMinor(a.amountMinor, m.currency))}</b><span class="muted small">${t(`Butuh ${a.required} persetujuan. Saat ini: ${a.approvals}.`, `Needs ${a.required} approvals. So far: ${a.approvals}.`)} ${esc(a.note)}</span>${manager ? `<div class="chips"><button class="btn sm" data-act="vote" data-id="${v.row.id}" data-a="${a.id}" data-vote="approve">${t("Setujui", "Approve")}</button><button class="btn outlined sm" data-act="vote" data-id="${v.row.id}" data-a="${a.id}" data-vote="reject">${t("Tolak", "Reject")}</button></div>` : ""}</div>`).join("")}` : ""}
  <div class="section-title">${t("Aktivitas", "Activity")}</div>${v.activity.length ? v.activity.slice(0, 10).map((a) => `<p>• ${esc(a.text)} · ${new Date(a.at).toLocaleDateString()}</p>`).join("") : `<p class="muted">${t("Belum ada aktivitas.", "No activity yet.")}</p>`}`;
}

const pushRecord = async (v: GroupView, c: Contribution) => {
  const key = keyFor(v.row.id, v.row.epoch)!; const now = Date.now(); const rec = { ...c, updatedAt: now };
  await session.api.call("POST", `/v1/groups/${v.row.id}/sync`, { epoch: v.row.epoch, cursor: 0, records: [{ entity: "contribution", id: rec.id, updatedAt: now, deletedAt: null, payload: G.sealRecord(key, v.row.id, v.row.epoch, "contribution", rec.id, JSON.stringify(rec)) }] });
};
const log = async (v: GroupView, text: string) => {
  const key = keyFor(v.row.id, v.row.epoch)!;
  await session.api.call("POST", `/v1/groups/${v.row.id}/activity`, { payload: C.sealText(key, JSON.stringify({ text, at: Date.now() }), G.activityAad(v.row.id)) }).catch(() => {});
};
const myName = () => session.ledger().profile?.name ?? "—";
export const WITHDRAW_THRESHOLD = 1_000_000;

export async function contribute(v: GroupView, withdraw: boolean): Promise<boolean> {
  const m = v.row.meta!;
  const f = await dialog(withdraw ? t("Tarik dana", "Withdraw") : t("Tambah kontribusi", "Add contribution"), `${field("amount", `${t("Nominal", "Amount")} (${m.currency})`, "", { required: true, amount: m.currency, hint: withdraw ? t(`Di atas ${formatMinor(WITHDRAW_THRESHOLD, m.currency)} butuh persetujuan 2 anggota.`, `Above ${formatMinor(WITHDRAW_THRESHOLD, m.currency)} needs 2 approvals.`) : "" })}${field("note", t("Catatan (opsional)", "Note (optional)"))}`);
  if (!f) return false;
  const value = evaluateAmount(String(f.get("amount")), m.currency); if (!value || value <= 0) { toast(t("Nominal tidak valid.", "Invalid amount.")); return false; }
  const minor = toMinor(value, m.currency); const note = String(f.get("note") ?? "").trim();
  try {
    if (withdraw && minor > WITHDRAW_THRESHOLD) {
      const key = keyFor(v.row.id, v.row.epoch)!;
      await session.api.call("POST", `/v1/groups/${v.row.id}/approvals`, { kind: "withdrawal", required: 2, payload: C.sealText(key, JSON.stringify({ amountMinor: minor, note }), G.approvalAad(v.row.id)) });
      await log(v, `${myName()} ${t("meminta penarikan", "requested a withdrawal")}`);
      toast(t("Permintaan penarikan dikirim.", "Withdrawal request sent."));
    } else {
      await pushRecord(v, { id: newId(), vaultId: session.vaultId, kind: withdraw ? "withdrawal" : "contribution", amountMinor: minor, note, at: Date.now(), updatedAt: 0, deletedAt: null });
      await log(v, `${myName()} ${withdraw ? t("menarik dana", "withdrew funds") : t("menambah kontribusi", "added a contribution")}`);
      toast(t("Tersimpan.", "Saved."));
    }
    return true;
  } catch { toast(t("Gagal. Periksa koneksi.", "Failed. Check your connection.")); return false; }
}

export async function vote(v: GroupView, approvalId: string, approve: boolean) {
  await session.api.call("POST", `/v1/groups/${v.row.id}/approvals/${approvalId}/vote`, { vote: approve ? "approve" : "reject" });
  await log(v, `${myName()} ${approve ? t("menyetujui penarikan", "approved a withdrawal") : t("menolak penarikan", "rejected a withdrawal")}`);
}

export async function approveMember(v: GroupView, vaultId: string) {
  const member = v.members.find((m) => m.vaultId === vaultId); const key = keyFor(v.row.id, v.row.epoch); if (!member || !key) return;
  await session.api.call("POST", `/v1/groups/${v.row.id}/members/${vaultId}/approve`, { envelope: G.wrapKey(key, v.row.epoch, member.boxPub, member.kemPub) });
}

export async function createInvite(v: GroupView): Promise<void> {
  const f = await dialog(t("Undang anggota", "Invite a member"), select("role", t("Peran", "Role"), [["member", t("Anggota", "Member")], ["viewer", t("Hanya lihat", "View only")]], "member"), t("Buat tautan", "Create link"));
  if (!f) return;
  const ik = C.randomBytes(32);
  const res = await session.api.call<{ token: string }>("POST", `/v1/groups/${v.row.id}/invites`, { role: String(f.get("role")), maxUses: 10, preview: C.sealText(ik, v.row.meta!.name, G.INVITE_PREVIEW_AAD) });
  const link = `${session.api.base}/invite/${res.token}#${C.b64url(ik)}`;
  await dialog(t("Tautan undangan", "Invite link"), `<p class="muted">${t("Berlaku 7 hari, maks 10 anggota. Kamu tetap yang menyetujui.", "Valid 7 days, max 10 members. You still approve each request.")}</p><div class="field"><input readonly value="${esc(link)}" onfocus="this.select()"></div>`, t("Selesai", "Done"));
}

void toMajor;
