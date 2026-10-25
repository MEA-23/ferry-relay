import { test, beforeEach, afterEach } from 'node:test';
import { PGlite } from '@electric-sql/pglite';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';
import * as crypto from '@ferry/crypto';
import { buildApp } from '../src/app.ts';
import type { Config } from '../src/config.ts';
import { SCHEMA, type Db } from '../src/db.ts';
import type { PushResult, PushTarget, Pusher, SrPayload } from '../src/push/index.ts';
import { createInvites } from '../src/invite.ts';
import { hashToken, uuidv7 } from '../src/util.ts';

await crypto.ready();

// In-process Postgres so the suite needs no database server.
const pglite = new PGlite({ parsers: { 20: Number } });
await pglite.exec(SCHEMA);
const db: Db = {
  query: (text, params) => pglite.query(text, params),
  tx: (fn) => pglite.transaction(fn),
  close: async () => {},
};

const APNS_TOKEN = 'ab'.repeat(32);

let app: FastifyInstance;
let clock: number;
let pushes: { target: PushTarget; sr: SrPayload }[];
let pushResult: PushResult;
let config: Config;

async function setup(overrides: Partial<Config> = {}) {
  await pglite.exec('TRUNCATE users, devices, invites, pairing_codes, events, commands');
  clock = 1_761_292_800_000;
  pushes = [];
  pushResult = 'ok';
  config = { host: '', port: 0, databaseUrl: '', retentionDays: 30, trustProxy: false, ...overrides };
  const pusher: Pusher = {
    async send(target, sr) {
      pushes.push({ target, sr });
      return pushResult;
    },
    close() {},
  };
  app = buildApp({ db, config, pusher, now: () => clock });
}

beforeEach(() => setup());
afterEach(() => app.close());

async function call(method: string, url: string, token?: string, body?: unknown) {
  const res = await app.inject({
    method: method as 'GET',
    url,
    headers: token ? { authorization: `Bearer ${token}` } : {},
    ...(body !== undefined && { payload: body as object }),
  });
  return { status: res.statusCode, body: res.body ? res.json() : undefined };
}

// §5.6 flow A: a node creates the account, its first receiver scans the node's QR code, sets the
// account key and sends the node the account public key. The receiver also gets a push token.
async function world() {
  const acct = await call('POST', '/v1/accounts', undefined, { device: { roles: ['source'], platform: 'android' } });
  assert.equal(acct.status, 201);
  const userId = acct.body.userId as string;
  const node = crypto.generateNodeKeyPair();
  const source = { id: acct.body.deviceId as string, token: acct.body.token as string };
  const secret = crypto.generateUmk(); // the pairing secret: 32 random bytes in the QR code

  const pairing = await call('POST', '/v1/pairing', source.token, { roles: ['receiver'] });
  assert.equal(pairing.status, 201);
  const code = pairing.body.code as string;
  const paired = await call('POST', '/v1/devices', undefined, { userId, code, roles: ['receiver'], platform: 'ios' });
  assert.equal(paired.status, 201);
  assert.equal(paired.body.keyId, null);
  const receiver = { id: paired.body.deviceId as string, token: paired.body.token as string };

  const umk = crypto.generateUmk();
  const keyId = crypto.keyId(umk);
  assert.equal((await call('POST', '/v1/keys/initial', receiver.token, { keyId })).status, 204);
  const acctPk = crypto.accountKeyPair(umk).publicKey;
  const mac = crypto.pairingMac(secret, userId, source.id, acctPk);
  const sent = await call('POST', `/v1/pairing/${code}/reply`, receiver.token, {
    deviceId: source.id,
    publicKey: crypto.toB64u(acctPk),
    mac: crypto.toB64u(mac),
  });
  assert.equal(sent.status, 204);

  const got = await call('GET', `/v1/pairing/${code}/reply`, source.token);
  assert.equal(got.status, 200);
  const msg = got.body.message;
  assert.ok(crypto.verifyPairingMac(secret, userId, msg.deviceId, crypto.fromB64u(msg.publicKey), crypto.fromB64u(msg.mac)));
  const root = crypto.nodeRootForNode(node.secretKey, crypto.fromB64u(msg.publicKey));
  assert.equal((await call('PUT', `/v1/devices/${source.id}/key`, source.token, { keyId: crypto.nodeKeyId(root) })).status, 204);

  const push = await call('PUT', `/v1/devices/${receiver.id}/push`, receiver.token, {
    provider: 'apns',
    token: APNS_TOKEN,
    appId: 'tech.thedevlab.ferry',
    environment: 'sandbox',
  });
  assert.equal(push.status, 204);
  return { umk, keyId, userId, source, receiver, node, root, code };
}

function envelope(w: Awaited<ReturnType<typeof world>>, payload: unknown, kind: 'alert' | 'silent' = 'alert') {
  const id = uuidv7(clock);
  return { id, kind, ...crypto.encryptNodeEvent(w.root, { userId: w.userId, sourceDeviceId: w.source.id, eventId: id }, payload) };
}

// Receivers find the node's root from the UMK and the node public key in its profile.
function receiverRoot(w: Awaited<ReturnType<typeof world>>) {
  return crypto.nodeRootForReceiver(w.umk, w.node.publicKey);
}

test('end to end: source uploads, receiver gets an inline push it can decrypt', async () => {
  const w = await world();
  const sms = { t: 'sms', from: '+4915112345678', body: 'Your code is 123456' };
  const env = envelope(w, sms);

  const up = await call('POST', '/v1/events', w.source.token, { events: [env] });
  assert.equal(up.status, 200);
  assert.deepEqual(up.body, { accepted: [env.id], duplicate: [] });

  assert.equal(pushes.length, 1);
  const { target, sr } = pushes[0];
  assert.deepEqual(target, { provider: 'apns', token: APNS_TOKEN, appId: 'tech.thedevlab.ferry', environment: 'sandbox' });
  assert.ok('e' in sr);
  assert.deepEqual(crypto.decryptNodeEvent(receiverRoot(w), { userId: sr.u, sourceDeviceId: sr.d, eventId: sr.e.id }, sr.e), sms);
});

test('large events are pushed by id and fetched', async () => {
  const w = await world();
  const env = envelope(w, { t: 'notification', text: 'x'.repeat(5000) });
  await call('POST', '/v1/events', w.source.token, { events: [env] });

  assert.deepEqual(pushes[0].sr, { v: 1, u: w.userId, id: env.id });
  const fetched = await call('GET', `/v1/events/${env.id}`, w.receiver.token);
  assert.equal(fetched.status, 200);
  assert.equal(fetched.body.ct, env.ct);
  assert.equal(fetched.body.sourceDeviceId, w.source.id);
});

test('silent events are stored but not pushed', async () => {
  const w = await world();
  await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'status' }, 'silent')] });
  assert.equal(pushes.length, 0);
  assert.equal((await call('GET', '/v1/events', w.receiver.token)).body.events.length, 1);
});

test('upload is idempotent', async () => {
  const w = await world();
  const env = envelope(w, { t: 'sms' });
  await call('POST', '/v1/events', w.source.token, { events: [env] });
  const again = await call('POST', '/v1/events', w.source.token, { events: [env, env] });
  assert.deepEqual(again.body, { accepted: [], duplicate: [env.id, env.id] });
  assert.equal(pushes.length, 1);
});

test('pull paginates in seq order', async () => {
  const w = await world();
  const envs = Array.from({ length: 5 }, (_, i) => envelope(w, { t: 'sms', body: String(i) }));
  await call('POST', '/v1/events', w.source.token, { events: envs });

  const p1 = await call('GET', '/v1/events?limit=3', w.receiver.token);
  assert.deepEqual(p1.body.events.map((e: { id: string }) => e.id), envs.slice(0, 3).map((e) => e.id));
  assert.equal(p1.body.hasMore, true);
  const p2 = await call('GET', `/v1/events?after=${p1.body.next}&limit=3`, w.receiver.token);
  assert.deepEqual(p2.body.events.map((e: { id: string }) => e.id), envs.slice(3).map((e) => e.id));
  assert.equal(p2.body.hasMore, false);
  const p3 = await call('GET', `/v1/events?after=${p2.body.next}`, w.receiver.token);
  assert.deepEqual(p3.body, { events: [], next: p2.body.next, hasMore: false });
});

test('an open app’s pull waits for the next event, silent ones included', async () => {
  const w = await world();
  const waiting = call('GET', '/v1/events?wait=5', w.receiver.token);
  const env = envelope(w, { t: 'sms_status', status: 'sent' }, 'silent');
  const t0 = Date.now();
  await call('POST', '/v1/events', w.source.token, { events: [env] });
  const got = await waiting;
  assert.ok(Date.now() - t0 < 2000, 'the long poll returns as soon as an event is stored');
  assert.deepEqual(got.body.events.map((e: { id: string }) => e.id), [env.id]);

  // Nothing new: it answers empty once the wait is up.
  const empty = await call('GET', `/v1/events?after=${got.body.next}&wait=1`, w.receiver.token);
  assert.deepEqual(empty.body, { events: [], next: got.body.next, hasMore: false });
});

test('ack deletes events once every receiver has them', async () => {
  const w = await world();
  const pairing = await call('POST', '/v1/pairing', w.receiver.token, { roles: ['receiver'] });
  const second = await call('POST', '/v1/devices', undefined, { userId: w.userId, code: pairing.body.code, roles: ['receiver'], platform: 'android' });

  await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' }), envelope(w, { t: 'sms' })] });
  assert.equal((await call('POST', '/v1/events/ack', w.receiver.token, { upTo: '2' })).status, 204);
  assert.equal((await call('GET', '/v1/events', second.body.token)).body.events.length, 2, 'second receiver has not acked');

  await call('POST', '/v1/events/ack', second.body.token, { upTo: '1' });
  const left = await call('GET', '/v1/events', w.receiver.token);
  assert.deepEqual(left.body.events.map((e: { seq: string }) => e.seq), ['2']);
});

test('events past retention are pruned', async () => {
  const w = await world();
  await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] });
  clock += 31 * 86_400_000;
  await call('POST', '/v1/events/ack', w.receiver.token, { upTo: '0' });
  assert.equal((await call('GET', '/v1/events', w.receiver.token)).body.events.length, 0);
});

test('roles are enforced', async () => {
  const w = await world();
  const env = envelope(w, { t: 'sms' });
  assert.equal((await call('POST', '/v1/events', w.receiver.token, { events: [env] })).body.error.code, 'forbidden');
  assert.equal((await call('GET', '/v1/events', w.source.token)).status, 403);

  const ios = await call('POST', '/v1/accounts', undefined, { device: { roles: ['source'], platform: 'ios' } });
  assert.equal(ios.status, 400);
});

test('pairing codes are single use, expire, and limit roles', async () => {
  const w = await world();
  const p = await call('POST', '/v1/pairing', w.receiver.token, { roles: ['receiver'] });
  const body = { userId: w.userId, code: p.body.code, roles: ['source'], platform: 'android' };
  assert.equal((await call('POST', '/v1/devices', undefined, body)).status, 403);

  body.roles = ['receiver'];
  assert.equal((await call('POST', '/v1/devices', undefined, body)).status, 201);
  assert.equal((await call('POST', '/v1/devices', undefined, body)).body.error.code, 'pairing_expired');

  const p2 = await call('POST', '/v1/pairing', w.receiver.token, { roles: ['receiver'] });
  clock += 5 * 60_000 + 1;
  assert.equal((await call('POST', '/v1/devices', undefined, { ...body, code: p2.body.code })).status, 410);
});

test('revoked devices lose access and stop getting pushes', async () => {
  const w = await world();
  assert.equal((await call('DELETE', `/v1/devices/${w.receiver.id}`, w.source.token)).status, 403, 'nodes cannot remove receivers');
  assert.equal((await call('DELETE', `/v1/devices/${w.receiver.id}`, w.receiver.token)).status, 204);
  assert.equal((await call('GET', '/v1/events', w.receiver.token)).status, 401);

  await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] });
  assert.equal(pushes.length, 0);
  assert.deepEqual((await call('GET', '/v1/devices', w.source.token)).body.devices.map((d: { id: string }) => d.id), [w.source.id]);
});

test('invalid push tokens are cleared', async () => {
  const w = await world();
  pushResult = 'invalid_token';
  await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] });
  await new Promise((r) => setImmediate(r));
  await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] });
  assert.equal(pushes.length, 1);
});

test('rotation makes every node key stale until the node re-pairs', async () => {
  const w = await world();
  const newUmk = crypto.generateUmk();
  assert.equal((await call('POST', '/v1/keys/rotate', w.source.token, { keyId: crypto.keyId(newUmk) })).status, 403, 'nodes cannot rotate');
  assert.equal((await call('POST', '/v1/keys/rotate', w.receiver.token, { keyId: crypto.keyId(newUmk) })).status, 204);
  const res = await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] });
  assert.equal(res.status, 409);
  assert.equal(res.body.error.code, 'key_rotated');
});

test('encrypted profiles: receivers can set any, version must increase', async () => {
  const w = await world();
  const profile = crypto.encryptProfile(w.umk, { userId: w.userId, deviceId: w.source.id, version: 1 }, { name: 'Work phone' });
  const url = `/v1/devices/${w.source.id}/profile`;
  assert.equal((await call('PUT', url, w.receiver.token, { version: 1, ...profile })).status, 204);
  assert.equal((await call('PUT', url, w.receiver.token, { version: 1, ...profile })).body.error.code, 'profile_stale');

  assert.equal((await call('PUT', url, w.source.token, { version: 2, ...profile })).status, 403, 'nodes have no UMK to seal profiles with');

  const listed = (await call('GET', '/v1/devices', w.receiver.token)).body.devices.find((d: { id: string }) => d.id === w.source.id);
  const umks = new Map([[w.keyId, w.umk]]);
  const ids = { userId: w.userId, deviceId: w.source.id, version: listed.profile.version };
  assert.deepEqual(crypto.decryptProfile(umks, ids, listed.profile), { name: 'Work phone' });
});

test('invite code is required when configured', async () => {
  await app.close();
  await setup({ inviteCode: 'let-me-in' });
  const body = { device: { roles: ['source'], platform: 'android' } };
  assert.equal((await call('POST', '/v1/accounts', undefined, body)).status, 403);
  assert.equal((await call('POST', '/v1/accounts', undefined, { ...body, invite: 'let-me-in' })).status, 201);
});

test('one-time invites work once and expire', async () => {
  await app.close();
  await setup({ inviteCode: 'let-me-in' });
  const [code, expired] = await createInvites(db, 2, 7, clock);
  await db.query('UPDATE invites SET expires_at = $1 WHERE code_hash = $2', [clock - 1, hashToken(expired)]);
  const body = { device: { roles: ['receiver'], platform: 'ios' }, keyId: crypto.keyId(crypto.generateUmk()) };
  assert.equal((await call('POST', '/v1/accounts', undefined, { ...body, invite: code })).status, 201);
  assert.equal((await call('POST', '/v1/accounts', undefined, { ...body, invite: code })).status, 403);
  assert.equal((await call('POST', '/v1/accounts', undefined, { ...body, invite: expired })).status, 403);
  assert.equal((await db.query('SELECT 1 FROM users')).rows.length, 1);
});

test('auth and validation errors use the error envelope', async () => {
  assert.deepEqual((await call('GET', '/v1/devices')).body.error.code, 'unauthorized');
  assert.deepEqual((await call('GET', '/v1/devices', 'x'.repeat(43))).status, 401);
  assert.equal((await call('POST', '/v1/accounts', undefined, { keyId: 'nope' })).body.error.code, 'invalid_request');
  assert.equal((await call('GET', '/nope')).body.error.code, 'not_found');
});

// ── v2: node keys and pairing (§3.7, §5.6) ──────────────────────────────────

test('nodes upload only under their own key; the account key is set once', async () => {
  const w = await world();
  const asAccount = { ...envelope(w, { t: 'sms' }), keyId: w.keyId };
  assert.equal((await call('POST', '/v1/events', w.source.token, { events: [asAccount] })).body.error.code, 'key_rotated');
  assert.equal((await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] })).status, 200);
  const again = await call('POST', '/v1/keys/initial', w.receiver.token, { keyId: crypto.keyId(crypto.generateUmk()) });
  assert.equal(again.body.error.code, 'key_set');
  assert.equal((await call('POST', '/v1/keys/initial', w.source.token, { keyId: w.keyId })).status, 403);
});

test('a node without a registered key cannot upload', async () => {
  const acct = await call('POST', '/v1/accounts', undefined, { device: { roles: ['source'], platform: 'android' } });
  const env = { id: uuidv7(clock), kind: 'alert', keyId: 'A'.repeat(22), nonce: 'A'.repeat(32), ct: 'AAAA' };
  assert.equal((await call('POST', '/v1/events', acct.body.token, { events: [env] })).body.error.code, 'key_rotated');
});

test('nodes pair receivers only until the account has a key, and other nodes any time', async () => {
  const w = await world();
  assert.equal((await call('POST', '/v1/pairing', w.source.token, { roles: ['receiver'] })).status, 403);
  assert.equal((await call('POST', '/v1/pairing', w.source.token, { roles: ['source'] })).status, 201);
});

test('flow B: a receiver adds a node, which answers through the mailbox', async () => {
  const w = await world();
  const secret = crypto.generateUmk();
  const p = await call('POST', '/v1/pairing', w.receiver.token, { roles: ['source'] });
  const code = p.body.code as string;
  const joined = await call('POST', '/v1/devices', undefined, { userId: w.userId, code, roles: ['source'], platform: 'android' });
  assert.equal(joined.status, 201);
  assert.equal(joined.body.keyId, w.keyId);
  const node = { id: joined.body.deviceId as string, token: joined.body.token as string };

  assert.deepEqual((await call('GET', `/v1/pairing/${code}/reply`, w.receiver.token)).body, { message: null });
  const keys = crypto.generateNodeKeyPair();
  const root = crypto.nodeRootForNode(keys.secretKey, crypto.accountKeyPair(w.umk).publicKey);
  assert.equal((await call('PUT', `/v1/devices/${node.id}/key`, node.token, { keyId: crypto.nodeKeyId(root) })).status, 204);
  const message = {
    deviceId: node.id,
    publicKey: crypto.toB64u(keys.publicKey),
    mac: crypto.toB64u(crypto.pairingMac(secret, w.userId, node.id, keys.publicKey)),
  };
  assert.equal((await call('POST', `/v1/pairing/${code}/reply`, node.token, message)).status, 204);
  assert.equal((await call('POST', `/v1/pairing/${code}/reply`, node.token, message)).body.error.code, 'pairing_replied');

  const got = (await call('GET', `/v1/pairing/${code}/reply`, w.receiver.token)).body.message;
  assert.deepEqual(got, message);
  assert.deepEqual(crypto.nodeRootForReceiver(w.umk, crypto.fromB64u(got.publicKey)), root);

  // Only the two devices on the code can use its mailbox.
  assert.equal((await call('GET', `/v1/pairing/${code}/reply`, w.source.token)).status, 404);
  assert.equal((await call('POST', `/v1/pairing/${code}/reply`, w.source.token, message)).status, 404);
});

test('the mailbox outlives the code briefly, then is pruned', async () => {
  const w = await world();
  clock += 5 * 60_000 + 1;
  await call('POST', '/v1/events/ack', w.receiver.token, { upTo: '0' });
  assert.equal((await call('GET', `/v1/pairing/${w.code}/reply`, w.source.token)).status, 200);
  clock += 10 * 60_000;
  await call('POST', '/v1/events/ack', w.receiver.token, { upTo: '0' });
  assert.equal((await call('GET', `/v1/pairing/${w.code}/reply`, w.source.token)).status, 404);
});

test('nodes cannot become receivers or register a key for others', async () => {
  const w = await world();
  assert.equal((await call('PATCH', `/v1/devices/${w.source.id}`, w.source.token, { roles: ['source', 'receiver'] })).status, 403);
  assert.equal((await call('PUT', `/v1/devices/${w.receiver.id}/key`, w.source.token, { keyId: w.keyId })).status, 403);
  assert.equal((await call('PUT', `/v1/devices/${w.receiver.id}/key`, w.receiver.token, { keyId: w.keyId })).status, 403);
});

test('nodes can remove other nodes and themselves', async () => {
  const w = await world();
  const p = await call('POST', '/v1/pairing', w.source.token, { roles: ['source'] });
  const other = await call('POST', '/v1/devices', undefined, { userId: w.userId, code: p.body.code, roles: ['source'], platform: 'android' });
  assert.equal((await call('DELETE', `/v1/devices/${other.body.deviceId}`, w.source.token)).status, 204);
  assert.equal((await call('DELETE', `/v1/devices/${w.source.id}`, w.source.token)).status, 204);
});

test('flow C: a node introduces another node through the mailbox', async () => {
  const w = await world();
  const secret = crypto.generateUmk();
  const code = (await call('POST', '/v1/pairing', w.source.token, { roles: ['source'] })).body.code as string;
  const joined = await call('POST', '/v1/devices', undefined, { userId: w.userId, code, roles: ['source'], platform: 'android' });
  const b = { id: joined.body.deviceId as string, token: joined.body.token as string };
  const keys = crypto.generateNodeKeyPair();
  const message = {
    deviceId: b.id,
    publicKey: crypto.toB64u(keys.publicKey),
    mac: crypto.toB64u(crypto.pairingMac(secret, w.userId, b.id, keys.publicKey)),
  };
  assert.equal((await call('POST', `/v1/pairing/${code}/reply`, b.token, message)).status, 204);
  assert.deepEqual((await call('GET', `/v1/pairing/${code}/reply`, w.source.token)).body.message, message);

  // A tells the receivers with an introduce event under its own key (§4.7).
  const intro = envelope(w, { t: 'introduce', deviceId: b.id, nodePk: message.publicKey });
  assert.equal((await call('POST', '/v1/events', w.source.token, { events: [intro] })).status, 200);
  const pulled = (await call('GET', '/v1/events', w.receiver.token)).body.events[0];
  const ids = { userId: w.userId, sourceDeviceId: pulled.sourceDeviceId, eventId: pulled.id };
  assert.deepEqual(crypto.decryptNodeEvent(receiverRoot(w), ids, pulled), { t: 'introduce', deviceId: b.id, nodePk: message.publicKey });
});

// ── v2: commands (§6.6) ─────────────────────────────────────────────────────

function command(w: Awaited<ReturnType<typeof world>>, payload: unknown) {
  const id = uuidv7(clock);
  return { id, ...crypto.encryptCommand(receiverRoot(w), { userId: w.userId, nodeId: w.source.id, commandId: id }, payload) };
}

test('commands reach their node through a long poll, until acked', async () => {
  const w = await world();
  const waiting = call('GET', '/v1/commands?wait=5', w.source.token);
  const cmd = command(w, { t: 'send_sms', to: '+1', body: 'hi' });
  const t0 = Date.now();
  assert.equal((await call('POST', `/v1/devices/${w.source.id}/commands`, w.receiver.token, cmd)).status, 202);
  const got = await waiting;
  assert.ok(Date.now() - t0 < 2000, 'the long poll returns as soon as a command arrives');
  assert.equal(got.body.commands.length, 1);
  const c = got.body.commands[0];
  assert.equal(c.senderId, w.receiver.id);
  const ids = { userId: w.userId, nodeId: w.source.id, commandId: c.id };
  assert.deepEqual(crypto.decryptCommand(w.root, ids, c), { t: 'send_sms', to: '+1', body: 'hi' });

  // Re-sending the same id is a no-op; unacked commands come back; acked ones don't.
  await call('POST', `/v1/devices/${w.source.id}/commands`, w.receiver.token, cmd);
  assert.equal((await call('GET', '/v1/commands', w.source.token)).body.commands.length, 1);
  assert.equal((await call('POST', '/v1/commands/ack', w.source.token, { ids: [cmd.id] })).status, 204);
  assert.deepEqual((await call('GET', '/v1/commands', w.source.token)).body, { commands: [] });
});

test('only receivers send commands, only to a node, under its current key', async () => {
  const w = await world();
  const cmd = command(w, { t: 'send_sms' });
  assert.equal((await call('POST', `/v1/devices/${w.source.id}/commands`, w.source.token, cmd)).status, 403);
  assert.equal((await call('POST', `/v1/devices/${w.receiver.id}/commands`, w.receiver.token, cmd)).status, 404);
  const stale = { ...cmd, keyId: w.keyId };
  assert.equal((await call('POST', `/v1/devices/${w.source.id}/commands`, w.receiver.token, stale)).body.error.code, 'key_rotated');
  assert.equal((await call('GET', '/v1/commands', w.receiver.token)).status, 403);
});

test('unacked commands expire on the server', async () => {
  const w = await world();
  await call('POST', `/v1/devices/${w.source.id}/commands`, w.receiver.token, command(w, { t: 'send_sms' }));
  clock += 16 * 60_000;
  await call('POST', '/v1/events/ack', w.receiver.token, { upTo: '0' });
  assert.deepEqual((await call('GET', '/v1/commands', w.source.token)).body, { commands: [] });
});
