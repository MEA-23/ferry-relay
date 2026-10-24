// Reference implementation of Ferry crypto (docs/PROTOCOL.md §3).
// Android and iOS implementations must produce identical bytes — see docs/test-vectors.

import sodium from 'libsodium-wrappers';

export const UMK_BYTES = 32;
export const NONCE_BYTES = 24;
export const MAX_PADDED_BYTES = 256 * 1024;

const BUCKETS = [256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536];
const LARGE_BLOCK = 65536;

const KEYID_MESSAGE = 'ferry-keyid-v1';
const KDF = {
  event: { context: 'FR_EVENT', id: 1 },
  profile: { context: 'FR_PROFL', id: 1 },
  recovery: { context: 'FR_RECOV', id: 1 },
} as const;

export type SubkeyPurpose = keyof typeof KDF;

export interface Sealed {
  keyId: string;
  nonce: string;
  ct: string;
}

export async function ready(): Promise<void> {
  await sodium.ready;
}

// ── encoding ────────────────────────────────────────────────────────────────

export function toB64u(bytes: Uint8Array): string {
  return sodium.to_base64(bytes, sodium.base64_variants.URLSAFE_NO_PADDING);
}

export function fromB64u(s: string): Uint8Array {
  return sodium.from_base64(s, sodium.base64_variants.URLSAFE_NO_PADDING);
}

const utf8 = new TextEncoder();
const utf8Decoder = new TextDecoder('utf-8', { fatal: true });

// ── keys ────────────────────────────────────────────────────────────────────

export function generateUmk(): Uint8Array {
  return sodium.randombytes_buf(UMK_BYTES);
}

export function keyId(umk: Uint8Array): string {
  assertUmk(umk);
  return toB64u(sodium.crypto_generichash(16, utf8.encode(KEYID_MESSAGE), umk));
}

export function deriveSubkey(umk: Uint8Array, purpose: SubkeyPurpose): Uint8Array {
  assertUmk(umk);
  const { context, id } = KDF[purpose];
  return sodium.crypto_kdf_derive_from_key(32, id, context, umk);
}

function assertUmk(umk: Uint8Array): void {
  if (umk.length !== UMK_BYTES) throw new Error(`UMK must be ${UMK_BYTES} bytes`);
}

// ── associated data ─────────────────────────────────────────────────────────

export function eventAd(userId: string, sourceDeviceId: string, eventId: string): Uint8Array {
  return utf8.encode(`ferry:v1:event:${userId}:${sourceDeviceId}:${eventId}`);
}

export function profileAd(userId: string, deviceId: string, version: number): Uint8Array {
  if (!Number.isSafeInteger(version) || version < 0) throw new Error('invalid profile version');
  return utf8.encode(`ferry:v1:profile:${userId}:${deviceId}:${version}`);
}

// ── padding ─────────────────────────────────────────────────────────────────

export function paddedLength(n: number): number {
  const bucket = BUCKETS.find((b) => b >= n + 1);
  return bucket ?? Math.ceil((n + 1) / LARGE_BLOCK) * LARGE_BLOCK;
}

export function pad(plain: Uint8Array): Uint8Array {
  const target = paddedLength(plain.length);
  if (target > MAX_PADDED_BYTES) throw new Error('plaintext too large; truncate before encrypting');
  const block = target <= LARGE_BLOCK ? target : LARGE_BLOCK;
  return sodium.pad(plain, block);
}

export function unpad(padded: Uint8Array): Uint8Array {
  const block = padded.length <= LARGE_BLOCK ? padded.length : LARGE_BLOCK;
  return sodium.unpad(padded, block);
}

// ── AEAD ────────────────────────────────────────────────────────────────────

// `nonce` is only for test vectors; production callers must omit it.
export function seal(key: Uint8Array, plain: Uint8Array, ad: Uint8Array, nonce?: Uint8Array) {
  const n = nonce ?? sodium.randombytes_buf(NONCE_BYTES);
  if (n.length !== NONCE_BYTES) throw new Error(`nonce must be ${NONCE_BYTES} bytes`);
  const ct = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(pad(plain), ad, null, n, key);
  return { nonce: n, ct };
}

export function open(key: Uint8Array, nonce: Uint8Array, ct: Uint8Array, ad: Uint8Array): Uint8Array {
  // Throws on authentication failure (wrong key, tampered ct, or mismatched AD).
  const padded = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, ad, nonce, key);
  return unpad(padded);
}

// ── events (§4, §6.1) ───────────────────────────────────────────────────────

export interface EventIds {
  userId: string;
  sourceDeviceId: string;
  eventId: string;
}

export function encryptEvent(umk: Uint8Array, ids: EventIds, payload: unknown, nonce?: Uint8Array): Sealed {
  const ad = eventAd(ids.userId, ids.sourceDeviceId, ids.eventId);
  const sealed = seal(deriveSubkey(umk, 'event'), utf8.encode(JSON.stringify(payload)), ad, nonce);
  return { keyId: keyId(umk), nonce: toB64u(sealed.nonce), ct: toB64u(sealed.ct) };
}

// `umks` maps keyId → UMK so receivers can still read events from before a rotation.
export function decryptEvent(umks: Map<string, Uint8Array>, ids: EventIds, env: Sealed): unknown {
  const umk = umks.get(env.keyId);
  if (!umk) throw new Error(`unknown keyId ${env.keyId}`);
  const ad = eventAd(ids.userId, ids.sourceDeviceId, ids.eventId);
  const plain = open(deriveSubkey(umk, 'event'), fromB64u(env.nonce), fromB64u(env.ct), ad);
  return JSON.parse(utf8Decoder.decode(plain));
}

// ── device profiles (§5.4) ──────────────────────────────────────────────────

export interface ProfileIds {
  userId: string;
  deviceId: string;
  version: number;
}

export function encryptProfile(umk: Uint8Array, ids: ProfileIds, profile: unknown, nonce?: Uint8Array): Sealed {
  const ad = profileAd(ids.userId, ids.deviceId, ids.version);
  const sealed = seal(deriveSubkey(umk, 'profile'), utf8.encode(JSON.stringify(profile)), ad, nonce);
  return { keyId: keyId(umk), nonce: toB64u(sealed.nonce), ct: toB64u(sealed.ct) };
}

export function decryptProfile(umks: Map<string, Uint8Array>, ids: ProfileIds, env: Sealed): unknown {
  const umk = umks.get(env.keyId);
  if (!umk) throw new Error(`unknown keyId ${env.keyId}`);
  const ad = profileAd(ids.userId, ids.deviceId, ids.version);
  const plain = open(deriveSubkey(umk, 'profile'), fromB64u(env.nonce), fromB64u(env.ct), ad);
  return JSON.parse(utf8Decoder.decode(plain));
}
