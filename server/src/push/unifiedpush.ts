import type { FastifyBaseLogger } from 'fastify';
import type { PushResult, SrPayload } from './index.ts';

// UnifiedPush tokens are endpoint URLs; the `sr` object is POSTed to them as-is (§7.4).
export async function sendUnifiedPush(endpoint: string, sr: SrPayload, log: FastifyBaseLogger): Promise<PushResult> {
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ttl: '86400', urgency: 'high' },
      body: JSON.stringify(sr),
      redirect: 'error',
      signal: AbortSignal.timeout(10_000),
    });
    if (res.ok) return 'ok';
    if (res.status === 404 || res.status === 410) return 'invalid_token';
    log.warn({ status: res.status }, 'unifiedpush rejected');
    return 'error';
  } catch (err) {
    log.warn({ err }, 'unifiedpush failed');
    return 'error';
  }
}
