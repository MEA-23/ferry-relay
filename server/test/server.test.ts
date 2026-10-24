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
  await pglite.exec('TRUNCATE users, devices, invites, pairing_codes, events');
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

// Creates an account with an Android source and a paired iOS receiver with a push token.
async function world() {
  const umk = crypto.generateUmk();
  const keyId = crypto.keyId(umk);
  const acct = await call('POST', '/v1/accounts', undefined, { device: { roles: ['source'], platform: 'android' }, keyId });
  assert.equal(acct.status, 201);
  const source = { id: acct.body.deviceId as string, token: acct.body.token as string };
  const userId = acct.body.userId as string;

  const pairing = await call('POST', '/v1/pairing', source.token, { roles: ['receiver'] });
  assert.equal(pairing.status, 201);
  const paired = await call('POST', '/v1/devices', undefined, { userId, code: pairing.body.code, roles: ['receiver'], platform: 'ios' });
  assert.equal(paired.status, 201);
  assert.equal(paired.body.keyId, keyId);
  const receiver = { id: paired.body.deviceId as string, token: paired.body.token as string };

  const push = await call('PUT', `/v1/devices/${receiver.id}/push`, receiver.token, {
    provider: 'apns',
    token: APNS_TOKEN,
    appId: 'tech.thedevlab.ferry',
    environment: 'sandbox',
  });
  assert.equal(push.status, 204);
  return { umk, keyId, userId, source, receiver };
}

function envelope(w: Awaited<ReturnType<typeof world>>, payload: unknown, kind: 'alert' | 'silent' = 'alert') {
  const id = uuidv7(clock);
  return { id, kind, ...crypto.encryptEvent(w.umk, { userId: w.userId, sourceDeviceId: w.source.id, eventId: id }, payload) };
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
  const umks = new Map([[w.keyId, w.umk]]);
  assert.deepEqual(crypto.decryptEvent(umks, { userId: sr.u, sourceDeviceId: sr.d, eventId: sr.e.id }, sr.e), sms);
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

test('ack deletes events once every receiver has them', async () => {
  const w = await world();
  const pairing = await call('POST', '/v1/pairing', w.source.token, { roles: ['receiver'] });
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

  const ios = await call('POST', '/v1/accounts', undefined, { device: { roles: ['source'], platform: 'ios' }, keyId: w.keyId });
  assert.equal(ios.status, 400);
});

test('pairing codes are single use, expire, and limit roles', async () => {
  const w = await world();
  const p = await call('POST', '/v1/pairing', w.source.token, { roles: ['receiver'] });
  const body = { userId: w.userId, code: p.body.code, roles: ['source'], platform: 'android' };
  assert.equal((await call('POST', '/v1/devices', undefined, body)).status, 403);

  body.roles = ['receiver'];
  assert.equal((await call('POST', '/v1/devices', undefined, body)).status, 201);
  assert.equal((await call('POST', '/v1/devices', undefined, body)).body.error.code, 'pairing_expired');

  const p2 = await call('POST', '/v1/pairing', w.source.token, { roles: ['receiver'] });
  clock += 5 * 60_000 + 1;
  assert.equal((await call('POST', '/v1/devices', undefined, { ...body, code: p2.body.code })).status, 410);
});

test('revoked devices lose access and stop getting pushes', async () => {
  const w = await world();
  assert.equal((await call('DELETE', `/v1/devices/${w.receiver.id}`, w.source.token)).status, 204);
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

test('events under an old key are rejected after rotation', async () => {
  const w = await world();
  const newUmk = crypto.generateUmk();
  assert.equal((await call('POST', '/v1/keys/rotate', w.source.token, { keyId: crypto.keyId(newUmk) })).status, 204);
  const res = await call('POST', '/v1/events', w.source.token, { events: [envelope(w, { t: 'sms' })] });
  assert.equal(res.status, 409);
  assert.equal(res.body.error.code, 'key_rotated');
});

test('encrypted profiles: any device can set, version must increase', async () => {
  const w = await world();
  const profile = crypto.encryptProfile(w.umk, { userId: w.userId, deviceId: w.source.id, version: 1 }, { name: 'Work phone' });
  const url = `/v1/devices/${w.source.id}/profile`;
  assert.equal((await call('PUT', url, w.receiver.token, { version: 1, ...profile })).status, 204);
  assert.equal((await call('PUT', url, w.receiver.token, { version: 1, ...profile })).body.error.code, 'profile_stale');

  const listed = (await call('GET', '/v1/devices', w.receiver.token)).body.devices.find((d: { id: string }) => d.id === w.source.id);
  const umks = new Map([[w.keyId, w.umk]]);
  const ids = { userId: w.userId, deviceId: w.source.id, version: listed.profile.version };
  assert.deepEqual(crypto.decryptProfile(umks, ids, listed.profile), { name: 'Work phone' });
});

test('invite code is required when configured', async () => {
  await app.close();
  await setup({ inviteCode: 'let-me-in' });
  const body = { device: { roles: ['source'], platform: 'android' }, keyId: crypto.keyId(crypto.generateUmk()) };
  assert.equal((await call('POST', '/v1/accounts', undefined, body)).status, 403);
  assert.equal((await call('POST', '/v1/accounts', undefined, { ...body, invite: 'let-me-in' })).status, 201);
});

test('one-time invites work once and expire', async () => {
  await app.close();
  await setup({ inviteCode: 'let-me-in' });
  const [code, expired] = await createInvites(db, 2, 7, clock);
  await db.query('UPDATE invites SET expires_at = $1 WHERE code_hash = $2', [clock - 1, hashToken(expired)]);
  const body = { device: { roles: ['source'], platform: 'android' }, keyId: crypto.keyId(crypto.generateUmk()) };
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
