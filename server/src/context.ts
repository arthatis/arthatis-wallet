import type { Context, MiddlewareHandler } from "hono";
import type { Db } from "./db.ts";
import { sha256 } from "./ids.ts";

export type Mailer = (input: { to: string; subject: string; html: string }) => Promise<void>;

export type AiConfig = { url: string; model: string; apiKey?: string };

/** Server-wide rules. The open source server knows nothing about money: it only knows activation and registration rules. */
export type Policy = {
  registrationDefault: "open" | "email";
  /** Hosted servers set this: a vault must be activated (by the admin API) before it can sync. */
  requireActivation: boolean;
  payUrl?: string;
  /** Static token for a service (e.g. a billing site) calling /v1/admin. Unset = admin API only for the admin vault. */
  adminToken?: string;
  pendingPurgeDays: number;
  registerRatePerHour: number;
  /** Trust X-Forwarded-For (only behind your own reverse proxy). */
  trustProxy: boolean;
};

export const defaultPolicy: Policy = {
  registrationDefault: "open", requireActivation: false, pendingPurgeDays: 7, registerRatePerHour: 20, trustProxy: false,
};

export type AppDeps = {
  db: Db;
  now: () => number;
  /** null = no SMTP configured; email features answer 503. */
  mailer: Mailer | null;
  contactTo?: string;
  appOrigin: string;
  corsOrigin: string;
  ai: AiConfig | null;
  policy: Policy;
};

export type Auth = { vaultId: string; deviceId: string; kind: "phone" | "web"; isAdmin: boolean };
export type HonoEnv = { Variables: { auth: Auth } };

export function requireAuth(deps: AppDeps): MiddlewareHandler<HonoEnv> {
  return async (c, next) => {
    const header = c.req.header("authorization") ?? "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    if (!token) return c.json({ error: "unauthorized" }, 401);
    const row = deps.db
      .prepare(
        `SELECT d.id, d.vault_id, d.kind, d.revoked_at, v.is_admin, v.suspended_at, v.active_until
         FROM devices d JOIN vaults v ON v.id = d.vault_id WHERE d.token_hash = ?`,
      )
      .get(sha256(token)) as
      | { id: string; vault_id: string; kind: "phone" | "web"; revoked_at: number | null; is_admin: number; suspended_at: number | null; active_until: number | null }
      | undefined;
    if (!row || row.revoked_at !== null) return c.json({ error: "unauthorized" }, 401);
    const now = deps.now();
    if (row.suspended_at !== null) return c.json({ error: "suspended" }, 403);
    // Hosted servers: an unactivated or expired vault may only read its own status (so the app can show its ID and a pay link).
    if (deps.policy.requireActivation && !row.is_admin && !(row.active_until !== null && row.active_until > now)) {
      const statusRead = c.req.method === "GET" && /\/v1\/vault$/.test(c.req.path);
      if (!statusRead) return c.json({ error: "payment_required", vaultId: row.vault_id }, 402);
    }
    deps.db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(now, row.id);
    c.set("auth", { vaultId: row.vault_id, deviceId: row.id, kind: row.kind, isAdmin: row.is_admin === 1 });
    await next();
  };
}

/** Best-effort client address for rate limiting. */
export function clientIp(c: Context, trustProxy: boolean): string {
  if (trustProxy) {
    const forwarded = c.req.header("x-forwarded-for");
    if (forwarded) return forwarded.split(",")[0]!.trim();
  }
  const incoming = (c.env as { incoming?: { socket?: { remoteAddress?: string } } } | undefined)?.incoming;
  return incoming?.socket?.remoteAddress ?? "unknown";
}

export const requirePhone: MiddlewareHandler<HonoEnv> = async (c, next) => {
  if (c.get("auth").kind !== "phone") return c.json({ error: "phone_only" }, 403);
  await next();
};
