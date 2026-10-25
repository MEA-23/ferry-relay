import Fastify, { type FastifyInstance, type FastifyRequest, type FastifyServerOptions } from 'fastify';
import type { Config } from './config.ts';
import { rolesOf, type Db, type DeviceRow, type Role } from './db.ts';
import { createPusher, type Pusher } from './push/index.ts';
import { accountRoutes } from './routes/accounts.ts';
import { commandRoutes } from './routes/commands.ts';
import { deviceRoutes } from './routes/devices.ts';
import { eventRoutes } from './routes/events.ts';
import { ApiError, hashToken } from './util.ts';

declare module 'fastify' {
  interface FastifyRequest {
    device: DeviceRow;
  }
}

export interface AppOptions {
  db: Db;
  config: Config;
  pusher?: Pusher;
  now?: () => number;
  logger?: FastifyServerOptions['logger'];
}

type PreHandler = (req: FastifyRequest) => Promise<void>;

export interface Ctx {
  db: Db;
  config: Config;
  pusher: Pusher;
  now: () => number;
  auth: PreHandler;
  role: (role: Role) => PreHandler;
}

const BEARER = /^Bearer ([A-Za-z0-9_-]{43})$/;

export function buildApp(opts: AppOptions): FastifyInstance {
  const app = Fastify({
    logger: opts.logger ?? false,
    bodyLimit: 2.5 * 1024 * 1024,
    trustProxy: opts.config.trustProxy,
  });
  const { db } = opts;
  const now = opts.now ?? Date.now;
  const pusher = opts.pusher ?? createPusher(opts.config, app.log);

  const auth: PreHandler = async (req) => {
    const token = req.headers.authorization?.match(BEARER)?.[1];
    const device =
      token &&
      (await db.query<DeviceRow>('SELECT * FROM devices WHERE token_hash = $1 AND revoked_at IS NULL', [hashToken(token)]))
        .rows[0];
    if (!device) throw new ApiError(401, 'unauthorized', 'Missing, invalid or revoked token');
    const t = now();
    if (t - device.last_seen_at > 60_000) {
      await db.query('UPDATE devices SET last_seen_at = $1 WHERE id = $2', [t, device.id]);
      device.last_seen_at = t;
    }
    req.device = device;
  };

  const role = (r: Role): PreHandler => async (req) => {
    if (!rolesOf(req.device).includes(r)) throw new ApiError(403, 'forbidden', `Requires the ${r} role`);
  };

  app.decorateRequest('device', null as unknown as DeviceRow);

  app.setErrorHandler((err: Error & { statusCode?: number; validation?: unknown }, req, reply) => {
    if (err instanceof ApiError) {
      return reply.code(err.statusCode).send({ error: { code: err.code, message: err.message } });
    }
    if (err.validation) {
      return reply.code(400).send({ error: { code: 'invalid_request', message: err.message } });
    }
    if (err.statusCode === 413) {
      return reply.code(413).send({ error: { code: 'too_large', message: err.message } });
    }
    if (err.statusCode && err.statusCode < 500) {
      return reply.code(400).send({ error: { code: 'invalid_request', message: err.message } });
    }
    req.log.error(err);
    return reply.code(500).send({ error: { code: 'internal', message: 'Internal server error' } });
  });

  app.setNotFoundHandler((_req, reply) => {
    reply.code(404).send({ error: { code: 'not_found', message: 'Not found' } });
  });

  app.addHook('onClose', async () => pusher.close());

  app.get('/healthz', async () => ({ ok: true }));

  const ctx: Ctx = { db, config: opts.config, pusher, now, auth, role };
  accountRoutes(app, ctx);
  deviceRoutes(app, ctx);
  eventRoutes(app, ctx);
  commandRoutes(app, ctx);

  return app;
}
