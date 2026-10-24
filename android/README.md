# Ferry for Android

Kotlin + Jetpack Compose. Source node (relays SMS, calls, chosen notifications) and/or receiver.

- `:crypto` — pure-Kotlin Ferry crypto on BouncyCastle, tested against `docs/test-vectors`
- `:app` — capture (SMS/call receivers, notification listener), encrypted outbox + WorkManager upload,
  foreground relay service, pairing (QR via ZXing + CameraX), and the receiver inbox: conversations,
  search, push (UnifiedPush or FCM) with a 15-minute pull as the fallback

App ID: `tech.thedevlab.ferry`. Debug builds allow plain HTTP to `10.0.2.2` for a local server.

```sh
./gradlew :crypto:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Receiver push

A push only wakes the app; it then pulls and decrypts as usual (§7). Two services are supported:

- **UnifiedPush** — no setup in the build. On the phone, install a distributor app such as
  [ntfy](https://ntfy.sh) and tap *Turn on* in the inbox. Used whenever a distributor is set up.
- **Firebase Cloud Messaging** — optional. Put your Firebase project's `google-services.json` in
  `android/app/` (it's gitignored; the build reads it directly, no Google Services plugin), and set
  `FCM_SERVICE_ACCOUNT_PATH` on the server. Used when no UnifiedPush distributor is set up.

Without either, receivers check every 15 minutes and whenever Ferry opens.
