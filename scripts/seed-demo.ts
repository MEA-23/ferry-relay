// Fills a Ferry account with demo messages, for screenshots and App Store review.
// It joins the account as an extra source device (docs/PROTOCOL.md §5.3) and uploads
// encrypted events (§6.2), exactly like the Android app does.
//
//   node scripts/seed-demo.ts pair 'ferry://pair?...'   # once; needs a code made for the *relay* role
//   node scripts/seed-demo.ts seed                      # uploads the demo set, timed relative to now
//   node scripts/seed-demo.ts send 'See you soon'       # one new text from Omar
//
// The device token and the account key are kept in ~/.config/ferry/demo-device.json, outside the repo.
import { randomBytes } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { encryptEvent, encryptProfile, fromB64u, keyId, ready } from '../packages/crypto-ts/src/index.ts';

const STATE = join(homedir(), '.config', 'ferry', 'demo-device.json');
const DEVICE_NAME = 'Galaxy S24';
const SIMS = { 0: 'Personal', 1: 'Work' };

interface State {
  server: string;
  userId: string;
  deviceId: string;
  token: string;
  umk: string;
  profileVersion: number;
}

function uuidv7(now = Date.now()): string {
  const b = randomBytes(16);
  let ts = BigInt(now);
  for (let i = 5; i >= 0; i--) {
    b[i] = Number(ts & 0xffn);
    ts >>= 8n;
  }
  b[6] = (b[6] & 0x0f) | 0x70;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

async function api(s: Pick<State, 'server'> & Partial<State>, method: string, path: string, body?: unknown) {
  const res = await fetch(s.server + path, {
    method,
    headers: { 'content-type': 'application/json', ...(s.token ? { authorization: `Bearer ${s.token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function pair(link: string) {
  const url = new URL(link);
  const server = url.searchParams.get('s')!.replace(/\/$/, '');
  const userId = url.searchParams.get('u')!;
  const umk = url.searchParams.get('k')!;
  const reg = await api({ server }, 'POST', '/v1/devices', {
    userId, code: url.searchParams.get('c'), roles: ['source'], platform: 'android', appVersion: '0.1.0',
  });
  if (reg.keyId !== keyId(fromB64u(umk))) throw new Error('keyId mismatch: the link is for a different account key');
  const state: State = { server, userId, deviceId: reg.deviceId, token: reg.token, umk, profileVersion: 0 };
  await putProfile(state);
  mkdirSync(dirname(STATE), { recursive: true });
  writeFileSync(STATE, JSON.stringify(state, null, 2), { mode: 0o600 });
  console.log(`Paired as ${DEVICE_NAME} (${state.deviceId}). State saved to ${STATE}`);
}

async function putProfile(s: State) {
  s.profileVersion += 1;
  const sealed = encryptProfile(fromB64u(s.umk), { userId: s.userId, deviceId: s.deviceId, version: s.profileVersion },
    { name: DEVICE_NAME, sims: SIMS });
  await api(s, 'PUT', `/v1/devices/${s.deviceId}/profile`, { version: s.profileVersion, ...sealed });
}

const MIN = 60_000;
const sim = (slot: 0 | 1) => ({ slot, subscriptionId: slot + 1, carrier: slot === 0 ? 'Vodafone' : 'T-Mobile', number: null });

// Oldest first. `ago` is minutes before now.
function demoEvents(now: number) {
  const at = (ago: number) => now - ago * MIN;
  const call = (ago: number, from: string, contactName: string | null, slot: 0 | 1) => {
    const callKey = randomBytes(8).toString('hex');
    return [
      { t: 'call', occurredAt: at(ago), sim: sim(slot), from, contactName, state: 'ringing', durationSec: 0, callKey },
      { t: 'call', occurredAt: at(ago), sim: sim(slot), from, contactName, state: 'missed', durationSec: 0, callKey },
    ];
  };
  return [
    { t: 'sms', occurredAt: at(3 * 1440 + 20), sim: sim(0), from: '+15555550101', contactName: 'Omar Haddad',
      body: 'Are we still on for Saturday?' },
    { t: 'sms', occurredAt: at(3 * 1440 + 15), sim: sim(0), from: '+15555550101', contactName: 'Omar Haddad',
      body: 'Yes! 7pm at the usual place.', direction: 'out' },
    { t: 'sms', occurredAt: at(1440 + 30), sim: sim(0), from: '+15555550101', contactName: 'Omar Haddad',
      body: 'مرحبا، هل وصلت بالسلامة؟' },
    { t: 'sms', occurredAt: at(300), sim: sim(1), from: 'DHL', contactName: null,
      body: 'Your parcel 5551-0042 arrives today between 2pm and 5pm.' },
    ...call(180, '+15555550103', 'Mum', 0),
    { t: 'sms', occurredAt: at(95), sim: sim(1), from: 'MyBank', contactName: null,
      body: 'Card ending 4821 was charged $23.40 at Blue Bottle Coffee.' },
    { t: 'notification', occurredAt: at(50), package: 'com.whatsapp', appName: 'WhatsApp', title: 'Family',
      text: 'Sara: Dinner at 8?', category: 'msg', key: `0|com.whatsapp|1|demo-${now}` },
    { t: 'sms', occurredAt: at(12), sim: sim(0), from: '+15555550101', contactName: 'Omar Haddad',
      body: 'Running 10 minutes late, sorry' },
    { t: 'status', occurredAt: at(1), battery: 81, charging: false,
      sims: [{ slot: 0, subscriptionId: 1, carrier: 'Vodafone', active: true }, { slot: 1, subscriptionId: 2, carrier: 'T-Mobile', active: true }],
      permissions: { sms: true, notifications: true, phone: true }, appVersion: '0.1.0' },
  ];
}

async function seed() {
  await upload(demoEvents(Date.now()));
}

// One new text from Omar, for testing a single notification.
async function send(body: string) {
  await upload([{ t: 'sms', occurredAt: Date.now(), sim: sim(0), from: '+15555550101', contactName: 'Omar Haddad', body }]);
}

async function upload(payloads: ReturnType<typeof demoEvents> | object[]) {
  const s: State = JSON.parse(readFileSync(STATE, 'utf8'));
  const umk = fromB64u(s.umk);
  const now = Date.now();
  const events = payloads.map((payload, i) => {
    const id = uuidv7(now + i);
    const p = payload as { t: string; direction?: string };
    const silent = p.t === 'status' || p.direction === 'out';
    return { id, kind: silent ? 'silent' : 'alert', ...encryptEvent(umk, { userId: s.userId, sourceDeviceId: s.deviceId, eventId: id }, payload) };
  });
  const res = await api(s, 'POST', '/v1/events', { events });
  console.log(`Uploaded ${res.accepted.length} events (${res.duplicate.length} duplicates).`);
}

await ready();
const [cmd, arg] = process.argv.slice(2);
if (cmd === 'pair' && arg) await pair(arg);
else if (cmd === 'seed') await seed();
else if (cmd === 'send' && arg) await send(arg);
else {
  console.error("usage: node scripts/seed-demo.ts pair 'ferry://pair?...' | seed | send 'text from Omar'");
  process.exit(1);
}
