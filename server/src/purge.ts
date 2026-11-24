import type { Db } from "./db.ts";
import { tx } from "./db.ts";

/** Removes a shared wallet and everything stored for it. */
export function deleteGroupData(db: Db, groupId: string) {
  db.prepare("DELETE FROM group_votes WHERE approval_id IN (SELECT id FROM group_approvals WHERE group_id = ?)").run(groupId);
  for (const table of ["group_approvals", "group_activity", "group_records", "group_invites", "group_members"]) {
    db.prepare(`DELETE FROM ${table} WHERE group_id = ?`).run(groupId);
  }
  db.prepare("DELETE FROM groups WHERE id = ?").run(groupId);
}

/** Deletes a vault and all its data. Shared wallets it owns are deleted; memberships in others are removed. */
export function deleteVault(db: Db, vaultId: string) {
  tx(db, () => {
    const owned = db.prepare("SELECT id FROM groups WHERE owner_vault = ?").all(vaultId) as { id: string }[];
    for (const g of owned) deleteGroupData(db, g.id);
    db.prepare("DELETE FROM group_votes WHERE vault_id = ?").run(vaultId);
    db.prepare("DELETE FROM group_members WHERE vault_id = ?").run(vaultId);
    db.prepare("DELETE FROM records WHERE vault_id = ?").run(vaultId);
    db.prepare("DELETE FROM magic_links WHERE vault_id = ?").run(vaultId);
    db.prepare("DELETE FROM link_sessions WHERE vault_id = ?").run(vaultId);
    db.prepare("DELETE FROM devices WHERE vault_id = ?").run(vaultId);
    db.prepare("DELETE FROM vaults WHERE id = ?").run(vaultId);
  });
}

/** Hosted servers: vaults that were never activated within [days] are removed. Returns how many. */
export function purgeUnactivated(db: Db, now: number, days: number): number {
  const cutoff = now - days * 86_400_000;
  const rows = db
    .prepare("SELECT id FROM vaults WHERE is_admin = 0 AND active_until IS NULL AND ref IS NULL AND created_at < ?")
    .all(cutoff) as { id: string }[];
  for (const r of rows) deleteVault(db, r.id);
  return rows.length;
}
