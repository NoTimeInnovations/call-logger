# Call Logger → cravings-v2 WhatsApp Follow-up — Final Implementation Plan

**Status:** Ready for engineering, pending 6 owner decisions (Section 10)

Everything is grounded in the actual repo files (`CallEntity.kt`, `CallRepository.kt`, `AppDatabase.kt`, `PhoneStateReceiver.kt`, `OnboardingScreen.kt`, `SettingsManager.kt`) and the confirmed cravings-v2 surface (admin MCP tools + dev-team Hasura migrations only).

---

## 1. Executive summary

We are extending the existing Android call-logger so that **every captured call is durably recorded into cravings-v2 (Hasura/Postgres)** attributed to the partner identified by the onboarding email, and so that **each inbound caller is sent one WhatsApp message the day after, at the same wall-clock time**. The app gains INTERNET + an HTTP client + WorkManager + a durable upload outbox; cravings-v2 gains a **new, non-admin ingestion API** (the single most load-bearing new component), five new Postgres tables, and two crons. A per-device scoped token (minted only after **email-OTP proof**) authenticates ingestion; the app never holds the Hasura admin secret. Idempotency is guaranteed end-to-end by a device- and account-independent event key hashed over **immutable raw call facts only**, so resyncs, reinstalls, and email changes never produce duplicate rows or duplicate sends.

**The one hard compliance fact that shapes everything:** a caller who *phoned* the business by voice does **not** open a WhatsApp 24-hour customer-service window. The next-day message is therefore **business-initiated** and **MUST be sent as an APPROVED WhatsApp template** (never free-form text), gated by opt-out, consent, per-partner Meta daily tier caps, and a per-caller cool-down. Historical backfilled calls whose next-day slot is already in the past are **recorded but never sent**, to avoid a flood of stale unsolicited templates.

---

## 2. End-to-end architecture

```
 ANDROID APP (this repo: call-logger)                          cravings-v2 (separate repo: Next.js + Hasura + Postgres)
 ┌────────────────────────────────────────────┐               ┌──────────────────────────────────────────────────────────┐
 │ PhoneStateReceiver (IDLE)                    │               │                                                            │
 │   └─enqueue CaptureThenUploadWorker          │               │  POST /api/call-logger/register  (OTP-gated, no admin key)  │
 │ App open / 15-min periodic safety net        │               │     email + OTP  -> get_partner_by_email -> mint token      │
 │   └─CallRepository.syncFromDeviceCallLog()   │               │     store sha256(token) in cl_device_registrations          │
 │        reads system CallLog provider         │               │                                                            │
 │        writes Room 'calls' (+ client_uuid,   │  HTTPS +TLS   │  POST /api/call-logger/ingest  (Bearer device token)       │
 │        sync_state, tz_iana)   ── OUTBOX ──────┼──── gzip ────▶│     token_hash -> partner_id (x-hasura-partner-id)         │
 │ CallUploadWorker (WorkManager, backoff)      │   JSON batch  │     upsert cl_contacts / append cl_call_events (idempotent) │
 │   marks rows SYNCED only on 2xx echo         │◀── accepted ──┤     create cl_followups for NEW eligible inbound rows       │
 └────────────────────────────────────────────┘   uuids       │                          │                                 │
                                                                │                          ▼                                 │
                                                                │  cron: followup-dispatch (every 60s, SKIP LOCKED lease)    │
                                                                │     eligibility (opt-out, tier, cool-down, valid e164)     │
                                                                │     send_whatsapp_message(APPROVED template) ──────────────┼──▶ Meta Graph API
                                                                │     write whatsapp_message_logs + cl_followups.status       │
                                                                │                          ▲                                 │
                                                                │  POST /api/webhooks/whatsapp  (Meta signed) ───────────────┼──◀ delivery/read/failed
                                                                │     map wamid -> cl_followups (delivered/read/failed)       │      + inbound STOP
                                                                │                                                            │
                                                                │  CAMPAIGNS: cl_contacts + engagement ─▶ create_broadcast   │
                                                                │             (reuse existing broadcast dispatch/optout/tier)│
                                                                └──────────────────────────────────────────────────────────┘
```

Two data planes are deliberately decoupled: **recording** (Req 1, must never fail or block) and **messaging** (Req 3, gated and revocable). A call is always recorded even when it can never be messaged.

---

## 3. Data model (Hasura/Postgres) — ONE unified schema

All tables are created by a **Hasura migration in the cravings-v2 repo** (there is no DDL tool from this app). All are keyed to existing `partners.id` (uuid, unique email) — no partner data is duplicated. Prefix `cl_` = call-logger.

Phone numbers are stored **plaintext** so segmentation works, protected by disk encryption + Hasura row-level tenant isolation (not app-layer column encryption, which would break GIN/joins). Idempotency key excludes partner_id/email and e164. IANA zone id stored, never bare offset.

```sql
-- ENUMS (compact, query-friendly; raw android int also kept for XLSX parity)
CREATE TYPE cl_call_type      AS ENUM ('incoming','outgoing','missed','voicemail','rejected','blocked','unknown');
CREATE TYPE cl_call_direction AS ENUM ('inbound','outbound','unknown');   -- direction derived server-side at ingest
CREATE TYPE cl_followup_status AS ENUM
  ('pending','claimed','sending','sent','delivered','read','failed','skipped','cancelled');
```

### 3.1 cl_device_registrations — account link + per-device auth

```sql
CREATE TABLE cl_device_registrations (
  id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id        uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  email             citext      NOT NULL,                    -- snapshot for audit; partner_id is authoritative
  device_id         text        NOT NULL,                    -- app-generated random UUID (NOT ANDROID_ID)
  platform          text        NOT NULL DEFAULT 'android',
  app_version       text,
  model             text,
  default_timezone  text        NOT NULL DEFAULT 'Asia/Kolkata',  -- IANA id reported by device
  token_hash        bytea       NOT NULL,                    -- sha256(plaintext token); plaintext returned once
  token_prefix      text        NOT NULL,                    -- first 8 chars, for log correlation without leaking secret
  consent_version   text        NOT NULL,                    -- which disclosure text the user accepted
  consent_accepted_at timestamptz NOT NULL,
  status            text        NOT NULL DEFAULT 'active' CHECK (status IN ('active','revoked','suspended')),
  last_seen_at      timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  revoked_at        timestamptz,
  CONSTRAINT uq_cl_dev_partner_device UNIQUE (partner_id, device_id),  -- re-register = upsert, not a dup
  CONSTRAINT uq_cl_dev_token          UNIQUE (token_hash)
);
CREATE INDEX ix_cl_dev_partner ON cl_device_registrations (partner_id);
```

### 3.2 cl_contacts — campaign-grade lead (one row per partner+phone)

```sql
CREATE TABLE cl_contacts (
  id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id     uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  phone_e164     text        NOT NULL,                       -- normalized; plaintext for segmentation
  phone_raw      text,                                       -- last-seen as dialed
  display_name   text,                                       -- best CACHED_NAME seen
  first_call_at  timestamptz,
  last_call_at   timestamptz,
  last_inbound_at  timestamptz,
  last_contacted_at timestamptz,                             -- last WhatsApp actually sent (cool-down input)
  total_calls    int         NOT NULL DEFAULT 0,
  count_incoming int NOT NULL DEFAULT 0, count_outgoing int NOT NULL DEFAULT 0,
  count_missed   int NOT NULL DEFAULT 0, count_rejected int NOT NULL DEFAULT 0,
  count_blocked  int NOT NULL DEFAULT 0, count_voicemail int NOT NULL DEFAULT 0,
  total_talk_seconds bigint  NOT NULL DEFAULT 0,
  lifecycle_stage text       NOT NULL DEFAULT 'new'
                   CHECK (lifecycle_stage IN ('new','contacted','engaged','converted','dormant','blocked')),
  tags           text[]      NOT NULL DEFAULT '{}',
  attributes     jsonb       NOT NULL DEFAULT '{}',          -- open campaign attrs (city, cuisine_pref, ...)
  consent_status text        NOT NULL DEFAULT 'implied' CHECK (consent_status IN ('implied','explicit','none')),
  opted_out      boolean     NOT NULL DEFAULT false,         -- fast local cache of opt-out
  opted_out_at   timestamptz, opt_out_source text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_cl_contact UNIQUE (partner_id, phone_e164)   -- dedup key + primary lookup
);
CREATE INDEX ix_cl_contact_stage    ON cl_contacts (partner_id, lifecycle_stage);
CREATE INDEX ix_cl_contact_lastcall ON cl_contacts (partner_id, last_call_at DESC);
CREATE INDEX ix_cl_contact_tags     ON cl_contacts USING gin (tags);
CREATE INDEX ix_cl_contact_attrs    ON cl_contacts USING gin (attributes);
CREATE INDEX ix_cl_contact_active   ON cl_contacts (partner_id) WHERE opted_out = false;  -- eligible audience
```

### 3.3 cl_call_events — immutable, append-only, idempotent log

```sql
CREATE TABLE cl_call_events (
  id                 uuid       NOT NULL DEFAULT gen_random_uuid(),
  partner_id         uuid       NOT NULL REFERENCES partners(id),
  contact_id         uuid       NOT NULL REFERENCES cl_contacts(id),
  device_registration_id uuid   NOT NULL REFERENCES cl_device_registrations(id),
  event_key          text       NOT NULL,                    -- see IDEMPOTENCY below (device+account independent)
  number_raw         text       NOT NULL,                    -- exactly as CallLog.NUMBER ('Unknown' allowed)
  number_e164        text,                                   -- nullable when unparseable/private
  call_type          cl_call_type      NOT NULL,
  raw_type           smallint   NOT NULL,                    -- android CallLog.Calls constant (XLSX parity)
  direction          cl_call_direction NOT NULL,
  started_at         timestamptz NOT NULL,                   -- true UTC instant (from CallLog.DATE ms)
  local_started_at   timestamp  NOT NULL,                    -- wall-clock, no tz (drives +1 day same-wallclock)
  event_timezone     text       NOT NULL,                    -- IANA id captured on device (e.g. Asia/Kolkata)
  duration_seconds   bigint     NOT NULL DEFAULT 0,
  cached_name        text,
  source             text       NOT NULL DEFAULT 'device_calllog'
                     CHECK (source IN ('device_calllog','manual','import')),
  ingested_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, started_at)                               -- partition key must be in PK
) PARTITION BY RANGE (started_at);                           -- monthly partitions (pg_partman or manual + default)

-- IDEMPOTENCY (the backbone). event_key = sha256(digitsOnly(number_raw) || '|' || started_at_epoch_ms || '|' || raw_type)
-- computed IDENTICALLY on client and server, over IMMUTABLE call facts ONLY (no partner_id, no email, no e164).
-- Tenancy is scoped by the composite UNIQUE, NOT by putting partner in the hash:
CREATE UNIQUE INDEX uq_cl_event_key ON cl_call_events (partner_id, event_key, started_at);
CREATE INDEX ix_cl_event_partner_time ON cl_call_events (partner_id, started_at DESC);  -- hot query + XLSX range
CREATE INDEX ix_cl_event_contact      ON cl_call_events (contact_id, started_at DESC);
```

**Why this key:** raw digits (not e164) so client normalization and server forced-+91 normalization can never diverge and cause a duplicate send; no partner/email in the hash so it survives reinstall **and** an email change. Rows are never updated after insert; name backfill lives on `cl_contacts`.

### 3.4 cl_followups — the outbox and the single source of truth for "sent or not"

```sql
CREATE TABLE cl_followups (
  id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id         uuid        NOT NULL REFERENCES partners(id),
  contact_id         uuid        NOT NULL REFERENCES cl_contacts(id),
  call_event_id      uuid        NOT NULL,
  call_event_started_at timestamptz NOT NULL,                -- composite FK -> cl_call_events(id, started_at)
  contact_phone_e164 text        NOT NULL,
  call_local_time    time        NOT NULL,                   -- auditable "same time" components
  call_local_date    date        NOT NULL,
  schedule_tz        text        NOT NULL,                   -- IANA zone actually used to schedule
  tz_source          text        NOT NULL CHECK (tz_source IN ('partner','device','default')),
  scheduled_send_at  timestamptz NOT NULL,                   -- UTC instant, DST-correct (Section 6)
  status             cl_followup_status NOT NULL DEFAULT 'pending',
  skip_reason        text,                                   -- backfill_stale|opted_out|tier_cap_expired|no_e164|
                                                             --   invalid_number|self_number|no_template|cooldown|outbound_call
  attempts           int         NOT NULL DEFAULT 0,
  max_attempts       int         NOT NULL DEFAULT 5,
  next_attempt_at    timestamptz,
  claimed_by         text, claimed_at timestamptz, lease_expires_at timestamptz,
  send_idempotency_key text      NOT NULL,                   -- = followup id; passed to send primitive (exactly-once)
  template_id        uuid        REFERENCES whatsapp_message_templates(id),
  rendered_params    jsonb,
  wa_message_id      text,                                   -- Meta wamid (webhook correlation)
  whatsapp_message_log_id uuid   REFERENCES whatsapp_message_logs(id),
  delivery_status    text,                                   -- queued|sent|delivered|read|failed (from webhook)
  error_code text, error_detail text,
  sent_at timestamptz, delivered_at timestamptz, read_at timestamptz, failed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_cl_followup_event UNIQUE (call_event_id)     -- at most ONE follow-up per call, ever
);
-- Anti-spam: at most one send per caller per calendar day (a serial caller isn't messaged 5x):
CREATE UNIQUE INDEX uq_cl_followup_daily ON cl_followups (partner_id, contact_phone_e164, (scheduled_send_at::date))
  WHERE status NOT IN ('failed','skipped','cancelled');
-- HOT dispatch scan (kept tiny — rows flip out of 'pending' as they send):
CREATE INDEX ix_cl_followup_due   ON cl_followups (scheduled_send_at) WHERE status = 'pending';
CREATE INDEX ix_cl_followup_wamid ON cl_followups (wa_message_id);
CREATE INDEX ix_cl_followup_lease ON cl_followups (lease_expires_at) WHERE status IN ('claimed','sending');
```

### 3.5 cl_optout_global — cross-partner suppression (NEW; broadcast_optout is per-partner only)

```sql
-- broadcast_optout (existing) is checked per-partner; this adds a global list for DPDP erasure /
-- caller-initiated STOP that must apply across ALL partners and both partner-owned + shared numbers.
CREATE TABLE cl_optout_global (
  phone_e164 text PRIMARY KEY,
  source     text NOT NULL,                                  -- 'stop_reply'|'erasure_request'|'manual'
  created_at timestamptz NOT NULL DEFAULT now()
);
```

### 3.6 cl_campaigns / cl_campaign_targets — reuse the broadcast send path (Req 5)

```sql
CREATE TABLE cl_campaigns (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id uuid NOT NULL REFERENCES partners(id),
  name text NOT NULL, description text,
  template_id uuid REFERENCES whatsapp_message_templates(id),
  status text NOT NULL DEFAULT 'draft'
    CHECK (status IN ('draft','scheduled','running','paused','completed','cancelled')),
  segment_definition jsonb NOT NULL DEFAULT '{}',            -- whitelisted filter set (Section 7)
  scheduled_at timestamptz,                                  -- <= 90 days out (broadcast ceiling)
  broadcast_id uuid,                                         -- link to the created broadcast row
  default_params jsonb,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_cl_campaign ON cl_campaigns (partner_id, status);

CREATE TABLE cl_campaign_targets (   -- snapshot of the segment at build time (mirrors broadcast recipients)
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  campaign_id uuid NOT NULL REFERENCES cl_campaigns(id) ON DELETE CASCADE,
  partner_id  uuid NOT NULL REFERENCES partners(id),
  contact_id  uuid NOT NULL REFERENCES cl_contacts(id),
  phone_e164  text NOT NULL, rendered_params jsonb,
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','sent','failed','skipped','opted_out','cancelled')),
  whatsapp_message_log_id uuid REFERENCES whatsapp_message_logs(id),
  sent_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_cl_camp_target UNIQUE (campaign_id, contact_id)
);
```

---

## 4. Android app changes (this repo: `call-logger`)

### 4.1 Manifest / permissions (`app/src/main/AndroidManifest.xml`)

| Change | Reason |
|---|---|
| ADD `android.permission.INTERNET` | app has none today; required to reach ingestion API |
| ADD `android.permission.ACCESS_NETWORK_STATE` | WorkManager `CONNECTED` network constraint |
| ADD `android.permission.POST_NOTIFICATIONS` (runtime on API 33+) | only for the rare expedited-upload foreground notification |
| CHANGE `android:allowBackup="true"` → **`"false"`** | keep the encrypted token store out of cloud backups |
| KEEP `PhoneStateReceiver` manifest-registered | PHONE_STATE still deliverable to manifest receivers on API 26+; but strip network I/O from it (4.5) |
| ADD `network_security_config` with `cleartextTrafficPermitted="false"` | TLS-only for PII payloads |

### 4.2 Device registration + secure token storage

- New `AccountManager` wrapping **EncryptedSharedPreferences** (`androidx.security:security-crypto`, Keystore-backed AES256-GCM). Stores `partnerId`, `apiToken` (opaque bearer), `deviceId`, `registeredEmail`, `consentVersion`, `tokenIssuedAt`.
- `deviceId` = `UUID.randomUUID()` persisted once in the encrypted store (NOT `ANDROID_ID`). It is **not** part of the idempotency key, so a reinstall regenerating it is harmless.
- `SettingsManager` (existing plaintext prefs) keeps only non-secret UI flags (`selectedRange`, `uploadPaused`, `consentAccepted`). The API token **never** goes in plaintext prefs.

### 4.3 Room migration 1 → 2 (`AppDatabase.kt` version bump; `CallEntity.kt`)

Add columns to `calls` (all with defaults → no data loss; **never** `fallbackToDestructiveMigration`):

```sql
ALTER TABLE calls ADD COLUMN clientEventKey TEXT NOT NULL DEFAULT '';   -- = server event_key (raw-digits hash)
ALTER TABLE calls ADD COLUMN syncState INTEGER NOT NULL DEFAULT 0;       -- 0=PENDING 1=SYNCED 2=FAILED_TRANSIENT 3=FAILED_PERMANENT
ALTER TABLE calls ADD COLUMN syncAttempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE calls ADD COLUMN lastSyncAttemptAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE calls ADD COLUMN syncedAt INTEGER;
ALTER TABLE calls ADD COLUMN tzIana TEXT NOT NULL DEFAULT '';            -- TimeZone.getDefault().id (IANA, not offset)
ALTER TABLE calls ADD COLUMN e164 TEXT;
CREATE INDEX index_calls_syncState ON calls(syncState);
```

Because `clientEventKey` is hashed from **raw digits + epoch + type only** (no partnerId), it can be computed at capture time even before registration. Keep the existing `UNIQUE(number,date,type)` index; flip `exportSchema = true` and test with `MigrationTestHelper`.

### 4.4 CallRepository + DAO outbox

- In `syncFromDeviceCallLog()`, for each row also compute: `e164` (`PhoneNumberUtils.formatNumberToE164(number, simRegion ?: "IN")`), `tzIana = TimeZone.getDefault().id`, `clientEventKey = sha256Hex(digitsOnly(number) + "|" + date + "|" + type)`, `syncState = 0`. After first full scan, read only `CallLog.DATE > max(stored date)`. On `>0` inserts, enqueue the one-shot uploader.
- New DAO queries: `nextUnsynced(maxAttempts,limit)`, `markSynced(keys,ts)`, `markFailedTransient(keys,ts)`, `markFailedPermanent(keys)`, `observePendingCount(): Flow<Int>`.
- **Export path (`getRange`/`observeRange`) is untouched — XLSX keeps working regardless of sync state.**

### 4.5 PhoneStateReceiver hardening (`PhoneStateReceiver.kt`)

Replace `goAsync()` + `delay(2500)` + direct DB call (fragile; network in a broadcast is disallowed on modern Android). On `EXTRA_STATE_IDLE`, enqueue a `OneTimeWorkRequest<CaptureThenUploadWorker>` with ~3s initial delay; that worker runs `syncFromDeviceCallLog()` then chains `CallUploadWorker`. No network in the receiver.

### 4.6 WorkManager outbox uploader

`CallUploadWorker(CoroutineWorker)` with `Constraints{ CONNECTED }`:
- `doWork()`: if `uploadPaused` or no token → `Result.success()`. Loop `dao.nextUnsynced(maxAttempts=8, limit=200)`; gzip-POST batch; on 2xx map `accepted[]`→`markSynced`, permanent-rejected→`markFailedPermanent`, transient-rejected→leave pending; on 5xx/IOException→`Result.retry()`.
- Backoff: `setBackoffCriteria(EXPONENTIAL, 30s, …)`.
- Two enqueue paths, both **unique-named**: a `UNIQUE PeriodicWorkRequest` every 15 min (KEEP; safety net) and a `OneTimeWorkRequest` (APPEND_OR_REPLACE) fired after each call ends and app-open sync.
- **Honor server `429 Retry-After`.**

### 4.7 Upload payload shape

`partner_id` is **never** sent — the server derives it from the token.

```json
POST /api/call-logger/ingest
Authorization: Bearer <device token>
Content-Encoding: gzip
Idempotency-Key: <batchUuid>
{
  "deviceId": "b3f1...uuid",
  "appVersion": "1.1.0",
  "sentAt": 1752480000000,
  "calls": [
    {
      "clientEventKey": "9f86d0818...e3b0c44298",
      "numberRaw": "+91 98765 43210",
      "e164": "+919876543210",
      "callType": 3,
      "callEpochMs": 1752470520000,
      "tzIana": "Asia/Kolkata",
      "durationSec": 0,
      "cachedName": "Ravi Kumar"
    }
  ]
}
```
Response `200`: `{ "accepted": ["<clientEventKey>"...], "deduped": ["..."], "rejected": [{"clientEventKey":"...","reason":"..."}] }`. Client marks **only** `accepted`+`deduped` as SYNCED; a 2xx that omits a key leaves it pending.

### 4.8 Consent UX rewrite (`OnboardingScreen.kt`)

Line 116 currently reads **"Your email stays on this device."** — becomes false and must be **removed**. Replace with a two-part gate before any upload:
1. Disclosure block: *"Call Logger uploads your call history (caller numbers, names, call time, duration, direction) to your Menuthere account so we can message your callers on WhatsApp on your behalf. Only calls on this device linked to `<email>` are uploaded."*
2. **Required checkbox** (Continue disabled until checked): *"I have permission to contact these callers and agree to the Privacy Policy and WhatsApp messaging terms."*

Flow on Continue: send email-OTP → verify → `/register`. On `PARTNER_NOT_FOUND` show "This email isn't a Menuthere partner yet"; rows stay queued locally. `AppViewModel.saveEmail()` becomes `registerDevice(email, otp)`. Settings gains: **Upload toggle** (pause = capture continues locally, export still works — DPDP revocation), **"N calls waiting to upload" + Retry**, **Sign out**.

### 4.9 Historical backfill (first register)

After first `/register`, enqueue a **throttled** `BackfillWorker` paging `calls` oldest→newest (LIMIT 200/run), default **180-day** window (opt-in "all history"). Server dedups by `event_key` and — critically — the **server** suppresses stale sends (Section 6.4), so backfill can never fire late messages.

### 4.10 Networking deps to add

`retrofit 2.11.0` + kotlinx-serialization converter, `okhttp 4.12.0` + logging-interceptor (debug only), `kotlinx-serialization-json`, `androidx.work:work-runtime-ktx 2.9.x`, `androidx.security:security-crypto 1.1.0-alpha06`. Use platform `PhoneNumberUtils`. OkHttp interceptors: `AuthInterceptor`, `GzipRequestInterceptor`, `401→clear-token→re-register`.

---

## 5. Ingestion API (cravings-v2 repo) — the first thing built; single owner

This endpoint does not exist yet and is a **first-class cravings-v2 deliverable** (Next.js route → Hasura using a dedicated **non-admin** role). Nothing else works until it ships, so it is sequenced first (Phase 1).

### 5.1 Endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/call-logger/register` | email-OTP proof (reuse `send_email_otp`/`verify_email_otp`) | Verify email ownership → `get_partner_by_email` → mint 32-byte CSPRNG token, store `sha256(token)` in `cl_device_registrations`, return plaintext **once**. Idempotent on `(partner_id, device_id)`. |
| `POST /api/call-logger/ingest` | `Authorization: Bearer <device token>` | The **only** writer to `cl_*` ingestion tables. |
| `POST /api/webhooks/whatsapp` | Meta `X-Hub-Signature-256` (HMAC verified) | Delivery status + inbound STOP → opt-out. |

**Registration requires OTP:** a partner email is public; minting a token off a bare `get_partner_by_email` would let anyone inject calls and trigger sends from that partner's number. OTP binds the token to proven email ownership.

### 5.2 Ingest processing (idempotent, one transaction per batch)

1. `sha256(bearer)` → lookup active device registration → 401 if absent/revoked. Derive `partner_id`. **App never sends partner_id.**
2. Per event: server re-normalizes `number_e164` (region IN), derives `call_type`/`direction` from `raw_type`, recomputes `event_key` and **verifies it matches the client's**.
3. `INSERT INTO cl_contacts … ON CONFLICT (partner_id, phone_e164) DO UPDATE` (bump counters, `last_call_at`, `display_name`).
4. `INSERT INTO cl_call_events … ON CONFLICT (partner_id, event_key, started_at) DO NOTHING RETURNING id`.
5. For each **new inbound** event with valid `number_e164` and non-opted-out contact: `INSERT INTO cl_followups … ON CONFLICT (call_event_id) DO NOTHING`, with `scheduled_send_at` per Section 6 and stale-suppression (6.4).
6. Response: `{accepted, deduped, rejected}`.

### 5.3 Rate limiting & abuse

- `/register`: 5/hour/email and /IP.
- `/ingest`: 60 req/min/device, 10k events/day/partner; `429 + Retry-After` on breach.
- Reject batches with >100 events for a single number in 24h (scraping signal).

### 5.4 Hasura tenant isolation

- Dedicated role **`ingest_service`** with session var `x-hasura-partner-id` from the token lookup — **never** the admin secret.
- INSERT/UPDATE/SELECT on `cl_*` permitted only where `{partner_id: {_eq: X-Hasura-Partner-Id}}`.
- The Android app has **no** Hasura/GraphQL access; it speaks only to `/api/call-logger/*`. CI: decompile the release APK and grep for the admin secret / GraphQL endpoint → fail build if present.

---

## 6. Next-day-same-time scheduling + WhatsApp delivery

### 6.1 Timezone frame — DECIDED

Store the **device IANA zone id** (`event_timezone`) on every event *and* the true UTC instant. **Schedule in the partner's business timezone when set, else the device IANA zone, else `Asia/Kolkata`**, recording which in `tz_source`. Never store a bare UTC offset (can't carry DST).

### 6.2 `scheduled_send_at` computation (DST-safe, zoned — never epoch + 86400)

```
Z          = partner.timezone ?? event_timezone ?? 'Asia/Kolkata'
local      = started_at AT TIME ZONE Z          -- wall-clock at the call
target     = (local::date + 1) + local::time    -- same wall-clock, next calendar day
scheduled_send_at = target AT TIME ZONE Z       -- re-resolve through Z back to a UTC instant
```
`(local_started_at + interval '1 day') AT TIME ZONE event_timezone` yields the correct UTC instant across DST. India has no DST so the immediate case is trivially safe; the design is correct for any tenant.

### 6.3 Quiet-hours clamp — DECIDED (per-partner config, default ON)

Literal "3am call → 3am message" conflicts with quality/DPDP. A **per-partner `quiet_hours` config** (default **21:00–09:00 local, ON**); if `scheduled_send_at` falls inside it, roll forward to 09:00 local. Flagged for owner sign-off (Section 10).

### 6.4 Historical-backfill stale suppression — MANDATORY server rule

At follow-up creation, if `scheduled_send_at <= now()` **or** the call is older than 48h → insert as `status='skipped', skip_reason='backfill_stale'` (call still **recorded**; no message fires). Enforced **server-side** so a resync of old rows never triggers weeks-late unsolicited templates.

### 6.5 Dispatch cron (every 60s, lease-based, horizontally scalable)

```sql
UPDATE cl_followups SET status='claimed', claimed_at=now(), claimed_by=$worker,
       lease_expires_at=now()+interval '2 minutes'
WHERE id IN (
  SELECT id FROM cl_followups
  WHERE status='pending' AND scheduled_send_at <= now()
  ORDER BY scheduled_send_at
  LIMIT 200
  FOR UPDATE SKIP LOCKED               -- N workers grab disjoint rows; zero double-claim
) RETURNING *;
```
Lease reaper reclaims `status IN ('claimed','sending') AND lease_expires_at < now() AND wa_message_id IS NULL` back to `pending`.

### 6.6 Eligibility gate (at send time, first failure → `skipped`)

Order: (1) valid mobile E.164 → `invalid_number`; (2) not a partner-owned / Menuthere number → `self_number`; (3) not in `broadcast_optout` **and not in `cl_optout_global`** → `opted_out`; (4) `contact.opted_out=false`; (5) an APPROVED partner-owned template exists → `no_template`; (6) per-caller cool-down (≤1/contact/day, ≤1/rolling-7-days) → `cooldown`; (7) partner Meta daily **tier cap** (snapshot via `get_whatsapp_status`).

### 6.7 Exactly-once send

- Pass `send_idempotency_key = followup.id` to `send_whatsapp_message` (dev team adds an idempotency-key param).
- **If that param cannot be added:** reconciliation-before-resend is **mandatory** — before resending any `sending` row, query `whatsapp_message_logs` by `(partner_id, phone, template, day)`; resend only if none exists.
- `whatsapp_message_logs` written **transactionally** with the `cl_followups` status flip. Persist `wa_message_id` immediately on Meta 2xx. `uq_cl_followup_daily` is a second backstop.

### 6.8 Tier-cap = defer, with a hard ceiling

Cap hit → do **not** drop; leave `pending`, push `next_attempt_at` to the next window. **Ceiling:** undelivered > **72h** past its slot → `status='skipped', skip_reason='tier_cap_expired'`. Alert the partner on persistent backlog.

### 6.9 Approved template spec

- **Category: UTILITY** framed as a service follow-up to the actual call (easier approval, lower policy risk). Name e.g. `call_followup_reengage`, language `en` (+ variants).
- Body: `"Hi {{1}}, thanks for calling {{2}} yesterday. We'd love to help — reply here and our team will assist you."` `{{1}}`=caller name (fallback "there"), `{{2}}`=business name. FOOTER: `"Reply STOP to opt out."`
- Must be **APPROVED on the partner's own WABA** before sending; until then rows `skip('no_template')`. **Free-form is forbidden** (no 24h window from a voice call).

### 6.10 Delivery-status webhook mapping (Req 6)

Extend `POST /api/webhooks/whatsapp`: verify HMAC, then by `wa_message_id` advance `cl_followups.status` and set `delivered_at`/`read_at`/`failed_at`+`error_code`, **and** update `whatsapp_message_logs`. FSM: `pending → claimed → sending → sent → delivered → read`; any → `failed`/`skipped`/`cancelled`. Permanent Meta failure → `failed`, no retry. Inbound STOP → `broadcast_optout` **and** `cl_optout_global`. Nightly reconciliation: `sent` with no update after 48h → `delivered_unknown` (reporting only).

---

## 7. Campaign reuse (Req 5)

`cl_contacts` is already campaign-grade: rolling call counts, `last_call_at`, `lifecycle_stage`, `tags`, `attributes` (GIN-indexed), consent + opt-out. Future campaigns:

1. Define `cl_campaigns.segment_definition` as a **whitelisted filter set** over `cl_contacts` (`lifecycle_stage IN (...)`, `tags && (...)`, `last_call_at` window, `attributes @> (...)`, always `opted_out=false`). No free-form SQL.
2. Materialize matches into `cl_campaign_targets` (snapshot, `UNIQUE(campaign_id, contact_id)`).
3. **Send via the existing `create_broadcast`** — campaigns are batch/one-`scheduled_at`, exactly the broadcast model, which already dedupes recipients, filters `broadcast_optout`, and snapshots the Meta tier. The per-call outbox (`cl_followups`) is used **only** for arbitrary-minute next-day follow-ups. Also check `cl_optout_global` when building targets.

Phase-2 scope: data model + segment evaluator now; campaign UI later.

---

## 8. Compliance & privacy checklist

- [ ] **Business-initiated ⇒ APPROVED template only.** Dispatch hard-blocks free-form; `template_id` required and APPROVED.
- [ ] **Consent gate** in onboarding: remove "stays on this device"; required checkbox; record `consent_version`+timestamp locally and in `cl_device_registrations`.
- [ ] **Opt-out honored twice + globally:** `broadcast_optout` (per-partner) **and** `cl_optout_global` (cross-partner); STOP auto-adds to both; template footer carries STOP.
- [ ] **Per-caller cool-down** (≤1/day, ≤1/7-days).
- [ ] **Quiet hours** default 21:00–09:00 local (per-partner config).
- [ ] **Meta tier caps** respected; defer-not-drop with a 72h ceiling.
- [ ] **Force partner-owned WhatsApp numbers** for this feature (contains blast radius on quality rating).
- [ ] **No admin secret in APK** (CI decompile-grep); per-device token only; `token_hash` stored, plaintext shown once; TLS-only; `allowBackup=false`; EncryptedSharedPreferences.
- [ ] **Tenant isolation:** `ingest_service` role + row-level `x-hasura-partner-id`; `partner_id` server-derived.
- [ ] **DPDP:** at-rest disk encryption + row-level isolation; data minimization (no recordings/SMS/own-number); retention partitions + hard-delete cron (default 18 months); **right-to-erasure** endpoint (partner + caller-by-number, add to `cl_optout_global`).
- [ ] **Secrets:** Meta tokens only in server env / KMS, never returned/logged; logs scrub full numbers + tokens.
- [ ] **Honest capture promise:** "durable once captured," not absolute "no loss" (residual window if user clears system call log before a sync).

---

## 9. Phased rollout

| Phase | Ships | Repo |
|---|---|---|
| **0 — Decisions & schema lock** | Owner confirms Section 10; freeze the unified schema | — |
| **1 — Ingestion backbone (blocking; build first)** | Hasura migration (all `cl_*` tables + enums); `ingest_service` role + row-level perms; `POST /register` (OTP) + `POST /ingest`; rate limits | **cravings-v2** |
| **2 — App upload** | Manifest perms + `allowBackup=false`; Room 1→2 migration; `AccountManager`+EncryptedSharedPreferences; Retrofit/OkHttp/WorkManager; `event_key` at capture; `CallUploadWorker`; hardened receiver; consent UX; `BackfillWorker` | **call-logger (this repo)** |
| **3 — Follow-up scheduling + send** | Follow-up creation on ingest (+ stale suppression 6.4); `followup-dispatch` cron (SKIP LOCKED lease); eligibility gate; APPROVED template; send-idempotency; tier defer + 72h ceiling; quiet hours | **cravings-v2** |
| **4 — Delivery status + opt-out** | Extend `/api/webhooks/whatsapp` → `cl_followups` + `whatsapp_message_logs`; STOP → opt-out; reconciliation job; erasure endpoint | **cravings-v2** |
| **5 — Campaigns** | `segment_definition` evaluator; `cl_campaign_targets` build → `create_broadcast`; campaign UI | **cravings-v2** (+ optional app surface) |

Ordering rule: **Phase 1 before everything** (today the app cannot write to Hasura). Phase 2 and 3 can proceed in parallel once Phase 1's API contract is frozen. Sends (3) must not enable until the template is APPROVED and Phase 4 webhook wiring is ready.

---

## 10. Open decisions for the owner (recommended defaults in **bold**)

1. **Is the app email always an existing cravings-v2 partner?** Recommend **yes — reject registration if `get_partner_by_email` misses**; app shows "not a partner yet" and keeps calls queued locally.
2. **Template category: UTILITY vs MARKETING?** Recommend **UTILITY**, framed as a follow-up to the actual call.
3. **Send from partner-owned number vs Menuthere shared number?** Recommend **force partner-owned** (contains quality blast radius). Shared only as explicit opt-in fallback.
4. **Which call types trigger a follow-up?** Owner said "any type," but you can't sensibly WhatsApp people the business itself dialed. Recommend **inbound only (incoming/missed/rejected/voicemail/blocked); record all types, follow-up on inbound**; per-partner config.
5. **Backfill historical calls, how far?** Recommend **record up to 180 days on first register, but NEVER message stale calls**.
6. **Exact wall-clock vs quiet-hours clamp?** Recommend **quiet-hours ON (21:00–09:00 → roll to 09:00), per-partner configurable**.

Secondary: multi-device/multi-line per account (recommend **allow**, dedup follow-ups per caller/day across lines); `partners.timezone` source; retention window (default **18 months**).

---

**Files this plan concretely touches in `call-logger`:** `AndroidManifest.xml`, `app/build.gradle.kts`, `data/CallEntity.kt`, `data/AppDatabase.kt`, `data/CallDao.kt`, `data/CallRepository.kt`, `calllog/PhoneStateReceiver.kt`, `prefs/SettingsManager.kt`, `ui/OnboardingScreen.kt`, `ui/AppViewModel.kt`, plus new: `net/ApiService.kt`, `net/AccountManager.kt`, `work/CallUploadWorker.kt`, `work/CaptureThenUploadWorker.kt`, `work/BackfillWorker.kt`, `data/IdempotencyKey.kt`. **Everything server-side lives in the cravings-v2 repo.**
