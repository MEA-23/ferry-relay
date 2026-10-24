// Generates docs/test-vectors/v1.json. Deterministic: fixed keys and nonces.
// Plaintexts are given as exact strings (not re-serialized JSON) so every platform
// encrypts the same bytes.

import { fileURLToPath } from 'node:url';
import sodium from 'libsodium-wrappers';
import { deriveSubkey, eventAd, keyId, paddedLength, profileAd, ready, seal, toB64u } from './index.ts';

const seq = (start: number, len: number) => Uint8Array.from({ length: len }, (_, i) => (start + i) & 0xff);

const USER = '0192c2a0-0000-7000-8000-000000000001';
const SOURCE = '0192c2a0-0000-7000-8000-0000000000a1';

const EVENTS = [
  {
    name: 'sms-ascii',
    eventId: '0192c2a0-0000-7000-8000-00000000e001',
    plaintext:
      '{"t":"sms","occurredAt":1761292800000,"sim":{"slot":0,"subscriptionId":3,"carrier":"Vodafone","number":null},"from":"+4915112345678","body":"Your code is 123456"}',
  },
  {
    name: 'sms-unicode',
    eventId: '0192c2a0-0000-7000-8000-00000000e002',
    plaintext:
      '{"t":"sms","occurredAt":1761292801000,"sim":{"slot":1,"subscriptionId":4,"carrier":null,"number":null},"from":"Mom","body":"مرحبا! كيف حالك؟ 🚢"}',
  },
  {
    name: 'call-missed',
    eventId: '0192c2a0-0000-7000-8000-00000000e003',
    plaintext:
      '{"t":"call","occurredAt":1761292802000,"sim":{"slot":0,"subscriptionId":3,"carrier":"Vodafone","number":null},"from":"+4915112345678","state":"missed","durationSec":0,"callKey":"c1"}',
  },
  {
    name: 'notification-large',
    eventId: '0192c2a0-0000-7000-8000-00000000e004',
    plaintext: `{"t":"notification","occurredAt":1761292803000,"package":"com.whatsapp","appName":"WhatsApp","title":"Alice","text":"${'x'.repeat(3000)}"}`,
  },
];

const PADDING_LENGTHS = [0, 1, 254, 255, 256, 2047, 2048, 65534, 65535, 65536, 100000, 262143];

export async function buildVectors() {
  await ready();
  const umk = seq(0x00, 32);
  const eventKey = deriveSubkey(umk, 'event');
  const profileKey = deriveSubkey(umk, 'profile');
  const enc = new TextEncoder();

  const events = EVENTS.map((e, i) => {
    const nonce = seq(0x40 + i, 24);
    const ad = eventAd(USER, SOURCE, e.eventId);
    const { ct } = seal(eventKey, enc.encode(e.plaintext), ad, nonce);
    return {
      name: e.name,
      userId: USER,
      sourceDeviceId: SOURCE,
      eventId: e.eventId,
      ad: new TextDecoder().decode(ad),
      plaintext: e.plaintext,
      plaintextBytes: enc.encode(e.plaintext).length,
      paddedLength: paddedLength(enc.encode(e.plaintext).length),
      nonce: toB64u(nonce),
      ct: toB64u(ct),
    };
  });

  const profilePlain = '{"name":"Work phone","sims":{"0":"Work SIM","1":"Travel SIM"}}';
  const profileNonce = seq(0x80, 24);
  const profile = {
    userId: USER,
    deviceId: SOURCE,
    version: 1,
    ad: new TextDecoder().decode(profileAd(USER, SOURCE, 1)),
    plaintext: profilePlain,
    nonce: toB64u(profileNonce),
    ct: toB64u(seal(profileKey, enc.encode(profilePlain), profileAd(USER, SOURCE, 1), profileNonce).ct),
  };

  return {
    version: 1,
    description: 'Ferry crypto test vectors (docs/PROTOCOL.md §3). Binary values are base64url without padding unless suffixed _hex.',
    umk_hex: sodium.to_hex(umk),
    keyId: keyId(umk),
    subkeys_hex: {
      event: sodium.to_hex(eventKey),
      profile: sodium.to_hex(profileKey),
      recovery: sodium.to_hex(deriveSubkey(umk, 'recovery')),
    },
    padding: PADDING_LENGTHS.map((len) => ({ plaintextBytes: len, paddedLength: paddedLength(len) })),
    events,
    profile,
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.stdout.write(JSON.stringify(await buildVectors(), null, 2) + '\n');
}
