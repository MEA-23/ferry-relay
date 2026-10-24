import { buildApp } from './app.ts';
import { loadConfig } from './config.ts';
import { openDb, prune } from './db.ts';

const config = loadConfig();
const db = await openDb(config.databaseUrl);
const app = buildApp({ db, config, logger: { level: process.env.LOG_LEVEL ?? 'info' } });

if (!config.inviteCode) app.log.warn('INVITE_CODE is not set — anyone who can reach this server can create an account');

const pruneTimer = setInterval(
  () => prune(db, Date.now(), config.retentionDays).catch((err) => app.log.error({ err }, 'prune failed')),
  10 * 60_000,
);
pruneTimer.unref();

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.once(signal, async () => {
    await app.close();
    await db.close();
    process.exit(0);
  });
}

await app.listen({ host: config.host, port: config.port });
