# Dayrise sharing backend

Lets someone publish their habit list and lets accountability partners subscribe to it.
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

A whole list is stored as one JSON row. Publishing a change is one row write. A partner
refreshing is one or two row reads, and an unchanged list is answered with a `304` and no
body. A person who completes ten habits a day with three partners uses a few dozen requests.

## Run locally

```sh
npm run dev                      # serves http://localhost:8787 with a local database
npm run smoke                    # runs the end-to-end checks against it
```

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

Owner and partner requests send `Authorization: Bearer <token>`.

A snapshot holds the shared habits and about 400 days of entries. Notes, reminders, groups
and archived habits never leave the phone, and the owner can hide individual habits.

## Limits

- A snapshot may be at most 256 KB.
- A list may have at most 20 partners.
- Invite codes have about 50 bits of entropy. If the address becomes public, add a
  Cloudflare rate limiting rule on `/v1/join` to slow guessing further.
