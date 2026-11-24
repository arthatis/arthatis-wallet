import { describe, expect, it } from "vitest";
import { makeApp, rec } from "./helpers.ts";

describe("vault sync", () => {
  it("rejects unauthenticated calls", async () => {
    const { call } = makeApp();
    expect((await call("POST", "/v1/sync", { body: {} })).status).toBe(401);
  });

  it("stores ciphertext, resolves conflicts by updatedAt, and pages by cursor", async () => {
    const { call, register } = makeApp();
    const phone = await register();
    const first = await call("POST", "/v1/sync", {
      token: phone.token,
      body: { cursor: 0, records: [rec("t1", 10), rec("t2", 10)] },
    });
    expect(first.json.records).toHaveLength(2);
    const cursor = first.json.cursor;

    // older write loses, later delete wins
    const second = await call("POST", "/v1/sync", {
      token: phone.token,
      body: { cursor, records: [rec("t1", 5, null, "b2xk"), rec("t2", 20, 20)] },
    });
    expect(second.json.records).toHaveLength(1);
    expect(second.json.records[0]).toMatchObject({ id: "t2", deletedAt: 20 });

    const all = await call("POST", "/v1/sync", { token: phone.token, body: { cursor: 0, records: [] } });
    expect(all.json.records.find((r: any) => r.id === "t1").payload).toBe("Y2lwaGVy");
  });

  it("refuses plaintext objects as payload", async () => {
    const { call, register } = makeApp();
    const phone = await register();
    const bad = await call("POST", "/v1/sync", {
      token: phone.token,
      body: { cursor: 0, records: [{ ...rec("t1", 1), payload: { amount: 25000 } }] },
    });
    expect(bad.status).toBe(400);
  });

  it("isolates vaults", async () => {
    const { call, register } = makeApp();
    const a = await register();
    const b = await register();
    await call("POST", "/v1/sync", { token: a.token, body: { cursor: 0, records: [rec("t1", 1)] } });
    const seen = await call("POST", "/v1/sync", { token: b.token, body: { cursor: 0, records: [] } });
    expect(seen.json.records).toHaveLength(0);
  });
});

describe("web link (QR)", () => {
  const start = async (ctx: ReturnType<typeof makeApp>) => {
    const phone = await ctx.register();
    const link = await ctx.call("POST", "/v1/link/sessions", { body: { webPub: "web-pub-key", label: "Chrome" } });
    return { phone, link: link.json };
  };

  it("links a browser, delivers the envelope once, and shares the vault", async () => {
    const ctx = makeApp();
    const { phone, link } = await start(ctx);
    expect(link.code).toMatch(/^[A-Z2-9]{3}-[A-Z2-9]{3}$/);

    const poll = () => ctx.call("GET", `/v1/link/sessions/${link.id}`, { headers: { "x-link-secret": link.secret } });
    expect((await poll()).json.status).toBe("pending");

    const scan = await ctx.call("POST", "/v1/link/scan", { token: phone.token, body: { id: link.id, nonce: link.nonce } });
    expect(scan.json).toMatchObject({ webPub: "web-pub-key", code: link.code });
    await ctx.call("POST", "/v1/link/approve", {
      token: phone.token,
      body: { id: link.id, nonce: link.nonce, envelope: "c2VhbGVk" },
    });

    const done = await poll();
    expect(done.json).toMatchObject({ status: "approved", envelope: "c2VhbGVk", vaultId: phone.vaultId });
    expect((await poll()).status).toBe(410);

    // web writes, phone reads (mirror)
    await ctx.call("POST", "/v1/sync", { token: done.json.token, body: { cursor: 0, records: [rec("w1", 3)] } });
    const phoneView = await ctx.call("POST", "/v1/sync", { token: phone.token, body: { cursor: 0, records: [] } });
    expect(phoneView.json.records.map((r: any) => r.id)).toEqual(["w1"]);

    // a linked browser can list sessions, but only a phone can approve a new link
    expect((await ctx.call("GET", "/v1/sessions", { token: done.json.token })).status).toBe(200);
    const second = await ctx.call("POST", "/v1/link/sessions", { body: { webPub: "web-pub-key", label: "Safari" } });
    expect((await ctx.call("POST", "/v1/link/approve", { token: done.json.token, body: { id: second.json.id, nonce: second.json.nonce, envelope: "eA==" } })).status).toBe(403);
    const list = await ctx.call("GET", "/v1/sessions", { token: phone.token });
    expect(list.json.sessions).toHaveLength(1);
    await ctx.call("DELETE", `/v1/sessions/${list.json.sessions[0].id}`, { token: phone.token });
    expect((await ctx.call("POST", "/v1/sync", { token: done.json.token, body: {} })).status).toBe(401);
  });

  it("rejects wrong nonce or secret and expires after 5 minutes", async () => {
    const ctx = makeApp();
    const { phone, link } = await start(ctx);
    expect(
      (await ctx.call("POST", "/v1/link/scan", { token: phone.token, body: { id: link.id, nonce: "wrong-nonce-1" } })).status,
    ).toBe(404);
    expect((await ctx.call("GET", `/v1/link/sessions/${link.id}`, { headers: { "x-link-secret": "nope" } })).status).toBe(404);
    ctx.clock.t += 5 * 60 * 1000 + 1;
    expect(
      (await ctx.call("POST", "/v1/link/approve", { token: phone.token, body: { id: link.id, nonce: link.nonce, envelope: "eA==" } })).status,
    ).toBe(410);
  });

  it("lets a browser unlink itself and revoke-all clears every web session", async () => {
    const ctx = makeApp();
    const { phone, link } = await start(ctx);
    await ctx.call("POST", "/v1/link/approve", { token: phone.token, body: { id: link.id, nonce: link.nonce, envelope: "eA==" } });
    const web = (await ctx.call("GET", `/v1/link/sessions/${link.id}`, { headers: { "x-link-secret": link.secret } })).json;
    await ctx.call("POST", "/v1/session/logout", { token: web.token });
    expect((await ctx.call("POST", "/v1/sync", { token: web.token, body: {} })).status).toBe(401);
    // server copy remains
    expect((await ctx.call("POST", "/v1/sync", { token: phone.token, body: {} })).status).toBe(200);
  });
});

describe("optional email recovery", () => {
  /** Attaches and verifies an email the way the phone does: PUT, read the mailed code, POST it back. */
  const attach = async (ctx: ReturnType<typeof makeApp>, token: string, email: string) => {
    await ctx.call("PUT", "/v1/vault/email", { token, body: { email } });
    const code = /(\d{6})/.exec(ctx.mails.at(-1)!.html)![1]!;
    return { code, verify: (c = code) => ctx.call("POST", "/v1/vault/email/verify", { token, body: { code: c } }) };
  };

  it("answers the same for unknown emails and sends nothing", async () => {
    const ctx = makeApp();
    const r = await ctx.call("POST", "/v1/recovery/request", { body: { email: "nobody@example.com" } });
    expect(r.status).toBe(202);
    expect(ctx.mails).toHaveLength(0);
  });

  it("does not trust an email until the owner types the mailed code", async () => {
    const ctx = makeApp();
    const phone = await ctx.register();
    const { verify } = await attach(ctx, phone.token, "Randi@Example.com");
    expect(ctx.mails[0]!.to).toBe("randi@example.com");
    // pending only: it cannot be used for recovery and the vault does not report an email yet
    expect((await ctx.call("GET", "/v1/vault", { token: phone.token })).json).toMatchObject({ hasEmail: false, emailPending: true });
    await ctx.call("POST", "/v1/recovery/request", { body: { email: "randi@example.com" } });
    expect(ctx.mails).toHaveLength(1);
    expect((await verify()).status).toBe(200);
    expect((await ctx.call("GET", "/v1/vault", { token: phone.token })).json).toMatchObject({ hasEmail: true, emailPending: false });
  });

  it("locks after 5 wrong codes and rejects expired codes", async () => {
    const ctx = makeApp();
    const phone = await ctx.register();
    const { code, verify } = await attach(ctx, phone.token, "a@example.com");
    const wrong = code === "000000" ? "111111" : "000000";
    for (let i = 0; i < 5; i++) expect((await verify(wrong)).json.error).toBe("wrong_code");
    expect((await verify(code)).status).toBe(429);

    const ctx2 = makeApp();
    const p2 = await ctx2.register();
    const a2 = await attach(ctx2, p2.token, "b@example.com");
    ctx2.clock.t += 15 * 60 * 1000 + 1;
    expect((await a2.verify()).json.error).toBe("no_pending_code");
  });

  it("rate-limits code emails and lets one address belong to one vault", async () => {
    const ctx = makeApp();
    const a = await ctx.register(); const b = await ctx.register();
    const first = await attach(ctx, a.token, "same@example.com");
    expect((await ctx.call("PUT", "/v1/vault/email", { token: a.token, body: { email: "same@example.com" } })).status).toBe(429);
    await first.verify();
    const second = await attach(ctx, b.token, "same@example.com");
    expect((await second.verify()).json.error).toBe("email_in_use");
  });

  it("issues a one-time link that returns a new device and the recovery blob", async () => {
    const ctx = makeApp();
    const phone = await ctx.register();
    await (await attach(ctx, phone.token, "randi@example.com")).verify();
    await ctx.call("PUT", "/v1/vault/recovery-blob", { token: phone.token, body: { blob: "d3JhcHBlZA==" } });
    await ctx.call("POST", "/v1/recovery/request", { body: { email: "randi@example.com" } });
    const mail = ctx.mails.at(-1)!;
    expect(mail.subject).toContain("Pulihkan");
    const token = /token=([\w-]+)/.exec(mail.html)![1]!;

    // cooldown: a second request within 60s sends no second mail
    const count = ctx.mails.length;
    await ctx.call("POST", "/v1/recovery/request", { body: { email: "randi@example.com" } });
    expect(ctx.mails).toHaveLength(count);

    const ok = await ctx.call("POST", "/v1/recovery/verify", { body: { token, label: "HP baru" } });
    expect(ok.json).toMatchObject({ vaultId: phone.vaultId, recoveryBlob: "d3JhcHBlZA==" });
    expect((await ctx.call("POST", "/v1/recovery/verify", { body: { token, label: "HP baru" } })).status).toBe(400);
  });

  it("answers 503 when the server has no mail transport", async () => {
    const ctx = makeApp({ mailer: null });
    const phone = await ctx.register();
    expect((await ctx.call("PUT", "/v1/vault/email", { token: phone.token, body: { email: "a@example.com" } })).json.error).toBe("mail_not_configured");
    expect((await ctx.call("POST", "/v1/recovery/request", { body: { email: "a@example.com" } })).status).toBe(503);
  });
});

describe("ai proxy", () => {
  it("is unavailable until configured", async () => {
    const ctx = makeApp();
    const phone = await ctx.register();
    const r = await ctx.call("POST", "/v1/ai/chat", { token: phone.token, body: { messages: [{ role: "user", content: "hai" }] } });
    expect(r.status).toBe(503);
    expect((await ctx.call("GET", "/v1/ai/status", { token: phone.token })).json.available).toBe(false);
  });
});
