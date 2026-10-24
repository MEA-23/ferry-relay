import { sign } from 'node:crypto';
import type { FastifyBaseLogger } from 'fastify';
import type { FcmServiceAccount } from '../config.ts';
import type { PushResult, PushTarget, SrPayload } from './index.ts';

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

export function createFcm(sa: FcmServiceAccount, log: FastifyBaseLogger) {
  let access = { token: '', exp: 0 };

  async function accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (access.exp - 60 > now) return access.token;

    const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
    const claims = Buffer.from(
      JSON.stringify({ iss: sa.client_email, scope: SCOPE, aud: sa.token_uri, iat: now, exp: now + 3600 }),
    ).toString('base64url');
    const sig = sign('sha256', Buffer.from(`${header}.${claims}`), sa.private_key).toString('base64url');

    const res = await fetch(sa.token_uri, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${header}.${claims}.${sig}`,
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`fcm oauth failed: ${res.status}`);
    const json = (await res.json()) as { access_token: string; expires_in: number };
    access = { token: json.access_token, exp: now + json.expires_in };
    return access.token;
  }

  async function send(target: PushTarget, sr: SrPayload): Promise<PushResult> {
    try {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: 'POST',
        headers: { authorization: `Bearer ${await accessToken()}`, 'content-type': 'application/json' },
        // Data-only message (§7.2): the app decrypts and posts its own notification.
        body: JSON.stringify({
          message: { token: target.token, data: { sr: JSON.stringify(sr) }, android: { priority: 'HIGH' } },
        }),
        signal: AbortSignal.timeout(10_000),
      });
      if (res.ok) return 'ok';
      const text = await res.text();
      if (res.status === 404 || text.includes('UNREGISTERED')) return 'invalid_token';
      log.warn({ status: res.status, body: text.slice(0, 300) }, 'fcm push rejected');
      return 'error';
    } catch (err) {
      log.warn({ err }, 'fcm push failed');
      return 'error';
    }
  }

  return { send };
}
