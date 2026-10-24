# Ferry Protocol — v1 (draft)

Status: **Draft**. Breaking changes are allowed until v1 is tagged.
License: CC-BY-4.0

This document defines how Ferry devices and servers talk to each other. Every
client (Android, iOS, third-party) and every server implementation must follow it.
The key words MUST, SHOULD and MAY are used as in RFC 2119.

---

## 1. Overview

```
 ┌──────────────┐  encrypted events   ┌──────────┐  opaque push   ┌──────────────┐   ┌───────────┐
 │ Source node  │ ──────────────────▶ │  Server  │ ─────────────▶ │ Push gateway │──▶│ APNs/FCM  │
 │ (Android)    │   HTTPS POST        │ (relay)  │                └──────────────┘   └─────┬─────┘
 └──────────────┘                     └────┬─────┘                                         │
                                           │ HTTPS pull / WebSocket                         ▼
                                           └─────────────────────────────────────▶ ┌──────────────┐
                                                                                   │  Receiver    │
                                                                                   │ (iOS/Android)│
                                                                                   └──────────────┘
```

- A **user** (account) owns one **User Master Key (UMK)**, held only on that user's devices.
- A **device** has one or both **roles**:
  - `source` reads SMS, calls and notifications, encrypts them, and uploads them. Android only.
  - `receiver` receives pushes, pulls events and decrypts them. Android or iOS.
- The **server** stores and forwards ciphertext. It never holds key material.
- The **push gateway** holds the APNs/FCM credentials for the official app builds and
  forwards opaque payloads. Self-hosted servers use it to reach official apps.

Each user is fully isolated. Users never share keys, events or devices.

### 1.1 Threat model

| Party | Can see | Cannot see |
|---|---|---|
| Server operator | user IDs, device IDs, roles, platforms, push tokens, event timestamps (receive time), padded ciphertext sizes, IP addresses | message contents, senders, phone numbers, app names, SIM info, device/SIM labels |
| Push gateway | push tokens, timing, padded payload sizes, which server sent the push | anything inside the payload |
| Network attacker | TLS metadata only | everything else |
| A paired device | everything for that user, including past events it fetched | other users' data |

Out of scope: a compromised paired device; a malicious client build; traffic analysis
from timing. The server **can** drop, delay or withhold events. It **cannot** forge,
modify, reorder between devices, or replay events without detection (see §3.4).

**Revocation caveat:** all devices of a user share the UMK. Revoking a device on the
server stops it from fetching new ciphertext, but it does not cryptographically lock
it out. Rotate the key (§5.5) if a device is lost or compromised.

---

## 2. Conventions

- Transport: HTTPS only (TLS 1.2+). Servers MUST reject plain HTTP outside `localhost`.
- Bodies: JSON, UTF-8, `Content-Type: application/json`.
- Binary values: **base64url without padding** (RFC 4648 §5), written `b64u` below.
- IDs: **UUIDv7**, lowercase, hyphenated. Clients generate event IDs; the server
  generates user and device IDs.
- Timestamps: integer **milliseconds since Unix epoch**, UTC.
- Unknown JSON fields MUST be ignored, so the protocol can be extended.
- Every API path is prefixed with `/v1`.

---

## 3. Cryptography

All primitives come from **libsodium** (swift-sodium on iOS, lazysodium-android on Android,
`libsodium-wrappers` for any JS tooling).

### 3.1 User Master Key (UMK)

- 32 random bytes from `randombytes_buf`.
- Generated once, on the first device of an account.
- Stored only in Android Keystore-wrapped storage or the iOS Keychain. On iOS, use an
  access group shared by the app and its Notification Service Extension, with
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
- Never sent to the server.

### 3.2 Key ID

So that key rotation can work, every ciphertext carries a key ID:

```
keyId = b64u( crypto_generichash(outlen = 16, message = "ferry-keyid-v1", key = UMK) )
```

(The UMK is the BLAKE2b key and the constant is the message, because BLAKE2b keys must
be at least 16 bytes.)

### 3.3 Subkeys

Each subkey is derived with `crypto_kdf_derive_from_key(32, subkeyId, context, UMK)`:

| Purpose | `context` (8 bytes) | `subkeyId` |
|---|---|---|
| Event payloads | `FR_EVENT` | 1 |
| Device profiles (names, SIM labels) | `FR_PROFL` | 1 |
| Recovery-kit integrity check | `FR_RECOV` | 1 |

### 3.4 AEAD

Algorithm: **XChaCha20-Poly1305-IETF** (`crypto_aead_xchacha20poly1305_ietf_*`).

- Nonce: 24 random bytes per message. Never reuse one.
- **Associated data (AD)** ties a ciphertext to its envelope, so the server cannot
  move it to another device, event or user. AD is the UTF-8 encoding of:

  ```
  event:    "ferry:v1:event:"   + userId + ":" + sourceDeviceId + ":" + eventId
  profile:  "ferry:v1:profile:" + userId + ":" + deviceId + ":" + profileVersion
  ```

  (`profileVersion` is a decimal integer.)

- Replay protection: receivers keep a set of seen `eventId`s (at least as long as the
  server's retention period) and discard duplicates. Since AD binds the ID, a replayed
  ciphertext cannot be relabelled with a fresh ID.

### 3.5 Padding

Before encryption, the plaintext is padded with `sodium_pad` (ISO/IEC 7816-4). Padding
always adds at least one byte, so a plaintext of length `n` goes into the smallest
**bucket** that is `≥ n + 1`:

```
256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536 bytes
```

Call `sodium_pad` with the bucket size as the block size. Above 65536, use block size
65536 (next multiple). The maximum padded plaintext is **256 KiB**; larger notification
content MUST be truncated by the source (§4.6).
After decrypting, receivers remove the padding with `sodium_unpad`.

### 3.6 Recovery kit (optional)

A user MAY export the UMK protected by a passphrase. The kit is **never uploaded**; the
user keeps it (prints it, or stores it in a password manager).

```
salt   = randombytes(16)
kek    = crypto_pwhash(32, passphrase, salt,
                       OPSLIMIT_MODERATE, MEMLIMIT_MODERATE, ALG_ARGON2ID13)
nonce  = randombytes(24)
ct     = xchacha20poly1305_encrypt(UMK, ad = "ferry:v1:recovery", nonce, kek)
kit    = "FRK1." + b64u(salt) + "." + b64u(nonce) + "." + b64u(ct)
```

Importing a kit yields the UMK. The device then pairs with the server as described in §5.3.

---

## 4. Event payloads (plaintext)

This is the JSON inside the ciphertext. The server never sees it.

### 4.1 Common fields

```json
{
  "t": "sms",
  "occurredAt": 1761292800000,
  "sim": { "slot": 0, "subscriptionId": 3, "carrier": "Vodafone", "number": "+491701234567" }
}
```

| Field | Type | Notes |
|---|---|---|
| `t` | string | `sms` · `call` · `notification` · `status` |
| `occurredAt` | int | when the event happened on the source device |
| `sim` | object? | present for `sms` and `call`. `slot` is 0-based. `number` and `carrier` are best-effort and MAY be null |

### 4.2 `sms`

```json
{ "t": "sms", "occurredAt": 0, "sim": {}, "from": "+4915112345678",
  "body": "Your code is 123456", "contactName": "Bank" }
```

- Multipart SMS MUST be reassembled into one event before upload.
- `contactName` is optional. It is resolved on the source if contacts permission was granted.
- `from` is always **the other party**: the sender of an incoming SMS, the recipient of an
  outgoing one.
- `direction` (optional, default `"in"`): `"in"` or `"out"`. Sources MAY relay SMS sent from the
  phone (by any SMS app) as `"out"`, with kind `silent`. Receivers SHOULD show them as sent
  messages and MUST NOT notify for them.
- `imported` (optional, default `false`): the message was copied from the phone's existing SMS
  history, not captured live. Sources MUST upload imported events with kind `silent` (§6.1), so
  they never cause a push. Receivers MUST NOT notify for them.
- Imported history arrives out of order (newest first, after newer live messages), so receivers
  MUST order conversations by `occurredAt`, not by `seq`.
- A source MUST NOT relay the same SMS twice: history covers incoming SMS received before live
  capture started and sent SMS older than its outgoing watermark.

### 4.3 `call`

```json
{ "t": "call", "occurredAt": 0, "sim": {}, "from": "+4915112345678",
  "state": "missed", "durationSec": 0, "contactName": null }
```

`state`: `ringing` · `missed` · `rejected` · `answered` · `ended`.
A source SHOULD send `ringing` in real time and MAY send a final `missed` or `ended`
event for the same call. Both events share the same `callKey` (a random string the source chooses), so receivers can merge them.

### 4.4 `notification`

```json
{ "t": "notification", "occurredAt": 0, "package": "com.whatsapp",
  "appName": "WhatsApp", "title": "Alice", "text": "See you at 8",
  "category": "msg", "key": "0|com.whatsapp|1|..." }
```

- Sources MUST let the user choose which apps are relayed (allowlist, empty by default).
- `key` is the Android notification key. Receivers MAY use it to collapse updates.
- Sources SHOULD drop group-summary notifications and ongoing (non-dismissible) notifications.

### 4.5 `status`

A source sends this heartbeat at least every 6 hours, and whenever its SIM state changes:

```json
{ "t": "status", "occurredAt": 0, "battery": 81, "charging": false,
  "sims": [ { "slot": 0, "subscriptionId": 3, "carrier": "Vodafone", "active": true } ],
  "permissions": { "sms": true, "notifications": true, "phone": true },
  "appVersion": "1.0.0" }
```

Status events MUST NOT trigger a visible push (§7.2).

### 4.6 Truncation

`body`, `text` and `title` MUST be truncated so the padded plaintext fits in 256 KiB.
When content is cut, the source sets `"truncated": true`.

---

## 5. Accounts, devices and pairing

### 5.1 Authentication

Each device has a **device token**: 32 random bytes generated by the server, returned
once, and sent as:

```
Authorization: Bearer <b64u token>
```

The server stores only `SHA-256(token)`. Tokens identify a device for **API access
only**. They play no part in E2E encryption.

### 5.2 Create an account (first device)

The client generates the UMK locally, then calls:

```
POST /v1/accounts
{ "device": { "roles": ["source"], "platform": "android", "appVersion": "1.0.0" },
  "keyId": "<b64u>", "invite": "optional-invite-code" }

201 { "userId": "...", "deviceId": "...", "token": "<b64u>" }
```

The server MAY require an `invite` (or another sign-up mechanism) on hosted instances.

### 5.3 Pair an additional device

1. A paired device requests a short-lived pairing code:

   ```
   POST /v1/pairing            (authenticated)
   { "roles": ["receiver"] }   // roles the new device may claim

   201 { "code": "<b64u 16 bytes>", "expiresAt": 1761293100000 }   // TTL: 5 minutes, single use
   ```

2. The device shows a QR code with this content:

   ```
   ferry://pair?v=1&s=<url-encoded server base URL>&u=<userId>&c=<code>&k=<b64u UMK>
   ```

   The QR code contains the UMK. Clients MUST show it only after local authentication
   (biometric or device PIN), MUST hide it once a device has paired or the code has
   expired, and MUST block screenshots of that screen where the platform allows it
   (`FLAG_SECURE`).

3. The new device scans the code, checks that `keyId` computed from `k` matches the
   account's (returned in step 4), stores the UMK, and registers:

   ```
   POST /v1/devices
   { "userId": "...", "code": "...", "roles": ["receiver"], "platform": "ios",
     "appVersion": "1.0.0" }

   201 { "deviceId": "...", "token": "<b64u>", "keyId": "<b64u>" }
   ```

   If `keyId` does not match, the client MUST discard everything and show an error.

A recovery-kit import (§3.6) still needs a pairing code from a paired device, or an
account-recovery mechanism specific to the server (out of scope for v1).

### 5.4 Device management

```
GET    /v1/devices                     → list of devices for this user
PATCH  /v1/devices/{id}                { "roles": [...] }                     (self only)
PUT    /v1/devices/{id}/push           { "provider": "apns"|"fcm"|"unifiedpush",
                                         "token": "...", "appId": "tech.thedevlab.ferry",
                                         "environment": "production"|"sandbox" }
PUT    /v1/devices/{id}/profile        { "version": 3, "keyId": "...", "nonce": "...", "ct": "..." }
DELETE /v1/devices/{id}                → revoke (any device of the user may revoke any other)
```

A device listing returns:

```json
{ "devices": [ {
  "id": "...", "roles": ["source"], "platform": "android", "appVersion": "1.0.0",
  "createdAt": 0, "lastSeenAt": 0,
  "profile": { "version": 3, "keyId": "...", "nonce": "...", "ct": "..." }
} ] }
```

The **profile** is encrypted with the profile subkey. Its plaintext is:

```json
{ "name": "Work phone", "sims": { "0": "Work SIM", "1": "Travel SIM" } }
```

Servers MUST reject a profile `PUT` whose `version` is not greater than the stored one.

`lastSeenAt` is plaintext metadata, so receivers can warn when a source goes quiet.

### 5.5 Key rotation

1. A device generates a new UMK and computes its new `keyId`.
2. It calls `POST /v1/keys/rotate { "keyId": "<new>" }`. The server records the new
   current `keyId`.
3. Remaining devices are re-paired by QR code, with the same flow as §5.3. Unpaired
   devices are revoked.
4. Receivers keep old UMKs (indexed by `keyId`) so they can still decrypt old events,
   until those events expire.

Sources MUST encrypt only with the current key. The server MUST reject events whose
`keyId` is not the account's current one, with `409 key_rotated`.

---

## 6. Events API

### 6.1 Envelope

```json
{
  "id": "0192c2a0-....",            // UUIDv7, generated by the source
  "keyId": "<b64u>",
  "nonce": "<b64u 24 bytes>",
  "ct": "<b64u ciphertext incl. 16-byte tag>",
  "kind": "alert"                   // "alert" | "silent"
}
```

`kind` is the only plaintext hint about content. It tells the server whether to send a
visible push (`alert` for sms, call and notification) or none (`silent` for status).

The server adds these fields when it stores the event:

```json
{ "seq": "184467", "sourceDeviceId": "...", "receivedAt": 1761292800123 }
```

`seq` is a per-user, strictly increasing integer, serialized as a string.

### 6.2 Upload (source)

```
POST /v1/events
{ "events": [ <envelope>, ... ] }     // 1–50 events, total request ≤ 2 MiB

200 { "accepted": ["id", ...], "duplicate": ["id", ...] }
```

- Upload is **idempotent** on `id`. A source keeps events in a persistent outbox and
  retries with exponential backoff (1 s up to 15 min, with jitter) until the server
  reports them as `accepted` or `duplicate`.
- Only devices with the `source` role may upload.

### 6.3 Pull (receiver)

```
GET /v1/events?after=<seq>&limit=100

200 { "events": [ <stored envelope>, ... ], "next": "<seq>", "hasMore": false }
```

Results are ordered by `seq` ascending. `after` is omitted on the first sync.

### 6.4 Acknowledge

```
POST /v1/events/ack
{ "upTo": "<seq>" }
```

The server records a per-receiver watermark. It MAY delete an event once **every**
active receiver has acknowledged it, and MUST delete events older than the retention
period (default **30 days**, configurable per server).

### 6.5 Live stream (optional)

```
GET /v1/stream          (WebSocket, Authorization header or ?token= query parameter)
```

Server → client:

```json
{ "type": "event", "event": <stored envelope> }
{ "type": "device", "deviceId": "...", "change": "added"|"revoked"|"profile" }
{ "type": "ping" }
```

Client → server:

```json
{ "type": "ack", "upTo": "<seq>" }
{ "type": "pong" }
```

A client using the stream MUST still run `GET /v1/events` after connecting, to fill any
gap since its last watermark.

---

## 7. Push

### 7.1 When to push

For each stored event of kind `alert`, the server sends one push to every active device
of that user that has the `receiver` role and a registered push token, **except** the
source device itself. Status events (kind `silent`) MAY trigger a background push, but
never a visible one.

### 7.2 Payload sent to the device

Inline mode is used when `len(b64u(ct)) ≤ 3000`. With the 2048-byte padding bucket,
this covers almost every SMS:

```json
{
  "aps": {
    "alert": { "title": "Ferry", "body": "New message" },
    "mutable-content": 1,
    "sound": "default"
  },
  "sr": { "v": 1, "u": "<userId>", "d": "<sourceDeviceId>", "e": <stored envelope> }
}
```

Fetch mode is used for larger events:

```json
{ "aps": { ...same as above... },
  "sr": { "v": 1, "u": "<userId>", "id": "<eventId>" } }
```

In fetch mode, the receiver calls `GET /v1/events/{id}`.

The `aps` alert text is a **fixed placeholder**. It MUST NOT contain any event data.
For FCM, the same `sr` object is sent as a **data-only** message (no `notification`
block), with the JSON serialized under the key `sr`.

### 7.3 Receiver handling

**iOS (Notification Service Extension):**

1. Read the UMK from the shared Keychain group.
2. Inline mode: decrypt `sr.e`. Fetch mode: fetch the event with the device token
   (also in the shared Keychain), then decrypt it.
3. Replace the title and body, for example `"Bank · SIM 1 (Work phone)"` /
   `"Your code is 123456"`. Set `threadIdentifier` per source device and sender.
4. Write the decrypted event to the shared App Group store, so the app shows it
   without re-fetching.
5. On any failure (timeout, decrypt error, memory pressure), leave the placeholder text
   in place. The app catches up on its next pull.

**Android receiver:** `FirebaseMessagingService` or a UnifiedPush receiver decrypts the
event and posts a local notification, following the same steps.

Push and pull can deliver the same event twice. Receivers deduplicate by `id`.

### 7.4 Push gateway contract

**Optional in v1.** A server MAY send pushes to APNs/FCM directly, using credentials
for an app build it controls. The gateway exists so that self-hosted servers can reach
app builds signed by someone else. When a gateway is used, it is configurable, and the
official one (if any) is `https://push.ferry.thedevlab.cloud`.

```
POST /v1/push
Authorization: Bearer <server API key>
{
  "provider": "apns" | "fcm",
  "appId": "tech.thedevlab.ferry",
  "environment": "production" | "sandbox",
  "token": "<device push token>",
  "priority": "high" | "normal",
  "collapseId": "<optional, ≤ 64 bytes>",
  "payload": { ... }          // exactly the JSON from §7.2, passed through unchanged
}

202 { "status": "queued" }
410 { "error": { "code": "token_invalid" } }     // server MUST clear this token
429 { "error": { "code": "rate_limited", "retryAfter": 30 } }
```

- The gateway is **stateless**. It MUST NOT log payloads, and SHOULD log only counts and errors.
- The gateway MUST reject payloads larger than 4096 bytes when serialized.
- UnifiedPush tokens are endpoint URLs. Servers POST the §7.2 `sr` object straight to
  them and skip the gateway.
- Self-hosters building their own iOS app with their own Apple team can run their own
  gateway instance using their own credentials.

---

## 8. Errors

Error responses have this shape:

```json
{ "error": { "code": "snake_case_code", "message": "Human readable" } }
```

| HTTP | code | Meaning |
|---|---|---|
| 400 | `invalid_request` | schema validation failed |
| 401 | `unauthorized` | missing, invalid or revoked token |
| 403 | `forbidden` | role not allowed (e.g. a receiver uploading events) |
| 404 | `not_found` | |
| 409 | `key_rotated` | event `keyId` is not current |
| 409 | `profile_stale` | profile `version` is not greater than the stored one |
| 410 | `pairing_expired` | pairing code used or expired |
| 413 | `too_large` | request or event exceeds limits |
| 429 | `rate_limited` | see the `Retry-After` header |
| 500 | `internal` | unexpected server error |

Default rate limits (servers MAY change them): 600 uploaded events per device per hour,
120 pulls per device per hour, 10 pairing codes per user per hour.

---

## 9. Platform requirements (normative)

These apply to official builds and SHOULD apply to third-party clients:

- A source device MUST show a **persistent, non-dismissible notification** while relaying.
- A source app MUST NOT offer to hide its launcher icon, and MUST be set up on the device itself.
- A source app MUST list all paired devices, and let the user revoke any of them from that screen.
- Clients MUST use the platform's secure storage for the UMK and the device token.

### 9.1 Receiver storage

Decrypted events stored on a receiver are as sensitive as the messages themselves.

- Receivers MUST encrypt their local message store at rest with a random 256-bit key that is
  separate from the UMK, kept in the Keychain / Android Keystore, and never synced or backed up
  (`…ThisDeviceOnly`). SQLCipher (whole-database) is the recommended approach.
- The store and its key SHOULD use "after first unlock" protection, so the iOS Notification
  Service Extension can save messages while the phone is locked. Stricter classes break that.
- The store MUST be excluded from cloud and computer backups.
- When the server answers `401 unauthorized` (the device was revoked), the receiver MUST delete
  its local events, the UMKs and the device token.
- Receivers SHOULD offer an app lock (biometric / device passcode) and a local retention period.

---

## 10. Versioning

- The path prefix `/v1` and the `v` field in push payloads identify the protocol version.
- Additive changes (new optional fields, new event types, new endpoints) do not bump the version.
- Receivers MUST ignore unknown event types `t`, showing them as "Unsupported event" at most.

---

## 11. Test vectors

[`docs/test-vectors/v1.json`](test-vectors/v1.json) is generated by the reference
implementation in `packages/crypto-ts` (`pnpm vectors`). It contains a fixed UMK, the
`keyId`, all subkeys, padding lengths at each bucket boundary, and event and profile
ciphertexts with fixed nonces (ASCII, Arabic + emoji, and a large payload).

Every implementation MUST reproduce the `keyId`, the subkeys and each `ct` byte for
byte, and MUST decrypt each `ct` to the exact `plaintext` string.

---

## Appendix A — Open questions

1. **Reply/send SMS from a receiver**: needs a command channel to sources
   (receiver → server → source). Planned for v2.
2. **Server-side account recovery** when all devices are lost: currently impossible by
   design, unless the user kept a recovery kit.
3. **Multiple users sharing one source device** (e.g. family): not supported in v1. One
   device belongs to exactly one user.
4. **Per-device keys** (asymmetric, so revocation is cryptographic): considered for v2.
   It would replace the shared UMK with key wrapping per receiver.
