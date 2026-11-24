import { timingSafeEqual } from "node:crypto";
import { Hono, type MiddlewareHandler } from "hono";
import { zValidator } from "@hono/zod-validator";
import { requireAuth, type AppDeps, type HonoEnv } from "../context.ts";
import { activateSchema, adminSettingsSchema, allowlistAddSchema } from "../schemas.ts";
import { deleteVault } from "../purge.ts";

type VaultRow = {
  id: string; createdAt: number; email: string | null; isAdmin: number; suspendedAt: number | null;
  activeUntil: number | null; ref: string | null; lastSeenAt: number | null; devices: number;
};

const safeEqual = (a: string, b: string) => {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};

/**
 * Server administration. The caller is either the admin vault (web/phone token) or a service holding ADMIN_API_TOKEN
 * (for example a billing site). The server never learns about money: a service just activates a vault until a date.
 */
export function adminRoutes(deps: AppDeps) {
  const app = new Hono<HonoEnv & { Variables: { service: boolean } }>();
  const { db } = deps;
  const vaultAuth = requireAuth(deps);

  const guard: MiddlewareHandler = async (c, next) => {
    const header = c.req.header("authorization") ?? "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    if (deps.policy.adminToken && token && safeEqual(token, deps.policy.adminToken)) {
      c.set("service", true);
      return next();
    }
    c.set("service", false);
    const denied = await vaultAuth(c, async () => {});
    if (denied) return denied;
    if (!c.get("auth").isAdmin) return c.json({ error: "forbidden" }, 403);
    return next();
  };
  app.use("*", guard);

  const callerVault = (c: any): string | null => (c.get("service") ? null : (c.get("auth") as { vaultId: string }).vaultId);

  app.get("/settings", (c) =>
    c.json({
      registration: ((db.prepare("SELECT value FROM settings WHERE key = 'registration'").get() as { value: string } | undefined)?.value ?? deps.policy.registrationDefault),
      mail: deps.mailer !== null,
      activation: deps.policy.requireActivation,
    }),
  );

  app.put("/settings", zValidator("json", adminSettingsSchema), (c) => {
    const { registration } = c.req.valid("json");
    // An email gate cannot work without a way to send the code.
    if (registration === "email" && !deps.mailer) return c.json({ error: "mail_not_configured" }, 409);
    db.prepare("INSERT INTO settings (key, value) VALUES ('registration', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value").run(registration);
    return c.json({ ok: true, registration });
  });

  app.get("/allowlist", (c) =>
    c.json({ emails: (db.prepare("SELECT email FROM allowed_emails ORDER BY email").all() as { email: string }[]).map((r) => r.email) }),
  );

  app.post("/allowlist", zValidator("json", allowlistAddSchema), (c) => {
    const add = db.prepare("INSERT OR IGNORE INTO allowed_emails (email, added_at) VALUES (?, ?)");
    for (const email of c.req.valid("json").emails) add.run(email.toLowerCase(), deps.now());
    return c.json({ ok: true });
  });

  app.delete("/allowlist/:email", (c) => {
    db.prepare("DELETE FROM allowed_emails WHERE email = ?").run(decodeURIComponent(c.req.param("email")).toLowerCase());
    return c.json({ ok: true });
  });

  const SELECT_VAULTS = `SELECT v.id, v.created_at AS createdAt, v.email, v.is_admin AS isAdmin, v.suspended_at AS suspendedAt,
      v.active_until AS activeUntil, v.ref,
      (SELECT MAX(last_seen_at) FROM devices d WHERE d.vault_id = v.id) AS lastSeenAt,
      (SELECT COUNT(*) FROM devices d WHERE d.vault_id = v.id AND d.revoked_at IS NULL) AS devices
    FROM vaults v`;
  const present = (r: VaultRow) => ({ ...r, isAdmin: r.isAdmin === 1 });

  // Metadata only. Contents are ciphertext the server cannot read.
  app.get("/vaults", (c) => c.json({ vaults: (db.prepare(`${SELECT_VAULTS} ORDER BY v.created_at`).all() as VaultRow[]).map(present) }));

  app.get("/vaults/by-ref/:ref", (c) =>
    c.json({ vaults: (db.prepare(`${SELECT_VAULTS} WHERE v.ref = ?`).all(c.req.param("ref")) as VaultRow[]).map(present) }),
  );

  const target = (c: any) => db.prepare("SELECT id, is_admin AS isAdmin FROM vaults WHERE id = ?").get(c.req.param("id")) as { id: string; isAdmin: number } | undefined;
  /** The admin cannot lock out or erase itself (or another admin) from the UI; only a service token can. */
  const protectedFromVaultCaller = (c: any, t: { id: string; isAdmin: number }) => callerVault(c) !== null && (t.id === callerVault(c) || t.isAdmin === 1);

  app.post("/vaults/:id/suspend", (c) => {
    const t = target(c); if (!t) return c.json({ error: "not_found" }, 404);
    if (protectedFromVaultCaller(c, t)) return c.json({ error: "cannot_target_admin" }, 409);
    db.prepare("UPDATE vaults SET suspended_at = ? WHERE id = ?").run(deps.now(), t.id);
    return c.json({ ok: true });
  });

  app.post("/vaults/:id/resume", (c) => {
    const t = target(c); if (!t) return c.json({ error: "not_found" }, 404);
    db.prepare("UPDATE vaults SET suspended_at = NULL WHERE id = ?").run(t.id);
    return c.json({ ok: true });
  });

  app.delete("/vaults/:id", (c) => {
    const t = target(c); if (!t) return c.json({ error: "not_found" }, 404);
    if (protectedFromVaultCaller(c, t)) return c.json({ error: "cannot_target_admin" }, 409);
    deleteVault(db, t.id);
    return c.json({ ok: true });
  });

  // Used by a billing service: activate (or extend) a vault until a date; `ref` links it to the customer on their side.
  app.post("/vaults/:id/activate", zValidator("json", activateSchema), (c) => {
    const t = target(c); if (!t) return c.json({ error: "not_found" }, 404);
    const { ref, until } = c.req.valid("json");
    db.prepare("UPDATE vaults SET active_until = ?, ref = ? WHERE id = ?").run(until, ref, t.id);
    return c.json({ ok: true, activeUntil: until });
  });

  app.post("/vaults/:id/deactivate", (c) => {
    const t = target(c); if (!t) return c.json({ error: "not_found" }, 404);
    db.prepare("UPDATE vaults SET active_until = ? WHERE id = ?").run(deps.now(), t.id);
    return c.json({ ok: true });
  });

  return app;
}
