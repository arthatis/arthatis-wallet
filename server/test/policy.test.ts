import { describe, expect, it } from "vitest";
import { purgeUnactivated } from "../src/purge.ts";
import { makeApp, rec } from "./helpers.ts";

const reg = (ctx: ReturnType<typeof makeApp>, extra: Record<string, unknown> = {}) =>
  ctx.call("POST", "/v1/devices", { body: { label: "HP", signPub: "sign-pub-key", boxPub: "box-pub-key", kemPub: "kem-pub-key", ...extra } });
const SVC = { Authorization: "Bearer service-secret" };
const svc = (ctx: ReturnType<typeof makeApp>, method: string, path: string, body?: unknown) =>
  ctx.call(method, path, { headers: SVC, body });

describe("first vault is the admin", () => {
  it("makes only the first registration an admin, even when two race", async () => {
    const ctx = makeApp();
    const [a, b] = await Promise.all([reg(ctx), reg(ctx)]);
    const info = async (r: typeof a) => (await ctx.call("GET", "/v1/vault", { token: r.json.token })).json.isAdmin;
    expect([await info(a), await info(b)].filter(Boolean)).toHaveLength(1);
    const c = await reg(ctx);
    expect((await ctx.call("GET", "/v1/vault", { token: c.json.token })).json.isAdmin).toBe(false);
  });

  it("only the admin vault can use the admin API", async () => {
    const ctx = makeApp();
    const admin = (await reg(ctx)).json; const other = (await reg(ctx)).json;
    expect((await ctx.call("GET", "/v1/admin/vaults", { token: admin.token })).status).toBe(200);
    expect((await ctx.call("GET", "/v1/admin/vaults", { token: other.token })).status).toBe(403);
    expect((await ctx.call("GET", "/v1/admin/vaults")).status).toBe(401);
  });
});

describe("registration modes", () => {
  it("advertises the policy publicly", async () => {
    const ctx = makeApp({ policy: { requireActivation: true, payUrl: "https://pay.example/beli" } });
    expect((await ctx.call("GET", "/v1/server")).json).toEqual({ registration: "open", mail: true, activation: true, payUrl: "https://pay.example/beli" });
  });

  it("email mode: only allowlisted, verified emails can register (the first vault is exempt)", async () => {
    const ctx = makeApp({ policy: { registrationDefault: "email" } });
    const admin = (await reg(ctx)).json;
    expect((await reg(ctx)).json.error).toBe("email_required");

    await ctx.call("POST", "/v1/admin/allowlist", { token: admin.token, body: { emails: ["Sinta@Example.com"] } });
    expect((await ctx.call("GET", "/v1/admin/allowlist", { token: admin.token })).json.emails).toEqual(["sinta@example.com"]);

    // not on the list: same 202, no mail
    await ctx.call("POST", "/v1/register/email", { body: { email: "stranger@example.com" } });
    expect(ctx.mails).toHaveLength(0);
    expect((await reg(ctx, { email: "stranger@example.com", emailCode: "123456" })).json.error).toBe("email_not_allowed");

    await ctx.call("POST", "/v1/register/email", { body: { email: "sinta@example.com" } });
    const code = /(\d{6})/.exec(ctx.mails.at(-1)!.html)![1]!;
    expect((await reg(ctx, { email: "sinta@example.com", emailCode: code === "000000" ? "111111" : "000000" })).json.error).toBe("wrong_code");
    const ok = await reg(ctx, { email: "sinta@example.com", emailCode: code });
    expect(ok.status).toBe(201);
    // the verified email becomes the vault's recovery email
    expect((await ctx.call("GET", "/v1/vault", { token: ok.json.token })).json.hasEmail).toBe(true);
    // codes are single use
    expect((await reg(ctx, { email: "sinta@example.com", emailCode: code })).status).toBe(403);
  });

  it("the admin can switch modes at runtime, but not to email without SMTP", async () => {
    const ctx = makeApp();
    const admin = (await reg(ctx)).json;
    expect((await ctx.call("PUT", "/v1/admin/settings", { token: admin.token, body: { registration: "email" } })).status).toBe(200);
    expect((await ctx.call("GET", "/v1/server")).json.registration).toBe("email");
    expect((await reg(ctx)).status).toBe(403);
    await ctx.call("PUT", "/v1/admin/settings", { token: admin.token, body: { registration: "open" } });
    expect((await reg(ctx)).status).toBe(201);

    const noMail = makeApp({ mailer: null });
    const a2 = (await reg(noMail)).json;
    expect((await noMail.call("PUT", "/v1/admin/settings", { token: a2.token, body: { registration: "email" } })).json.error).toBe("mail_not_configured");
  });
});

describe("activation (hosted)", () => {
  const hosted = () => makeApp({ policy: { requireActivation: true, adminToken: "service-secret" } });

  it("keeps an unpaid vault out until a service activates it, then locks it again on expiry", async () => {
    const ctx = hosted();
    await reg(ctx); // first = admin, exempt
    const user = (await reg(ctx)).json;

    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).json).toMatchObject({ error: "payment_required", vaultId: user.vaultId });
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).status).toBe(402);
    // the status call stays open so the app can show the vault ID and pay link
    const status = (await ctx.call("GET", "/v1/vault", { token: user.token })).json;
    expect(status).toMatchObject({ active: false, activationRequired: true, vaultId: user.vaultId });

    const until = ctx.clock.t + 30 * 86_400_000;
    expect((await svc(ctx, "POST", `/v1/admin/vaults/${user.vaultId}/activate`, { ref: "cust_42", until })).status).toBe(200);
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: { cursor: 0, records: [rec("t1", 1)] } })).status).toBe(200);
    expect((await ctx.call("GET", "/v1/vault", { token: user.token })).json).toMatchObject({ active: true, activeUntil: until });
    expect((await svc(ctx, "GET", "/v1/admin/vaults/by-ref/cust_42")).json.vaults).toHaveLength(1);

    ctx.clock.t = until + 1;
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).status).toBe(402);

    // renewing = activating again with a later date; no new vault, no new code
    await svc(ctx, "POST", `/v1/admin/vaults/${user.vaultId}/activate`, { ref: "cust_42", until: until + 86_400_000 });
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).status).toBe(200);
    await svc(ctx, "POST", `/v1/admin/vaults/${user.vaultId}/deactivate`);
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).status).toBe(402);
  });

  it("never lets a vault activate itself or an outsider use the service token", async () => {
    const ctx = hosted();
    await reg(ctx);
    const user = (await reg(ctx)).json;
    const until = ctx.clock.t + 86_400_000;
    expect((await ctx.call("POST", `/v1/admin/vaults/${user.vaultId}/activate`, { token: user.token, body: { ref: "x", until } })).status).toBe(402);
    expect((await ctx.call("POST", `/v1/admin/vaults/${user.vaultId}/activate`, { headers: { Authorization: "Bearer wrong" }, body: { ref: "x", until } })).status).toBe(401);
  });

  it("rate-limits registrations per client and purges vaults that never paid", async () => {
    const ctx = makeApp({ policy: { requireActivation: true, registerRatePerHour: 2, pendingPurgeDays: 7 } });
    await reg(ctx);
    expect((await reg(ctx)).status).toBe(201);
    expect((await reg(ctx)).status).toBe(201);
    expect((await reg(ctx)).json.error).toBe("rate_limited");
    ctx.clock.t += 3_600_001;
    expect((await reg(ctx)).status).toBe(201);

    const paid = (await reg(ctx)).json;
    await svc(ctx, "POST", `/v1/admin/vaults/${paid.vaultId}/activate`, { ref: "c", until: ctx.clock.t + 1e12 }).catch(() => null);
    ctx.clock.t += 8 * 86_400_000;
    const before = (ctx.deps.db.prepare("SELECT COUNT(*) n FROM vaults").get() as { n: number }).n;
    const purged = purgeUnactivated(ctx.deps.db, ctx.clock.t, 7);
    const after = (ctx.deps.db.prepare("SELECT COUNT(*) n FROM vaults").get() as { n: number }).n;
    expect(purged).toBe(before - after);
    expect(purged).toBeGreaterThan(0);
    expect(ctx.deps.db.prepare("SELECT is_admin FROM vaults WHERE is_admin = 1").get()).toBeTruthy();
  });
});

describe("suspension and deletion", () => {
  it("lets the admin suspend, resume and delete others but not itself", async () => {
    const ctx = makeApp();
    const admin = (await reg(ctx)).json; const user = (await reg(ctx)).json;
    expect((await ctx.call("POST", `/v1/admin/vaults/${user.vaultId}/suspend`, { token: admin.token })).status).toBe(200);
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).json.error).toBe("suspended");
    await ctx.call("POST", `/v1/admin/vaults/${user.vaultId}/resume`, { token: admin.token });
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).status).toBe(200);

    expect((await ctx.call("POST", `/v1/admin/vaults/${admin.vaultId}/suspend`, { token: admin.token })).json.error).toBe("cannot_target_admin");
    expect((await ctx.call("DELETE", `/v1/admin/vaults/${admin.vaultId}`, { token: admin.token })).status).toBe(409);

    await ctx.call("POST", "/v1/sync", { token: user.token, body: { cursor: 0, records: [rec("t1", 1)] } });
    expect((await ctx.call("DELETE", `/v1/admin/vaults/${user.vaultId}`, { token: admin.token })).status).toBe(200);
    expect((await ctx.call("POST", "/v1/sync", { token: user.token, body: {} })).status).toBe(401);
    expect((ctx.deps.db.prepare("SELECT COUNT(*) n FROM records").get() as { n: number }).n).toBe(0);
  });

  it("lists vault metadata without any content", async () => {
    const ctx = makeApp();
    const admin = (await reg(ctx)).json; const u = (await reg(ctx)).json;
    await ctx.call("POST", "/v1/sync", { token: u.token, body: { cursor: 0, records: [rec("t1", 1)] } });
    const list = (await ctx.call("GET", "/v1/admin/vaults", { token: admin.token })).json.vaults;
    expect(list).toHaveLength(2);
    expect(JSON.stringify(list)).not.toContain("Y2lwaGVy");
    expect(list.find((v: any) => v.id === admin.vaultId).isAdmin).toBe(true);
  });
});
