// §6.6 commands: a receiver asks one node to do something, such as send an SMS. The server only
// queues ciphertext under the node's own key; the node checks expiry, replays and limits itself.

import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.ts';
import { isNode, type CommandRow, type DeviceRow } from '../db.ts';
import { ApiError, KEY_ID, NONCE, S } from '../util.ts';

// Commands are small (an SMS body); 4096 padded bytes + tag is plenty.
const CT_MAX = Math.ceil(((4096 + 16) * 4) / 3);
const MAX_WAIT_S = 30;
const BATCH = 20;

interface CommandBody {
  id: string;
  keyId: string;
  nonce: string;
  ct: string;
}

function serializeCommand(c: CommandRow) {
  return { id: c.id, keyId: c.key_id, nonce: c.nonce, ct: c.ct, senderId: c.sender_id, createdAt: c.created_at };
}

export function commandRoutes(app: FastifyInstance, { db, now, auth, role }: Ctx): void {
  // Long polls waiting on each node; a new command wakes them.
  const waiters = new Map<string, Set<() => void>>();
  const wake = (nodeId: string) => waiters.get(nodeId)?.forEach((done) => done());
  app.addHook('onClose', async () => waiters.forEach((set) => set.forEach((done) => done())));

  const requireNode = async (req: { device: DeviceRow }) => {
    if (!isNode(req.device)) throw new ApiError(403, 'forbidden', 'Only source-only devices take commands');
  };

  app.post<{ Params: { id: string }; Body: CommandBody }>(
    '/v1/devices/:id/commands',
    {
      preHandler: [auth, role('receiver')],
      schema: {
        params: { type: 'object', required: ['id'], properties: { id: S.uuid } },
        body: {
          type: 'object',
          required: ['id', 'keyId', 'nonce', 'ct'],
          properties: { id: S.uuid, keyId: KEY_ID, nonce: NONCE, ct: S.b64u(1, CT_MAX) },
        },
      },
    },
    async (req, reply) => {
      const [node] = (
        await db.query<DeviceRow>('SELECT * FROM devices WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL', [
          req.params.id,
          req.device.user_id,
        ])
      ).rows;
      if (!node || !isNode(node)) throw new ApiError(404, 'not_found', 'No such node');
      if (node.key_id === null || req.body.keyId !== node.key_id) {
        throw new ApiError(409, 'key_rotated', 'Command keyId is not the node’s current key');
      }
      const { id, keyId, nonce, ct } = req.body;
      // Idempotent on the command id, like event upload.
      await db.query(
        `INSERT INTO commands (node_id, id, user_id, sender_id, key_id, nonce, ct, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT DO NOTHING`,
        [node.id, id, req.device.user_id, req.device.id, keyId, nonce, ct, now()],
      );
      wake(node.id);
      reply.code(202);
      return { status: 'queued' };
    },
  );

  app.get<{ Querystring: { wait?: number } }>(
    '/v1/commands',
    {
      preHandler: [auth, role('source'), requireNode],
      schema: {
        querystring: { type: 'object', properties: { wait: { type: 'integer', minimum: 0, maximum: MAX_WAIT_S, default: 0 } } },
      },
    },
    async (req) => {
      const nodeId = req.device.id;
      const pending = async () =>
        (await db.query<CommandRow>('SELECT * FROM commands WHERE node_id = $1 ORDER BY created_at LIMIT $2', [nodeId, BATCH])).rows;
      let rows = await pending();
      if (rows.length === 0 && (req.query.wait ?? 0) > 0) {
        await new Promise<void>((resolve) => {
          const set = waiters.get(nodeId) ?? new Set();
          waiters.set(nodeId, set);
          const done = () => {
            clearTimeout(timer);
            set.delete(done);
            if (set.size === 0) waiters.delete(nodeId);
            req.raw.off('close', done);
            resolve();
          };
          const timer = setTimeout(done, (req.query.wait ?? 0) * 1000);
          set.add(done);
          req.raw.once('close', done);
        });
        rows = await pending();
      }
      return { commands: rows.map(serializeCommand) };
    },
  );

  app.post<{ Body: { ids: string[] } }>(
    '/v1/commands/ack',
    {
      preHandler: [auth, role('source'), requireNode],
      schema: {
        body: { type: 'object', required: ['ids'], properties: { ids: { type: 'array', items: S.uuid, minItems: 1, maxItems: 100 } } },
      },
    },
    async (req, reply) => {
      await db.query('DELETE FROM commands WHERE node_id = $1 AND id = ANY($2)', [req.device.id, req.body.ids]);
      reply.code(204);
    },
  );
}
