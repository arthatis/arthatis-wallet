import { Hono } from "hono";
import { zValidator } from "@hono/zod-validator";
import { requireAuth, type AppDeps, type HonoEnv } from "../context.ts";
import { tx } from "../db.ts";
import { INVITE_TTL_MS } from "../schemas.ts";
import { randomId, sha256 } from "../ids.ts";
import {
  activitySchema,
  approvalCreateSchema,
  approvalVoteSchema,
  groupCreateSchema,
  groupMetaSchema,
  groupSyncSchema,
  inviteCreateSchema,
  inviteJoinSchema,
  memberApproveSchema,
  memberRoleSchema,
  profileSchema,
  rotateSchema,
  type Role,
} from "../schemas.ts";
import { GROUP_SCOPE, syncScope } from "../sync.ts";
import { deleteGroupData } from "../purge.ts";

type Member = { role: Role; status: "pending" | "active"; envelope: string | null };
type GroupRow = { id: string; owner_vault: string; meta: string; epoch: number; rotate_required: number };

const RANK: Record<Role, number> = { owner: 4, admin: 3, member: 2, viewer: 1 };
const isManager = (role: Role) => RANK[role] >= RANK.admin;
const canWrite = (role: Role) => RANK[role] >= RANK.member;

export function groupRoutes(deps: AppDeps) {
  const app = new Hono<HonoEnv>();
  const { db } = deps;
  app.use("*", requireAuth(deps));

  const getGroup = (id: string) =>
    db.prepare("SELECT id, owner_vault, meta, epoch, rotate_required FROM groups WHERE id = ?").get(id) as
      | GroupRow
      | undefined;
  const getMember = (groupId: string, vaultId: string) =>
    db
      .prepare("SELECT role, status, envelope FROM group_members WHERE group_id = ? AND vault_id = ?")
      .get(groupId, vaultId) as Member | undefined;
  const activeMembers = (groupId: string) =>
    db
      .prepare("SELECT vault_id AS vaultId, role FROM group_members WHERE group_id = ? AND status = 'active'")
      .all(groupId) as Array<{ vaultId: string; role: Role }>;

  app.post("/", zValidator("json", groupCreateSchema), (c) => {
    const body = c.req.valid("json");
    const { vaultId } = c.get("auth");
    const id = randomId(12);
    const now = deps.now();
    tx(db, () => {
      db.prepare("INSERT INTO groups (id, owner_vault, meta, created_at) VALUES (?, ?, ?, ?)").run(
        id,
        vaultId,
        body.meta,
        now,
      );
      db.prepare(
        "INSERT INTO group_members (group_id, vault_id, role, status, envelope, profile, joined_at) VALUES (?, ?, 'owner', 'active', ?, ?, ?)",
      ).run(id, vaultId, body.envelope, body.profile, now);
    });
    return c.json({ id, epoch: 1 }, 201);
  });

  app.get("/", (c) => {
    const rows = db
      .prepare(
        `SELECT g.id, g.epoch, g.rotate_required AS rotateRequired,
                m.role, m.status,
                CASE WHEN m.status = 'active' THEN g.meta END AS meta,
                CASE WHEN m.status = 'active' THEN m.envelope END AS envelope,
                (SELECT COUNT(*) FROM group_members x WHERE x.group_id = g.id AND x.status = 'active') AS memberCount
         FROM groups g JOIN group_members m ON m.group_id = g.id AND m.vault_id = ?`,
      )
      .all(c.get("auth").vaultId);
    return c.json({ groups: rows });
  });

  app.get("/:id", (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    const members = db
      .prepare(
        `SELECT m.vault_id AS vaultId, m.role, m.status, m.profile, m.joined_at AS joinedAt,
                v.box_pub AS boxPub, v.kem_pub AS kemPub
         FROM group_members m JOIN vaults v ON v.id = m.vault_id WHERE m.group_id = ?`,
      )
      .all(group.id);
    return c.json({
      id: group.id,
      epoch: group.epoch,
      rotateRequired: group.rotate_required === 1,
      meta: group.meta,
      role: me.role,
      envelope: me.envelope,
      members,
    });
  });

  app.put("/:id/meta", zValidator("json", groupMetaSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!isManager(me.role)) return c.json({ error: "forbidden" }, 403);
    db.prepare("UPDATE groups SET meta = ? WHERE id = ?").run(c.req.valid("json").meta, group.id);
    return c.json({ ok: true });
  });

  app.delete("/:id", (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (me.role !== "owner") return c.json({ error: "forbidden" }, 403);
    tx(db, () => deleteGroupData(db, group.id));
    return c.json({ ok: true });
  });

  // --- Invites
  app.post("/:id/invites", zValidator("json", inviteCreateSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!isManager(me.role)) return c.json({ error: "forbidden" }, 403);
    const body = c.req.valid("json");
    const token = randomId(24);
    const id = randomId(12);
    const now = deps.now();
    db.prepare(
      `INSERT INTO group_invites (id, group_id, token_hash, preview, role, expires_at, max_uses, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(id, group.id, sha256(token), body.preview ?? null, body.role, now + INVITE_TTL_MS, body.maxUses, c.get("auth").vaultId, now);
    return c.json({ id, token, role: body.role, maxUses: body.maxUses, expiresAt: now + INVITE_TTL_MS }, 201);
  });

  app.delete("/:id/invites/:inviteId", (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!isManager(me.role)) return c.json({ error: "forbidden" }, 403);
    db.prepare("UPDATE group_invites SET revoked_at = ? WHERE id = ? AND group_id = ?").run(
      deps.now(),
      c.req.param("inviteId"),
      group.id,
    );
    return c.json({ ok: true });
  });

  // --- Members
  app.post("/:id/members/:vaultId/approve", zValidator("json", memberApproveSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!isManager(me.role)) return c.json({ error: "forbidden" }, 403);
    if (group.rotate_required) return c.json({ error: "rotation_required" }, 409);
    const target = getMember(group.id, c.req.param("vaultId"));
    if (!target || target.status !== "pending") return c.json({ error: "not_pending" }, 409);
    const body = c.req.valid("json");
    db.prepare(
      "UPDATE group_members SET status = 'active', envelope = ?, role = COALESCE(?, role) WHERE group_id = ? AND vault_id = ?",
    ).run(body.envelope, body.role ?? null, group.id, c.req.param("vaultId"));
    return c.json({ ok: true });
  });

  /** A member sets their own display profile once they hold the group key. */
  app.put("/:id/profile", zValidator("json", profileSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    db.prepare("UPDATE group_members SET profile = ? WHERE group_id = ? AND vault_id = ?").run(
      c.req.valid("json").profile,
      group.id,
      c.get("auth").vaultId,
    );
    return c.json({ ok: true });
  });

  app.put("/:id/members/:vaultId/role", zValidator("json", memberRoleSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    const target = getMember(group.id, c.req.param("vaultId"));
    if (!target || target.status !== "active") return c.json({ error: "not_found" }, 404);
    const { role } = c.req.valid("json");
    const allowed =
      target.role !== "owner" &&
      (me.role === "owner" || (me.role === "admin" && target.role !== "admin" && role !== "admin"));
    if (!allowed) return c.json({ error: "forbidden" }, 403);
    db.prepare("UPDATE group_members SET role = ? WHERE group_id = ? AND vault_id = ?").run(
      role,
      group.id,
      c.req.param("vaultId"),
    );
    return c.json({ ok: true });
  });

  /** Leave (self) or remove (manager). Removal forces a key rotation. */
  app.delete("/:id/members/:vaultId", (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me) return c.json({ error: "not_found" }, 404);
    const targetId = c.req.param("vaultId");
    const target = getMember(group.id, targetId);
    if (!target) return c.json({ error: "not_found" }, 404);
    const self = targetId === c.get("auth").vaultId;
    if (target.role === "owner") return c.json({ error: "owner_cannot_leave" }, 403);
    const allowed =
      self || (me.status === "active" && (me.role === "owner" || (me.role === "admin" && target.role !== "admin")));
    if (!allowed) return c.json({ error: "forbidden" }, 403);
    tx(db, () => {
      db.prepare("DELETE FROM group_members WHERE group_id = ? AND vault_id = ?").run(group.id, targetId);
      if (target.status === "active") db.prepare("UPDATE groups SET rotate_required = 1 WHERE id = ?").run(group.id);
    });
    return c.json({ ok: true });
  });

  app.post("/:id/rotate", zValidator("json", rotateSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!isManager(me.role)) return c.json({ error: "forbidden" }, 403);
    const body = c.req.valid("json");
    if (body.epoch !== group.epoch + 1) return c.json({ error: "epoch_mismatch", epoch: group.epoch }, 409);
    const members = activeMembers(group.id);
    const given = Object.keys(body.envelopes).sort().join(",");
    const expected = members.map((m) => m.vaultId).sort().join(",");
    if (given !== expected) return c.json({ error: "envelopes_must_cover_active_members" }, 400);
    tx(db, () => {
      const update = db.prepare("UPDATE group_members SET envelope = ? WHERE group_id = ? AND vault_id = ?");
      for (const [vaultId, envelope] of Object.entries(body.envelopes)) update.run(envelope, group.id, vaultId);
      db.prepare("UPDATE groups SET epoch = ?, rotate_required = 0, meta = COALESCE(?, meta) WHERE id = ?").run(
        body.epoch,
        body.meta ?? null,
        group.id,
      );
    });
    return c.json({ ok: true, epoch: body.epoch });
  });

  // --- Shared ledger
  app.post("/:id/sync", zValidator("json", groupSyncSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    const body = c.req.valid("json");
    if (group.rotate_required) return c.json({ error: "rotation_required" }, 409);
    if (body.epoch !== group.epoch) return c.json({ error: "epoch_mismatch", epoch: group.epoch }, 409);
    if (body.records.length && !canWrite(me.role)) return c.json({ error: "forbidden" }, 403);
    return c.json(syncScope(db, GROUP_SCOPE, group.id, body.cursor, body.records, c.get("auth").vaultId));
  });

  // --- Activity feed
  app.post("/:id/activity", zValidator("json", activitySchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!canWrite(me.role)) return c.json({ error: "forbidden" }, 403);
    const seq = tx(db, () => {
      const next = (
        db.prepare("SELECT COALESCE(MAX(seq), 0) + 1 AS n FROM group_activity WHERE group_id = ?").get(group.id) as {
          n: number;
        }
      ).n;
      db.prepare(
        "INSERT INTO group_activity (group_id, seq, actor, payload, created_at) VALUES (?, ?, ?, ?, ?)",
      ).run(group.id, next, c.get("auth").vaultId, c.req.valid("json").payload, deps.now());
      return next;
    });
    return c.json({ seq }, 201);
  });

  app.get("/:id/activity", (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    const cursor = Number(c.req.query("cursor") ?? 0) || 0;
    const rows = db
      .prepare(
        "SELECT seq, actor, payload, created_at AS createdAt FROM group_activity WHERE group_id = ? AND seq > ? ORDER BY seq LIMIT 200",
      )
      .all(group.id, cursor);
    return c.json({ activity: rows });
  });

  // --- Withdrawal approvals. `required` is set by the client (amounts are encrypted).
  const approvalRow = (id: string) =>
    db.prepare("SELECT id, group_id, required, status FROM group_approvals WHERE id = ?").get(id) as
      | { id: string; group_id: string; required: number; status: string }
      | undefined;

  app.post("/:id/approvals", zValidator("json", approvalCreateSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!canWrite(me.role)) return c.json({ error: "forbidden" }, 403);
    const body = c.req.valid("json");
    const id = randomId(12);
    db.prepare(
      `INSERT INTO group_approvals (id, group_id, kind, payload, required, status, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)`,
    ).run(id, group.id, body.kind, body.payload, body.required, c.get("auth").vaultId, deps.now());
    return c.json({ id }, 201);
  });

  app.get("/:id/approvals", (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    const rows = db
      .prepare(
        `SELECT a.id, a.kind, a.payload, a.required, a.status, a.created_by AS createdBy, a.created_at AS createdAt,
                (SELECT COUNT(*) FROM group_votes v WHERE v.approval_id = a.id AND v.vote = 'approve') AS approvals,
                (SELECT COUNT(*) FROM group_votes v WHERE v.approval_id = a.id AND v.vote = 'reject') AS rejections
         FROM group_approvals a WHERE a.group_id = ? ORDER BY a.created_at DESC`,
      )
      .all(group.id);
    return c.json({ approvals: rows });
  });

  app.post("/:id/approvals/:approvalId/vote", zValidator("json", approvalVoteSchema), (c) => {
    const group = getGroup(c.req.param("id"));
    const me = group && getMember(group.id, c.get("auth").vaultId);
    if (!group || !me || me.status !== "active") return c.json({ error: "not_found" }, 404);
    if (!isManager(me.role)) return c.json({ error: "forbidden" }, 403);
    const approval = approvalRow(c.req.param("approvalId"));
    if (!approval || approval.group_id !== group.id) return c.json({ error: "not_found" }, 404);
    if (approval.status !== "pending") return c.json({ error: "already_resolved" }, 409);
    const { vote } = c.req.valid("json");
    const status = tx(db, () => {
      db.prepare(
        `INSERT INTO group_votes (approval_id, vault_id, vote, created_at) VALUES (?, ?, ?, ?)
         ON CONFLICT (approval_id, vault_id) DO UPDATE SET vote = excluded.vote, created_at = excluded.created_at`,
      ).run(approval.id, c.get("auth").vaultId, vote, deps.now());
      const count = (v: string) =>
        (
          db.prepare("SELECT COUNT(*) AS n FROM group_votes WHERE approval_id = ? AND vote = ?").get(approval.id, v) as {
            n: number;
          }
        ).n;
      const eligible = activeMembers(group.id).filter((m) => isManager(m.role)).length;
      let next = "pending";
      if (count("approve") >= approval.required) next = "approved";
      else if (eligible - count("reject") < approval.required) next = "rejected";
      if (next !== "pending") {
        db.prepare("UPDATE group_approvals SET status = ?, resolved_at = ? WHERE id = ?").run(
          next,
          deps.now(),
          approval.id,
        );
      }
      return next;
    });
    return c.json({ status });
  });

  return app;
}

/** `POST /v1/invites/join`: a signed-in vault asks to join; managers approve. */
export function inviteRoutes(deps: AppDeps) {
  const app = new Hono<HonoEnv>();
  const { db } = deps;
  app.use("*", requireAuth(deps));

  app.post("/join", zValidator("json", inviteJoinSchema), (c) => {
    const body = c.req.valid("json");
    const { vaultId } = c.get("auth");
    const now = deps.now();
    const invite = db
      .prepare(
        "SELECT id, group_id, preview, role, expires_at, max_uses, uses, revoked_at FROM group_invites WHERE token_hash = ?",
      )
      .get(sha256(body.token)) as
      | {
          id: string;
          group_id: string;
          preview: string | null;
          role: Role;
          expires_at: number;
          max_uses: number;
          uses: number;
          revoked_at: number | null;
        }
      | undefined;
    if (!invite || invite.revoked_at !== null || invite.expires_at <= now || invite.uses >= invite.max_uses) {
      return c.json({ error: "invalid_invite" }, 410);
    }
    const existing = db
      .prepare("SELECT status FROM group_members WHERE group_id = ? AND vault_id = ?")
      .get(invite.group_id, vaultId) as { status: string } | undefined;
    if (existing) return c.json({ groupId: invite.group_id, status: existing.status, preview: invite.preview });
    tx(db, () => {
      db.prepare(
        "INSERT INTO group_members (group_id, vault_id, role, status, profile, joined_at) VALUES (?, ?, ?, 'pending', ?, ?)",
      ).run(invite.group_id, vaultId, invite.role, body.profile, now);
      db.prepare("UPDATE group_invites SET uses = uses + 1 WHERE id = ?").run(invite.id);
    });
    return c.json({ groupId: invite.group_id, status: "pending", preview: invite.preview }, 201);
  });

  return app;
}
