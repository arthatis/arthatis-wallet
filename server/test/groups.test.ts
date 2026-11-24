import { describe, expect, it } from "vitest";
import { makeApp, rec } from "./helpers.ts";

async function setup() {
  const ctx = makeApp();
  const owner = await ctx.register("Sinta");
  const created = await ctx.call("POST", "/v1/groups", {
    token: owner.token,
    body: { meta: "bWV0YQ==", envelope: "ZW52LW93bmVy", profile: "cHJvZi1vd25lcg==" },
  });
  const groupId = created.json.id as string;
  const join = async (label: string, role: "admin" | "member" | "viewer" = "member") => {
    const u = await ctx.register(label);
    const invite = await ctx.call("POST", `/v1/groups/${groupId}/invites`, { token: owner.token, body: { role } });
    const joined = await ctx.call("POST", "/v1/invites/join", {
      token: u.token,
      body: { token: invite.json.token, profile: "cHJvZg==" },
    });
    expect(joined.json.status).toBe("pending");
    await ctx.call("POST", `/v1/groups/${groupId}/members/${u.vaultId}/approve`, {
      token: owner.token,
      body: { envelope: `ZW52LSR7bGFiZWx9` },
    });
    return u;
  };
  return { ...ctx, owner, groupId, join };
}

describe("Bareng groups", () => {
  it("requires approval before a joiner gets access", async () => {
    const t = await setup();
    const u = await t.register("Bayu");
    const invite = await t.call("POST", `/v1/groups/${t.groupId}/invites`, { token: t.owner.token, body: {} });
    await t.call("POST", "/v1/invites/join", { token: u.token, body: { token: invite.json.token, profile: "cA==" } });
    const peek = await t.call("GET", `/v1/groups/${t.groupId}`, { token: u.token });
    expect(peek.status).toBe(404);
    const listed = await t.call("GET", "/v1/groups", { token: u.token });
    expect(listed.json.groups[0]).toMatchObject({ status: "pending", meta: null, envelope: null });
    await t.call("POST", `/v1/groups/${t.groupId}/members/${u.vaultId}/approve`, {
      token: t.owner.token,
      body: { envelope: "ZW52" },
    });
    expect((await t.call("GET", `/v1/groups/${t.groupId}`, { token: u.token })).status).toBe(200);
  });

  it("returns only the invite-key preview to someone who has not been approved", async () => {
    const t = await setup();
    const invite = await t.call("POST", `/v1/groups/${t.groupId}/invites`, { token: t.owner.token, body: { preview: "cHJldmlldw==" } });
    const u = await t.register();
    const joined = await t.call("POST", "/v1/invites/join", { token: u.token, body: { token: invite.json.token, profile: "cA==" } });
    expect(joined.json).toMatchObject({ preview: "cHJldmlldw==" });
    expect(JSON.stringify(joined.json)).not.toContain("bWV0YQ==");
  });

  it("enforces invite expiry and use limits", async () => {
    const t = await setup();
    const invite = await t.call("POST", `/v1/groups/${t.groupId}/invites`, { token: t.owner.token, body: { maxUses: 1 } });
    const a = await t.register();
    const b = await t.register();
    expect((await t.call("POST", "/v1/invites/join", { token: a.token, body: { token: invite.json.token, profile: "cA==" } })).status).toBe(201);
    expect((await t.call("POST", "/v1/invites/join", { token: b.token, body: { token: invite.json.token, profile: "cA==" } })).status).toBe(410);
    const old = await t.call("POST", `/v1/groups/${t.groupId}/invites`, { token: t.owner.token, body: {} });
    t.clock.t += 7 * 24 * 3600 * 1000 + 1;
    expect((await t.call("POST", "/v1/invites/join", { token: b.token, body: { token: old.json.token, profile: "cA==" } })).status).toBe(410);
  });

  it("lets members write, blocks viewers, and syncs between members", async () => {
    const t = await setup();
    const member = await t.join("Bayu", "member");
    const viewer = await t.join("Nadia", "viewer");
    const write = await t.call("POST", `/v1/groups/${t.groupId}/sync`, {
      token: member.token,
      body: { epoch: 1, cursor: 0, records: [rec("c1", 5)] },
    });
    expect(write.status).toBe(200);
    const view = await t.call("POST", `/v1/groups/${t.groupId}/sync`, { token: viewer.token, body: { epoch: 1, cursor: 0, records: [] } });
    expect(view.json.records).toHaveLength(1);
    const denied = await t.call("POST", `/v1/groups/${t.groupId}/sync`, {
      token: viewer.token,
      body: { epoch: 1, cursor: 0, records: [rec("c2", 6)] },
    });
    expect(denied.status).toBe(403);
    const outsider = await t.register();
    expect((await t.call("POST", `/v1/groups/${t.groupId}/sync`, { token: outsider.token, body: { epoch: 1 } })).status).toBe(404);
  });

  it("forces key rotation after removing a member", async () => {
    const t = await setup();
    const member = await t.join("Bayu");
    expect((await t.call("DELETE", `/v1/groups/${t.groupId}/members/${member.vaultId}`, { token: t.owner.token })).status).toBe(200);
    const blocked = await t.call("POST", `/v1/groups/${t.groupId}/sync`, { token: t.owner.token, body: { epoch: 1 } });
    expect(blocked.json.error).toBe("rotation_required");

    const wrong = await t.call("POST", `/v1/groups/${t.groupId}/rotate`, {
      token: t.owner.token,
      body: { epoch: 2, envelopes: { [t.owner.vaultId]: "bg==", [member.vaultId]: "bg==" } },
    });
    expect(wrong.status).toBe(400);
    const ok = await t.call("POST", `/v1/groups/${t.groupId}/rotate`, {
      token: t.owner.token,
      body: { epoch: 2, envelopes: { [t.owner.vaultId]: "bmV3" } },
    });
    expect(ok.json.epoch).toBe(2);
    const stale = await t.call("POST", `/v1/groups/${t.groupId}/sync`, { token: t.owner.token, body: { epoch: 1 } });
    expect(stale.json).toMatchObject({ error: "epoch_mismatch", epoch: 2 });
    expect((await t.call("POST", `/v1/groups/${t.groupId}/sync`, { token: t.owner.token, body: { epoch: 2 } })).status).toBe(200);
    // removed member lost access
    expect((await t.call("GET", `/v1/groups/${t.groupId}`, { token: member.token })).status).toBe(404);
  });

  it("approves a withdrawal when 2 of 3 managers vote yes", async () => {
    const t = await setup();
    const admin = await t.join("Randi", "admin");
    const member = await t.join("Bayu", "member");
    const created = await t.call("POST", `/v1/groups/${t.groupId}/approvals`, {
      token: member.token,
      body: { kind: "withdrawal", payload: "cGF5", required: 2 },
    });
    const id = created.json.id;
    const v1 = await t.call("POST", `/v1/groups/${t.groupId}/approvals/${id}/vote`, { token: admin.token, body: { vote: "approve" } });
    expect(v1.json.status).toBe("pending");
    expect((await t.call("POST", `/v1/groups/${t.groupId}/approvals/${id}/vote`, { token: member.token, body: { vote: "approve" } })).status).toBe(403);
    const v2 = await t.call("POST", `/v1/groups/${t.groupId}/approvals/${id}/vote`, { token: t.owner.token, body: { vote: "approve" } });
    expect(v2.json.status).toBe("approved");
    const list = await t.call("GET", `/v1/groups/${t.groupId}/approvals`, { token: member.token });
    expect(list.json.approvals[0]).toMatchObject({ status: "approved", approvals: 2 });
  });

  it("limits role changes and owner removal", async () => {
    const t = await setup();
    const admin = await t.join("Randi", "admin");
    const member = await t.join("Bayu", "member");
    expect((await t.call("PUT", `/v1/groups/${t.groupId}/members/${member.vaultId}/role`, { token: admin.token, body: { role: "admin" } })).status).toBe(403);
    expect((await t.call("PUT", `/v1/groups/${t.groupId}/members/${member.vaultId}/role`, { token: admin.token, body: { role: "viewer" } })).status).toBe(200);
    expect((await t.call("DELETE", `/v1/groups/${t.groupId}/members/${t.owner.vaultId}`, { token: admin.token })).status).toBe(403);
    expect((await t.call("DELETE", `/v1/groups/${t.groupId}`, { token: admin.token })).status).toBe(403);
    expect((await t.call("DELETE", `/v1/groups/${t.groupId}`, { token: t.owner.token })).status).toBe(200);
  });

  it("lets a member replace their own profile", async () => {
    const t = await setup();
    const m = await t.join("Bayu");
    expect((await t.call("PUT", `/v1/groups/${t.groupId}/profile`, { token: m.token, body: { profile: "bmV3" } })).status).toBe(200);
    const detail = await t.call("GET", `/v1/groups/${t.groupId}`, { token: t.owner.token });
    expect(detail.json.members.find((x: any) => x.vaultId === m.vaultId).profile).toBe("bmV3");
  });

  it("records an activity feed for writers only", async () => {
    const t = await setup();
    const viewer = await t.join("Nadia", "viewer");
    expect((await t.call("POST", `/v1/groups/${t.groupId}/activity`, { token: t.owner.token, body: { payload: "YWN0" } })).status).toBe(201);
    expect((await t.call("POST", `/v1/groups/${t.groupId}/activity`, { token: viewer.token, body: { payload: "YWN0" } })).status).toBe(403);
    const feed = await t.call("GET", `/v1/groups/${t.groupId}/activity`, { token: viewer.token });
    expect(feed.json.activity).toHaveLength(1);
  });
});
