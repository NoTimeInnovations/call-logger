/// <reference types="@cloudflare/workers-types" />
/**
 * Flow execution engine. A partner's flow is a node graph:
 *   trigger(call_received) -> send -> wait -> condition -> send ...
 *
 * On a new inbound call we create a flow_run (a snapshot of the graph + a cursor).
 * The Cron Trigger advances due runs: sends fire immediately, waits reschedule the
 * cursor for later, conditions branch. Every send is idempotent (unique dedupe_key
 * in wa_sends) so a crash/retry never double-messages a caller.
 */
import { hasura } from './hasura';
import { getWhatsAppCreds, isOptedOut, sendTemplate, WhatsAppEnv } from './whatsapp';

export type FlowEnv = WhatsAppEnv;

interface FlowNode {
  id: string;
  type: 'trigger' | 'send' | 'wait' | 'condition' | string;
  data?: Record<string, unknown>;
}
interface FlowEdge {
  from: string;
  to: string | null;
  branch?: string;
}
interface Graph {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

interface RunRow {
  id: string;
  partner_id: string;
  account_email: string;
  contact_e164: string;
  contact_name: string | null;
  graph: Graph;
  cursor_node_id: string;
  created_at: string;
}

/** A call row that just landed (from the queue consumer). */
export interface NewCall {
  id: string;
  partner_id: string | null;
  account_email: string;
  number_e164: string | null;
  cached_name: string | null;
  direction: string;
}

const MAX_STEPS = 25; // guard against malformed cyclic graphs
const LEASE_MS = 120_000;

function nodeById(g: Graph, id: string | null): FlowNode | null {
  if (!id) return null;
  return g.nodes.find((n) => n.id === id) ?? null;
}
function nextNodeId(g: Graph, fromId: string, branch?: string): string | null {
  const edge = branch
    ? g.edges.find((e) => e.from === fromId && e.branch === branch)
    : g.edges.find((e) => e.from === fromId);
  return edge?.to ?? null;
}
function triggerNode(g: Graph): FlowNode | null {
  return g.nodes.find((n) => n.type === 'trigger') ?? null;
}

/** Create flow_runs for new INBOUND calls whose partner has an enabled flow. */
export async function startFlowRunsForCalls(env: FlowEnv, calls: NewCall[]): Promise<void> {
  const inbound = calls.filter((c) => c.direction === 'inbound' && c.partner_id && c.number_e164);
  if (inbound.length === 0) return;

  // Cache each partner's enabled flow within this batch.
  const flowCache = new Map<string, { flow_id: string; graph: Graph; start: string } | null>();

  for (const c of inbound) {
    const partnerId = c.partner_id as string;
    let flow = flowCache.get(partnerId);
    if (flow === undefined) {
      flow = await loadEnabledFlow(env, partnerId);
      flowCache.set(partnerId, flow);
    }
    if (!flow) continue;

    await hasura(
      env,
      `mutation Start($o: flow_runs_insert_input!) {
        insert_flow_runs_one(object: $o, on_conflict: { constraint: flow_runs_call_log_id_key, update_columns: [] }) { id }
      }`,
      {
        o: {
          partner_id: partnerId,
          account_email: c.account_email,
          flow_id: flow.flow_id,
          call_log_id: c.id,
          contact_e164: c.number_e164,
          contact_name: c.cached_name,
          graph: flow.graph,
          cursor_node_id: flow.start,
          status: 'active',
        },
      }
    ).catch(() => {
      /* duplicate trigger for this call — ignore */
    });
  }
}

async function loadEnabledFlow(
  env: FlowEnv,
  partnerId: string
): Promise<{ flow_id: string; graph: Graph; start: string } | null> {
  const data = await hasura<{ call_flows: Array<{ id: string; graph: Graph; enabled: boolean }> }>(
    env,
    `query F($p: uuid!) { call_flows(where: { partner_id: { _eq: $p }, enabled: { _eq: true } }, limit: 1) { id graph enabled } }`,
    { p: partnerId }
  );
  const f = data.call_flows[0];
  if (!f || !f.graph) return null;
  const trg = triggerNode(f.graph);
  if (!trg) return null;
  let start = nextNodeId(f.graph, trg.id);
  if (!start) {
    // Tolerate a reversed edge (something -> trigger): the other end is the first step.
    const rev = f.graph.edges.find((e) => e.to === trg.id && e.from && e.from !== trg.id);
    start = rev ? rev.from : null;
  }
  if (!start) return null; // trigger with no action
  return { flow_id: f.id, graph: f.graph, start };
}

/** Cron: advance all runs whose cursor is due. */
export async function advanceDueRuns(env: FlowEnv, now: Date, limit = 200): Promise<number> {
  const nowIso = now.toISOString();
  const leaseIso = new Date(now.getTime() + LEASE_MS).toISOString();

  const due = await hasura<{ flow_runs: Array<{ id: string }> }>(
    env,
    `query Due($now: timestamptz!, $limit: Int!) {
      flow_runs(
        where: { status: { _eq: "active" }, next_due_at: { _lte: $now },
                 _or: [{ lease_until: { _is_null: true } }, { lease_until: { _lt: $now } }] }
        order_by: { next_due_at: asc }, limit: $limit
      ) { id }
    }`,
    { now: nowIso, limit }
  );

  let processed = 0;
  for (const { id } of due.flow_runs) {
    const claimed = await claimRun(env, id, nowIso, leaseIso);
    if (!claimed) continue;
    try {
      await runOne(env, claimed);
    } catch (e) {
      await failRun(env, id, (e as Error).message);
    }
    processed++;
  }
  return processed;
}

async function claimRun(
  env: FlowEnv,
  id: string,
  nowIso: string,
  leaseIso: string
): Promise<RunRow | null> {
  const res = await hasura<{ update_flow_runs: { returning: RunRow[] } }>(
    env,
    `mutation Claim($id: uuid!, $now: timestamptz!, $lease: timestamptz!) {
      update_flow_runs(
        where: { id: { _eq: $id }, status: { _eq: "active" },
                 _or: [{ lease_until: { _is_null: true } }, { lease_until: { _lt: $now } }] }
        _set: { lease_until: $lease, claimed_at: $now }
      ) { returning { id partner_id account_email contact_e164 contact_name graph cursor_node_id created_at } }
    }`,
    { id, now: nowIso, lease: leaseIso }
  );
  return res.update_flow_runs.returning[0] ?? null;
}

async function runOne(env: FlowEnv, run: RunRow): Promise<void> {
  const g = run.graph;
  let cursor: string | null = run.cursor_node_id;

  for (let steps = 0; steps < MAX_STEPS; steps++) {
    const node = nodeById(g, cursor);
    if (!node) return finishRun(env, run.id, 'done');

    if (node.type === 'send') {
      await executeSend(env, run, node);
      cursor = nextNodeId(g, node.id);
    } else if (node.type === 'wait') {
      const seconds = Number(node.data?.seconds) || 0;
      const next = nextNodeId(g, node.id);
      if (!next) return finishRun(env, run.id, 'done');
      const dueAt = new Date(Date.now() + seconds * 1000).toISOString();
      return scheduleNext(env, run.id, next, dueAt);
    } else if (node.type === 'condition') {
      const check = String(node.data?.check ?? 'not_replied');
      const replied = await hasReplied(env, run.contact_e164, run.created_at);
      const satisfied = check === 'replied' ? replied : !replied;
      cursor = nextNodeId(g, node.id, satisfied ? 'true' : 'false');
    } else {
      // trigger / unknown -> pass through
      cursor = nextNodeId(g, node.id);
    }
    if (!cursor) return finishRun(env, run.id, 'done');
  }
  // Too many steps (malformed graph) — stop safely.
  return finishRun(env, run.id, 'done');
}

/** Whether the contact has replied on WhatsApp since the run started (inbound webhook). */
async function hasReplied(env: FlowEnv, e164: string, sinceIso: string): Promise<boolean> {
  const d = await hasura<{ wa_replies: Array<{ id: string }> }>(
    env,
    `query R($p: String!, $s: timestamptz!) {
      wa_replies(where: { from_e164: { _eq: $p }, received_at: { _gte: $s } }, limit: 1) { id }
    }`,
    { p: e164, s: sinceIso }
  );
  return d.wa_replies.length > 0;
}

async function executeSend(env: FlowEnv, run: RunRow, node: FlowNode): Promise<void> {
  const template = String(node.data?.template ?? '').trim();
  if (!template) return;
  const language = String(node.data?.language ?? 'en');
  const dedupeKey = `flowrun:${run.id}:${node.id}`;

  // Claim this exact step by inserting a pending row; on conflict we already handled it.
  const claim = await hasura<{ insert_wa_sends_one: { id: string } | null }>(
    env,
    `mutation Claim($o: wa_sends_insert_input!) {
      insert_wa_sends_one(object: $o, on_conflict: { constraint: wa_sends_dedupe_key, update_columns: [] }) { id }
    }`,
    {
      o: {
        partner_id: run.partner_id,
        account_email: run.account_email,
        to_e164: run.contact_e164,
        template_name: template,
        language,
        source: 'flow',
        flow_run_id: run.id,
        dedupe_key: dedupeKey,
        status: 'pending',
      },
    },
    true
  );
  const sendId = claim.insert_wa_sends_one?.id;
  if (!sendId) return; // already sent/attempted by a prior run of this step

  if (await isOptedOut(env, run.contact_e164)) {
    await updateSend(env, sendId, 'failed', null, 'opted_out');
    return;
  }

  try {
    const creds = await getWhatsAppCreds(env, run.partner_id);
    if (!creds) throw new Error('no connected whatsapp number');
    const params = resolveParams(node.data?.params, {
      contact_name: run.contact_name || 'there',
      business_name: creds.businessName,
      number: run.contact_e164,
    });
    const mid = await sendTemplate(env, creds, run.contact_e164, { template, language, bodyParams: params });
    await updateSend(env, sendId, 'sent', mid, null);
  } catch (e) {
    await updateSend(env, sendId, 'failed', null, (e as Error).message);
  }
}

function resolveParams(raw: unknown, ctx: Record<string, string>): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((p) =>
    String(p).replace(/\{\{(\w+)\}\}/g, (_m, k: string) => ctx[k] ?? '')
  );
}

async function updateSend(
  env: FlowEnv,
  id: string,
  status: 'sent' | 'failed',
  waMessageId: string | null,
  error: string | null
): Promise<void> {
  await hasura(
    env,
    `mutation U($id: uuid!, $s: String!, $m: String, $e: String) {
      update_wa_sends_by_pk(pk_columns: { id: $id }, _set: { status: $s, wa_message_id: $m, error: $e }) { id }
    }`,
    { id, s: status, m: waMessageId, e: error?.slice(0, 500) ?? null },
    true
  );
}

async function scheduleNext(env: FlowEnv, id: string, cursor: string, dueAtIso: string): Promise<void> {
  await hasura(
    env,
    `mutation S($id: uuid!, $c: String!, $d: timestamptz!) {
      update_flow_runs_by_pk(pk_columns: { id: $id },
        _set: { cursor_node_id: $c, next_due_at: $d, lease_until: null }) { id }
    }`,
    { id, c: cursor, d: dueAtIso }
  );
}

async function finishRun(env: FlowEnv, id: string, status: 'done' | 'failed'): Promise<void> {
  await hasura(
    env,
    `mutation F($id: uuid!, $s: String!) {
      update_flow_runs_by_pk(pk_columns: { id: $id }, _set: { status: $s, lease_until: null }) { id }
    }`,
    { id, s: status }
  );
}

async function failRun(env: FlowEnv, id: string, err: string): Promise<void> {
  await hasura(
    env,
    `mutation FE($id: uuid!, $e: String!) {
      update_flow_runs_by_pk(pk_columns: { id: $id },
        _set: { status: "failed", last_error: $e, lease_until: null }) { id }
    }`,
    { id, e: err.slice(0, 500) }
  ).catch(() => {});
}
