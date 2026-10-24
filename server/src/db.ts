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
  key_id: string;
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
}

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
  key_id     TEXT NOT NULL,
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
  await db.query('DELETE FROM pairing_codes WHERE expires_at < $1', [now]);
  await db.query('DELETE FROM invites WHERE used_at IS NULL AND expires_at < $1', [now]);
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
