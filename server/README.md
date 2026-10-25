# Ferry server

Relay server for Ferry: stores ciphertext, fans out pushes, never sees plaintext.
Implements [docs/PROTOCOL.md](../docs/PROTOCOL.md) §5–§7. Fastify + TypeScript on Node 24, Postgres via `pg` (connection from `DATABASE_URL`).

## Run

```sh
cp .env.example .env    # fill in DATABASE_URL, INVITE_CODE and APNs values
pnpm start              # or: pnpm dev
pnpm test               # runs on in-process PGlite, no database needed
```

The schema is created on startup (`CREATE TABLE IF NOT EXISTS`).

## Invites

With `INVITE_CODE` set, sign-up takes that shared code or a one-time invite. To create invites,
run this inside the server container:

```sh
node --disable-warning=ExperimentalWarning src/invite.ts 3 7   # 3 codes, valid 7 days
```

## Deploy

```sh
docker build -f server/Dockerfile -t ferry-server .    # from repo root
docker run -d --network <proxy-network> --env-file server/.env \
  -v /path/to/AuthKey.p8:/secrets/apns.p8:ro -e APNS_KEY_PATH=/secrets/apns.p8 ferry-server
```

Put it behind TLS (the protocol requires HTTPS) at `https://ferry.thedevlab.cloud`.

## Not implemented yet

Rate limits (§8), push gateway (§7.4).
