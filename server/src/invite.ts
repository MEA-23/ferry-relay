// One-time invite codes. In the container:  node src/invite.ts [count=1] [days=7]

import { randomBytes } from 'node:crypto';
import { loadConfig } from './config.ts';
import { openDb, type Queryable } from './db.ts';
import { hashToken } from './util.ts';

export async function createInvites(db: Queryable, count: number, days: number, now = Date.now()): Promise<string[]> {
  const codes = Array.from({ length: count }, () => randomBytes(16).toString('base64url'));
  for (const code of codes) {
    await db.query('INSERT INTO invites (code_hash, expires_at) VALUES ($1, $2)', [hashToken(code), now + days * 86_400_000]);
  }
  return codes;
}

if (import.meta.main) {
  const [count = 1, days = 7] = process.argv.slice(2).map(Number);
  if (!(count >= 1 && count <= 100 && days > 0)) throw new Error('Usage: node src/invite.ts [count 1-100] [days]');
  const db = await openDb(loadConfig().databaseUrl);
  const codes = await createInvites(db, count, days);
  await db.close();
  console.log(codes.join('\n'));
  console.error(`${codes.length} one-time invite(s), valid for ${days} day(s). Requires INVITE_CODE to be set on the server.`);
}
