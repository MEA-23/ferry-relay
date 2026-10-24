import type { FastifyBaseLogger } from 'fastify';
import type { Config } from '../config.ts';
import type { EventRow } from '../db.ts';
import { createApns } from './apns.ts';
import { createFcm } from './fcm.ts';
import { sendUnifiedPush } from './unifiedpush.ts';

export type PushProvider = 'apns' | 'fcm' | 'unifiedpush';
export type PushResult = 'ok' | 'invalid_token' | 'skipped' | 'error';

export interface PushTarget {
  provider: PushProvider;
  token: string;
  appId: string;
  environment: 'production' | 'sandbox';
}

// The `sr` object from §7.2: either the whole envelope inline, or just the event id.
export type SrPayload =
  | { v: 1; u: string; d: string; e: ReturnType<typeof serializeEvent> }
  | { v: 1; u: string; id: string };

export interface Pusher {
  send(target: PushTarget, sr: SrPayload): Promise<PushResult>;
  close(): void;
}

export const INLINE_CT_MAX = 3000;

export function serializeEvent(e: EventRow) {
  return {
    id: e.id,
    keyId: e.key_id,
    nonce: e.nonce,
    ct: e.ct,
    kind: e.kind,
    seq: String(e.seq),
    sourceDeviceId: e.source_device_id,
    receivedAt: e.received_at,
  };
}

export function buildSr(e: EventRow): SrPayload {
  return e.ct.length <= INLINE_CT_MAX
    ? { v: 1, u: e.user_id, d: e.source_device_id, e: serializeEvent(e) }
    : { v: 1, u: e.user_id, id: e.id };
}

export function createPusher(config: Config, log: FastifyBaseLogger): Pusher {
  const apns = config.apns && createApns(config.apns, log);
  const fcm = config.fcm && createFcm(config.fcm, log);
  if (!apns) log.warn('APNs not configured — iOS receivers will only get events by pulling');
  else
    log.info(
      { production: config.apns!.production?.keyId ?? null, sandbox: config.apns!.sandbox?.keyId ?? null },
      'APNs keys loaded',
    );
  if (fcm) log.info({ project: config.fcm!.project_id }, 'FCM loaded');

  return {
    async send(target, sr) {
      switch (target.provider) {
        case 'apns':
          return apns ? apns.send(target, sr) : 'skipped';
        case 'fcm':
          return fcm ? fcm.send(target, sr) : 'skipped';
        case 'unifiedpush':
          return sendUnifiedPush(target.token, sr, log);
      }
    },
    close() {
      apns?.close();
    },
  };
}
