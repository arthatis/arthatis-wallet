import { z } from "zod";

export const emailSchema = z.string().trim().email().max(320);

/** Opaque base64 ciphertext. The server never parses it. */
export const cipherSchema = z.string().min(1).max(256 * 1024);
const keySchema = z.string().min(8).max(8192);

export const ledgerEntitySchema = z.enum([
  "wallet",
  "transaction",
  "transfer",
  "category",
  "recurring",
  "rate_override",
  "setting",
]);
export type LedgerEntity = z.infer<typeof ledgerEntitySchema>;

export const syncRecordSchema = z.object({
  entity: z.string().min(1).max(40),
  id: z.string().min(1).max(80),
  updatedAt: z.number().int().nonnegative(),
  deletedAt: z.number().int().nonnegative().nullable(),
  payload: cipherSchema,
});
export type SyncRecord = z.infer<typeof syncRecordSchema>;

export const syncRequestSchema = z.object({
  cursor: z.number().int().nonnegative().default(0),
  records: z.array(syncRecordSchema).max(500).default([]),
});

export const registerDeviceSchema = z.object({
  label: z.string().trim().min(1).max(120),
  signPub: keySchema,
  boxPub: keySchema,
  kemPub: keySchema,
});

export const registerSchema = registerDeviceSchema.extend({
  /** Only on servers that gate registration by email (admin allowlist). */
  email: emailSchema.optional(),
  emailCode: z.string().regex(/^\d{6}$/).optional(),
});
export const registerEmailSchema = z.object({ email: emailSchema });

export const registrationModeSchema = z.enum(["open", "email"]);
export const adminSettingsSchema = z.object({ registration: registrationModeSchema });
export const allowlistAddSchema = z.object({ emails: z.array(emailSchema).min(1).max(200) });
export const activateSchema = z.object({
  ref: z.string().trim().min(1).max(200),
  /** Epoch ms until which the vault may sync. */
  until: z.number().int().positive(),
});

export const linkCreateSchema = z.object({
  webPub: keySchema,
  label: z.string().trim().min(1).max(120),
});

export const linkScanSchema = z.object({
  id: z.string().min(1).max(80),
  nonce: z.string().min(8).max(80),
});

export const linkApproveSchema = linkScanSchema.extend({
  /** Vault key sealed to the browser public key. Opaque to the server. */
  envelope: cipherSchema,
});

export const emailSetSchema = z.object({ email: emailSchema });
export const emailVerifySchema = z.object({ code: z.string().regex(/^\d{6}$/) });
export const recoveryBlobSchema = z.object({ blob: cipherSchema });
export const recoveryRequestSchema = z.object({ email: emailSchema });
export const recoveryVerifySchema = registerDeviceSchema.pick({ label: true }).extend({
  token: z.string().min(20).max(200),
});

export const roleSchema = z.enum(["owner", "admin", "member", "viewer"]);
export type Role = z.infer<typeof roleSchema>;

export const groupCreateSchema = z.object({
  meta: cipherSchema,
  /** Owner's own envelope for epoch 1. */
  envelope: cipherSchema,
  profile: cipherSchema,
});

export const groupMetaSchema = z.object({ meta: cipherSchema });

export const inviteCreateSchema = z.object({
  role: z.enum(["admin", "member", "viewer"]).default("member"),
  maxUses: z.number().int().min(1).max(10).default(10),
  /** Group name/target encrypted with the invite key that lives only in the link fragment. */
  preview: cipherSchema.optional(),
});

export const inviteJoinSchema = z.object({
  token: z.string().min(20).max(200),
  profile: cipherSchema,
});

export const memberApproveSchema = z.object({
  role: z.enum(["admin", "member", "viewer"]).optional(),
  envelope: cipherSchema,
});

export const profileSchema = z.object({ profile: cipherSchema });

export const memberRoleSchema = z.object({ role: z.enum(["admin", "member", "viewer"]) });

export const rotateSchema = z.object({
  epoch: z.number().int().positive(),
  meta: cipherSchema.optional(),
  envelopes: z.record(z.string(), cipherSchema),
});

export const groupSyncSchema = z.object({
  epoch: z.number().int().positive(),
  cursor: z.number().int().nonnegative().default(0),
  records: z.array(syncRecordSchema).max(500).default([]),
});

export const activitySchema = z.object({ payload: cipherSchema });

export const approvalCreateSchema = z.object({
  kind: z.string().min(1).max(40),
  payload: cipherSchema,
  required: z.number().int().min(1).max(10),
});
export const approvalVoteSchema = z.object({ vote: z.enum(["approve", "reject"]) });

export const aiChatSchema = z.object({
  messages: z
    .array(z.object({ role: z.enum(["system", "user", "assistant"]), content: z.string().max(8000) }))
    .min(1)
    .max(30),
});

export const contactSchema = z.object({
  name: z.string().trim().min(1).max(120),
  email: emailSchema,
  message: z.string().trim().min(1).max(5000),
});

export const MAGIC_LINK_TTL_MS = 15 * 60 * 1000;
export const RESEND_COOLDOWN_MS = 60 * 1000;
export const EMAIL_CODE_TTL_MS = 15 * 60 * 1000;
export const EMAIL_CODE_MAX_ATTEMPTS = 5;
export const LINK_SESSION_TTL_MS = 5 * 60 * 1000;
export const INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
