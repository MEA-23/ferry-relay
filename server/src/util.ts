import { createHash, randomBytes } from 'node:crypto';

export class ApiError extends Error {
  readonly statusCode: number;
  readonly code: string;

  constructor(statusCode: number, code: string, message: string) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
  }
}

export function newToken(): string {
  return randomBytes(32).toString('base64url');
}

export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

export function uuidv7(now = Date.now()): string {
  const b = randomBytes(16);
  let ts = BigInt(now);
  for (let i = 5; i >= 0; i--) {
    b[i] = Number(ts & 0xffn);
    ts >>= 8n;
  }
  b[6] = (b[6] & 0x0f) | 0x70;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// JSON schema fragments shared by routes.
export const S = {
  uuid: { type: 'string', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' },
  b64u: (minLength: number, maxLength: number) => ({ type: 'string', pattern: '^[A-Za-z0-9_-]+$', minLength, maxLength }),
  seq: { type: 'string', pattern: '^(0|[1-9][0-9]{0,15})$' },
  roles: {
    type: 'array',
    items: { type: 'string', enum: ['source', 'receiver'] },
    minItems: 1,
    maxItems: 2,
    uniqueItems: true,
  },
  platform: { type: 'string', enum: ['android', 'ios'] },
  appVersion: { type: 'string', maxLength: 32 },
} as const;

export const KEY_ID = S.b64u(22, 22);
export const NONCE = S.b64u(32, 32);
