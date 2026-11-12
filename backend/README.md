# Dayrise sharing backend

Lets someone publish their habit list and lets accountability partners subscribe to it, and keeps
a cloud backup of each phone's data that a recovery code restores.
It is one Cloudflare Worker and one D1 (SQLite) database, with no other dependencies.

## Deploy

You need a free Cloudflare account and Node 20 or newer.

```sh
cd backend
npm install
npx wrangler login
npm run deploy
```

The shared deployment lives at `https://dayrise-sharing.dayrise-sharing.workers.dev`, and
`wrangler.jsonc` pins its D1 database, so `npm run deploy` from that Cloudflare account updates it
in place. To run your own copy on another account, delete `database_id` from `wrangler.jsonc`
first: the deploy then creates a fresh database and prints the new Worker's address. The Worker
creates its own tables on first use, so there is no migration step. A brand-new `workers.dev`
subdomain can take a few minutes to get its HTTPS certificate.

Then point the app at it, either way:

- In the app: Partners, then Server, then paste the address.
- At build time: add `dayrise.sharingUrl=https://…` to `local.properties` in the project root.

Check a deployment end to end with `npm run smoke -- https://your-address`.

## Cost

Everything fits in Cloudflare's free plan, which allows 100,000 Worker requests a day and,
for D1, 5 million row reads and 100,000 row writes a day.

A cloud backup is one row too. The phone uploads at most once every 15 seconds while you're
making changes, and skips the upload when nothing changed.

A whole list is stored as one JSON row. Publishing a change is one row write. A partner
refreshing is one or two row reads, and an unchanged list is answered with a `304` and no
body. A person who completes ten habits a day with three partners uses a few dozen requests.

## Run locally

```sh
npm run dev                      # serves http://localhost:8787 with a local database
npm run smoke                    # runs the end-to-end checks against it
```

`npm run dev` turns off the per-IP caps (`--var RATE_LIMITS:off`) so the smoke test can be run
again and again. Use `npm run dev:limits` to try the caps locally.

To use the local server from a phone on USB, run `adb reverse tcp:8787 tcp:8787` and set
the app's server address to `http://localhost:8787`. Plain HTTP is only allowed for
`localhost`, `127.0.0.1` and `10.0.2.2` (the emulator's name for your computer).

The Android unit tests include a client-to-server check that runs when a server is given:

```sh
DAYRISE_TEST_SERVER=http://localhost:8787 ./gradlew :app:testDebugUnitTest
```

## How it works

There are no accounts. Creating a share returns a secret owner token and a ten-character
invite code. Redeeming the code returns a secret subscriber token. Only SHA-256 hashes of
tokens are stored, so a database leak does not expose working credentials.

| Request | Who | Does |
| --- | --- | --- |
| `POST /v1/shares` | anyone | Creates a share. Returns `id`, `token`, `code`. |
| `PUT /v1/shares/:id` | owner | Publishes a snapshot of the list. |
| `GET /v1/shares/:id` | owner | Returns the invite code and the partners. |
| `POST /v1/shares/:id/rotate-code` | owner | Replaces the invite code. Existing partners keep access. |
| `DELETE /v1/shares/:id/subscribers/:sid` | owner | Removes one partner. |
| `DELETE /v1/shares/:id` | owner | Deletes the list and ends every subscription. |
| `POST /v1/join` | anyone with a code | Subscribes. Returns a subscriber `token`. |
| `GET /v1/feed` | partner | Returns the latest snapshot, or `304` if unchanged. |
| `DELETE /v1/feed` | partner | Unsubscribes. |
| `GET /j/:code` | anyone | Invite page with a link that opens the app. |
| `POST /v1/backups` | anyone | Creates an empty cloud backup. Returns a 30-character recovery `code`. |
| `PUT /v1/backups` | code holder | Replaces the backup. Send `baseVersion` to get `409` if another phone wrote first. |
| `GET /v1/backups` | code holder | Returns the backup, or `304` if unchanged. |
| `DELETE /v1/backups` | code holder | Deletes the backup. |

Owner and partner requests send `Authorization: Bearer <token>`. Backup requests send the recovery
code as the bearer token. It has about 148 bits of entropy and only its SHA-256 is stored.

A snapshot holds the shared habits and about 400 days of entries. Notes, reminders, groups
and archived habits never leave the phone, and the owner can hide individual habits.

## Limits

- A snapshot may be at most 256 KB.
- A list may have at most 20 partners.
- A cloud backup may be at most 1 MB, which is many years of history.
- Invite codes have about 50 bits of entropy.

## Abuse protection

Every limit answers `429` with a `Retry-After` header.

| Limit | Where |
| --- | --- |
| 120 API requests a minute per IP | Workers rate limiting binding (`BURST` in `wrangler.jsonc`), no database cost |
| 5 new backups a day per IP, 2,000 a day in total | `rate_limits` table |
| 10 new shares a day per IP | `rate_limits` table |
| 30 invite-code attempts an hour per IP, right or wrong | `rate_limits` table |
| 1 write every 10 seconds and 500 writes a day per backup | the backup's own row |
| Request bodies are cut off as soon as they pass the size cap | `readBody` |

IP addresses are hashed before they're stored. A daily cron (`triggers` in `wrangler.jsonc`)
deletes backups that haven't been written to in 400 days, backups that were created but never
written to, and old rate-limit counters.
