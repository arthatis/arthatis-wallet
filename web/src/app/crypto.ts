// Implements docs/protocol.md. Mirrors the Kotlin `Crypto` object byte for byte.
import { xchacha20poly1305 } from "@noble/ciphers/chacha";
import { x25519 } from "@noble/curves/ed25519";
import { argon2id } from "@noble/hashes/argon2";
import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha2";
import { ml_kem768 } from "@noble/post-quantum/ml-kem";

const VERSION = 1;
const KEM_CT = 1088;
const enc = new TextEncoder();
const dec = new TextDecoder();
const INFO = enc.encode("arthatis-envelope-v1");

export const randomBytes = (n: number): Uint8Array => crypto.getRandomValues(new Uint8Array(n));

export function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}
export function unb64(text: string): Uint8Array {
  const s = atob(text);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}
export const b64url = (bytes: Uint8Array) => b64(bytes).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
export function unb64url(text: string): Uint8Array {
  const pad = "=".repeat((4 - (text.length % 4)) % 4);
  return unb64(text.replaceAll("-", "+").replaceAll("_", "/") + pad);
}

const concat = (...parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

/** `0x01 ‖ nonce(24) ‖ ciphertext+tag`. */
export function seal(key: Uint8Array, plaintext: Uint8Array, aad: Uint8Array = new Uint8Array()): Uint8Array {
  const nonce = randomBytes(24);
  return concat(Uint8Array.of(VERSION), nonce, xchacha20poly1305(key, nonce, aad).encrypt(plaintext));
}

export function open(key: Uint8Array, blob: Uint8Array, aad: Uint8Array = new Uint8Array()): Uint8Array {
  if (blob.length < 1 + 24 + 16 || blob[0] !== VERSION) throw new Error("bad blob");
  return xchacha20poly1305(key, blob.slice(1, 25), aad).decrypt(blob.slice(25));
}

export const sealText = (key: Uint8Array, text: string, aad: string) => b64(seal(key, enc.encode(text), enc.encode(aad)));
export const openText = (key: Uint8Array, blob: string, aad: string) => dec.decode(open(key, unb64(blob), enc.encode(aad)));

export interface WebKeys { boxPriv: Uint8Array; boxPub: Uint8Array; kemPriv: Uint8Array; kemPub: Uint8Array }

/** Ephemeral browser keys for the QR link. `webPub` = `x25519Pub(32) ‖ mlKemPub(1184)`. */
export function newWebKeys(): WebKeys {
  const boxPriv = x25519.utils.randomSecretKey();
  const kem = ml_kem768.keygen();
  return { boxPriv, boxPub: x25519.getPublicKey(boxPriv), kemPriv: kem.secretKey, kemPub: kem.publicKey };
}
export const webPub = (k: WebKeys) => concat(k.boxPub, k.kemPub);

const envelopeKey = (x: Uint8Array, kem: Uint8Array) => hkdf(sha256, concat(x, kem), undefined, INFO, 32);

export function sealTo(boxPub: Uint8Array, kemPub: Uint8Array, secret: Uint8Array): Uint8Array {
  const ephPriv = x25519.utils.randomSecretKey();
  const shared = x25519.getSharedSecret(ephPriv, boxPub);
  const { cipherText, sharedSecret } = ml_kem768.encapsulate(kemPub);
  const nonce = randomBytes(24);
  const key = envelopeKey(shared, sharedSecret);
  return concat(Uint8Array.of(VERSION), x25519.getPublicKey(ephPriv), cipherText, nonce, xchacha20poly1305(key, nonce).encrypt(secret));
}

export function openFrom(keys: WebKeys, blob: Uint8Array): Uint8Array {
  if (blob.length <= 1 + 32 + KEM_CT + 24 + 16 || blob[0] !== VERSION) throw new Error("bad envelope");
  const eph = blob.slice(1, 33);
  const ct = blob.slice(33, 33 + KEM_CT);
  const nonce = blob.slice(33 + KEM_CT, 33 + KEM_CT + 24);
  const body = blob.slice(33 + KEM_CT + 24);
  const key = envelopeKey(x25519.getSharedSecret(keys.boxPriv, eph), ml_kem768.decapsulate(ct, keys.kemPriv));
  return xchacha20poly1305(key, nonce).decrypt(body);
}

// --- Recovery key (also wraps `.arthatis` exports) ---------------------------------------------

const RK = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function parseRecoveryKey(text: string): Uint8Array | null {
  const clean = text.toUpperCase().replace(/[-\s]/g, "");
  if ([...clean].some((c) => !RK.includes(c))) return null;
  const out: number[] = [];
  let bits = 0, value = 0;
  for (const c of clean) {
    value = (value << 5) | RK.indexOf(c); bits += 5;
    if (bits >= 8) { out.push((value >> (bits - 8)) & 0xff); bits -= 8; value &= (1 << bits) - 1; }
  }
  return out.length === 20 ? Uint8Array.from(out) : null;
}

const argon = (rk: Uint8Array, salt: Uint8Array, mKiB: number) => argon2id(rk, salt, { t: 3, m: mKiB, p: 1, dkLen: 32 });

export function wrapWithRecoveryKey(rk: Uint8Array, data: Uint8Array, mKiB = 65536): Uint8Array {
  const salt = randomBytes(16);
  return concat(Uint8Array.of(VERSION), salt, seal(argon(rk, salt, mKiB), data));
}

export function unwrapWithRecoveryKey(rk: Uint8Array, blob: Uint8Array, mKiB = 65536): Uint8Array {
  if (blob[0] !== VERSION) throw new Error("bad blob");
  return open(argon(rk, blob.slice(1, 17), mKiB), blob.slice(17));
}
