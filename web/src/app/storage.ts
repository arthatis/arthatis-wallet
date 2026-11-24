// Browser-side vault. Everything sensitive is encrypted with a non-extractable AES-GCM key that never leaves IndexedDB,
// so a copied storage dump is useless without this browser profile. Unlinking deletes the whole database.
const DB = "arthatis-web";

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => { req.result.createObjectStore("kv"); };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
async function get<T>(k: string): Promise<T | undefined> {
  const db = await open();
  return new Promise((res, rej) => { const r = db.transaction("kv").objectStore("kv").get(k); r.onsuccess = () => res(r.result as T); r.onerror = () => rej(r.error); });
}
async function put(k: string, v: unknown): Promise<void> {
  const db = await open();
  return new Promise((res, rej) => { const tx = db.transaction("kv", "readwrite"); tx.objectStore("kv").put(v, k); tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error); });
}

async function masterKey(): Promise<CryptoKey> {
  const existing = await get<CryptoKey>("master");
  if (existing) return existing;
  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  await put("master", key);
  return key;
}

export async function saveSecret(name: string, data: Uint8Array): Promise<void> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await masterKey(), data as BufferSource));
  await put(`sec.${name}`, { iv, ct });
}

export async function loadSecret(name: string): Promise<Uint8Array | null> {
  const row = await get<{ iv: Uint8Array; ct: Uint8Array }>(`sec.${name}`);
  if (!row) return null;
  try { return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: row.iv as BufferSource }, await masterKey(), row.ct as BufferSource)); } catch { return null; }
}

export async function saveJson(name: string, value: unknown) { await saveSecret(name, new TextEncoder().encode(JSON.stringify(value))); }
export async function loadJson<T>(name: string): Promise<T | null> {
  const b = await loadSecret(name); return b ? (JSON.parse(new TextDecoder().decode(b)) as T) : null;
}

/** Unlink: drop the key and every cached byte. The server copy stays. */
export function wipe(): Promise<void> {
  return new Promise((resolve) => {
    const req = indexedDB.deleteDatabase(DB);
    req.onsuccess = req.onerror = req.onblocked = () => resolve();
  });
}
