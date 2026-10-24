import http2 from 'node:http2';
import { createPrivateKey, sign } from 'node:crypto';
import type { FastifyBaseLogger } from 'fastify';
import type { ApnsConfig, ApnsKey } from '../config.ts';
import type { PushResult, PushTarget, SrPayload } from './index.ts';

const HOSTS = {
  production: 'https://api.push.apple.com',
  sandbox: 'https://api.sandbox.push.apple.com',
};

// Fixed placeholder (§7.2). The Notification Service Extension replaces it after decrypting.
const APS = { alert: { title: 'Ferry', body: 'New message' }, 'mutable-content': 1, sound: 'default' };

export function createApns(cfg: ApnsConfig, log: FastifyBaseLogger) {
  const signers = {
    production: cfg.production && createSigner(cfg.production, cfg.teamId),
    sandbox: cfg.sandbox && createSigner(cfg.sandbox, cfg.teamId),
  };
  const sessions = new Map<string, http2.ClientHttp2Session>();

  function session(env: PushTarget['environment']): http2.ClientHttp2Session {
    const host = HOSTS[env];
    let s = sessions.get(host);
    if (!s || s.closed || s.destroyed) {
      s = http2.connect(host);
      s.on('error', (err) => log.warn({ err }, 'apns session error'));
      s.on('close', () => sessions.delete(host));
      s.unref();
      sessions.set(host, s);
    }
    return s;
  }

  function send(target: PushTarget, sr: SrPayload): Promise<PushResult> {
    const bearer = signers[target.environment];
    if (!bearer) return Promise.resolve('skipped');
    return new Promise((resolve) => {
      let req: http2.ClientHttp2Stream;
      try {
        req = session(target.environment).request({
          ':method': 'POST',
          ':path': `/3/device/${target.token}`,
          authorization: `bearer ${bearer()}`,
          'apns-topic': target.appId,
          'apns-push-type': 'alert',
          'apns-priority': '10',
        });
      } catch (err) {
        log.warn({ err }, 'apns request failed');
        return resolve('error');
      }

      let status = 0;
      let body = '';
      req.setEncoding('utf8');
      req.setTimeout(10_000, () => req.close(http2.constants.NGHTTP2_CANCEL));
      req.on('response', (h) => (status = Number(h[':status'])));
      req.on('data', (chunk) => (body += chunk));
      req.on('error', (err) => {
        log.warn({ err }, 'apns stream error');
        resolve('error');
      });
      req.on('close', () => {
        if (status === 200) return resolve('ok');
        let reason: string | undefined;
        try {
          reason = JSON.parse(body).reason;
        } catch {}
        if (status === 410 || reason === 'BadDeviceToken' || reason === 'Unregistered') return resolve('invalid_token');
        log.warn({ status, reason }, 'apns push rejected');
        resolve('error');
      });
      req.end(JSON.stringify({ aps: APS, sr }));
    });
  }

  return {
    send,
    close() {
      for (const s of sessions.values()) s.close();
    },
  };
}

function createSigner(cfg: ApnsKey, teamId: string): () => string {
  const key = createPrivateKey(cfg.key);
  let jwt = { token: '', iat: 0 };

  // Apple accepts a provider token for up to 60 minutes; refresh at 50.
  return () => {
    const now = Math.floor(Date.now() / 1000);
    if (now - jwt.iat > 50 * 60) {
      const header = Buffer.from(JSON.stringify({ alg: 'ES256', kid: cfg.keyId })).toString('base64url');
      const claims = Buffer.from(JSON.stringify({ iss: teamId, iat: now })).toString('base64url');
      const sig = sign('sha256', Buffer.from(`${header}.${claims}`), { key, dsaEncoding: 'ieee-p1363' });
      jwt = { token: `${header}.${claims}.${sig.toString('base64url')}`, iat: now };
    }
    return jwt.token;
  };
}
