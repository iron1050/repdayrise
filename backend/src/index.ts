/**
 * Dayrise sharing backend.
 *
 * An owner publishes a snapshot of their habit list and gets an invite code. A partner
 * redeems the code to subscribe, then polls the feed. There are no accounts: each side holds
 * a random bearer token, and only its SHA-256 is stored here.
 *
 *   POST   /v1/shares                        create a share            -> { id, token, code }
 *   GET    /v1/shares/:id                    owner: status + partners
 *   PUT    /v1/shares/:id                    owner: publish a snapshot
 *   POST   /v1/shares/:id/rotate-code        owner: invalidate the invite code
 *   DELETE /v1/shares/:id/subscribers/:sid   owner: remove a partner
 *   DELETE /v1/shares/:id                    owner: stop sharing
 *   POST   /v1/join                          partner: redeem a code    -> { token, ... }
 *   GET    /v1/feed                          partner: latest snapshot (ETag / 304 aware)
 *   DELETE /v1/feed                          partner: unsubscribe
 *   GET    /j/:code                          invite landing page that opens the app
 *
 * Cloud backup: a phone uploads its whole database as one JSON row. The recovery code is the
 * only credential (sent as the bearer token), and only its SHA-256 is stored.
 *
 *   POST   /v1/backups                       create a backup           -> { code }
 *   GET    /v1/backups                       read it (ETag / 304 aware)
 *   PUT    /v1/backups                       replace it                -> { version, updatedAt }
 *   DELETE /v1/backups                       delete it
 *
 * Abuse limits: a per-IP burst limit on every /v1 request (Workers rate limiting binding), daily
 * per-IP and global caps on the endpoints that create rows, a per-backup write throttle, size
 * caps on every body, and a daily cron that clears out abandoned backups.
 */

export interface Env {
  DB: D1Database;
  /** Per-IP burst limiter. Optional so a copy deployed without it still works. */
  BURST?: RateLimit;
  /** "off" disables the per-IP and global caps, for local testing only. */
  RATE_LIMITS?: string;
}

const MAX_SNAPSHOT_BYTES = 256 * 1024;
const MAX_SUBSCRIBERS = 20;
const MAX_NAME = 40;
/** How stale a partner's "last seen" may get before a read turns into a row write. */
const SEEN_THROTTLE_MS = 60 * 60 * 1000;
/** No 0/O/1/I/L: codes get read aloud and typed on phones. */
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 10;
/** ~148 bits: recovery codes are bearer credentials, so they must be unguessable. */
const RECOVERY_LENGTH = 30;
const MAX_BACKUP_BYTES = 1024 * 1024;
/** A phone debounces its uploads; anything faster than this is a bug or abuse. */
const BACKUP_MIN_INTERVAL_MS = 10 * 1000;
const BACKUP_MAX_WRITES_PER_DAY = 500;
/** Backups nobody has written to for this long are deleted by the daily cron. */
const BACKUP_TTL_MS = 400 * 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
/** [limit, window] for each kind of request that creates rows. */
const CAPS = {
  createBackupPerIp: [5, DAY_MS],
  createBackupGlobal: [2000, DAY_MS],
  createSharePerIp: [10, DAY_MS],
  joinPerIp: [30, HOUR_MS],
} as const;

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS shares (
     id TEXT PRIMARY KEY,
     owner_hash TEXT NOT NULL,
     code TEXT NOT NULL UNIQUE,
     owner_name TEXT NOT NULL,
     snapshot TEXT,
     version INTEGER NOT NULL DEFAULT 0,
     created_at INTEGER NOT NULL,
     updated_at INTEGER NOT NULL
   )`,
  `CREATE TABLE IF NOT EXISTS subscribers (
     id TEXT PRIMARY KEY,
     share_id TEXT NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
     token_hash TEXT NOT NULL UNIQUE,
     name TEXT NOT NULL,
     created_at INTEGER NOT NULL,
     last_seen_at INTEGER
   )`,
  `CREATE INDEX IF NOT EXISTS subscribers_share ON subscribers(share_id)`,
  `CREATE TABLE IF NOT EXISTS backups (
     id TEXT PRIMARY KEY,
     code_hash TEXT NOT NULL UNIQUE,
     data TEXT,
     version INTEGER NOT NULL DEFAULT 0,
     size INTEGER NOT NULL DEFAULT 0,
     created_at INTEGER NOT NULL,
     updated_at INTEGER NOT NULL,
     day INTEGER NOT NULL DEFAULT 0,
     day_writes INTEGER NOT NULL DEFAULT 0
   )`,
  `CREATE INDEX IF NOT EXISTS backups_updated ON backups(updated_at)`,
  `CREATE TABLE IF NOT EXISTS rate_limits (
     key TEXT PRIMARY KEY,
     window INTEGER NOT NULL,
     count INTEGER NOT NULL
   )`,
];

/** Tables are created on the first request an isolate serves, so deploying needs no migration step. */
let schemaReady: Promise<unknown> | null = null;
function ensureSchema(db: D1Database): Promise<unknown> {
  schemaReady ??= db.batch(SCHEMA.map((s) => db.prepare(s))).catch((e) => {
    schemaReady = null;
    throw e;
  });
  return schemaReady;
}

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly retryAfterSec?: number) {
    super(message);
  }
}

function tooMany(retryAfterMs: number, message = "Too many requests. Try again later."): HttpError {
  return new HttpError(429, "rate_limited", message, Math.max(1, Math.ceil(retryAfterMs / 1000)));
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

function randomToken(bytes = 32): string {
  const buf = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...buf)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomCode(length = CODE_LENGTH): string {
  // Rejection sampling keeps every character equally likely.
  const limit = 256 - (256 % CODE_ALPHABET.length);
  let out = "";
  while (out.length < length) {
    for (const b of crypto.getRandomValues(new Uint8Array(length * 2))) {
      if (b < limit && out.length < length) out += CODE_ALPHABET[b % CODE_ALPHABET.length];
    }
  }
  return out;
}

/** Groups of five, so a 30-character recovery code reads as ABCDE-FGHJK-… */
function groupCode(code: string): string {
  return code.match(/.{1,5}/g)?.join("-") ?? code;
}

/** Accepts codes however people type them: lower case, with dashes or spaces. */
function normalizeCode(raw: unknown): string {
  return typeof raw === "string" ? raw.toUpperCase().replace(/[^A-Z0-9]/g, "") : "";
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function bearer(req: Request): string {
  const match = /^Bearer\s+(\S+)$/i.exec(req.headers.get("authorization") ?? "");
  if (!match) throw new HttpError(401, "unauthorized", "Missing bearer token.");
  return match[1];
}

function cleanName(raw: unknown): string {
  if (typeof raw !== "string") throw new HttpError(400, "bad_name", "A name is required.");
  const name = raw.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, MAX_NAME);
  if (!name) throw new HttpError(400, "bad_name", "A name is required.");
  return name;
}

/** Reads at most [maxBytes] of the body, stopping as soon as it goes over rather than buffering it all. */
async function readBody(req: Request, maxBytes: number): Promise<string> {
  const tooLarge = () => new HttpError(413, "too_large", "Request body is too large.");
  if (Number(req.headers.get("content-length") ?? 0) > maxBytes) throw tooLarge();
  if (!req.body) return "";
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw tooLarge();
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    all.set(c, offset);
    offset += c.byteLength;
  }
  return new TextDecoder().decode(all);
}

async function readJson(req: Request, maxBytes: number): Promise<Record<string, unknown>> {
  const text = await readBody(req, maxBytes);
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    throw new HttpError(400, "bad_json", "Request body must be JSON.");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw new HttpError(400, "bad_json", "Request body must be a JSON object.");
  }
  return body as Record<string, unknown>;
}

function clientIp(req: Request): string {
  return req.headers.get("cf-connecting-ip") ?? "unknown";
}

/** Burst limit on every API request. Costs no database reads or writes. */
async function checkBurst(env: Env, req: Request): Promise<void> {
  if (!env.BURST || env.RATE_LIMITS === "off") return;
  const { success } = await env.BURST.limit({ key: clientIp(req) });
  if (!success) throw tooMany(60_000);
}

/**
 * Counts one request against [key] in a fixed window and rejects it past [limit]. One row write.
 * IPs are hashed first so the table never holds a raw address.
 */
async function hit(env: Env, key: string, [limit, windowMs]: readonly [number, number]): Promise<void> {
  if (env.RATE_LIMITS === "off") return;
  const now = Date.now();
  // The window's start time, so the cron can clear old rows whatever their window length.
  const window = Math.floor(now / windowMs) * windowMs;
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (key, window, count) VALUES (?, ?, 1)
     ON CONFLICT(key) DO UPDATE SET
       count = CASE WHEN window = excluded.window THEN count + 1 ELSE 1 END,
       window = excluded.window
     RETURNING count`,
  ).bind(key, window).first<{ count: number }>();
  if ((row?.count ?? 0) > limit) throw tooMany(window + windowMs - now);
}

async function hitPerIp(env: Env, req: Request, name: string, cap: readonly [number, number]): Promise<void> {
  if (env.RATE_LIMITS === "off") return;
  await hit(env, `${name}:${(await sha256(clientIp(req))).slice(0, 32)}`, cap);
}

interface ShareRow {
  id: string;
  owner_hash: string;
  code: string;
  owner_name: string;
  snapshot: string | null;
  version: number;
  created_at: number;
  updated_at: number;
}

/** Loads a share and checks the caller holds its owner token. Unknown ids and bad tokens look the same. */
async function ownedShare(env: Env, req: Request, id: string): Promise<ShareRow> {
  const hash = await sha256(bearer(req));
  const row = await env.DB.prepare("SELECT * FROM shares WHERE id = ?").bind(id).first<ShareRow>();
  if (!row || row.owner_hash !== hash) throw new HttpError(401, "unauthorized", "Unknown share or wrong token.");
  return row;
}

function formatCode(code: string): string {
  return code.length === CODE_LENGTH ? `${code.slice(0, 5)}-${code.slice(5)}` : code;
}

async function createShare(env: Env, req: Request): Promise<Response> {
  const body = await readJson(req, 4096);
  const name = cleanName(body.name);
  await hitPerIp(env, req, "share", CAPS.createSharePerIp);
  const id = crypto.randomUUID();
  const token = randomToken();
  const now = Date.now();
  // A code collision is a ~1 in 10^14 event; retry rather than fail if it ever happens.
  for (let attempt = 0; ; attempt++) {
    const code = randomCode();
    try {
      await env.DB.prepare(
        "INSERT INTO shares (id, owner_hash, code, owner_name, version, created_at, updated_at) VALUES (?, ?, ?, ?, 0, ?, ?)",
      ).bind(id, await sha256(token), code, name, now, now).run();
      return json({ id, token, code: formatCode(code) }, 201);
    } catch (e) {
      if (attempt >= 3) throw e;
    }
  }
}

async function shareStatus(env: Env, req: Request, id: string): Promise<Response> {
  const share = await ownedShare(env, req, id);
  const subs = await env.DB.prepare(
    "SELECT id, name, created_at, last_seen_at FROM subscribers WHERE share_id = ? ORDER BY created_at",
  ).bind(id).all<{ id: string; name: string; created_at: number; last_seen_at: number | null }>();
  return json({
    id: share.id,
    name: share.owner_name,
    code: formatCode(share.code),
    version: share.version,
    updatedAt: share.updated_at,
    subscribers: subs.results.map((s) => ({ id: s.id, name: s.name, joinedAt: s.created_at, lastSeenAt: s.last_seen_at })),
  });
}

async function publish(env: Env, req: Request, id: string): Promise<Response> {
  const share = await ownedShare(env, req, id);
  const body = await readJson(req, MAX_SNAPSHOT_BYTES);
  const snapshot = body.snapshot;
  if (typeof snapshot !== "object" || snapshot === null || Array.isArray(snapshot)) {
    throw new HttpError(400, "bad_snapshot", "snapshot must be a JSON object.");
  }
  const name = body.name === undefined ? share.owner_name : cleanName(body.name);
  const now = Date.now();
  const row = await env.DB.prepare(
    "UPDATE shares SET snapshot = ?, owner_name = ?, version = version + 1, updated_at = ? WHERE id = ? RETURNING version",
  ).bind(JSON.stringify(snapshot), name, now, id).first<{ version: number }>();
  return json({ version: row?.version ?? share.version + 1, updatedAt: now });
}

async function rotateCode(env: Env, req: Request, id: string): Promise<Response> {
  await ownedShare(env, req, id);
  const code = randomCode();
  await env.DB.prepare("UPDATE shares SET code = ? WHERE id = ?").bind(code, id).run();
  return json({ code: formatCode(code) });
}

async function removeSubscriber(env: Env, req: Request, id: string, subscriberId: string): Promise<Response> {
  await ownedShare(env, req, id);
  await env.DB.prepare("DELETE FROM subscribers WHERE id = ? AND share_id = ?").bind(subscriberId, id).run();
  return new Response(null, { status: 204 });
}

async function deleteShare(env: Env, req: Request, id: string): Promise<Response> {
  await ownedShare(env, req, id);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM subscribers WHERE share_id = ?").bind(id),
    env.DB.prepare("DELETE FROM shares WHERE id = ?").bind(id),
  ]);
  return new Response(null, { status: 204 });
}

async function join(env: Env, req: Request): Promise<Response> {
  const body = await readJson(req, 4096);
  const name = cleanName(body.name);
  const code = normalizeCode(body.code);
  // Counted before the lookup, so wrong guesses use up the allowance too.
  await hitPerIp(env, req, "join", CAPS.joinPerIp);
  if (code.length !== CODE_LENGTH) throw new HttpError(404, "bad_code", "That code doesn't match a shared list.");
  const share = await env.DB.prepare("SELECT id, owner_name FROM shares WHERE code = ?").bind(code).first<{ id: string; owner_name: string }>();
  if (!share) throw new HttpError(404, "bad_code", "That code doesn't match a shared list.");
  const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM subscribers WHERE share_id = ?").bind(share.id).first<{ n: number }>();
  if ((count?.n ?? 0) >= MAX_SUBSCRIBERS) throw new HttpError(409, "full", "This list already has the maximum number of partners.");
  const subscriberId = crypto.randomUUID();
  const token = randomToken();
  await env.DB.prepare(
    "INSERT INTO subscribers (id, share_id, token_hash, name, created_at) VALUES (?, ?, ?, ?, ?)",
  ).bind(subscriberId, share.id, await sha256(token), name, Date.now()).run();
  return json({ token, subscriberId, shareId: share.id, ownerName: share.owner_name }, 201);
}

interface FeedRow {
  subscriber_id: string;
  last_seen_at: number | null;
  share_id: string;
  owner_name: string;
  snapshot: string | null;
  version: number;
  updated_at: number;
}

async function feed(env: Env, req: Request, ctx: ExecutionContext): Promise<Response> {
  const hash = await sha256(bearer(req));
  const row = await env.DB.prepare(
    `SELECT sub.id AS subscriber_id, sub.last_seen_at, s.id AS share_id, s.owner_name, s.snapshot, s.version, s.updated_at
       FROM subscribers sub JOIN shares s ON s.id = sub.share_id
      WHERE sub.token_hash = ?`,
  ).bind(hash).first<FeedRow>();
  // 401 covers both "the owner removed you" and "the owner stopped sharing".
  if (!row) throw new HttpError(401, "unauthorized", "This subscription is no longer active.");

  const now = Date.now();
  if (!row.last_seen_at || now - row.last_seen_at > SEEN_THROTTLE_MS) {
    ctx.waitUntil(env.DB.prepare("UPDATE subscribers SET last_seen_at = ? WHERE id = ?").bind(now, row.subscriber_id).run());
  }

  const etag = `"${row.share_id}-${row.version}"`;
  // Weak comparison, as If-None-Match requires: Cloudflare adds W/ to the ETag when it compresses.
  const ifNoneMatch = (req.headers.get("if-none-match") ?? "").split(",").map((t) => t.trim().replace(/^W\//, ""));
  if (ifNoneMatch.includes(etag)) return new Response(null, { status: 304, headers: { etag } });
  return json(
    {
      shareId: row.share_id,
      ownerName: row.owner_name,
      version: row.version,
      updatedAt: row.updated_at,
      snapshot: row.snapshot ? JSON.parse(row.snapshot) : null,
    },
    200,
    { etag },
  );
}

async function unsubscribe(env: Env, req: Request): Promise<Response> {
  const hash = await sha256(bearer(req));
  await env.DB.prepare("DELETE FROM subscribers WHERE token_hash = ?").bind(hash).run();
  return new Response(null, { status: 204 });
}

// ---- Cloud backup -------------------------------------------------------------------------

interface BackupRow {
  id: string;
  data: string | null;
  version: number;
  size: number;
  updated_at: number;
  day: number;
  day_writes: number;
}

async function recoveryHash(req: Request): Promise<string> {
  const code = normalizeCode(bearer(req));
  if (code.length !== RECOVERY_LENGTH) throw new HttpError(401, "unauthorized", "Unknown recovery code.");
  return sha256(code);
}

async function ownedBackup(env: Env, req: Request): Promise<BackupRow> {
  const hash = await recoveryHash(req);
  const row = await env.DB.prepare(
    "SELECT id, data, version, size, updated_at, day, day_writes FROM backups WHERE code_hash = ?",
  ).bind(hash).first<BackupRow>();
  if (!row) throw new HttpError(401, "unauthorized", "Unknown recovery code.");
  return row;
}

async function createBackup(env: Env, req: Request): Promise<Response> {
  await hitPerIp(env, req, "backup", CAPS.createBackupPerIp);
  await hit(env, "backup:global", CAPS.createBackupGlobal);
  const code = randomCode(RECOVERY_LENGTH);
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO backups (id, code_hash, version, size, created_at, updated_at) VALUES (?, ?, 0, 0, ?, ?)",
  ).bind(crypto.randomUUID(), await sha256(code), now, now).run();
  return json({ code: groupCode(code) }, 201);
}

async function writeBackup(env: Env, req: Request): Promise<Response> {
  const row = await ownedBackup(env, req);
  const now = Date.now();
  // The first write after creating is allowed straight away.
  if (row.version > 0 && now - row.updated_at < BACKUP_MIN_INTERVAL_MS) {
    throw tooMany(BACKUP_MIN_INTERVAL_MS - (now - row.updated_at), "Backing up too often. Try again in a few seconds.");
  }
  const today = Math.floor(now / DAY_MS);
  if (row.day === today && row.day_writes >= BACKUP_MAX_WRITES_PER_DAY) {
    throw tooMany((today + 1) * DAY_MS - now, "This backup has been updated too many times today.");
  }

  const body = await readJson(req, MAX_BACKUP_BYTES + 1024);
  const data = body.data;
  if (typeof data !== "object" || data === null || Array.isArray(data) || (data as Record<string, unknown>).app !== "dayrise") {
    throw new HttpError(400, "bad_backup", "data must be a Dayrise backup.");
  }
  const text = JSON.stringify(data);
  const size = new TextEncoder().encode(text).length;
  if (size > MAX_BACKUP_BYTES) throw new HttpError(413, "too_large", "This backup is too large.");

  const base = body.baseVersion;
  if (base !== undefined && base !== null && typeof base !== "number") {
    throw new HttpError(400, "bad_version", "baseVersion must be a number.");
  }
  // The version check is part of the UPDATE, so two phones racing can't both win.
  const updated = await env.DB.prepare(
    `UPDATE backups SET data = ?, size = ?, version = version + 1, updated_at = ?,
       day_writes = CASE WHEN day = ? THEN day_writes + 1 ELSE 1 END, day = ?
     WHERE id = ? AND (? IS NULL OR version = ?)
     RETURNING version`,
  ).bind(text, size, now, today, today, row.id, base ?? null, base ?? null).first<{ version: number }>();
  if (!updated) throw new HttpError(409, "conflict", "This backup was changed from another device.");
  return json({ version: updated.version, updatedAt: now });
}

async function readBackup(env: Env, req: Request): Promise<Response> {
  const row = await ownedBackup(env, req);
  const etag = `"b-${row.version}"`;
  const ifNoneMatch = (req.headers.get("if-none-match") ?? "").split(",").map((t) => t.trim().replace(/^W\//, ""));
  if (ifNoneMatch.includes(etag)) return new Response(null, { status: 304, headers: { etag } });
  return json(
    { version: row.version, updatedAt: row.updated_at, size: row.size, data: row.data ? JSON.parse(row.data) : null },
    200,
    { etag },
  );
}

async function deleteBackup(env: Env, req: Request): Promise<Response> {
  await env.DB.prepare("DELETE FROM backups WHERE code_hash = ?").bind(await recoveryHash(req)).run();
  return new Response(null, { status: 204 });
}

async function cleanup(env: Env): Promise<void> {
  await ensureSchema(env.DB);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM backups WHERE updated_at < ?").bind(now - BACKUP_TTL_MS),
    // Created but never written to: someone turned backup on and the first upload never came.
    env.DB.prepare("DELETE FROM backups WHERE version = 0 AND created_at < ?").bind(now - DAY_MS),
    env.DB.prepare("DELETE FROM rate_limits WHERE window < ?").bind(now - 2 * DAY_MS),
  ]);
}

function invitePage(rawCode: string): Response {
  const code = normalizeCode(rawCode);
  if (code.length !== CODE_LENGTH) return new Response("Not found", { status: 404 });
  const pretty = formatCode(code);
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Follow my habits on Dayrise</title>
<style>
  body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:system-ui,sans-serif;color:#fff;
       background:linear-gradient(#0b1026,#3a2f6b 45%,#c2587a 78%,#f79b5e)}
  main{text-align:center;padding:32px;max-width:420px}
  h1{font-size:28px;margin:0 0 8px}
  p{opacity:.85;line-height:1.5}
  .code{font:700 30px ui-monospace,monospace;letter-spacing:3px;margin:24px 0}
  a{display:inline-block;padding:14px 28px;border-radius:999px;background:linear-gradient(90deg,#ff9a4d,#f2803e,#e85d8a);
    color:#fff;font-weight:700;text-decoration:none}
</style></head>
<body><main>
  <h1>You're invited</h1>
  <p>Someone wants you as their accountability partner on Dayrise.</p>
  <div class="code">${pretty}</div>
  <a href="dayrise://join/${code}">Open in Dayrise</a>
  <p>Or open Dayrise, go to Partners and enter the code above.</p>
</main></body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

async function route(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const { pathname } = new URL(req.url);
  const parts = pathname.split("/").filter(Boolean);
  const method = req.method;

  if (parts.length === 0 && method === "GET") return json({ service: "dayrise-sharing", ok: true });
  if (parts[0] === "j" && parts.length === 2 && method === "GET") return invitePage(parts[1]);
  if (parts[0] !== "v1") throw new HttpError(404, "not_found", "Not found.");

  await checkBurst(env, req);
  await ensureSchema(env.DB);
  const [, resource, id, sub, subId] = parts;

  if (resource === "backups" && parts.length === 2) {
    if (method === "POST") return createBackup(env, req);
    if (method === "GET") return readBackup(env, req);
    if (method === "PUT") return writeBackup(env, req);
    if (method === "DELETE") return deleteBackup(env, req);
  }

  if (resource === "shares") {
    if (parts.length === 2 && method === "POST") return createShare(env, req);
    if (parts.length === 3 && method === "GET") return shareStatus(env, req, id);
    if (parts.length === 3 && method === "PUT") return publish(env, req, id);
    if (parts.length === 3 && method === "DELETE") return deleteShare(env, req, id);
    if (parts.length === 4 && sub === "rotate-code" && method === "POST") return rotateCode(env, req, id);
    if (parts.length === 5 && sub === "subscribers" && method === "DELETE") return removeSubscriber(env, req, id, subId);
  }
  if (resource === "join" && parts.length === 2 && method === "POST") return join(env, req);
  if (resource === "feed" && parts.length === 2) {
    if (method === "GET") return feed(env, req, ctx);
    if (method === "DELETE") return unsubscribe(env, req);
  }
  throw new HttpError(404, "not_found", "Not found.");
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      return await route(req, env, ctx);
    } catch (e) {
      if (e instanceof HttpError) {
        const headers: Record<string, string> = e.retryAfterSec ? { "retry-after": String(e.retryAfterSec) } : {};
        return json({ error: e.code, message: e.message }, e.status, headers);
      }
      console.error(e);
      return json({ error: "internal", message: "Something went wrong." }, 500);
    }
  },
  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(cleanup(env));
  },
} satisfies ExportedHandler<Env>;
