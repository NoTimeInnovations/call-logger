/// <reference types="@cloudflare/workers-types" />
/**
 * Scheduled bulk messages: send a template to all (or a selected list of) customers
 * who called a partner. When a message becomes due the cron builds its recipient
 * snapshot from call_logs, then sends in batches across ticks. Every send is
 * idempotent (wa_sends dedupe_key) so retries never double-message.
 */
import { hasura } from './hasura';
import { getWhatsAppCreds, sendTemplate, WhatsAppEnv, WaCreds } from './whatsapp';

export type SchedulerEnv = WhatsAppEnv;

const LEASE_MS = 120_000;
const MSG_LIMIT = 5; // messages processed per tick

interface SchedMsg {
  id: string;
  partner_id: string;
  account_email: string;
  template_name: string;
  language: string;
  params: unknown;
  audience: { mode?: string; numbers?: string[]; since?: string; types?: number[] };
  targets_built: boolean;
}

export async function dispatchDueScheduledMessages(
  env: SchedulerEnv,
  now: Date,
  targetBatch = 50
): Promise<number> {
  const nowIso = now.toISOString();
  const leaseIso = new Date(now.getTime() + LEASE_MS).toISOString();

  const due = await hasura<{ scheduled_messages: SchedMsg[] }>(
    env,
    `query Due($now: timestamptz!, $limit: Int!) {
      scheduled_messages(
        where: { status: { _in: ["scheduled", "processing"] }, scheduled_at: { _lte: $now },
                 _or: [{ lease_until: { _is_null: true } }, { lease_until: { _lt: $now } }] }
        order_by: { scheduled_at: asc }, limit: $limit
      ) { id partner_id account_email template_name language params audience targets_built }
    }`,
    { now: nowIso, limit: MSG_LIMIT }
  );

  let processed = 0;
  for (const m of due.scheduled_messages) {
    const claimed = await claim(env, m.id, nowIso, leaseIso);
    if (!claimed) continue;
    try {
      await processMessage(env, m, targetBatch);
    } catch (e) {
      await setStatus(env, m.id, 'failed', (e as Error).message);
    }
    processed++;
  }
  return processed;
}

async function claim(env: SchedulerEnv, id: string, nowIso: string, leaseIso: string): Promise<boolean> {
  const res = await hasura<{ update_scheduled_messages: { affected_rows: number } }>(
    env,
    `mutation C($id: uuid!, $now: timestamptz!, $lease: timestamptz!) {
      update_scheduled_messages(
        where: { id: { _eq: $id }, status: { _in: ["scheduled", "processing"] },
                 _or: [{ lease_until: { _is_null: true } }, { lease_until: { _lt: $now } }] }
        _set: { status: "processing", claimed_at: $now, lease_until: $lease }
      ) { affected_rows }
    }`,
    { id, now: nowIso, lease: leaseIso }
  );
  return res.update_scheduled_messages.affected_rows > 0;
}

async function processMessage(env: SchedulerEnv, m: SchedMsg, targetBatch: number): Promise<void> {
  if (!m.targets_built) {
    await buildTargets(env, m);
    await hasura(
      env,
      `mutation B($id: uuid!) { update_scheduled_messages_by_pk(pk_columns: { id: $id }, _set: { targets_built: true }) { id } }`,
      { id: m.id }
    );
  }

  const pending = await hasura<{
    scheduled_message_targets: Array<{ id: string; to_e164: string; contact_name: string | null }>;
  }>(
    env,
    `query P($id: uuid!, $limit: Int!) {
      scheduled_message_targets(where: { scheduled_message_id: { _eq: $id }, status: { _eq: "pending" } }, limit: $limit) {
        id to_e164 contact_name
      }
    }`,
    { id: m.id, limit: targetBatch }
  );

  if (pending.scheduled_message_targets.length === 0) {
    await setStatus(env, m.id, 'done', null);
    return;
  }

  const creds = await getWhatsAppCreds(env, m.partner_id);
  for (const t of pending.scheduled_message_targets) {
    await sendToTarget(env, m, t, creds);
  }

  // Release the lease so the next tick continues the remaining targets.
  await hasura(
    env,
    `mutation R($id: uuid!) { update_scheduled_messages_by_pk(pk_columns: { id: $id }, _set: { lease_until: null }) { id } }`,
    { id: m.id }
  );
}

async function sendToTarget(
  env: SchedulerEnv,
  m: SchedMsg,
  t: { id: string; to_e164: string; contact_name: string | null },
  creds: WaCreds | null
): Promise<void> {
  const dedupeKey = `sched:${m.id}:${t.to_e164}`;
  const claimIns = await hasura<{ insert_wa_sends_one: { id: string } | null }>(
    env,
    `mutation Claim($o: wa_sends_insert_input!) {
      insert_wa_sends_one(object: $o, on_conflict: { constraint: wa_sends_dedupe_key, update_columns: [] }) { id }
    }`,
    {
      o: {
        partner_id: m.partner_id,
        account_email: m.account_email,
        to_e164: t.to_e164,
        template_name: m.template_name,
        language: m.language,
        source: 'schedule',
        scheduled_message_id: m.id,
        dedupe_key: dedupeKey,
        status: 'pending',
      },
    },
    true
  );
  const sendId = claimIns.insert_wa_sends_one?.id;
  if (!sendId) {
    // Already handled in a prior tick; reflect that on the target if still pending.
    await setTarget(env, t.id, 'sent', null, null);
    return;
  }

  try {
    if (!creds) throw new Error('no connected whatsapp number');
    const params = resolveParams(m.params, {
      contact_name: t.contact_name || 'there',
      business_name: creds.businessName,
      number: t.to_e164,
    });
    const mid = await sendTemplate(env, creds, t.to_e164, {
      template: m.template_name,
      language: m.language,
      bodyParams: params,
    });
    await updateWaSend(env, sendId, 'sent', mid, null);
    await setTarget(env, t.id, 'sent', mid, null);
  } catch (e) {
    const err = (e as Error).message;
    await updateWaSend(env, sendId, 'failed', null, err);
    await setTarget(env, t.id, 'failed', null, err);
  }
}

async function buildTargets(env: SchedulerEnv, m: SchedMsg): Promise<void> {
  let recipients: Array<{ to_e164: string; contact_name: string | null }> = [];

  if (m.audience?.mode === 'selected' && Array.isArray(m.audience.numbers)) {
    recipients = m.audience.numbers
      .map((n) => normalize(n))
      .filter((n): n is string => !!n)
      .map((n) => ({ to_e164: n, contact_name: null }));
  } else {
    const since = m.audience?.since || '1970-01-01T00:00:00Z';
    const data = await hasura<{
      call_logs: Array<{ number_e164: string; cached_name: string | null }>;
    }>(
      env,
      `query A($p: uuid!, $since: timestamptz!) {
        call_logs(
          where: { partner_id: { _eq: $p }, direction: { _eq: "inbound" },
                   number_e164: { _is_null: false }, started_at: { _gte: $since } }
          distinct_on: number_e164, order_by: { number_e164: asc }
        ) { number_e164 cached_name }
      }`,
      { p: m.partner_id, since }
    );
    recipients = data.call_logs.map((r) => ({ to_e164: r.number_e164, contact_name: r.cached_name }));
  }

  // Dedupe + insert in chunks.
  const seen = new Set<string>();
  const rows = recipients.filter((r) => (seen.has(r.to_e164) ? false : (seen.add(r.to_e164), true)));
  for (let i = 0; i < rows.length; i += 500) {
    const chunk = rows.slice(i, i + 500).map((r) => ({
      scheduled_message_id: m.id,
      partner_id: m.partner_id,
      to_e164: r.to_e164,
      contact_name: r.contact_name,
      status: 'pending',
    }));
    await hasura(
      env,
      `mutation Ins($objs: [scheduled_message_targets_insert_input!]!) {
        insert_scheduled_message_targets(objects: $objs, on_conflict: { constraint: sched_target_unique, update_columns: [] }) { affected_rows }
      }`,
      { objs: chunk }
    );
  }
}

function resolveParams(raw: unknown, ctx: Record<string, string>): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((p) => String(p).replace(/\{\{(\w+)\}\}/g, (_m, k: string) => ctx[k] ?? ''));
}

function normalize(n: string): string | null {
  const cleaned = String(n).replace(/[^\d+]/g, '');
  return cleaned.length >= 8 ? cleaned : null;
}

async function setStatus(env: SchedulerEnv, id: string, status: string, err: string | null): Promise<void> {
  await hasura(
    env,
    `mutation S($id: uuid!, $s: String!) {
      update_scheduled_messages_by_pk(pk_columns: { id: $id }, _set: { status: $s, lease_until: null }) { id }
    }`,
    { id, s: status }
  ).catch(() => {});
  if (err) console.log(`scheduled_message ${id} ${status}: ${err}`);
}

async function setTarget(
  env: SchedulerEnv,
  id: string,
  status: string,
  mid: string | null,
  err: string | null
): Promise<void> {
  await hasura(
    env,
    `mutation T($id: uuid!, $s: String!, $m: String, $e: String) {
      update_scheduled_message_targets_by_pk(pk_columns: { id: $id }, _set: { status: $s, wa_message_id: $m, error: $e }) { id }
    }`,
    { id, s: status, m: mid, e: err?.slice(0, 500) ?? null }
  );
}

async function updateWaSend(
  env: SchedulerEnv,
  id: string,
  status: string,
  mid: string | null,
  err: string | null
): Promise<void> {
  await hasura(
    env,
    `mutation U($id: uuid!, $s: String!, $m: String, $e: String) {
      update_wa_sends_by_pk(pk_columns: { id: $id }, _set: { status: $s, wa_message_id: $m, error: $e }) { id }
    }`,
    { id, s: status, m: mid, e: err?.slice(0, 500) ?? null },
    true
  );
}
