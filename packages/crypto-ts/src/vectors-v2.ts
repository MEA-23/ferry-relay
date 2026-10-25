// Generates docs/test-vectors/v2.json: per-node keys (§3.7), node events (§4.6, §4.7), the
// pairing MAC (§5.6) and commands (§6.6).
// Deterministic: fixed keys and nonces. Plaintexts are exact strings, as in v1.

import { fileURLToPath } from 'node:url';
import sodium from 'libsodium-wrappers';
import {
  accountKeyPair,
  commandAd,
  deriveSubkey,
  eventAd,
  nodeKeyId,
  nodeKeyPair,
  nodeRootForNode,
  nodeRootForReceiver,
  pairingMac,
  paddedLength,
  ready,
  seal,
  toB64u,
} from './index.ts';

const seq = (start: number, len: number) => Uint8Array.from({ length: len }, (_, i) => (start + i) & 0xff);

const USER = '0192c2a0-0000-7000-8000-000000000001';
const NODE = '0192c2a0-0000-7000-8000-0000000000b1';

const EVENTS = [
  {
    name: 'node-sms',
    eventId: '0192c2a0-0000-7000-8000-00000000f001',
    plaintext:
      '{"t":"sms","occurredAt":1761292800000,"sim":{"slot":0,"subscriptionId":3,"carrier":"Vodafone","number":null},"from":"+4915112345678","body":"Your code is 123456"}',
  },
  {
    name: 'node-profile',
    eventId: '0192c2a0-0000-7000-8000-00000000f002',
    plaintext: '{"t":"profile","occurredAt":1761292801000,"name":"Old phone","sims":{"0":"Work SIM"}}',
  },
  {
    name: 'node-introduce',
    eventId: '0192c2a0-0000-7000-8000-00000000f003',
    plaintext:
      '{"t":"introduce","occurredAt":1761292802000,"deviceId":"0192c2a0-0000-7000-8000-0000000000b2","nodePk":"NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ"}',
  },
];

// Public keys that must be rejected: X25519 of them with any secret is all zeros.
const LOW_ORDER = [new Uint8Array(32), Uint8Array.from([1, ...new Uint8Array(31)])];

export async function buildVectors() {
  await ready();
  const umk = seq(0x00, 32);
  const account = accountKeyPair(umk);
  const node = nodeKeyPair(seq(0x20, 32));
  const root = nodeRootForNode(node.secretKey, account.publicKey);
  if (toB64u(root) !== toB64u(nodeRootForReceiver(umk, node.publicKey))) throw new Error('node and receiver roots differ');
  const eventKey = deriveSubkey(root, 'event');
  const enc = new TextEncoder();

  const events = EVENTS.map((e, i) => {
    const nonce = seq(0x60 + i, 24);
    const ad = eventAd(USER, NODE, e.eventId);
    const { ct } = seal(eventKey, enc.encode(e.plaintext), ad, nonce);
    return {
      name: e.name,
      userId: USER,
      sourceDeviceId: NODE,
      eventId: e.eventId,
      ad: new TextDecoder().decode(ad),
      plaintext: e.plaintext,
      paddedLength: paddedLength(enc.encode(e.plaintext).length),
      nonce: toB64u(nonce),
      ct: toB64u(ct),
    };
  });

  const commandPlain =
    '{"t":"send_sms","to":"+4915112345678","body":"On my way 🚢","subscriptionId":3,"createdAt":1761292900000,"expiresAt":1761293200000}';
  const commandId = '0192c2a0-0000-7000-8000-00000000c001';
  const commandNonce = seq(0x90, 24);
  const commandAdBytes = commandAd(USER, NODE, commandId);
  const commands = [
    {
      name: 'send-sms',
      userId: USER,
      nodeId: NODE,
      commandId,
      ad: new TextDecoder().decode(commandAdBytes),
      plaintext: commandPlain,
      nonce: toB64u(commandNonce),
      ct: toB64u(seal(deriveSubkey(root, 'command'), enc.encode(commandPlain), commandAdBytes, commandNonce).ct),
    },
  ];

  const secret = seq(0xa0, 32);
  return {
    version: 2,
    description:
      'Ferry v2 test vectors: per-node keys and pairing MACs (docs/PROTOCOL.md §3.7, §5.6). Binary values are base64url without padding unless suffixed _hex.',
    umk_hex: sodium.to_hex(umk),
    account: {
      secretKey_hex: sodium.to_hex(account.secretKey),
      publicKey: toB64u(account.publicKey),
    },
    node: {
      deviceId: NODE,
      secretKey_hex: sodium.to_hex(node.secretKey),
      publicKey: toB64u(node.publicKey),
      shared_hex: sodium.to_hex(sodium.crypto_scalarmult(node.secretKey, account.publicKey)),
      root_hex: sodium.to_hex(root),
      keyId: nodeKeyId(root),
      subkeys_hex: { event: sodium.to_hex(eventKey), command: sodium.to_hex(deriveSubkey(root, 'command')) },
    },
    lowOrderPublicKeys: LOW_ORDER.map(toB64u),
    events,
    commands,
    pairingMacs: [
      { name: 'account-key-to-node', publicKey: toB64u(account.publicKey) },
      { name: 'node-key-to-introducer', publicKey: toB64u(node.publicKey) },
    ].map((m) => ({
      ...m,
      secret_hex: sodium.to_hex(secret),
      userId: USER,
      deviceId: NODE,
      mac: toB64u(pairingMac(secret, USER, NODE, sodium.from_base64(m.publicKey, sodium.base64_variants.URLSAFE_NO_PADDING))),
    })),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.stdout.write(JSON.stringify(await buildVectors(), null, 2) + '\n');
}
