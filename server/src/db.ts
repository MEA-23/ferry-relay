import pg from 'pg';

// BIGINT columns hold ms timestamps and sequence numbers, all well inside Number's safe range.
pg.types.setTypeParser(pg.types.builtins.INT8, Number);

export interface Queryable {
  query<T = any>(text: string, params?: unknown[]): Promise<{ rows: T[] }>;
}

export interface Db extends Queryable {
  tx<T>(fn: (q: Queryable) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

export type Role = 'source' | 'receiver';

export interface UserRow {
  id: string;
  key_id: string | null; // null until the first receiver sets it (§5.6 flow A)
  seq: number;
  created_at: number;
}

export interface DeviceRow {
  id: string;
  user_id: string;
  roles: string; // JSON array of Role
  platform: string;
  app_version: string | null;
  token_hash: string;
  created_at: number;
  last_seen_at: number;
  revoked_at: number | null;
  push_provider: string | null;
  push_token: string | null;
  push_app_id: string | null;
  push_env: string | null;
  profile_version: number | null;
  profile_key_id: string | null;
  profile_nonce: string | null;
  profile_ct: string | null;
  ack_seq: number;
  key_id: string | null; // a source-only node's own key ID (§3.7)
}

export interface CommandRow {
  node_id: string;
  id: string;
  user_id: string;
  sender_id: string;
  key_id: string;
  nonce: string;
  ct: string;
  created_at: number;
}

/** Commands expire on the node within 10 minutes (§6.6); the server drops them a little later. */
export const COMMAND_TTL_MS = 15 * 60_000;

export interface EventRow {
  user_id: string;
  seq: number;
  id: string;
  source_device_id: string;
  key_id: string;
  nonce: string;
  ct: string;
  kind: 'alert' | 'silent';
  received_at: number;
}

export const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id         TEXT PRIMARY KEY,
  key_id     TEXT,
  seq        BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users(id),
  roles           TEXT NOT NULL,
  platform        TEXT NOT NULL,
  app_version     TEXT,
  token_hash      TEXT NOT NULL UNIQUE,
  created_at      BIGINT NOT NULL,
  last_seen_at    BIGINT NOT NULL,
  revoked_at      BIGINT,
  push_provider   TEXT,
  push_token      TEXT,
  push_app_id     TEXT,
  push_env        TEXT,
  profile_version BIGINT,
  profile_key_id  TEXT,
  profile_nonce   TEXT,
  profile_ct      TEXT,
  ack_seq         BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id);
-- Added for v2 (§3.7); servers created before it get them here.
ALTER TABLE users ALTER COLUMN key_id DROP NOT NULL;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS key_id TEXT;

CREATE TABLE IF NOT EXISTS invites (
  code_hash  TEXT PRIMARY KEY,
  expires_at BIGINT NOT NULL,
  used_at    BIGINT,
  used_by    TEXT REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS pairing_codes (
  code_hash  TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id),
  roles      TEXT NOT NULL,
  expires_at BIGINT NOT NULL,
  used_at    BIGINT
);
-- §5.6 reply mailbox: one message from the device that made the code, one from the device that used it.
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS created_by  TEXT;
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS claimed_by  TEXT;
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS owner_msg   TEXT;
ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS claimer_msg TEXT;

CREATE TABLE IF NOT EXISTS events (
  user_id          TEXT NOT NULL REFERENCES users(id),
  seq              BIGINT NOT NULL,
  id               TEXT NOT NULL,
  source_device_id TEXT NOT NULL,
  key_id           TEXT NOT NULL,
  nonce            TEXT NOT NULL,
  ct               TEXT NOT NULL,
  kind             TEXT NOT NULL,
  received_at      BIGINT NOT NULL,
  PRIMARY KEY (user_id, seq),
  UNIQUE (user_id, id)
);
CREATE INDEX IF NOT EXISTS events_received ON events(received_at);

-- §6.6: commands from receivers to one node, until it acks them.
CREATE TABLE IF NOT EXISTS commands (
  node_id    TEXT NOT NULL,
  id         TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES users(id),
  sender_id  TEXT NOT NULL,
  key_id     TEXT NOT NULL,
  nonce      TEXT NOT NULL,
  ct         TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (node_id, id)
);
CREATE INDEX IF NOT EXISTS commands_created ON commands(created_at);
`;

export async function openDb(connectionString: string): Promise<Db> {
  const pool = new pg.Pool({ connectionString });
  await pool.query(SCHEMA);
  return {
    query: (text, params) => pool.query(text, params as unknown[]) as Promise<{ rows: never[] }>,
    async tx(fn) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const result = await fn(client);
        await client.query('COMMIT');
        return result;
      } catch (err) {
        await client.query('ROLLBACK');
        throw err;
      } finally {
        client.release();
      }
    },
    close: () => pool.end(),
  };
}

export function rolesOf(device: DeviceRow): Role[] {
  return JSON.parse(device.roles);
}

/** A source without the receiver role holds only its own node key, never the UMK (§3.7). */
export function isNode(device: DeviceRow): boolean {
  return !rolesOf(device).includes('receiver');
}

/** How long a used or expired pairing code's mailbox stays readable (§5.6). */
export const PAIRING_GRACE_MS = 10 * 60_000;

const IS_RECEIVER = `roles::jsonb ? 'receiver'`;

export async function activeReceivers(db: Queryable, userId: string): Promise<DeviceRow[]> {
  const { rows } = await db.query<DeviceRow>(
    `SELECT * FROM devices WHERE user_id = $1 AND revoked_at IS NULL AND ${IS_RECEIVER}`,
    [userId],
  );
  return rows;
}

// §6.4: drop events every active receiver has acked, everything past retention,
// and stale pairing codes.
export async function prune(db: Queryable, now: number, retentionDays: number, userId?: string): Promise<void> {
  await db.query('DELETE FROM events WHERE received_at < $1', [now - retentionDays * 86_400_000]);
  await db.query('DELETE FROM pairing_codes WHERE expires_at < $1', [now - PAIRING_GRACE_MS]);
  await db.query('DELETE FROM invites WHERE used_at IS NULL AND expires_at < $1', [now]);
  await db.query('DELETE FROM commands WHERE created_at < $1', [now - COMMAND_TTL_MS]);
  await db.query(
    `DELETE FROM events e USING (
       SELECT user_id, MIN(ack_seq) AS m FROM devices
       WHERE revoked_at IS NULL AND ${IS_RECEIVER} AND ($1::text IS NULL OR user_id = $1)
       GROUP BY user_id
     ) a
     WHERE e.user_id = a.user_id AND e.seq <= a.m`,
    [userId ?? null],
  );
}
