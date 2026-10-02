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
 */

export interface Env {
  DB: D1Database;
}

const MAX_SNAPSHOT_BYTES = 256 * 1024;
const MAX_SUBSCRIBERS = 20;
const MAX_NAME = 40;
/** How stale a partner's "last seen" may get before a read turns into a row write. */
const SEEN_THROTTLE_MS = 60 * 60 * 1000;
/** No 0/O/1/I/L: codes get read aloud and typed on phones. */
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 10;

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
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
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

function randomCode(): string {
  // Rejection sampling keeps every character equally likely.
  const limit = 256 - (256 % CODE_ALPHABET.length);
  let out = "";
  while (out.length < CODE_LENGTH) {
    for (const b of crypto.getRandomValues(new Uint8Array(CODE_LENGTH * 2))) {
      if (b < limit && out.length < CODE_LENGTH) out += CODE_ALPHABET[b % CODE_ALPHABET.length];
    }
  }
  return out;
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

async function readJson(req: Request, maxBytes: number): Promise<Record<string, unknown>> {
  const declared = Number(req.headers.get("content-length") ?? 0);
  if (declared > maxBytes) throw new HttpError(413, "too_large", "Request body is too large.");
  const text = await req.text();
  if (new TextEncoder().encode(text).length > maxBytes) throw new HttpError(413, "too_large", "Request body is too large.");
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

  await ensureSchema(env.DB);
  const [, resource, id, sub, subId] = parts;

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
      if (e instanceof HttpError) return json({ error: e.code, message: e.message }, e.status);
      console.error(e);
      return json({ error: "internal", message: "Something went wrong." }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
