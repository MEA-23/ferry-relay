// §6 events API and §7.1 push fan-out.

import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.ts';
import { activeReceivers, prune, type EventRow, type UserRow } from '../db.ts';
import { buildSr, serializeEvent, type PushTarget } from '../push/index.ts';
import { ApiError, KEY_ID, NONCE, S } from '../util.ts';

// Max padded plaintext 256 KiB + 16-byte tag, base64url-encoded.
const CT_MAX = Math.ceil(((256 * 1024 + 16) * 4) / 3);

interface Envelope {
  id: string;
  keyId: string;
  nonce: string;
  ct: string;
  kind: 'alert' | 'silent';
}

const envelopeSchema = {
  type: 'object',
  required: ['id', 'keyId', 'nonce', 'ct', 'kind'],
  properties: {
    id: S.uuid,
    keyId: KEY_ID,
    nonce: NONCE,
    ct: S.b64u(1, CT_MAX),
    kind: { type: 'string', enum: ['alert', 'silent'] },
  },
};

export function eventRoutes(app: FastifyInstance, { db, config, pusher, now, auth, role }: Ctx): void {
  async function fanOut(userId: string, sourceId: string, events: EventRow[]): Promise<void> {
    const alerts = events.filter((e) => e.kind === 'alert');
    if (alerts.length === 0) return;
    const targets = (await activeReceivers(db, userId)).filter((d) => d.id !== sourceId && d.push_token && d.push_provider);
    for (const e of alerts) {
      const sr = buildSr(e);
      for (const d of targets) {
        const target: PushTarget = {
          provider: d.push_provider as PushTarget['provider'],
          token: d.push_token!,
          appId: d.push_app_id ?? '',
          environment: (d.push_env as PushTarget['environment']) ?? 'production',
        };
        pusher.send(target, sr).then(
          (result) => {
            if (result === 'invalid_token') {
              return db.query('UPDATE devices SET push_token = NULL WHERE id = $1 AND push_token = $2', [d.id, target.token]);
            }
          },
          (err) => app.log.warn({ err }, 'push failed'),
        );
      }
    }
  }

  app.post<{ Body: { events: Envelope[] } }>(
    '/v1/events',
    {
      preHandler: [auth, role('source')],
      bodyLimit: 2 * 1024 * 1024,
      schema: {
        body: {
          type: 'object',
          required: ['events'],
          properties: { events: { type: 'array', items: envelopeSchema, minItems: 1, maxItems: 50 } },
        },
      },
    },
    async (req) => {
      const { user_id: userId, id: sourceId } = req.device;
      const t = now();
      const stored: EventRow[] = [];
      const duplicate: string[] = [];

      await db.tx(async (q) => {
        // Locking the user row serializes concurrent uploads for this account.
        const [user] = (await q.query<Pick<UserRow, 'key_id'>>('SELECT key_id FROM users WHERE id = $1 FOR UPDATE', [userId])).rows;
        if (req.body.events.some((e) => e.keyId !== user.key_id)) {
          throw new ApiError(409, 'key_rotated', 'Event keyId is not the account’s current key');
        }
        const seen = new Set<string>();
        for (const e of req.body.events) {
          if (seen.has(e.id) || (await q.query('SELECT 1 FROM events WHERE user_id = $1 AND id = $2', [userId, e.id])).rows.length) {
            duplicate.push(e.id);
            continue;
          }
          seen.add(e.id);
          const [{ seq }] = (await q.query<{ seq: number }>('UPDATE users SET seq = seq + 1 WHERE id = $1 RETURNING seq', [userId])).rows;
          await q.query(
            `INSERT INTO events (user_id, seq, id, source_device_id, key_id, nonce, ct, kind, received_at)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
            [userId, seq, e.id, sourceId, e.keyId, e.nonce, e.ct, e.kind, t],
          );
          stored.push({ user_id: userId, seq, id: e.id, source_device_id: sourceId, key_id: e.keyId, nonce: e.nonce, ct: e.ct, kind: e.kind, received_at: t });
        }
      });

      await fanOut(userId, sourceId, stored).catch((err) => app.log.warn({ err }, 'push fan-out failed'));
      return { accepted: stored.map((e) => e.id), duplicate };
    },
  );

  app.get<{ Querystring: { after?: string; limit?: number } }>(
    '/v1/events',
    {
      preHandler: [auth, role('receiver')],
      schema: {
        querystring: {
          type: 'object',
          properties: { after: S.seq, limit: { type: 'integer', minimum: 1, maximum: 100, default: 100 } },
        },
      },
    },
    async (req) => {
      const after = Number(req.query.after ?? 0);
      const limit = req.query.limit ?? 100;
      const { rows } = await db.query<EventRow>('SELECT * FROM events WHERE user_id = $1 AND seq > $2 ORDER BY seq LIMIT $3', [
        req.device.user_id,
        after,
        limit + 1,
      ]);
      const page = rows.slice(0, limit);
      return {
        events: page.map(serializeEvent),
        next: String(page.at(-1)?.seq ?? after),
        hasMore: rows.length > limit,
      };
    },
  );

  app.get<{ Params: { id: string } }>(
    '/v1/events/:id',
    {
      preHandler: [auth, role('receiver')],
      schema: { params: { type: 'object', required: ['id'], properties: { id: S.uuid } } },
    },
    async (req) => {
      const [row] = (
        await db.query<EventRow>('SELECT * FROM events WHERE user_id = $1 AND id = $2', [req.device.user_id, req.params.id])
      ).rows;
      if (!row) throw new ApiError(404, 'not_found', 'Event not found or expired');
      return serializeEvent(row);
    },
  );

  app.post<{ Body: { upTo: string } }>(
    '/v1/events/ack',
    {
      preHandler: [auth, role('receiver')],
      schema: { body: { type: 'object', required: ['upTo'], properties: { upTo: S.seq } } },
    },
    async (req, reply) => {
      const { user_id: userId, id } = req.device;
      const [{ seq }] = (await db.query<Pick<UserRow, 'seq'>>('SELECT seq FROM users WHERE id = $1', [userId])).rows;
      const upTo = Math.min(Number(req.body.upTo), seq);
      await db.query('UPDATE devices SET ack_seq = GREATEST(ack_seq, $1) WHERE id = $2', [upTo, id]);
      await prune(db, now(), config.retentionDays, userId);
      reply.code(204);
    },
  );
}
