import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import sodium from 'libsodium-wrappers';
import { createPrivateKey, createPublicKey, diffieHellman } from 'node:crypto';
import {
  accountKeyPair,
  decryptCommand,
  decryptEvent,
  decryptNodeEvent,
  decryptProfile,
  deriveSubkey,
  encryptCommand,
  encryptEvent,
  encryptNodeEvent,
  encryptProfile,
  eventAd,
  fromB64u,
  generateNodeKeyPair,
  generateUmk,
  keyId,
  nodeKeyId,
  nodeKeyPair,
  nodeRoot,
  nodeRootForNode,
  nodeRootForReceiver,
  open,
  pad,
  paddedLength,
  pairingMac,
  ready,
  toB64u,
  unpad,
  verifyPairingMac,
} from '../src/index.ts';
import { buildVectors } from '../src/vectors.ts';
import { buildVectors as buildV2Vectors } from '../src/vectors-v2.ts';

await ready();

const vectors = JSON.parse(readFileSync(new URL('../../../docs/test-vectors/v1.json', import.meta.url), 'utf8'));
const v2 = JSON.parse(readFileSync(new URL('../../../docs/test-vectors/v2.json', import.meta.url), 'utf8'));
const ids = { userId: 'u1', sourceDeviceId: 'd1', eventId: 'e1' };

test('committed vectors match the generator', async () => {
  assert.deepEqual(vectors, await buildVectors());
});

test('vector events decrypt to their exact plaintext', () => {
  const key = deriveSubkey(sodium.from_hex(vectors.umk_hex), 'event');
  for (const v of vectors.events) {
    const ct = fromB64u(v.ct);
    assert.equal(ct.length, v.paddedLength + 16, `${v.name}: ct length = padded + tag`);
    const plain = open(key, fromB64u(v.nonce), ct, new TextEncoder().encode(v.ad));
    assert.equal(new TextDecoder().decode(plain), v.plaintext, v.name);
  }
});

test('event round trip', () => {
  const umk = generateUmk();
  const payload = { t: 'sms', from: '+1', body: 'hi 👋' };
  const env = encryptEvent(umk, ids, payload);
  assert.equal(env.keyId, keyId(umk));
  assert.deepEqual(decryptEvent(new Map([[env.keyId, umk]]), ids, env), payload);
});

test('profile round trip', () => {
  const umk = generateUmk();
  const pids = { userId: 'u1', deviceId: 'd1', version: 2 };
  const env = encryptProfile(umk, pids, { name: 'Work phone' });
  assert.deepEqual(decryptProfile(new Map([[env.keyId, umk]]), pids, env), { name: 'Work phone' });
});

test('ciphertext moved to another event, device or user is rejected', () => {
  const umk = generateUmk();
  const umks = new Map([[keyId(umk), umk]]);
  const env = encryptEvent(umk, ids, { t: 'sms' });
  for (const other of [
    { ...ids, eventId: 'e2' },
    { ...ids, sourceDeviceId: 'd2' },
    { ...ids, userId: 'u2' },
  ]) {
    assert.throws(() => decryptEvent(umks, other, env));
  }
});

test('wrong key and unknown keyId are rejected', () => {
  const env = encryptEvent(generateUmk(), ids, { t: 'sms' });
  const other = generateUmk();
  assert.throws(() => decryptEvent(new Map([[env.keyId, other]]), ids, env));
  assert.throws(() => decryptEvent(new Map([[keyId(other), other]]), ids, env), /unknown keyId/);
});

test('tampered ciphertext is rejected', () => {
  const umk = generateUmk();
  const env = encryptEvent(umk, ids, { t: 'sms' });
  const ct = fromB64u(env.ct);
  ct[0] ^= 1;
  const key = deriveSubkey(umk, 'event');
  assert.throws(() => open(key, fromB64u(env.nonce), ct, eventAd('u1', 'd1', 'e1')));
});

test('padding buckets', () => {
  for (const { plaintextBytes, paddedLength: expected } of vectors.padding) {
    assert.equal(paddedLength(plaintextBytes), expected);
    const p = pad(new Uint8Array(plaintextBytes).fill(7));
    assert.equal(p.length, expected);
    assert.equal(unpad(p).length, plaintextBytes);
  }
  assert.throws(() => pad(new Uint8Array(262144)), /too large/);
});

// ── v2: node keys ───────────────────────────────────────────────────────────

test('committed v2 vectors match the generator', async () => {
  assert.deepEqual(v2, await buildV2Vectors());
});

test('X25519 matches an independent implementation (OpenSSL)', () => {
  const b64 = (b: Uint8Array) => Buffer.from(b).toString('base64url');
  const node = nodeKeyPair(sodium.from_hex(v2.node.secretKey_hex));
  const acctPk = fromB64u(v2.account.publicKey);
  const priv = createPrivateKey({ key: { kty: 'OKP', crv: 'X25519', d: b64(node.secretKey), x: b64(node.publicKey) }, format: 'jwk' });
  const pub = createPublicKey({ key: { kty: 'OKP', crv: 'X25519', x: b64(acctPk) }, format: 'jwk' });
  assert.equal(diffieHellman({ privateKey: priv, publicKey: pub }).toString('hex'), v2.node.shared_hex);
  assert.equal(b64(node.publicKey), v2.node.publicKey);
});

test('node and receivers derive the same root', () => {
  const umk = generateUmk();
  const node = generateNodeKeyPair();
  const root = nodeRootForNode(node.secretKey, accountKeyPair(umk).publicKey);
  assert.deepEqual(nodeRootForReceiver(umk, node.publicKey), root);
  assert.notEqual(nodeKeyId(root), keyId(umk));
});

test('v2 vector events decrypt with the node root', () => {
  const root = sodium.from_hex(v2.node.root_hex);
  assert.equal(nodeKeyId(root), v2.node.keyId);
  for (const v of v2.events) {
    const env = { keyId: v2.node.keyId, nonce: v.nonce, ct: v.ct };
    const plain = decryptNodeEvent(root, { userId: v.userId, sourceDeviceId: v.sourceDeviceId, eventId: v.eventId }, env);
    assert.deepEqual(plain, JSON.parse(v.plaintext), v.name);
  }
});

test('node event round trip; another node root cannot read it', () => {
  const umk = generateUmk();
  const a = nodeRootForReceiver(umk, generateNodeKeyPair().publicKey);
  const b = nodeRootForReceiver(umk, generateNodeKeyPair().publicKey);
  const env = encryptNodeEvent(a, ids, { t: 'sms', body: 'hi' });
  assert.equal(env.keyId, nodeKeyId(a));
  assert.deepEqual(decryptNodeEvent(a, ids, env), { t: 'sms', body: 'hi' });
  assert.throws(() => decryptNodeEvent(b, ids, env), /unknown keyId/);
  assert.throws(() => decryptNodeEvent(b, ids, { ...env, keyId: nodeKeyId(b) }));
});

test('low-order public keys are rejected', () => {
  const node = generateNodeKeyPair();
  for (const pk of v2.lowOrderPublicKeys.map(fromB64u)) {
    assert.throws(() => nodeRoot(node.secretKey, pk, pk, node.publicKey), /weak public key/);
  }
});

test('pairing MACs match the vectors and bind every field', () => {
  for (const m of v2.pairingMacs) {
    const secret = sodium.from_hex(m.secret_hex);
    const pk = fromB64u(m.publicKey);
    assert.equal(toB64u(pairingMac(secret, m.userId, m.deviceId, pk)), m.mac, m.name);
    assert.ok(verifyPairingMac(secret, m.userId, m.deviceId, pk, fromB64u(m.mac)));
    assert.ok(!verifyPairingMac(secret, m.userId, 'other', pk, fromB64u(m.mac)));
    assert.ok(!verifyPairingMac(secret, m.userId, m.deviceId, generateNodeKeyPair().publicKey, fromB64u(m.mac)));
    assert.ok(!verifyPairingMac(generateUmk(), m.userId, m.deviceId, pk, fromB64u(m.mac)));
  }
});

test('v2 commands decrypt with the node root, and only for their node and id', () => {
  const root = sodium.from_hex(v2.node.root_hex);
  for (const c of v2.commands) {
    const env = { keyId: v2.node.keyId, nonce: c.nonce, ct: c.ct };
    const ids = { userId: c.userId, nodeId: c.nodeId, commandId: c.commandId };
    assert.deepEqual(decryptCommand(root, ids, env), JSON.parse(c.plaintext), c.name);
    assert.throws(() => decryptCommand(root, { ...ids, commandId: 'other' }, env));
    assert.throws(() => decryptCommand(root, { ...ids, nodeId: 'other' }, env));
  }
  const fresh = encryptCommand(root, { userId: 'u', nodeId: 'n', commandId: 'c' }, { t: 'send_sms' });
  assert.deepEqual(decryptCommand(root, { userId: 'u', nodeId: 'n', commandId: 'c' }, fresh), { t: 'send_sms' });
  // A command is not an event: the subkey and AD differ.
  assert.throws(() => decryptNodeEvent(root, { userId: 'u', sourceDeviceId: 'n', eventId: 'c' }, fresh));
});
