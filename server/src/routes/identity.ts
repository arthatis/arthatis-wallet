import { Hono } from "hono";
import { zValidator } from "@hono/zod-validator";
import { clientIp, requireAuth, requirePhone, type AppDeps, type HonoEnv } from "../context.ts";
import { randomBytes } from "node:crypto";
import { randomId, sessionCode, sha256 } from "../ids.ts";
import { tx } from "../db.ts";
import {
  LINK_SESSION_TTL_MS,
  MAGIC_LINK_TTL_MS,
  RESEND_COOLDOWN_MS,
  EMAIL_CODE_MAX_ATTEMPTS,
  EMAIL_CODE_TTL_MS,
  emailSetSchema,
  emailVerifySchema,
  linkApproveSchema,
  linkCreateSchema,
  linkScanSchema,
  recoveryBlobSchema,
  recoveryRequestSchema,
  recoveryVerifySchema,
  registerEmailSchema,
  registerSchema,
  syncRequestSchema,
} from "../schemas.ts";
import { VAULT_SCOPE, syncScope } from "../sync.ts";

type LinkRow = {
  id: string;
  secret_hash: string;
  nonce: string;
  code: string;
  web_pub: string;
  label: string;
  status: string;
  expires_at: number;
  vault_id: string | null;
  envelope: string | null;
};

export function identityRoutes(deps: AppDeps) {
  const app = new Hono<HonoEnv>();
  const { db } = deps;
  const auth = requireAuth(deps);

  const newDevice = (vaultId: string, kind: "phone" | "web", label: string) => {
    const token = randomId(32);
    const id = randomId(12);
    const now = deps.now();
    db.prepare(
      "INSERT INTO devices (id, vault_id, kind, label, token_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).run(id, vaultId, kind, label, sha256(token), now, now);
    return { deviceId: id, token };
  };

  // --- Server policy (public): lets the app/web adapt the connect step before registering.
  const registrationMode = (): "open" | "email" =>
    ((db.prepare("SELECT value FROM settings WHERE key = 'registration'").get() as { value: string } | undefined)?.value as "open" | "email" | undefined) ??
    deps.policy.registrationDefault;

  app.get("/server", (c) =>
    c.json({
      registration: registrationMode(),
      mail: deps.mailer !== null,
      activation: deps.policy.requireActivation,
      payUrl: deps.policy.payUrl ?? null,
    }),
  );

  /** Email-gated servers: mail a code only if the address is on the admin's allowlist. Always 202 so the list is not leaked. */
  app.post("/register/email", zValidator("json", registerEmailSchema), async (c) => {
    if (!deps.mailer) return c.json({ error: "mail_not_configured" }, 503);
    if (registrationMode() !== "email") return c.json({ error: "not_required" }, 400);
    const email = c.req.valid("json").email.toLowerCase();
    const now = deps.now();
    const allowed = db.prepare("SELECT 1 FROM allowed_emails WHERE email = ?").get(email);
    const last = db.prepare("SELECT sent_at AS sentAt FROM register_email_codes WHERE email = ?").get(email) as { sentAt: number } | undefined;
    if (allowed && !(last && now - last.sentAt < RESEND_COOLDOWN_MS)) {
      const code = String(randomBytes(4).readUInt32BE() % 1_000_000).padStart(6, "0");
      db.prepare(
        `INSERT INTO register_email_codes (email, code_hash, expires_at, attempts, sent_at) VALUES (?, ?, ?, 0, ?)
         ON CONFLICT (email) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0, sent_at = excluded.sent_at`,
      ).run(email, sha256(`register:${email}:${code}`), now + EMAIL_CODE_TTL_MS, now);
      try {
        await deps.mailer({
          to: email,
          subject: "Kode pendaftaran Arthatis Wallet",
          html: `<p>Kode untuk mendaftar di server ini:</p><p style="font-size:28px;letter-spacing:4px"><b>${code}</b></p><p>Berlaku 15 menit.</p>`,
        });
      } catch {
        return c.json({ error: "mail_failed" }, 502);
      }
    }
    return c.json({ ok: true }, 202);
  });

  const rate = new Map<string, number[]>();

  // --- Identity: a vault is created with the first phone, no account needed. The very first vault on a server is its admin.
  app.post("/devices", zValidator("json", registerSchema), (c) => {
    const body = c.req.valid("json");
    const now = deps.now();
    const first = (db.prepare("SELECT COUNT(*) AS n FROM vaults").get() as { n: number }).n === 0;

    if (deps.policy.requireActivation && !first) {
      const ip = clientIp(c, deps.policy.trustProxy);
      const recent = (rate.get(ip) ?? []).filter((t) => now - t < 3_600_000);
      if (recent.length >= deps.policy.registerRatePerHour) return c.json({ error: "rate_limited" }, 429);
      rate.set(ip, [...recent, now]);
    }

    let verifiedEmail: string | null = null;
    if (!first && registrationMode() === "email") {
      const email = body.email?.toLowerCase();
      if (!email || !body.emailCode) return c.json({ error: "email_required" }, 403);
      const row = db.prepare("SELECT code_hash AS hash, expires_at AS expires, attempts FROM register_email_codes WHERE email = ?").get(email) as
        | { hash: string; expires: number; attempts: number } | undefined;
      const allowed = db.prepare("SELECT 1 FROM allowed_emails WHERE email = ?").get(email);
      if (!allowed || !row || row.expires <= now) return c.json({ error: "email_not_allowed" }, 403);
      if (row.attempts >= EMAIL_CODE_MAX_ATTEMPTS) return c.json({ error: "too_many_attempts" }, 429);
      if (sha256(`register:${email}:${body.emailCode}`) !== row.hash) {
        db.prepare("UPDATE register_email_codes SET attempts = attempts + 1 WHERE email = ?").run(email);
        return c.json({ error: "wrong_code" }, 400);
      }
      if (db.prepare("SELECT 1 FROM vaults WHERE email = ?").get(email)) return c.json({ error: "email_in_use" }, 409);
      verifiedEmail = email;
    }

    const vaultId = randomId(16);
    const device = tx(db, () => {
      // Re-check inside the transaction so two simultaneous first registrations cannot both become admin.
      const isFirst = (db.prepare("SELECT COUNT(*) AS n FROM vaults").get() as { n: number }).n === 0;
      db.prepare(
        "INSERT INTO vaults (id, box_pub, kem_pub, sign_pub, email, is_admin, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
      ).run(vaultId, body.boxPub, body.kemPub, body.signPub, verifiedEmail, isFirst ? 1 : 0, now);
      if (verifiedEmail) db.prepare("DELETE FROM register_email_codes WHERE email = ?").run(verifiedEmail);
      return newDevice(vaultId, "phone", body.label);
    });
    return c.json({ vaultId, ...device }, 201);
  });

  app.get("/vault", auth, (c) => {
    const { vaultId, kind } = c.get("auth");
    const row = db
      .prepare("SELECT id, box_pub, kem_pub, sign_pub, email, email_pending, recovery_blob, is_admin, active_until FROM vaults WHERE id = ?")
      .get(vaultId) as Record<string, any>;
    return c.json({
      vaultId: row.id,
      kind,
      boxPub: row.box_pub,
      kemPub: row.kem_pub,
      signPub: row.sign_pub,
      hasEmail: row.email !== null,
      emailPending: row.email_pending !== null,
      isAdmin: row.is_admin === 1,
      activationRequired: deps.policy.requireActivation && row.is_admin !== 1,
      active: !deps.policy.requireActivation || row.is_admin === 1 || (row.active_until !== null && (row.active_until as number) > deps.now()),
      activeUntil: row.active_until ?? null,
      payUrl: deps.policy.payUrl ?? null,
      hasRecovery: row.recovery_blob !== null,
    });
  });

  app.post("/sync", auth, zValidator("json", syncRequestSchema), (c) => {
    const { vaultId, deviceId } = c.get("auth");
    const body = c.req.valid("json");
    return c.json(syncScope(db, VAULT_SCOPE, vaultId, body.cursor, body.records, deviceId));
  });

  // --- Web link sessions (QR). The server only relays an opaque envelope.
  app.post("/link/sessions", zValidator("json", linkCreateSchema), (c) => {
    const body = c.req.valid("json");
    const id = randomId(12);
    const secret = randomId(24);
    const nonce = randomId(12);
    const code = sessionCode();
    const now = deps.now();
    db.prepare(
      `INSERT INTO link_sessions (id, secret_hash, nonce, code, web_pub, label, status, expires_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
    ).run(id, sha256(secret), nonce, code, body.webPub, body.label, now + LINK_SESSION_TTL_MS, now);
    return c.json({ id, secret, nonce, code, expiresAt: now + LINK_SESSION_TTL_MS }, 201);
  });

  const loadLink = (id: string) =>
    db.prepare("SELECT * FROM link_sessions WHERE id = ?").get(id) as LinkRow | undefined;

  app.post("/link/scan", auth, requirePhone, zValidator("json", linkScanSchema), (c) => {
    const body = c.req.valid("json");
    const link = loadLink(body.id);
    if (!link || link.nonce !== body.nonce) return c.json({ error: "not_found" }, 404);
    if (link.expires_at <= deps.now() || link.status === "consumed") return c.json({ error: "expired" }, 410);
    db.prepare("UPDATE link_sessions SET status = 'scanned' WHERE id = ? AND status = 'pending'").run(link.id);
    return c.json({ webPub: link.web_pub, label: link.label, code: link.code });
  });

  app.post("/link/approve", auth, requirePhone, zValidator("json", linkApproveSchema), (c) => {
    const body = c.req.valid("json");
    const link = loadLink(body.id);
    if (!link || link.nonce !== body.nonce) return c.json({ error: "not_found" }, 404);
    if (link.expires_at <= deps.now()) return c.json({ error: "expired" }, 410);
    if (link.status !== "pending" && link.status !== "scanned") return c.json({ error: "already_used" }, 409);
    db.prepare(
      "UPDATE link_sessions SET status = 'approved', vault_id = ?, envelope = ? WHERE id = ?",
    ).run(c.get("auth").vaultId, body.envelope, link.id);
    return c.json({ ok: true });
  });

  app.get("/link/sessions/:id", (c) => {
    const link = loadLink(c.req.param("id"));
    const secret = c.req.header("x-link-secret") ?? "";
    if (!link || link.secret_hash !== sha256(secret)) return c.json({ error: "not_found" }, 404);
    if (link.status === "consumed") return c.json({ error: "already_used" }, 410);
    if (link.expires_at <= deps.now()) return c.json({ status: "expired" });
    if (link.status !== "approved" || !link.vault_id || !link.envelope) return c.json({ status: link.status });
    const device = tx(db, () => {
      db.prepare("UPDATE link_sessions SET status = 'consumed', envelope = NULL WHERE id = ?").run(link.id);
      return newDevice(link.vault_id!, "web", link.label);
    });
    return c.json({ status: "approved", vaultId: link.vault_id, envelope: link.envelope, ...device });
  });

  // --- Web sessions: listed and revoked from the phone or from any linked browser (W04).
  app.get("/sessions", auth, (c) => {
    const rows = db
      .prepare(
        `SELECT id, label, created_at AS createdAt, last_seen_at AS lastSeenAt FROM devices
         WHERE vault_id = ? AND kind = 'web' AND revoked_at IS NULL ORDER BY last_seen_at DESC`,
      )
      .all(c.get("auth").vaultId);
    return c.json({ sessions: rows });
  });

  app.delete("/sessions/:id", auth, (c) => {
    db.prepare(
      "UPDATE devices SET revoked_at = ? WHERE id = ? AND vault_id = ? AND kind = 'web' AND revoked_at IS NULL",
    ).run(deps.now(), c.req.param("id"), c.get("auth").vaultId);
    return c.json({ ok: true });
  });

  app.post("/sessions/revoke-all", auth, (c) => {
    db.prepare(
      "UPDATE devices SET revoked_at = ? WHERE vault_id = ? AND kind = 'web' AND revoked_at IS NULL",
    ).run(deps.now(), c.get("auth").vaultId);
    return c.json({ ok: true });
  });

  /** A browser unlinking itself. The vault copy on the server stays. */
  app.post("/session/logout", auth, (c) => {
    db.prepare("UPDATE devices SET revoked_at = ? WHERE id = ?").run(deps.now(), c.get("auth").deviceId);
    return c.json({ ok: true });
  });

  // --- Optional email recovery. Decrypting still needs the recovery key.
  /**
   * Step 1: ask to attach an email. A 6-digit code is mailed; nothing is stored as the recovery address until
   * the owner types that code back (step 2), which proves they control the inbox.
   */
  app.put("/vault/email", auth, requirePhone, zValidator("json", emailSetSchema), async (c) => {
    if (!deps.mailer) return c.json({ error: "mail_not_configured" }, 503);
    const { vaultId } = c.get("auth");
    const email = c.req.valid("json").email.toLowerCase();
    const now = deps.now();
    const row = db.prepare("SELECT email_code_sent_at AS sentAt FROM vaults WHERE id = ?").get(vaultId) as { sentAt: number | null };
    if (row.sentAt && now - row.sentAt < RESEND_COOLDOWN_MS) {
      return c.json({ error: "resend_cooldown", retryAfterMs: RESEND_COOLDOWN_MS - (now - row.sentAt) }, 429);
    }
    const code = String(randomBytes(4).readUInt32BE() % 1_000_000).padStart(6, "0");
    db.prepare(
      `UPDATE vaults SET email_pending = ?, email_code_hash = ?, email_code_expires = ?, email_code_attempts = 0, email_code_sent_at = ? WHERE id = ?`,
    ).run(email, sha256(`${vaultId}:${code}`), now + EMAIL_CODE_TTL_MS, now, vaultId);
    try {
      await deps.mailer({
        to: email,
        subject: "Kode verifikasi Arthatis Wallet",
        html: `<p>Kode verifikasi email pemulihanmu:</p><p style="font-size:28px;letter-spacing:4px"><b>${code}</b></p><p>Berlaku 15 menit. Jika bukan kamu yang meminta, abaikan email ini.</p>`,
      });
    } catch {
      return c.json({ error: "mail_failed" }, 502);
    }
    return c.json({ ok: true, pending: true });
  });

  /** Step 2: the code proves ownership. Limited attempts; an address can belong to only one vault. */
  app.post("/vault/email/verify", auth, requirePhone, zValidator("json", emailVerifySchema), (c) => {
    const { vaultId } = c.get("auth");
    const row = db
      .prepare("SELECT email_pending AS pending, email_code_hash AS hash, email_code_expires AS expires, email_code_attempts AS attempts FROM vaults WHERE id = ?")
      .get(vaultId) as { pending: string | null; hash: string | null; expires: number | null; attempts: number };
    if (!row.pending || !row.hash || !row.expires || row.expires <= deps.now()) return c.json({ error: "no_pending_code" }, 400);
    if (row.attempts >= EMAIL_CODE_MAX_ATTEMPTS) return c.json({ error: "too_many_attempts" }, 429);
    if (sha256(`${vaultId}:${c.req.valid("json").code}`) !== row.hash) {
      db.prepare("UPDATE vaults SET email_code_attempts = email_code_attempts + 1 WHERE id = ?").run(vaultId);
      return c.json({ error: "wrong_code" }, 400);
    }
    const taken = db.prepare("SELECT id FROM vaults WHERE email = ? AND id != ?").get(row.pending, vaultId);
    if (taken) return c.json({ error: "email_in_use" }, 409);
    db.prepare(
      "UPDATE vaults SET email = ?, email_pending = NULL, email_code_hash = NULL, email_code_expires = NULL, email_code_attempts = 0 WHERE id = ?",
    ).run(row.pending, vaultId);
    return c.json({ ok: true });
  });

  app.delete("/vault/email", auth, requirePhone, (c) => {
    db.prepare("UPDATE vaults SET email = NULL, email_pending = NULL, email_code_hash = NULL WHERE id = ?").run(c.get("auth").vaultId);
    return c.json({ ok: true });
  });

  app.put("/vault/recovery-blob", auth, requirePhone, zValidator("json", recoveryBlobSchema), (c) => {
    db.prepare("UPDATE vaults SET recovery_blob = ? WHERE id = ?").run(
      c.req.valid("json").blob,
      c.get("auth").vaultId,
    );
    return c.json({ ok: true });
  });

  app.post("/recovery/request", zValidator("json", recoveryRequestSchema), async (c) => {
    if (!deps.mailer) return c.json({ error: "mail_not_configured" }, 503);
    const email = c.req.valid("json").email.toLowerCase();
    const vault = db.prepare("SELECT id FROM vaults WHERE email = ?").get(email) as { id: string } | undefined;
    if (vault) {
      const now = deps.now();
      const latest = db
        .prepare("SELECT created_at, used_at FROM magic_links WHERE vault_id = ? ORDER BY created_at DESC LIMIT 1")
        .get(vault.id) as { created_at: number; used_at: number | null } | undefined;
      const cooling = latest && latest.used_at === null && now - latest.created_at < RESEND_COOLDOWN_MS;
      if (!cooling) {
        const token = randomId(32);
        db.prepare(
          "INSERT INTO magic_links (id, vault_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
        ).run(randomId(12), vault.id, sha256(token), now + MAGIC_LINK_TTL_MS, now);
        const link = `${deps.appOrigin}/auth/recover#token=${token}`;
        await deps.mailer!({
          to: email,
          subject: "Pulihkan Arthatis Wallet",
          html: `<p>Ketuk tautan ini dalam 15 menit untuk memulihkan akses:</p><p><a href="${link}">${link}</a></p><p>Kamu tetap membutuhkan recovery key untuk membuka data.</p>`,
        });
      }
    }
    // Same answer whether or not the email exists.
    return c.json({ ok: true }, 202);
  });

  app.post("/recovery/verify", zValidator("json", recoveryVerifySchema), (c) => {
    const body = c.req.valid("json");
    const now = deps.now();
    const link = db
      .prepare("SELECT id, vault_id, expires_at, used_at FROM magic_links WHERE token_hash = ?")
      .get(sha256(body.token)) as
      | { id: string; vault_id: string; expires_at: number; used_at: number | null }
      | undefined;
    if (!link || link.used_at !== null || link.expires_at <= now) return c.json({ error: "invalid_token" }, 400);
    const result = tx(db, () => {
      db.prepare("UPDATE magic_links SET used_at = ? WHERE id = ?").run(now, link.id);
      return newDevice(link.vault_id, "phone", body.label);
    });
    const vault = db
      .prepare("SELECT recovery_blob FROM vaults WHERE id = ?")
      .get(link.vault_id) as { recovery_blob: string | null };
    return c.json({ vaultId: link.vault_id, recoveryBlob: vault.recovery_blob, ...result });
  });

  return app;
}
