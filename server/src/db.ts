import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

export type Db = DatabaseSync;

const MIGRATIONS = [
  /* 1 */ `
CREATE TABLE vaults (
  id TEXT PRIMARY KEY,
  box_pub TEXT NOT NULL,
  kem_pub TEXT NOT NULL,
  sign_pub TEXT NOT NULL,
  email TEXT,
  recovery_blob TEXT,
  seq INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX vaults_email ON vaults (email);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  vault_id TEXT NOT NULL REFERENCES vaults(id),
  kind TEXT NOT NULL CHECK (kind IN ('phone','web')),
  label TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE TABLE link_sessions (
  id TEXT PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  nonce TEXT NOT NULL,
  code TEXT NOT NULL,
  web_pub TEXT NOT NULL,
  label TEXT NOT NULL,
  status TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  vault_id TEXT,
  envelope TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE magic_links (
  id TEXT PRIMARY KEY,
  vault_id TEXT NOT NULL REFERENCES vaults(id),
  token_hash TEXT NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL,
  used_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE records (
  vault_id TEXT NOT NULL,
  entity TEXT NOT NULL,
  id TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  seq INTEGER NOT NULL,
  PRIMARY KEY (vault_id, entity, id)
);
CREATE INDEX records_seq ON records (vault_id, seq);

CREATE TABLE groups (
  id TEXT PRIMARY KEY,
  owner_vault TEXT NOT NULL,
  meta TEXT NOT NULL,
  epoch INTEGER NOT NULL DEFAULT 1,
  seq INTEGER NOT NULL DEFAULT 0,
  rotate_required INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE group_members (
  group_id TEXT NOT NULL REFERENCES groups(id),
  vault_id TEXT NOT NULL,
  role TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending','active')),
  envelope TEXT,
  profile TEXT NOT NULL,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (group_id, vault_id)
);
CREATE INDEX group_members_vault ON group_members (vault_id);

CREATE TABLE group_invites (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id),
  token_hash TEXT NOT NULL UNIQUE,
  preview TEXT,
  role TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  max_uses INTEGER NOT NULL,
  uses INTEGER NOT NULL DEFAULT 0,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE TABLE group_records (
  group_id TEXT NOT NULL,
  entity TEXT NOT NULL,
  id TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  seq INTEGER NOT NULL,
  author TEXT NOT NULL,
  PRIMARY KEY (group_id, entity, id)
);
CREATE INDEX group_records_seq ON group_records (group_id, seq);

CREATE TABLE group_activity (
  group_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  actor TEXT NOT NULL,
  payload TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (group_id, seq)
);

CREATE TABLE group_approvals (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  payload TEXT NOT NULL,
  required INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending','approved','rejected')),
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  resolved_at INTEGER
);

CREATE TABLE group_votes (
  approval_id TEXT NOT NULL REFERENCES group_approvals(id),
  vault_id TEXT NOT NULL,
  vote TEXT NOT NULL CHECK (vote IN ('approve','reject')),
  created_at INTEGER NOT NULL,
  PRIMARY KEY (approval_id, vault_id)
);
`,
  /* 2: verified recovery email. `email` now only ever holds an address the owner proved they control. */
  `
ALTER TABLE vaults ADD COLUMN email_pending TEXT;
ALTER TABLE vaults ADD COLUMN email_code_hash TEXT;
ALTER TABLE vaults ADD COLUMN email_code_expires INTEGER;
ALTER TABLE vaults ADD COLUMN email_code_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE vaults ADD COLUMN email_code_sent_at INTEGER;
CREATE UNIQUE INDEX vaults_email_unique ON vaults (email) WHERE email IS NOT NULL;
`,
  /* 3: server policy. Admin vault, suspension, activation (set by an operator or a billing service via the admin API),
        registration allowlist, and emailed codes for registering on an email-gated server. */
  `
ALTER TABLE vaults ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0;
ALTER TABLE vaults ADD COLUMN suspended_at INTEGER;
ALTER TABLE vaults ADD COLUMN active_until INTEGER;
ALTER TABLE vaults ADD COLUMN ref TEXT;
CREATE INDEX vaults_ref ON vaults (ref);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE allowed_emails (email TEXT PRIMARY KEY, added_at INTEGER NOT NULL);
CREATE TABLE register_email_codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  sent_at INTEGER NOT NULL
);
`,
];

export function openDb(path: string): Db {
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;");
  migrate(db);
  return db;
}

function migrate(db: Db) {
  const row = db.prepare("PRAGMA user_version").get() as { user_version: number };
  for (let v = row.user_version; v < MIGRATIONS.length; v++) {
    db.exec("BEGIN");
    try {
      db.exec(MIGRATIONS[v]!);
      db.exec(`PRAGMA user_version = ${v + 1}`);
      db.exec("COMMIT");
    } catch (error) {
      db.exec("ROLLBACK");
      throw error;
    }
  }
}

/** Runs `fn` in a transaction. */
export function tx<T>(db: Db, fn: () => T): T {
  db.exec("BEGIN IMMEDIATE");
  try {
    const result = fn();
    db.exec("COMMIT");
    return result;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}
