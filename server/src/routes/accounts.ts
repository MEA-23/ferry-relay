// §5.2 account creation and §5.3 pairing.

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.ts';
import type { Role, UserRow } from '../db.ts';
import { ApiError, hashToken, KEY_ID, S, uuidv7 } from '../util.ts';
import { insertDevice, type NewDevice } from './devices.ts';

const PAIRING_TTL_MS = 5 * 60_000;

const deviceProps = { roles: S.roles, platform: S.platform, appVersion: S.appVersion };

function sameSecret(a: string | undefined, b: string): boolean {
  if (a === undefined) return false;
  const h = (s: string) => createHash('sha256').update(s).digest();
  return timingSafeEqual(h(a), h(b));
}

export function accountRoutes(app: FastifyInstance, { db, config, now, auth }: Ctx): void {
  app.post<{ Body: { device: NewDevice; keyId: string; invite?: string } }>(
    '/v1/accounts',
    {
      schema: {
        body: {
          type: 'object',
          required: ['device', 'keyId'],
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
        await q.query('INSERT INTO users (id, key_id, created_at) VALUES ($1, $2, $3)', [userId, req.body.keyId, t]);
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
      const code = randomBytes(16).toString('base64url');
      const expiresAt = now() + PAIRING_TTL_MS;
      await db.query('INSERT INTO pairing_codes (code_hash, user_id, roles, expires_at) VALUES ($1, $2, $3, $4)', [
        hashToken(code),
        req.device.user_id,
        JSON.stringify(req.body.roles),
        expiresAt,
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
        return insertDevice(q, userId, device, t);
      });
      const [user] = (await db.query<Pick<UserRow, 'key_id'>>('SELECT key_id FROM users WHERE id = $1', [userId])).rows;
      reply.code(201);
      return { ...created, keyId: user.key_id };
    },
  );
}
