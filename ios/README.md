# Ferry for iOS

Swift + SwiftUI receiver with a Notification Service Extension. iOS 18+.

- App: `tech.thedevlab.ferry`
- NSE: `tech.thedevlab.ferry.nse`
- App group / keychain group: `group.tech.thedevlab.ferry`

## Build

```sh
cd ios
xcodegen                 # generates Ferry.xcodeproj from project.yml (not committed)
open Ferry.xcodeproj
```

Tests (crypto vectors from `docs/test-vectors/v1.json`, pairing links, inbox ingest):

```sh
xcodebuild -project Ferry.xcodeproj -scheme Ferry -destination 'platform=iOS Simulator,name=iPhone 18 Pro' test
```

Build signed (the default) even on the simulator: the Keychain and App Group need entitlements.

## Layout

| Target | What |
|---|---|
| `FerryKit` | Shared framework: crypto (pure Swift BLAKE2b + HChaCha20 over CryptoKit ChaChaPoly, byte-compatible with libsodium), models, API client, Keychain account, SQLite inbox in the App Group, ingest/sync |
| `Ferry` | The app: onboarding (create account or join by QR / pasted link / `ferry://pair` link), inbox, threads, devices (rename SIMs, revoke, source health), pairing QR behind Face ID |
| `FerryNSE` | Decrypts pushes (inline or fetch mode, §7.2), rewrites the notification, files the event into the shared inbox |
| `FerryUITests` | Opt-in end-to-end pairing test against a live server (skipped unless `FERRY_PAIR_LINK` is set) |

The NSE and the app share one SQLite file in the App Group. Connections are opened per operation,
so no file lock is held while suspended (0xdead10cc).

## Not implemented yet

Recovery kit import (§3.6), key rotation UI (§5.5), WebSocket stream (§6.5), background app refresh.
