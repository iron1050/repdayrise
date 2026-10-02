// End-to-end check of a running backend: node scripts/smoke.mjs [baseUrl]
const base = (process.argv[2] ?? "http://localhost:8787").replace(/\/+$/, "");
let failed = 0;

async function call(method, path, { token, body, headers } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(body ? { "content-type": "application/json" } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  return { status: res.status, data, etag: res.headers.get("etag") };
}

function check(label, ok, detail = "") {
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${label}${ok ? "" : "  " + detail}`);
}

const created = await call("POST", "/v1/shares", { body: { name: "  Smoke   Owner " } });
check("create share", created.status === 201 && /^[A-Z2-9]{5}-[A-Z2-9]{5}$/.test(created.data.code), JSON.stringify(created));
const { id, token: owner, code } = created.data;

const snapshot = { schema: 1, habits: [{ id: 1, name: "Read" }], entries: { 1: { 20000: 1 } } };
const pub = await call("PUT", `/v1/shares/${id}`, { token: owner, body: { snapshot } });
check("publish snapshot", pub.status === 200 && pub.data.version === 1, JSON.stringify(pub));

check("publish rejects wrong token", (await call("PUT", `/v1/shares/${id}`, { token: "nope", body: { snapshot } })).status === 401);
check("publish rejects non-object snapshot", (await call("PUT", `/v1/shares/${id}`, { token: owner, body: { snapshot: [1] } })).status === 400);
check("join rejects unknown code", (await call("POST", "/v1/join", { body: { code: "AAAAA-AAAAA", name: "x" } })).status === 404);
check("join rejects blank name", (await call("POST", "/v1/join", { body: { code, name: "   " } })).status === 400);

const joined = await call("POST", "/v1/join", { body: { code: code.toLowerCase().replace("-", " "), name: "Partner" } });
check("join with sloppy code", joined.status === 201 && joined.data.ownerName === "Smoke Owner", JSON.stringify(joined));
const partner = joined.data.token;

const feed = await call("GET", "/v1/feed", { token: partner });
check("feed returns snapshot", feed.status === 200 && feed.data.snapshot.habits[0].name === "Read" && feed.data.version === 1, JSON.stringify(feed));
check("feed honours etag", (await call("GET", "/v1/feed", { token: partner, headers: { "if-none-match": feed.etag } })).status === 304);
check("feed rejects owner token", (await call("GET", "/v1/feed", { token: owner })).status === 401);

await call("PUT", `/v1/shares/${id}`, { token: owner, body: { snapshot, name: "Renamed" } });
const feed2 = await call("GET", "/v1/feed", { token: partner, headers: { "if-none-match": feed.etag } });
check("feed changes after republish", feed2.status === 200 && feed2.data.version === 2 && feed2.data.ownerName === "Renamed", JSON.stringify(feed2));

const status = await call("GET", `/v1/shares/${id}`, { token: owner });
check("owner sees partner", status.status === 200 && status.data.subscribers.length === 1 && status.data.subscribers[0].name === "Partner", JSON.stringify(status));

const rotated = await call("POST", `/v1/shares/${id}/rotate-code`, { token: owner });
check("rotate code", rotated.status === 200 && rotated.data.code !== code);
check("old code stops working", (await call("POST", "/v1/join", { body: { code, name: "Late" } })).status === 404);
check("existing partner survives rotation", (await call("GET", "/v1/feed", { token: partner })).status === 200);

const second = await call("POST", "/v1/join", { body: { code: rotated.data.code, name: "Second" } });
check("unsubscribe", (await call("DELETE", "/v1/feed", { token: second.data.token })).status === 204);
check("unsubscribed token is dead", (await call("GET", "/v1/feed", { token: second.data.token })).status === 401);

const removed = await call("DELETE", `/v1/shares/${id}/subscribers/${status.data.subscribers[0].id}`, { token: owner });
check("owner removes partner", removed.status === 204);
check("removed partner is locked out", (await call("GET", "/v1/feed", { token: partner })).status === 401);

const third = await call("POST", "/v1/join", { body: { code: rotated.data.code, name: "Third" } });
check("delete share", (await call("DELETE", `/v1/shares/${id}`, { token: owner })).status === 204);
check("deleting a share ends subscriptions", (await call("GET", "/v1/feed", { token: third.data.token })).status === 401);
check("deleted share is gone", (await call("GET", `/v1/shares/${id}`, { token: owner })).status === 401);

const big = await call("POST", "/v1/shares", { body: { name: "Big" } });
const huge = { blob: "x".repeat(300 * 1024) };
check("oversized snapshot rejected", (await call("PUT", `/v1/shares/${big.data.id}`, { token: big.data.token, body: { snapshot: huge } })).status === 413);
await call("DELETE", `/v1/shares/${big.data.id}`, { token: big.data.token });

check("invite page renders", (await fetch(`${base}/j/${rotated.data.code}`)).status === 200);
check("unknown route is 404", (await call("GET", "/v1/nope")).status === 404);

console.log(failed ? `\n${failed} check(s) failed` : "\nAll checks passed");
process.exit(failed ? 1 : 0);
