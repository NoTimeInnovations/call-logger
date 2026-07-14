-- ============================================================================
-- Flow builder + scheduler tables. Apply in the cravingsv2 Hasura project after
-- call_logs.sql and TRACK each table. All are keyed to partners(id).
--
-- The flow "graph" JSON (edited by the app + superadmin, executed by the Worker):
--   {
--     "nodes": [
--       {"id":"trigger","type":"trigger","data":{"event":"call_received"}},
--       {"id":"n1","type":"send","data":{"template":"call_followup","language":"en","params":["{{contact_name}}","{{business_name}}"]}},
--       {"id":"n2","type":"wait","data":{"seconds":86400}},
--       {"id":"n3","type":"condition","data":{"check":"not_replied"}}
--     ],
--     "edges": [
--       {"from":"trigger","to":"n1"},
--       {"from":"n1","to":"n2"},
--       {"from":"n2","to":"n3"},
--       {"from":"n3","to":"n4","branch":"true"},
--       {"from":"n3","to":null,"branch":"false"}
--     ]
--   }
-- Node types: trigger (fixed: call_received) | send | wait | condition.
-- ============================================================================

-- One editable flow per partner (the "what happens after a call" definition).
CREATE TABLE IF NOT EXISTS public.call_flows (
  id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id    uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  account_email text,                                   -- nullable: admin may edit before it's known
  name          text        NOT NULL DEFAULT 'Call follow-up',
  graph         jsonb       NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb,
  enabled       boolean     NOT NULL DEFAULT false,
  updated_by    text,                                   -- 'partner:<email>' | 'admin:<user>'
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT call_flows_partner_key UNIQUE (partner_id)
);

-- One run per triggering call. The cron advances active runs whose next step is due.
CREATE TABLE IF NOT EXISTS public.flow_runs (
  id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id     uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  account_email  text        NOT NULL,
  flow_id        uuid        REFERENCES call_flows(id) ON DELETE SET NULL,
  call_log_id    uuid,                                  -- the call that triggered it
  contact_e164   text        NOT NULL,                  -- who we message
  contact_name   text,
  graph          jsonb       NOT NULL,                  -- snapshot of the flow at trigger time
  cursor_node_id text        NOT NULL,                  -- next node to execute
  status         text        NOT NULL DEFAULT 'active'  -- active|done|failed|cancelled
                 CHECK (status IN ('active','done','failed','cancelled')),
  next_due_at    timestamptz NOT NULL DEFAULT now(),    -- when the cursor node should run
  attempts       int         NOT NULL DEFAULT 0,
  claimed_at     timestamptz,
  lease_until    timestamptz,
  last_error     text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
-- Hot due-scan for the dispatch cron.
CREATE INDEX IF NOT EXISTS flow_runs_due_idx
  ON public.flow_runs (next_due_at) WHERE status = 'active';
-- One live run per call (idempotent trigger). A full UNIQUE CONSTRAINT (not a
-- partial index) — Postgres allows multiple NULL call_log_id, and Hasura's
-- on_conflict requires a real constraint (partial indexes are rejected).
ALTER TABLE public.flow_runs ADD CONSTRAINT flow_runs_call_log_id_key UNIQUE (call_log_id);

-- Every WhatsApp send attempt (from a flow or a scheduled message). Source of truth
-- for "sent or not" + cross-run dedupe.
CREATE TABLE IF NOT EXISTS public.wa_sends (
  id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id           uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  account_email        text        NOT NULL,
  to_e164              text        NOT NULL,
  template_name        text        NOT NULL,
  language             text        NOT NULL DEFAULT 'en',
  source               text        NOT NULL,            -- 'flow' | 'schedule'
  flow_run_id          uuid        REFERENCES flow_runs(id) ON DELETE SET NULL,
  scheduled_message_id uuid,
  dedupe_key           text        NOT NULL,            -- e.g. flowrun:<id>:<node> or sched:<id>:<to>
  status               text        NOT NULL DEFAULT 'pending'  -- pending|sent|failed
                       CHECK (status IN ('pending','sent','failed')),
  wa_message_id        text,
  error                text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT wa_sends_dedupe_key UNIQUE (dedupe_key)    -- exactly-once per flow step / schedule target
);
CREATE INDEX IF NOT EXISTS wa_sends_partner_idx ON public.wa_sends (partner_id, created_at DESC);

-- Bulk scheduled messages to called customers (all or a selected list).
CREATE TABLE IF NOT EXISTS public.scheduled_messages (
  id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  partner_id    uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  account_email text,                                   -- nullable: admin-created schedules
  name          text,
  template_name text        NOT NULL,
  language      text        NOT NULL DEFAULT 'en',
  params        jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- template body params (with placeholders)
  audience      jsonb       NOT NULL DEFAULT '{}'::jsonb,   -- {"mode":"all_called"|"selected","numbers":[...],"since":"ISO","types":["incoming"]}
  scheduled_at  timestamptz NOT NULL,
  status        text        NOT NULL DEFAULT 'scheduled'    -- scheduled|processing|done|cancelled|failed
                CHECK (status IN ('scheduled','processing','done','cancelled','failed')),
  targets_built boolean     NOT NULL DEFAULT false,
  claimed_at    timestamptz,
  lease_until   timestamptz,
  created_by    text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS scheduled_messages_due_idx
  ON public.scheduled_messages (scheduled_at) WHERE status IN ('scheduled','processing');

-- Snapshot of a scheduled message's recipients (built when it becomes due).
CREATE TABLE IF NOT EXISTS public.scheduled_message_targets (
  id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  scheduled_message_id uuid        NOT NULL REFERENCES scheduled_messages(id) ON DELETE CASCADE,
  partner_id           uuid        NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
  to_e164              text        NOT NULL,
  contact_name         text,
  status               text        NOT NULL DEFAULT 'pending'  -- pending|sent|failed
                       CHECK (status IN ('pending','sent','failed')),
  wa_message_id        text,
  error                text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sched_target_unique UNIQUE (scheduled_message_id, to_e164)
);
CREATE INDEX IF NOT EXISTS sched_targets_pending_idx
  ON public.scheduled_message_targets (scheduled_message_id) WHERE status = 'pending';
