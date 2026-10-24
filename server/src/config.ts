import { readFileSync } from 'node:fs';

export interface ApnsKey {
  key: string; // PEM contents of the .p8 auth key
  keyId: string;
}

// One key per APNs environment. A single "Sandbox & Production" key fills both.
export interface ApnsConfig {
  teamId: string;
  production?: ApnsKey;
  sandbox?: ApnsKey;
}

export interface FcmServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

export interface Config {
  host: string;
  port: number;
  databaseUrl: string;
  retentionDays: number;
  inviteCode?: string;
  trustProxy: boolean;
  apns?: ApnsConfig;
  fcm?: FcmServiceAccount;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const config: Config = {
    host: env.HOST ?? '0.0.0.0',
    port: Number(env.PORT ?? 3000),
    databaseUrl: env.DATABASE_URL ?? '',
    retentionDays: Number(env.RETENTION_DAYS ?? 30),
    inviteCode: env.INVITE_CODE || undefined,
    trustProxy: env.TRUST_PROXY === 'true',
  };
  if (!config.databaseUrl) throw new Error('DATABASE_URL is required, e.g. postgres://user:pass@host:5432/ferry');

  // APNS_KEY_* is the production key, and also the sandbox key unless APNS_SANDBOX_KEY_* is set.
  const production = apnsKey(env.APNS_KEY_PATH, env.APNS_KEY_ID, 'APNS_KEY');
  const sandbox = apnsKey(env.APNS_SANDBOX_KEY_PATH, env.APNS_SANDBOX_KEY_ID, 'APNS_SANDBOX_KEY') ?? production;
  if (production || sandbox) {
    if (!env.APNS_TEAM_ID) throw new Error('APNS_TEAM_ID is required with an APNs key');
    config.apns = { teamId: env.APNS_TEAM_ID, production, sandbox };
  }
  if (env.FCM_SERVICE_ACCOUNT_PATH) {
    config.fcm = JSON.parse(readFileSync(env.FCM_SERVICE_ACCOUNT_PATH, 'utf8'));
  }
  return config;
}

function apnsKey(path: string | undefined, keyId: string | undefined, name: string): ApnsKey | undefined {
  if (!path) return undefined;
  if (!keyId) throw new Error(`${name}_ID is required with ${name}_PATH`);
  return { key: readFileSync(path, 'utf8'), keyId };
}
