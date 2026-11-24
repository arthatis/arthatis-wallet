import type { Db } from "./db.ts";
import { tx } from "./db.ts";
import { laterRecord } from "./lww.ts";
import type { SyncRecord } from "./schemas.ts";

type Scope = {
  recordsTable: "records" | "group_records";
  scopeColumn: "vault_id" | "group_id";
  ownerTable: "vaults" | "groups";
};

export const VAULT_SCOPE: Scope = { recordsTable: "records", scopeColumn: "vault_id", ownerTable: "vaults" };
export const GROUP_SCOPE: Scope = { recordsTable: "group_records", scopeColumn: "group_id", ownerTable: "groups" };

const PAGE = 1000;

export type SyncResult = {
  cursor: number;
  more: boolean;
  records: Array<{
    entity: string;
    id: string;
    updatedAt: number;
    deletedAt: number | null;
    payload: string;
    seq: number;
  }>;
};

/**
 * Applies incoming records with last-write-wins and returns everything
 * after `cursor`. Payloads are opaque ciphertext.
 */
export function syncScope(
  db: Db,
  scope: Scope,
  scopeId: string,
  cursor: number,
  incoming: SyncRecord[],
  author: string,
): SyncResult {
  const { recordsTable: table, scopeColumn: col, ownerTable } = scope;
  return tx(db, () => {
    const owner = db.prepare(`SELECT seq FROM ${ownerTable} WHERE id = ?`).get(scopeId) as { seq: number };
    let seq = owner.seq;
    const find = db.prepare(
      `SELECT entity, id, updated_at AS updatedAt, deleted_at AS deletedAt FROM ${table} WHERE ${col} = ? AND entity = ? AND id = ?`,
    );
    const authorColumn = table === "group_records" ? ", author" : "";
    const authorValue = table === "group_records" ? ", ?" : "";
    const upsert = db.prepare(
      `INSERT INTO ${table} (${col}, entity, id, payload, updated_at, deleted_at, seq${authorColumn})
       VALUES (?, ?, ?, ?, ?, ?, ?${authorValue})
       ON CONFLICT (${col}, entity, id) DO UPDATE SET
         payload = excluded.payload, updated_at = excluded.updated_at,
         deleted_at = excluded.deleted_at, seq = excluded.seq${table === "group_records" ? ", author = excluded.author" : ""}`,
    );
    for (const record of incoming) {
      const stored = find.get(scopeId, record.entity, record.id) as
        | { entity: string; id: string; updatedAt: number; deletedAt: number | null }
        | undefined;
      if (laterRecord(stored, record) === "stored") continue;
      seq += 1;
      const args: Array<string | number | null> = [
        scopeId,
        record.entity,
        record.id,
        record.payload,
        record.updatedAt,
        record.deletedAt,
        seq,
      ];
      if (table === "group_records") args.push(author);
      upsert.run(...args);
    }
    if (seq !== owner.seq) db.prepare(`UPDATE ${ownerTable} SET seq = ? WHERE id = ?`).run(seq, scopeId);
    const rows = db
      .prepare(
        `SELECT entity, id, updated_at AS updatedAt, deleted_at AS deletedAt, payload, seq
         FROM ${table} WHERE ${col} = ? AND seq > ? ORDER BY seq LIMIT ?`,
      )
      .all(scopeId, cursor, PAGE) as SyncResult["records"];
    const last = rows.length ? rows[rows.length - 1]!.seq : cursor;
    return { cursor: last, more: rows.length === PAGE, records: rows };
  });
}
