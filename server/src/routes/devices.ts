// §5.4 device management and §5.5 key rotation.

import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.ts';
import { isNode, rolesOf, type DeviceRow, type Queryable, type Role } from '../db.ts';
import { ApiError, hashToken, KEY_ID, newToken, NONCE, S, uuidv7 } from '../util.ts';

export interface NewDevice {
  roles: Role[];
  platform: 'android' | 'ios';
  appVersion?: string;
}

export async function insertDevice(db: Queryable, userId: string, d: NewDevice, now: number) {
  if (d.roles.includes('source') && d.platform !== 'android') {
    throw new ApiError(400, 'invalid_request', 'Only Android devices can be sources');
  }
  const deviceId = uuidv7(now);
  const token = newToken();
  await db.query(
    `INSERT INTO devices (id, user_id, roles, platform, app_version, token_hash, created_at, last_seen_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
    [deviceId, userId, JSON.stringify(d.roles), d.platform, d.appVersion ?? null, hashToken(token), now, now],
  );
  return { deviceId, token };
}

function serializeDevice(d: DeviceRow) {
  return {
    id: d.id,
    roles: rolesOf(d),
    platform: d.platform,
    appVersion: d.app_version,
    createdAt: d.created_at,
    lastSeenAt: d.last_seen_at,
    profile:
      d.profile_version === null
        ? null
        : { version: d.profile_version, keyId: d.profile_key_id, nonce: d.profile_nonce, ct: d.profile_ct },
  };
}

const idParam = { type: 'object', required: ['id'], properties: { id: S.uuid } };

interface PushBody {
  provider: 'apns' | 'fcm' | 'unifiedpush';
  token: string;
  appId: string;
  environment: 'production' | 'sandbox';
}

function validatePushToken({ provider, token }: PushBody): void {
  const ok =
    provider === 'apns'
      ? /^[0-9a-fA-F]{64,200}$/.test(token)
      : provider === 'fcm'
        ? /^[\w:.-]{20,4096}$/.test(token)
        : URL.canParse(token) && new URL(token).protocol === 'https:';
  if (!ok) throw new ApiError(400, 'invalid_request', `Malformed ${provider} token`);
}

export function deviceRoutes(app: FastifyInstance, { db, now, auth, role }: Ctx): void {
  const findOwn = async (userId: string, id: string): Promise<DeviceRow | undefined> =>
    (await db.query<DeviceRow>('SELECT * FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL', [id, userId]))
      .rows[0];

  const requireSelf = (req: { device: DeviceRow; params: { id: string } }) => {
    if (req.params.id !== req.device.id) throw new ApiError(403, 'forbidden', 'Devices can only change their own settings');
  };

  app.get('/v1/devices', { preHandler: auth }, async (req) => {
    const { rows } = await db.query<DeviceRow>(
      'SELECT * FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at',
      [req.device.user_id],
    );
    return { devices: rows.map(serializeDevice) };
  });

  app.patch<{ Params: { id: string }; Body: { roles: Role[] } }>(
    '/v1/devices/:id',
    {
      preHandler: auth,
      schema: { params: idParam, body: { type: 'object', required: ['roles'], properties: { roles: S.roles } } },
    },
    async (req) => {
      requireSelf(req);
      if (req.body.roles.includes('source') && req.device.platform !== 'android') {
        throw new ApiError(400, 'invalid_request', 'Only Android devices can be sources');
      }
      // A receiver needs the UMK, which only comes with pairing (§5.3).
      if (req.body.roles.includes('receiver') && isNode(req.device)) {
        throw new ApiError(403, 'forbidden', 'A device can only become a receiver by pairing');
      }
      await db.query('UPDATE devices SET roles = $1 WHERE id = $2', [JSON.stringify(req.body.roles), req.device.id]);
      return serializeDevice((await findOwn(req.device.user_id, req.device.id))!);
    },
  );

  app.put<{ Params: { id: string }; Body: PushBody }>(
    '/v1/devices/:id/push',
    {
      preHandler: auth,
      schema: {
        params: idParam,
        body: {
          type: 'object',
          required: ['provider', 'token', 'appId'],
          properties: {
            provider: { type: 'string', enum: ['apns', 'fcm', 'unifiedpush'] },
            token: { type: 'string', minLength: 1, maxLength: 4096 },
            appId: { type: 'string', pattern: '^[A-Za-z0-9.-]{3,255}$' },
            environment: { type: 'string', enum: ['production', 'sandbox'], default: 'production' },
          },
        },
      },
    },
    async (req, reply) => {
      requireSelf(req);
      validatePushToken(req.body);
      const { provider, token, appId, environment } = req.body;
      await db.query('UPDATE devices SET push_provider = $1, push_token = $2, push_app_id = $3, push_env = $4 WHERE id = $5', [
        provider,
        token,
        appId,
        environment,
        req.device.id,
      ]);
      reply.code(204);
    },
  );

  // §3.7: a node registers the key ID of its own root, once it knows the account public key.
  app.put<{ Params: { id: string }; Body: { keyId: string } }>(
    '/v1/devices/:id/key',
    {
      preHandler: auth,
      schema: { params: idParam, body: { type: 'object', required: ['keyId'], properties: { keyId: KEY_ID } } },
    },
    async (req, reply) => {
      requireSelf(req);
      if (!isNode(req.device)) throw new ApiError(403, 'forbidden', 'Only source-only devices have their own key');
      await db.query('UPDATE devices SET key_id = $1 WHERE id = $2', [req.body.keyId, req.device.id]);
      reply.code(204);
    },
  );

  app.put<{ Params: { id: string }; Body: { version: number; keyId: string; nonce: string; ct: string } }>(
    '/v1/devices/:id/profile',
    {
      // Profiles are sealed with the UMK, which nodes don't have (§3.7).
      preHandler: [auth, role('receiver')],
      schema: {
        params: idParam,
        body: {
          type: 'object',
          required: ['version', 'keyId', 'nonce', 'ct'],
          properties: {
            version: { type: 'integer', minimum: 1, maximum: Number.MAX_SAFE_INTEGER },
            keyId: KEY_ID,
            nonce: NONCE,
            ct: S.b64u(1, 8192),
          },
        },
      },
    },
    async (req, reply) => {
      // Any receiver may name any device (e.g. naming a source from the iPhone).
      const target = await findOwn(req.device.user_id, req.params.id);
      if (!target) throw new ApiError(404, 'not_found', 'Device not found');
      const { version, keyId, nonce, ct } = req.body;
      const { rows } = await db.query(
        `UPDATE devices SET profile_version = $1, profile_key_id = $2, profile_nonce = $3, profile_ct = $4
         WHERE id = $5 AND (profile_version IS NULL OR profile_version < $1)
         RETURNING id`,
        [version, keyId, nonce, ct, target.id],
      );
      if (rows.length === 0) throw new ApiError(409, 'profile_stale', 'Profile version must increase');
      reply.code(204);
    },
  );

  app.delete<{ Params: { id: string } }>(
    '/v1/devices/:id',
    { preHandler: auth, schema: { params: idParam } },
    async (req, reply) => {
      const target = await findOwn(req.device.user_id, req.params.id);
      if (!target) throw new ApiError(404, 'not_found', 'Device not found');
      if (isNode(req.device) && !isNode(target)) throw new ApiError(403, 'forbidden', 'Nodes cannot remove receivers');
      await db.query('UPDATE devices SET revoked_at = $1, push_token = NULL WHERE id = $2', [now(), target.id]);
      reply.code(204);
    },
  );

  app.post<{ Body: { keyId: string } }>(
    '/v1/keys/rotate',
    {
      preHandler: [auth, role('receiver')],
      schema: { body: { type: 'object', required: ['keyId'], properties: { keyId: KEY_ID } } },
    },
    async (req, reply) => {
      // A new UMK means a new account public key, so every node root goes stale until it re-pairs.
      await db.tx(async (q) => {
        await q.query('UPDATE users SET key_id = $1 WHERE id = $2', [req.body.keyId, req.device.user_id]);
        await q.query('UPDATE devices SET key_id = NULL WHERE user_id = $1', [req.device.user_id]);
      });
      reply.code(204);
    },
  );
}
