-- ============================================================================
-- Inbound WhatsApp: replies (for "if not replied" flow conditions) + opt-outs (STOP).
-- Apply after the other SQL files and TRACK both tables.
-- ============================================================================

-- Every inbound WhatsApp message from a customer (used to detect replies).
CREATE TABLE IF NOT EXISTS public.wa_replies (
  id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  phone_number_id text,                          -- the business number that received it
  from_e164       text        NOT NULL,          -- the customer who replied (+E.164)
  wa_message_id   text,
  text_body       text,
  received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS wa_replies_from_idx ON public.wa_replies (from_e164, received_at DESC);

-- Global opt-out list (STOP/UNSUBSCRIBE). Checked before every send.
CREATE TABLE IF NOT EXISTS public.cl_optout (
  phone_e164 text PRIMARY KEY,
  source     text NOT NULL DEFAULT 'stop_reply',
  created_at timestamptz NOT NULL DEFAULT now()
);
