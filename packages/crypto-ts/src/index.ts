// Reference implementation of Ferry crypto (docs/PROTOCOL.md §3).
// Android and iOS implementations must produce identical bytes — see docs/test-vectors.

import sodium from 'libsodium-wrappers';

export const UMK_BYTES = 32;
export const NONCE_BYTES = 24;
export const MAX_PADDED_BYTES = 256 * 1024;

const BUCKETS = [256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536];
const LARGE_BLOCK = 65536;

const KEYID_MESSAGE = 'ferry-keyid-v1';
const NODE_KEYID_MESSAGE = 'ferry-keyid-v2';
const NODE_ROOT_MESSAGE = 'ferry-node-v2';
const PAIR_MAC_MESSAGE = 'ferry-pair-v2';
const KDF = {
  event: { context: 'FR_EVENT', id: 1 },
  profile: { context: 'FR_PROFL', id: 1 },
  recovery: { context: 'FR_RECOV', id: 1 },
  account: { context: 'FR_ACCTK', id: 1 },
  command: { context: 'FR_CMMND', id: 1 },
} as const;

export const KEY_BYTES = 32;
export const MAC_BYTES = 32;

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

// `key` is a UMK, or a node root for the `event` and `command` subkeys (§3.7).
export function deriveSubkey(key: Uint8Array, purpose: SubkeyPurpose): Uint8Array {
  assertUmk(key);
  const { context, id } = KDF[purpose];
  return sodium.crypto_kdf_derive_from_key(32, id, context, key);
}

function assertUmk(umk: Uint8Array): void {
  if (umk.length !== UMK_BYTES) throw new Error(`UMK must be ${UMK_BYTES} bytes`);
}

// ── node keys (§3.7) ────────────────────────────────────────────────────────

export interface KeyPair {
  secretKey: Uint8Array;
  publicKey: Uint8Array;
}

/** The account keypair, derived from the UMK, so every receiver holds the same one. */
export function accountKeyPair(umk: Uint8Array): KeyPair {
  const secretKey = deriveSubkey(umk, 'account');
  return { secretKey, publicKey: sodium.crypto_scalarmult_base(secretKey) };
}

export function generateNodeKeyPair(): KeyPair {
  return nodeKeyPair(sodium.randombytes_buf(KEY_BYTES));
}

export function nodeKeyPair(secretKey: Uint8Array): KeyPair {
  if (secretKey.length !== KEY_BYTES) throw new Error(`secret key must be ${KEY_BYTES} bytes`);
  return { secretKey, publicKey: sodium.crypto_scalarmult_base(secretKey) };
}

/**
 * The secret a node shares with the receivers: X25519(nodeSk, acctPk) = X25519(acctSk, nodePk),
 * hashed with both public keys. Throws on a low-order public key (all-zero shared secret).
 */
export function nodeRoot(secretKey: Uint8Array, peerPublicKey: Uint8Array, acctPk: Uint8Array, nodePk: Uint8Array): Uint8Array {
  for (const k of [peerPublicKey, acctPk, nodePk]) if (k.length !== KEY_BYTES) throw new Error(`public key must be ${KEY_BYTES} bytes`);
  let shared: Uint8Array;
  try {
    shared = sodium.crypto_scalarmult(secretKey, peerPublicKey);
  } catch {
    throw new Error('weak public key');
  }
  if (shared.every((b) => b === 0)) throw new Error('weak public key');
  return sodium.crypto_generichash(32, concat(utf8.encode(NODE_ROOT_MESSAGE), acctPk, nodePk), shared);
}

/** On the node: its own secret key and the account public key from the pairing. */
export function nodeRootForNode(nodeSk: Uint8Array, acctPk: Uint8Array): Uint8Array {
  return nodeRoot(nodeSk, acctPk, acctPk, nodeKeyPair(nodeSk).publicKey);
}

/** On a receiver: the UMK and the node public key from its profile. */
export function nodeRootForReceiver(umk: Uint8Array, nodePk: Uint8Array): Uint8Array {
  const acct = accountKeyPair(umk);
  return nodeRoot(acct.secretKey, nodePk, acct.publicKey, nodePk);
}

export function nodeKeyId(root: Uint8Array): string {
  assertUmk(root);
  return toB64u(sodium.crypto_generichash(16, utf8.encode(NODE_KEYID_MESSAGE), root));
}

// ── pairing MAC (§5.6) ──────────────────────────────────────────────────────

/** Proves a public key came from whoever scanned (or showed) the QR code holding `secret`. */
export function pairingMac(secret: Uint8Array, userId: string, deviceId: string, publicKey: Uint8Array): Uint8Array {
  if (secret.length !== KEY_BYTES) throw new Error(`pairing secret must be ${KEY_BYTES} bytes`);
  return sodium.crypto_generichash(MAC_BYTES, concat(utf8.encode(PAIR_MAC_MESSAGE + userId + deviceId), publicKey), secret);
}

export function verifyPairingMac(secret: Uint8Array, userId: string, deviceId: string, publicKey: Uint8Array, mac: Uint8Array): boolean {
  return mac.length === MAC_BYTES && sodium.memcmp(pairingMac(secret, userId, deviceId, publicKey), mac);
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

// ── associated data ─────────────────────────────────────────────────────────

export function eventAd(userId: string, sourceDeviceId: string, eventId: string): Uint8Array {
  return utf8.encode(`ferry:v1:event:${userId}:${sourceDeviceId}:${eventId}`);
}

export function commandAd(userId: string, nodeId: string, commandId: string): Uint8Array {
  return utf8.encode(`ferry:v2:command:${userId}:${nodeId}:${commandId}`);
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

// A source-only node encrypts with its own root (§3.7); the AD is the same as above.
export function encryptNodeEvent(root: Uint8Array, ids: EventIds, payload: unknown, nonce?: Uint8Array): Sealed {
  const ad = eventAd(ids.userId, ids.sourceDeviceId, ids.eventId);
  const sealed = seal(deriveSubkey(root, 'event'), utf8.encode(JSON.stringify(payload)), ad, nonce);
  return { keyId: nodeKeyId(root), nonce: toB64u(sealed.nonce), ct: toB64u(sealed.ct) };
}

export function decryptNodeEvent(root: Uint8Array, ids: EventIds, env: Sealed): unknown {
  if (env.keyId !== nodeKeyId(root)) throw new Error(`unknown keyId ${env.keyId}`);
  const ad = eventAd(ids.userId, ids.sourceDeviceId, ids.eventId);
  const plain = open(deriveSubkey(root, 'event'), fromB64u(env.nonce), fromB64u(env.ct), ad);
  return JSON.parse(utf8Decoder.decode(plain));
}

// ── commands (§6.6) ─────────────────────────────────────────────────────────

export interface CommandIds {
  userId: string;
  nodeId: string;
  commandId: string;
}

// A receiver encrypts a command to one node with that node's command key.
export function encryptCommand(root: Uint8Array, ids: CommandIds, payload: unknown, nonce?: Uint8Array): Sealed {
  const ad = commandAd(ids.userId, ids.nodeId, ids.commandId);
  const sealed = seal(deriveSubkey(root, 'command'), utf8.encode(JSON.stringify(payload)), ad, nonce);
  return { keyId: nodeKeyId(root), nonce: toB64u(sealed.nonce), ct: toB64u(sealed.ct) };
}

export function decryptCommand(root: Uint8Array, ids: CommandIds, env: Sealed): unknown {
  if (env.keyId !== nodeKeyId(root)) throw new Error(`unknown keyId ${env.keyId}`);
  const ad = commandAd(ids.userId, ids.nodeId, ids.commandId);
  const plain = open(deriveSubkey(root, 'command'), fromB64u(env.nonce), fromB64u(env.ct), ad);
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
