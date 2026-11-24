// Web-side shared-wallet code against the real wallet server (a Node process). Skipped when the server dependencies are missing.
import "fake-indexeddb/auto";
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdtempSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const BACKEND = resolve(import.meta.dirname, "../../server");
const runnable = existsSync(join(BACKEND, "node_modules"));

describe.runIf(runnable)("web shared wallets ↔ backend", () => {
  let server: ChildProcess; let base = "";

  beforeAll(async () => {
    // The UI helpers read localStorage lazily (language); Node has none.
    (globalThis as any).localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
    const port = await new Promise<number>((ok) => { const s = createServer().listen(0, () => { const p = (s.address() as any).port; s.close(() => ok(p)); }); });
    server = spawn("node", ["--experimental-strip-types", "--disable-warning=ExperimentalWarning", "src/index.ts"], {
      cwd: BACKEND, env: { ...process.env, PORT: String(port), DATA_DIR: mkdtempSync(join(tmpdir(), "arthatis-web-")) }, stdio: "ignore",
    });
    base = `http://localhost:${port}`;
    for (let i = 0; i < 100; i++) { try { if ((await fetch(`${base}/health`)).ok) return; } catch { /* not up yet */ } await new Promise((r) => setTimeout(r, 100)); }
    throw new Error("backend did not start");
  }, 20_000);

  afterAll(() => { server?.kill(); });

  const call = async (method: string, path: string, body?: unknown, token?: string) => {
    const r = await fetch(base + path, { method, headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
    const j: any = await r.json().catch(() => ({})); if (!r.ok) throw Object.assign(new Error(`${method} ${path} ${r.status}`), { status: r.status, body: j }); return j;
  };

  it("reads a group the phone created, approves a joiner, votes, and shows their records", async () => {
    const C = await import("../src/app/crypto");
    const G = await import("../src/app/groupcrypto");
    const { session } = await import("../src/app/session");
    const { loadGroup, listGroups, approveMember, vote } = await import("../src/app/bareng");
    session.api.base = base;

    // The owner's phone: a vault with device keys, and a group sealed to those keys.
    const phoneKeys = C.newWebKeys(); const vk = C.randomBytes(32);
    const phone = await call("POST", "/v1/devices", { label: "Pixel", signPub: C.b64(C.randomBytes(32)), boxPub: C.b64(phoneKeys.boxPub), kemPub: C.b64(phoneKeys.kemPub) });
    const gk = C.randomBytes(32);
    const meta = { name: "Liburan Bali", kind: "tabungan", currency: "IDR", targetMinor: 12_000_000 };
    const created = await call("POST", "/v1/groups", {
      meta: C.sealText(gk, JSON.stringify(meta), G.metaAad("pending")), envelope: G.wrapKey(gk, 1, phoneKeys.boxPub, phoneKeys.kemPub),
      profile: C.sealText(gk, JSON.stringify({ name: "Sinta" }), G.profileAad("pending")),
    }, phone.token);
    const gid: string = created.id;
    await call("PUT", `/v1/groups/${gid}/meta`, { meta: C.sealText(gk, JSON.stringify(meta), G.metaAad(gid)) }, phone.token);
    await call("PUT", `/v1/groups/${gid}/profile`, { profile: C.sealText(gk, JSON.stringify({ name: "Sinta" }), G.profileAad(gid)) }, phone.token);
    // The phone mirrors the group key into the vault so a linked browser can read the group.
    const rowId = `gk_${gid}_1`; const now = Date.now();
    await call("POST", "/v1/sync", { cursor: 0, records: [{ entity: "groupkey", id: rowId, updatedAt: now, deletedAt: null,
      payload: C.sealText(vk, JSON.stringify({ id: rowId, groupId: gid, epoch: 1, key: C.b64(gk), updatedAt: now, deletedAt: null }), `groupkey|${rowId}`) }] }, phone.token);

    // Link a browser exactly like the real QR flow, then pull the vault.
    const link = await session.startLink();
    await call("POST", "/v1/link/approve", { id: link.id, nonce: link.nonce, envelope: C.b64(C.sealTo(link.keys.boxPub, link.keys.kemPub, vk)) }, phone.token);
    expect(await session.pollLink(link)).toBe("approved");
    expect(await session.sync()).toBe(true);

    const groups = await listGroups();
    expect(groups).toHaveLength(1);
    expect(groups[0]!.meta?.name).toBe("Liburan Bali");

    // A second person joins with an invite and asks for access.
    const ik = C.randomBytes(32);
    const invite = await call("POST", `/v1/groups/${gid}/invites`, { role: "member", maxUses: 5, preview: C.sealText(ik, "Liburan Bali", G.INVITE_PREVIEW_AAD) }, phone.token);
    const qKeys = C.newWebKeys();
    const q = await call("POST", "/v1/devices", { label: "HP Bayu", signPub: C.b64(C.randomBytes(32)), boxPub: C.b64(qKeys.boxPub), kemPub: C.b64(qKeys.kemPub) });
    await call("POST", "/v1/invites/join", { token: invite.token, profile: C.sealText(ik, JSON.stringify({ name: "Bayu" }), G.INVITE_PROFILE_AAD) }, q.token);

    let view = await loadGroup(gid);
    expect(view.locked).toBe(false);
    expect(view.members.filter((m) => m.status === "pending")).toHaveLength(1);

    // The browser approves them: the envelope it seals must open on Bayu's keys with the right epoch and key.
    await approveMember(view, q.vaultId);
    const env = (await call("GET", `/v1/groups/${gid}`, undefined, q.token)).envelope as string;
    const learned = G.unwrapKey(qKeys, env)!;
    expect(learned.epoch).toBe(1);
    expect(C.b64(learned.key)).toBe(C.b64(gk));

    // Bayu records a contribution and asks to withdraw; the browser sees both and votes.
    await call("POST", `/v1/groups/${gid}/sync`, { epoch: 1, cursor: 0, records: [{ entity: "contribution", id: "c1", updatedAt: now, deletedAt: null,
      payload: G.sealRecord(learned.key, gid, 1, "contribution", "c1", JSON.stringify({ id: "c1", vaultId: q.vaultId, kind: "contribution", amountMinor: 3_000_000, note: "awal", at: now, updatedAt: now, deletedAt: null })) }] }, q.token);
    const approval = await call("POST", `/v1/groups/${gid}/approvals`, { kind: "withdrawal", required: 2, payload: C.sealText(learned.key, JSON.stringify({ amountMinor: 2_000_000, note: "Tiket" }), G.approvalAad(gid)) }, q.token);

    view = await loadGroup(gid);
    expect(view.contributions.map((c) => c.amountMinor)).toEqual([3_000_000]);
    expect(view.approvals[0]).toMatchObject({ status: "pending", amountMinor: 2_000_000, note: "Tiket" });
    expect(view.members.filter((m) => m.status === "active").map((m) => m.role).sort()).toEqual(["member", "owner"]);

    await vote(view, approval.id, true);
    view = await loadGroup(gid);
    expect(view.approvals[0]!.approvals).toBe(1);
    expect(view.activity.length).toBeGreaterThan(0);
  }, 30_000);
});
