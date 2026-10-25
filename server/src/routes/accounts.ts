// §5.2 account creation, §5.3 pairing and the §5.6 node-pairing mailbox.

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.ts';
import { isNode, type DeviceRow, type Role, type UserRow } from '../db.ts';
import { ApiError, hashToken, KEY_ID, S, uuidv7 } from '../util.ts';
import { insertDevice, type NewDevice } from './devices.ts';

const PAIRING_TTL_MS = 5 * 60_000;

const deviceProps = { roles: S.roles, platform: S.platform, appVersion: S.appVersion };

const KEY32 = S.b64u(43, 43);

/** A §5.6 mailbox message: a public key and the MAC that proves who sent it. */
interface PairingMessage {
  deviceId: string;
  publicKey: string;
  mac: string;
}

interface PairingRow {
  user_id: string;
  created_by: string | null;
  claimed_by: string | null;
  owner_msg: string | null;
  claimer_msg: string | null;
}

function sameSecret(a: string | undefined, b: string): boolean {
  if (a === undefined) return false;
  const h = (s: string) => createHash('sha256').update(s).digest();
  return timingSafeEqual(h(a), h(b));
}

export function accountRoutes(app: FastifyInstance, { db, config, now, auth, role }: Ctx): void {
  // A node creates the account without a key ID; its first receiver sets it (§5.6 flow A).
  app.post<{ Body: { device: NewDevice; keyId?: string; invite?: string } }>(
    '/v1/accounts',
    {
      schema: {
        body: {
          type: 'object',
          required: ['device'],
          properties: {
            device: { type: 'object', required: ['roles', 'platform'], properties: deviceProps },
            keyId: KEY_ID,
            invite: { type: 'string', maxLength: 200 },
          },
        },
      },
    },
    async (req, reply) => {
      const { invite } = req.body;
      // With INVITE_CODE set, sign-up takes either that shared code or a one-time code from src/invite.ts.
      const needsOneTime = config.inviteCode !== undefined && !sameSecret(invite, config.inviteCode);
      const t = now();
      const userId = uuidv7(t);
      const created = await db.tx(async (q) => {
        await q.query('INSERT INTO users (id, key_id, created_at) VALUES ($1, $2, $3)', [userId, req.body.keyId ?? null, t]);
        if (needsOneTime) {
          const claimed = invite
            ? await q.query(
                `UPDATE invites SET used_at = $2, used_by = $3
                 WHERE code_hash = $1 AND used_at IS NULL AND expires_at >= $2
                 RETURNING 1`,
                [hashToken(invite), t, userId],
              )
            : { rows: [] };
          if (claimed.rows.length === 0) throw new ApiError(403, 'forbidden', 'Invalid invite code');
        }
        return insertDevice(q, userId, req.body.device, t);
      });
      reply.code(201);
      return { userId, ...created };
    },
  );

  app.post<{ Body: { roles: Role[] } }>(
    '/v1/pairing',
    {
      preHandler: auth,
      schema: { body: { type: 'object', required: ['roles'], properties: { roles: S.roles } } },
    },
    async (req, reply) => {
      // A node may introduce other nodes, and pair the account's first receiver. Once the account
      // has a key, only receivers can add receivers (§5.6).
      if (isNode(req.device) && req.body.roles.includes('receiver')) {
        const [user] = (await db.query<Pick<UserRow, 'key_id'>>('SELECT key_id FROM users WHERE id = $1', [req.device.user_id])).rows;
        if (user.key_id !== null) throw new ApiError(403, 'forbidden', 'Only receivers can pair receivers once the account has a key');
      }
      const code = randomBytes(16).toString('base64url');
      const expiresAt = now() + PAIRING_TTL_MS;
      await db.query('INSERT INTO pairing_codes (code_hash, user_id, roles, expires_at, created_by) VALUES ($1, $2, $3, $4, $5)', [
        hashToken(code),
        req.device.user_id,
        JSON.stringify(req.body.roles),
        expiresAt,
        req.device.id,
      ]);
      reply.code(201);
      return { code, expiresAt };
    },
  );

  app.post<{ Body: NewDevice & { userId: string; code: string } }>(
    '/v1/devices',
    {
      schema: {
        body: {
          type: 'object',
          required: ['userId', 'code', 'roles', 'platform'],
          properties: { userId: S.uuid, code: S.b64u(22, 22), ...deviceProps },
        },
      },
    },
    async (req, reply) => {
      const { userId, code, ...device } = req.body;
      const t = now();
      const created = await db.tx(async (q) => {
        // Claiming the code in one UPDATE keeps two devices from racing on the same code.
        const [row] = (
          await q.query<{ roles: string }>(
            `UPDATE pairing_codes SET used_at = $3
             WHERE code_hash = $1 AND user_id = $2 AND used_at IS NULL AND expires_at >= $3
             RETURNING roles`,
            [hashToken(code), userId, t],
          )
        ).rows;
        if (!row) throw new ApiError(410, 'pairing_expired', 'Pairing code is invalid, used or expired');

        const allowed: Role[] = JSON.parse(row.roles);
        if (!device.roles.every((r) => allowed.includes(r))) {
          throw new ApiError(403, 'forbidden', `Pairing code only allows roles: ${allowed.join(', ')}`);
        }
        const created = await insertDevice(q, userId, device, t);
        await q.query('UPDATE pairing_codes SET claimed_by = $1 WHERE code_hash = $2', [created.deviceId, hashToken(code)]);
        return created;
      });
      const [user] = (await db.query<Pick<UserRow, 'key_id'>>('SELECT key_id FROM users WHERE id = $1', [userId])).rows;
      reply.code(201);
      return { ...created, keyId: user.key_id };
    },
  );

  // §5.6: the first receiver sets the account key ID, once.
  app.post<{ Body: { keyId: string } }>(
    '/v1/keys/initial',
    {
      preHandler: [auth, role('receiver')],
      schema: { body: { type: 'object', required: ['keyId'], properties: { keyId: KEY_ID } } },
    },
    async (req, reply) => {
      const { rows } = await db.query('UPDATE users SET key_id = $1 WHERE id = $2 AND key_id IS NULL RETURNING id', [
        req.body.keyId,
        req.device.user_id,
      ]);
      if (rows.length === 0) throw new ApiError(409, 'key_set', 'The account already has a key');
      reply.code(204);
    },
  );

  // §5.6 mailbox. The device that made the code and the device that used it each leave one
  // message for the other. The server can't forge one: each carries a MAC under a secret that
  // only travels in the QR code.
  const findPairing = async (code: string, device: DeviceRow) => {
    const [row] = (
      await db.query<PairingRow>(
        'SELECT user_id, created_by, claimed_by, owner_msg, claimer_msg FROM pairing_codes WHERE code_hash = $1 AND user_id = $2',
        [hashToken(code), device.user_id],
      )
    ).rows;
    const side = row?.created_by === device.id ? 'owner' : row?.claimed_by === device.id ? 'claimer' : null;
    if (!row || !side) throw new ApiError(404, 'not_found', 'Pairing code not found or expired');
    return { row, side } as const;
  };
  const codeParam = { type: 'object', required: ['code'], properties: { code: S.b64u(22, 22) } };

  app.post<{ Params: { code: string }; Body: PairingMessage }>(
    '/v1/pairing/:code/reply',
    {
      preHandler: auth,
      schema: {
        params: codeParam,
        body: {
          type: 'object',
          required: ['deviceId', 'publicKey', 'mac'],
          properties: { deviceId: S.uuid, publicKey: KEY32, mac: KEY32 },
        },
      },
    },
    async (req, reply) => {
      const { side } = await findPairing(req.params.code, req.device);
      const column = side === 'owner' ? 'owner_msg' : 'claimer_msg';
      const { deviceId, publicKey, mac } = req.body;
      const { rows } = await db.query(
        `UPDATE pairing_codes SET ${column} = $1 WHERE code_hash = $2 AND ${column} IS NULL RETURNING 1`,
        [JSON.stringify({ deviceId, publicKey, mac }), hashToken(req.params.code)],
      );
      if (rows.length === 0) throw new ApiError(409, 'pairing_replied', 'This device already replied');
      reply.code(204);
    },
  );

  app.get<{ Params: { code: string } }>(
    '/v1/pairing/:code/reply',
    { preHandler: auth, schema: { params: codeParam } },
    async (req) => {
      const { row, side } = await findPairing(req.params.code, req.device);
      const msg = side === 'owner' ? row.claimer_msg : row.owner_msg;
      return { message: msg === null ? null : (JSON.parse(msg) as PairingMessage) };
    },
  );
}
