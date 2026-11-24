import { Api, API_BASE, ApiError } from "./api";
import * as C from "./crypto";
import { ENTITY, emptyLedger, type Ledger, type Budget, type Category, type Profile, type RateOverride, type Recurring, type Tx, type Wallet } from "./model";
import { loadJson, loadSecret, saveJson, saveSecret, wipe } from "./storage";

interface Row { entity: string; id: string; json: string; updatedAt: number; deletedAt: number | null; dirty: boolean }
interface Persisted { token: string; vaultId: string; deviceId: string; cursor: number; rows: Row[] }

export interface LinkState { id: string; secret: string; nonce: string; code: string; expiresAt: number; qr: string; keys: C.WebKeys }
export type LinkPoll = "pending" | "approved" | "expired";

const listeners = new Set<() => void>();
export const onChange = (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn); };
const emit = () => listeners.forEach((fn) => fn());

export interface VaultInfo { isAdmin: boolean; active: boolean; activationRequired: boolean; activeUntil: number | null; payUrl: string | null }

class Session {
  info: VaultInfo | null = null;
  /** Set when the server refuses this vault: payment_required (not activated/expired) or suspended. */
  blocked: "payment_required" | "suspended" | null = null;

  token: string | null = null;
  vaultId = "";
  deviceId = "";
  cursor = 0;
  vaultKey: Uint8Array | null = null;
  rows = new Map<string, Row>();
  online = true;
  readonly api = new Api(API_BASE, () => this.token);

  get linked() { return !!this.token && !!this.vaultKey; }
  private key = (e: string, id: string) => `${e}|${id}`;

  async restore(): Promise<boolean> {
    const [p, vk] = await Promise.all([loadJson<Persisted>("session"), loadSecret("vaultKey")]);
    if (!p || !vk) return false;
    this.token = p.token; this.vaultId = p.vaultId; this.deviceId = p.deviceId; this.cursor = p.cursor; this.vaultKey = vk;
    this.rows = new Map(p.rows.map((r) => [this.key(r.entity, r.id), r]));
    return true;
  }

  async persist() {
    if (!this.token) return;
    await saveJson("session", { token: this.token, vaultId: this.vaultId, deviceId: this.deviceId, cursor: this.cursor, rows: [...this.rows.values()] } satisfies Persisted);
  }

  // --- Linking (W03) -----------------------------------------------------------------------
  async startLink(): Promise<LinkState> {
    const keys = C.newWebKeys();
    const label = `${browserName()} · ${platformName()}`;
    const res = await this.api.call<{ id: string; secret: string; nonce: string; code: string; expiresAt: number }>(
      "POST", "/v1/link/sessions", { webPub: C.b64(C.webPub(keys)), label }, {}, false,
    );
    const qr = `arthatis://link?s=${encodeURIComponent(API_BASE)}&id=${encodeURIComponent(res.id)}&n=${encodeURIComponent(res.nonce)}`;
    return { ...res, qr, keys };
  }

  async pollLink(link: LinkState): Promise<LinkPoll> {
    if (Date.now() > link.expiresAt) return "expired";
    try {
      const r = await this.api.call<any>("GET", `/v1/link/sessions/${link.id}`, undefined, { "x-link-secret": link.secret }, false);
      if (r.status === "expired") return "expired";
      if (r.status !== "approved") return "pending";
      this.vaultKey = C.openFrom(link.keys, C.unb64(r.envelope));
      this.token = r.token; this.vaultId = r.vaultId; this.deviceId = r.deviceId; this.cursor = 0; this.rows.clear();
      await saveSecret("vaultKey", this.vaultKey);
      await this.persist();
      return "approved";
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 410)) return "expired";
      return "pending";
    }
  }

  /** Unlink: the browser forgets everything; the server copy is untouched. */
  async logout(remote = true) {
    if (remote && this.token) await this.api.call("POST", "/v1/session/logout").catch(() => {});
    this.token = null; this.vaultKey = null; this.rows.clear(); this.cursor = 0;
    await wipe(); emit();
  }

  async loadInfo() {
    try { this.info = await this.api.call<VaultInfo>("GET", "/v1/vault"); this.blocked = this.info.active ? null : "payment_required"; emit(); } catch { /* offline */ }
  }

  // --- Sync ---------------------------------------------------------------------------------
  async sync(): Promise<boolean> {
    if (!this.linked) return false;
    const vk = this.vaultKey!;
    try {
      for (;;) {
        const dirty = [...this.rows.values()].filter((r) => r.dirty).slice(0, 200);
        const res = await this.api.call<{ cursor: number; more: boolean; records: any[] }>("POST", "/v1/sync", {
          cursor: this.cursor,
          records: dirty.map((r) => ({ entity: r.entity, id: r.id, updatedAt: r.updatedAt, deletedAt: r.deletedAt, payload: C.sealText(vk, r.json, `${r.entity}|${r.id}`) })),
        });
        for (const d of dirty) { const cur = this.rows.get(this.key(d.entity, d.id)); if (cur && cur.updatedAt === d.updatedAt) cur.dirty = false; }
        for (const r of res.records) {
          const k = this.key(r.entity, r.id); const local = this.rows.get(k);
          if (local && local.updatedAt >= r.updatedAt) continue;
          let json: string; try { json = C.openText(vk, r.payload, `${r.entity}|${r.id}`); } catch { continue; }
          this.rows.set(k, { entity: r.entity, id: r.id, json, updatedAt: r.updatedAt, deletedAt: r.deletedAt, dirty: false });
        }
        this.cursor = res.cursor;
        if (!res.more && ![...this.rows.values()].some((r) => r.dirty)) break;
      }
      this.online = true; this.blocked = null; await this.persist(); emit(); return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) { await this.logout(false); return false; }
      if (e instanceof ApiError && (e.status === 402 || e.status === 403)) {
        this.blocked = e.status === 402 ? "payment_required" : "suspended"; emit(); return false;
      }
      this.online = false; emit(); return false;
    }
  }

  // --- Records ------------------------------------------------------------------------------
  put<T extends { id: string; updatedAt: number; deletedAt: number | null }>(entity: string, record: T) {
    const r = { ...record, updatedAt: Date.now() };
    this.rows.set(this.key(entity, r.id), { entity, id: r.id, json: JSON.stringify(r), updatedAt: r.updatedAt, deletedAt: r.deletedAt, dirty: true });
    void this.persist(); emit(); void this.sync();
    return r;
  }

  private of<T>(entity: string): T[] {
    const out: T[] = [];
    for (const r of this.rows.values()) if (r.entity === entity) { try { out.push(JSON.parse(r.json) as T); } catch { /* skip */ } }
    return out;
  }

  ledger(): Ledger {
    if (!this.linked) return emptyLedger();
    return {
      wallets: this.of<Wallet>(ENTITY.wallet).sort((a, b) => a.position - b.position),
      categories: this.of<Category>(ENTITY.category).sort((a, b) => a.position - b.position),
      txs: this.of<Tx>(ENTITY.tx).sort((a, b) => b.occurredAt - a.occurredAt),
      recurring: this.of<Recurring>(ENTITY.recurring),
      overrides: this.of<RateOverride>(ENTITY.rate),
      budgets: this.of<Budget>(ENTITY.budget),
      profile: this.of<Profile>(ENTITY.setting).find((p) => p.id === "profile") ?? null,
    };
  }

  /** Group keys the phone mirrored into the vault so this browser can read shared wallets. */
  groupKeys(): Map<string, Map<number, Uint8Array>> {
    const out = new Map<string, Map<number, Uint8Array>>();
    for (const r of this.rows.values()) {
      if (r.entity !== "groupkey" || r.deletedAt !== null) continue;
      const v = JSON.parse(r.json) as { groupId: string; epoch: number; key: string };
      if (!out.has(v.groupId)) out.set(v.groupId, new Map());
      out.get(v.groupId)!.set(v.epoch, C.unb64(v.key));
    }
    return out;
  }
}

export const session = new Session();

function browserName() {
  const u = navigator.userAgent;
  return /Edg\//.test(u) ? "Edge" : /Chrome\//.test(u) ? "Chrome" : /Firefox\//.test(u) ? "Firefox" : /Safari\//.test(u) ? "Safari" : "Browser";
}
function platformName() {
  const u = navigator.userAgent;
  return /Android/.test(u) ? "Android" : /iPhone|iPad/.test(u) ? "iOS" : /Mac/.test(u) ? "Mac" : /Windows/.test(u) ? "Windows" : /Linux/.test(u) ? "Linux" : "Web";
}
