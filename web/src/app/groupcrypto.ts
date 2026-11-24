// Wire format of shared (Bareng) wallets. Mirrors core `GroupCrypto.kt` exactly; cross-checked by docs/test-vectors/interop.json.
import * as C from "./crypto";

export const recordAad = (groupId: string, epoch: number, entity: string, id: string) => `${groupId}|${epoch}|${entity}|${id}`;
export const metaAad = (groupId: string) => `${groupId}|meta`;
export const profileAad = (groupId: string) => `${groupId}|profile`;
export const activityAad = (groupId: string) => `${groupId}|activity`;
export const approvalAad = (groupId: string) => `${groupId}|approval`;
export const INVITE_PREVIEW_AAD = "invite";
export const INVITE_PROFILE_AAD = "invite-profile";

/** Seals `epoch(4, big-endian) ‖ groupKey(32)` to a member's public keys. Returns base64. */
export function wrapKey(groupKey: Uint8Array, epoch: number, boxPub: Uint8Array, kemPub: Uint8Array): string {
  if (groupKey.length !== 32) throw new Error("group key");
  const secret = new Uint8Array(36);
  new DataView(secret.buffer).setUint32(0, epoch);
  secret.set(groupKey, 4);
  return C.b64(C.sealTo(boxPub, kemPub, secret));
}

export function unwrapKey(keys: C.WebKeys, envelopeB64: string): { epoch: number; key: Uint8Array } | null {
  try {
    const plain = C.openFrom(keys, C.unb64(envelopeB64));
    if (plain.length !== 36) return null;
    return { epoch: new DataView(plain.buffer, plain.byteOffset, 4).getUint32(0), key: plain.slice(4) };
  } catch { return null; }
}

export const sealRecord = (key: Uint8Array, groupId: string, epoch: number, entity: string, id: string, json: string) =>
  C.sealText(key, json, recordAad(groupId, epoch, entity, id));

export function openRecord(key: Uint8Array, groupId: string, epoch: number, entity: string, id: string, payload: string): string | null {
  try { return C.openText(key, payload, recordAad(groupId, epoch, entity, id)); } catch { return null; }
}

/** Items whose AAD has no epoch stay readable after a rotation to anyone holding an older key. Newest key first. */
export function openWithAnyKey(keysByEpoch: Map<number, Uint8Array> | undefined, payload: string, aad: string): string | null {
  if (!keysByEpoch) return null;
  for (const epoch of [...keysByEpoch.keys()].sort((a, b) => b - a)) {
    try { return C.openText(keysByEpoch.get(epoch)!, payload, aad); } catch { /* try the next key */ }
  }
  return null;
}
