import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import sodium from 'libsodium-wrappers';
import {
  decryptEvent,
  decryptProfile,
  deriveSubkey,
  encryptEvent,
  encryptProfile,
  eventAd,
  fromB64u,
  generateUmk,
  keyId,
  open,
  pad,
  paddedLength,
  ready,
  unpad,
} from '../src/index.ts';
import { buildVectors } from '../src/vectors.ts';

await ready();

const vectors = JSON.parse(readFileSync(new URL('../../../docs/test-vectors/v1.json', import.meta.url), 'utf8'));
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
