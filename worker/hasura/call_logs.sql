-- ============================================================================
-- call_logs  —  every captured call from the Call Logger app, one row per call.
-- Apply this in the cravingsv2 Hasura project (Data -> SQL, tick "track this"),
-- or add it as a Hasura migration. There is no DDL access from the Worker/app.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.call_logs (
  id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  account_email    text        NOT NULL,               -- identifies the partner/account (1 email = 1 account)
  partner_id       uuid,                               -- cravings-v2 partners.id, resolved at /register (nullable)
  event_key        text        NOT NULL,               -- sha256(digitsOnly(number)|epochMs|rawType) — idempotency
  number_raw       text        NOT NULL,               -- as dialed / as stored by the device
  number_e164      text,                               -- normalized, when parseable
  raw_type         smallint    NOT NULL,               -- Android CallLog.Calls.TYPE (1..7)
  call_type        text        NOT NULL,               -- incoming|outgoing|missed|voicemail|rejected|blocked|...
  direction        text        NOT NULL,               -- inbound|outbound|unknown
  started_at       timestamptz NOT NULL,               -- when the call happened (UTC)
  event_timezone   text,                               -- IANA zone reported by the device (e.g. Asia/Kolkata)
  duration_seconds bigint      NOT NULL DEFAULT 0,
  cached_name      text,                               -- contact name known to the device, if any
  device_id        text,                               -- app-generated device UUID
  app_version      text,
  source           text        NOT NULL DEFAULT 'android_calllog',
  created_at       timestamptz NOT NULL DEFAULT now(), -- when the row landed in Hasura

  -- Idempotency backbone: a given call for a given account can exist only once,
  -- so app resyncs and queue retries collapse to a no-op.
  CONSTRAINT call_logs_account_email_event_key_key UNIQUE (account_email, event_key)
);

-- Hot query: a partner's calls newest-first (dashboards / exports).
CREATE INDEX IF NOT EXISTS call_logs_account_started_idx
  ON public.call_logs (account_email, started_at DESC);

-- Lookup by caller (future follow-up / campaign use).
CREATE INDEX IF NOT EXISTS call_logs_account_number_idx
  ON public.call_logs (account_email, number_e164);

-- Join to cravings-v2 partner (dashboards / WhatsApp follow-up).
CREATE INDEX IF NOT EXISTS call_logs_partner_idx
  ON public.call_logs (partner_id, started_at DESC);

-- ----------------------------------------------------------------------------
-- OPTIONAL least-privilege role (defense in depth). If you set HASURA_ROLE=call_ingest
-- in wrangler.toml, the Worker still sends the admin secret but runs under this role,
-- which can ONLY insert into call_logs (no reads/updates/deletes, no other tables).
-- Configure in Hasura Console -> call_logs -> Permissions -> role "call_ingest":
--   * insert: columns = all above except id/created_at; no row check needed
--   * select/update/delete: none
-- Leave HASURA_ROLE unset to skip this and use admin.
-- ----------------------------------------------------------------------------
