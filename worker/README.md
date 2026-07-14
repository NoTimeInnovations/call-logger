# Call Logger ingestion backend (Cloudflare Worker)

Receives call records from the Android app and writes them to the cravingsv2 Hasura
DB **through a Cloudflare Queue**, so many partners sending many calls at once are
absorbed at the edge and written in efficient batches.

```
app  --POST /ingest (Bearer app key)-->  Worker (producer)  --enqueue-->  Queue
                                                    |                         |
                                              202 Accepted            consumer batches ≤100
                                                                       -> 1 idempotent insert -> Hasura
                                                                       (retry 5x -> dead-letter queue)
```

## Why a queue
- **Spike absorption:** `/ingest` just validates + enqueues and returns in milliseconds; the DB write is decoupled.
- **Batching:** up to 100 calls collapse into a single Hasura insert (`max_batch_size`), cutting write load under multi-partner load.
- **Durability:** transient Hasura failures are retried automatically; permanent failures land in `call-ingest-dlq` for inspection — nothing is silently lost.
- **Idempotent:** the insert is `on_conflict do-nothing` on `(account_email, event_key)`, so app resyncs *and* queue retries never create duplicates.

> Cloudflare Queues require the **Workers Paid** plan ($5/mo). If you can't use Queues, the consumer logic can be called inline from `/ingest` instead (less resilient under load) — ask and I'll wire that fallback.

## One-time setup

```bash
cd worker
npm install

# 1. Create the two queues
npx wrangler queues create call-ingest
npx wrangler queues create call-ingest-dlq

# 1b. Create the KV namespace for per-device tokens, then paste the id into wrangler.toml
npx wrangler kv namespace create TOKENS

# 2. Point the Worker at your Hasura instance
#    edit wrangler.toml -> [vars] HASURA_GRAPHQL_URL   (and HASURA_ROLE or remove it)
#    NOTE: /register reads the `partners` table with the admin secret (no role),
#          so keep the admin secret set even if HASURA_ROLE is used for inserts.

# 3. Create the Hasura tables (run in the Hasura console and TRACK every table):
#      worker/hasura/call_logs.sql
#      worker/hasura/flows_and_scheduler.sql

# 4. Set the secrets (never stored in the repo or the app)
npx wrangler secret put HASURA_ADMIN_SECRET   # your Hasura admin secret
npx wrangler secret put INGEST_APP_KEY        # a long random string: openssl rand -hex 32
npx wrangler secret put ADMIN_API_KEY         # for the superadmin flow/schedule endpoints
# Optional shared-number fallback (used only if a partner has no connected WhatsApp):
npx wrangler secret put WHATSAPP_PHONE_NUMBER_ID
npx wrangler secret put WHATSAPP_ACCESS_TOKEN

# 5. Deploy (registers the every-minute cron trigger automatically)
npx wrangler deploy
```

Local development: copy `.dev.vars.example` to `.dev.vars`, fill it in, then `npm run dev`.

## API

### `POST /register`
Headers: `Authorization: Bearer <INGEST_APP_KEY>` (shared bootstrap key)

```json
{ "email": "partner@example.com", "deviceId": "b3f1...uuid" }
```
Verifies the email is a real cravings-v2 partner, mints a per-device token and returns it once:
`201 { "token": "…", "partnerId": "uuid" }` · `404 { "error": "not a partner" }` if the email isn't a partner. The app stores the token in the Keystore and uses it for `/ingest`.

### `POST /ingest`
Headers: `Authorization: Bearer <per-device token from /register>`, `Content-Type: application/json`

```json
{
  "deviceId": "b3f1...uuid",
  "appVersion": "1.1.0",
  "calls": [
    {
      "eventKey": "9f86d0818...",
      "numberRaw": "+91 98765 43210",
      "e164": "+919876543210",
      "callType": 1,
      "callEpochMs": 1752470520000,
      "durationSec": 42,
      "tzIana": "Asia/Kolkata",
      "cachedName": "Ravi Kumar"
    }
  ]
}
```
Response `202`: `{ "accepted": 1 }`. The app should treat any `2xx` as "handed off" and mark those calls synced; retry on non-2xx / network error.

### `GET /health`
`200 { "ok": true }` — no auth, no secrets.

### Flow builder (partner uses the per-device token; superadmin uses `ADMIN_API_KEY`)
- `GET /flow` · `PUT /flow` — the partner reads/writes their own call-flow graph.
- `GET /admin/flow?partner=<id>` · `PUT /admin/flow?partner=<id>` — superadmin manages any partner's flow.

`PUT` body: `{ "name": "...", "enabled": true, "graph": { "nodes": [...], "edges": [...] } }` (graph shape documented in `hasura/flows_and_scheduler.sql`). Validated: one `trigger` node, unique ids, valid edges, bounded size.

### Scheduled bulk messages
- `GET /schedule` · `POST /schedule` — partner lists/creates.
- `GET /admin/schedule?partner=<id>` · `POST /admin/schedule?partner=<id>` — superadmin.

`POST` body: `{ "template": "promo", "language": "en", "params": ["{{contact_name}}"], "audience": { "mode": "all_called", "since": "2026-01-01T00:00:00Z" }, "scheduledAt": "2026-07-20T09:00:00Z" }`. `audience.mode` is `all_called` (everyone who called, inbound) or `selected` (`"numbers": ["+91..."]`).

## How sends happen
- The every-minute **cron** advances due flow steps and dispatches due scheduled messages.
- Sends go through **Meta Graph directly** using the partner's connected number+token read from `partners.whatsapp_numbers` (shared-number fallback via `WHATSAPP_*` secrets). Every send is idempotent (`wa_sends.dedupe_key`).
- ⚠️ **Confirm the `whatsapp_numbers` JSON shape:** `src/whatsapp.ts` extracts `phone_number_id` + `access_token` defensively (array or object map, common key names). Verify the field names against your Hasura data once; adjust `extractCreds()` if they differ.
- Inbound-reply detection (for `condition` nodes like "if not replied") needs a Meta inbound webhook — not wired yet; conditions currently treat "not_replied" as true. Ask to add the webhook.

## Security notes
- Hasura admin secret + app key live **only** in Worker Secrets — never in the APK or repo.
- App key is compared in **constant time**; the body is capped by a **streaming byte counter** (1 MB, does not trust `Content-Length`) and count-capped (1000 calls).
- **Per-account rate limiting** is built into the Worker (300 req/60s keyed by email, `[[unsafe.bindings]]` in `wrangler.toml`) — not deferred to an external rule.
- GraphQL uses **variables only** (no string building) — no injection surface.
- `event_key` is **always derived server-side** (client-supplied keys are ignored) — no dedup evasion.
- `duration_seconds` is clamped; secrets and full phone numbers are **never logged**.
- **Tenant isolation (closed):** `/ingest` authenticates a **per-device token** (minted by `/register`, stored only as its SHA-256 in KV) and derives `account_email` + `partner_id` server-side — the app can no longer attribute calls to another partner. The shared app key now only gates `/register`, which additionally verifies the email is a real cravings-v2 partner.
- **Remaining hardening:** `/register` proves *app authenticity* (app key) but not *email ownership* — someone with the app key could register a token for any partner email that exists. Add **email-OTP** to `/register` to fully bind it (cravings-v2 already has an OTP path). Also keep a Cloudflare WAF/rate rule on `/register`.
