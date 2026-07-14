/// <reference types="@cloudflare/workers-types" />
/**
 * Call Logger ingestion backend (Cloudflare Worker).
 *
 * Flow:  Android app  --POST /ingest (Bearer app key)-->  this Worker (producer)
 *          -> validate + normalize + enqueue to Cloudflare Queue  -> 202 Accepted (fast)
 *          -> queue consumer batches up to 100 calls -> single idempotent GraphQL
 *             insert into Hasura  (retries + dead-letter queue on failure)
 *
 * Security:
 *  - The Hasura admin secret lives ONLY in a Worker Secret (never in the app / repo).
 *  - The app authenticates with INGEST_APP_KEY (a Worker Secret), compared in constant time.
 *  - GraphQL is sent with variables only (parameterized) — no string interpolation / injection.
 *  - Idempotent insert (on_conflict do-nothing on account_email + event_key) => no duplicates
 *    across app resyncs OR queue retries.
 *  - Strict input validation + hard size/count caps; secrets and full numbers are never logged.
 */

import { hasura } from './hasura';
import { advanceDueRuns, startFlowRunsForCalls, type NewCall } from './flow';
import { dispatchDueScheduledMessages } from './scheduler';
import { getWhatsAppCreds } from './whatsapp';

/** Cloudflare Rate Limiting binding (configured in wrangler.toml [[unsafe.bindings]]). */
interface RateLimit {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  // vars (wrangler.toml [vars])
  HASURA_GRAPHQL_URL: string;
  HASURA_ROLE?: string;
  GRAPH_API_VERSION?: string;
  // secrets (wrangler secret put ...)
  HASURA_ADMIN_SECRET: string;
  INGEST_APP_KEY: string;
  ADMIN_API_KEY?: string;
  WHATSAPP_PHONE_NUMBER_ID?: string; // shared fallback number
  WHATSAPP_ACCESS_TOKEN?: string;
  META_WEBHOOK_VERIFY_TOKEN?: string; // Meta webhook GET verification
  META_APP_SECRET?: string; // Meta webhook HMAC signature
  // bindings
  INGEST_RATE_LIMITER: RateLimit;
  TOKENS: KVNamespace;
}

/** A minted per-device token's stored value (in KV, keyed by sha256(token)). */
interface TokenRecord {
  email: string;
  partnerId: string | null;
  deviceId?: string | null;
  createdAt?: number;
}

/** The normalized row shape — matches the Hasura `call_logs` columns 1:1. */
interface CallRecord {
  account_email: string;
  partner_id: string | null;
  event_key: string;
  number_raw: string;
  number_e164: string | null;
  raw_type: number;
  call_type: string;
  direction: string;
  started_at: string; // ISO-8601 UTC
  event_timezone: string | null;
  duration_seconds: number;
  cached_name: string | null;
  device_id: string | null;
  app_version: string | null;
  source: string;
}

const MAX_BODY_BYTES = 1_000_000; // 1 MB request cap
const MAX_CALLS = 1000; // per request
const MAX_DURATION_SECONDS = 30 * 24 * 3600; // clamp absurd/hostile durations (bigint safety)

const SECURITY_HEADERS: Record<string, string> = {
  'content-type': 'application/json; charset=utf-8',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'cache-control': 'no-store',
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: SECURITY_HEADERS });
}

/** Constant-time string comparison to avoid auth-token timing leaks. */
function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

function bearer(request: Request): string | null {
  const h = request.headers.get('authorization') || '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m?.[1] ?? null;
}

/** Resolve a per-device token to its stored { email, partnerId } record, or null. */
async function authDevice(request: Request, env: Env): Promise<TokenRecord | null> {
  const token = bearer(request);
  if (!token) return null;
  return (await env.TOKENS.get(`tok:${await sha256Hex(token)}`, 'json')) as TokenRecord | null;
}

/** Constant-time check of the admin API key (superadmin-only endpoints). */
function isAdmin(request: Request, env: Env): boolean {
  const token = bearer(request);
  return !!token && !!env.ADMIN_API_KEY && timingSafeEqual(token, env.ADMIN_API_KEY);
}

async function sha256Hex(input: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Reads the request body while enforcing a hard BYTE cap as it streams — never
 * trusts the client's Content-Length and never buffers an oversized body.
 * Returns null if the cap is exceeded.
 */
async function readBodyCapped(request: Request, maxBytes: number): Promise<string | null> {
  const body = request.body;
  if (!body) return '';
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    merged.set(c, offset);
    offset += c.byteLength;
  }
  return new TextDecoder().decode(merged);
}

function digitsOnly(s: string): string {
  return (s || '').replace(/[^0-9]/g, '');
}

function isEmail(s: unknown): s is string {
  return typeof s === 'string' && s.length <= 254 && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(s);
}

/** Trim, reject empty, and clamp length — defends against oversized fields. */
function asString(v: unknown, max: number): string | null {
  if (typeof v !== 'string') return null;
  const t = v.trim();
  if (!t) return null;
  return t.length > max ? t.slice(0, max) : t;
}

/** Android CallLog.Calls.TYPE -> label. Kept in sync with the app's CallTypes.kt. */
function callTypeLabel(t: number): string {
  switch (t) {
    case 1: return 'incoming';
    case 2: return 'outgoing';
    case 3: return 'missed';
    case 4: return 'voicemail';
    case 5: return 'rejected';
    case 6: return 'blocked';
    case 7: return 'answered_externally';
    default: return 'unknown';
  }
}

function directionFor(t: number): string {
  switch (t) {
    case 2: return 'outbound';
    case 1:
    case 3:
    case 4:
    case 5:
    case 6: return 'inbound';
    default: return 'unknown';
  }
}

/** Validate + normalize one raw call from the app. Returns null if unusable. */
async function normalizeCall(
  raw: unknown,
  email: string,
  partnerId: string | null
): Promise<CallRecord | null> {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;

  const rawType = Number(r.callType);
  const epoch = Number(r.callEpochMs);
  if (!Number.isFinite(rawType) || rawType < 0 || rawType > 99) return null;
  // Reject nonsensical timestamps (<=0 or beyond year 2100).
  if (!Number.isFinite(epoch) || epoch <= 0 || epoch > 4_102_444_800_000) return null;

  const numberRaw = asString(r.numberRaw, 64) || 'Unknown';
  const duration = Number(r.durationSec);
  // Always derive the idempotency key server-side — never trust a client-supplied
  // key (prevents dedup evasion and key-space poisoning).
  const eventKey = await sha256Hex(`${digitsOnly(numberRaw)}|${epoch}|${rawType}`);
  const durationSeconds =
    Number.isFinite(duration) && duration >= 0
      ? Math.min(Math.floor(duration), MAX_DURATION_SECONDS)
      : 0;

  return {
    account_email: email,
    partner_id: partnerId,
    event_key: eventKey,
    number_raw: numberRaw,
    number_e164: asString(r.e164, 32),
    raw_type: rawType,
    call_type: callTypeLabel(rawType),
    direction: directionFor(rawType),
    started_at: new Date(epoch).toISOString(),
    event_timezone: asString(r.tzIana, 64),
    duration_seconds: durationSeconds,
    cached_name: asString(r.cachedName, 128),
    device_id: null, // set from the request envelope below
    app_version: null,
    source: 'android_calllog',
  };
}

/** Look up the cravings-v2 partner id for an email (admin read). null if not a partner. */
async function lookupPartnerId(env: Env, email: string): Promise<string | null> {
  const query = `query Partner($email: String!) {
  partners(where: { email: { _eq: $email } }, limit: 1) { id }
}`;
  const resp = await fetch(env.HASURA_GRAPHQL_URL, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-hasura-admin-secret': env.HASURA_ADMIN_SECRET,
    },
    body: JSON.stringify({ query, variables: { email } }),
  });
  if (!resp.ok) throw new Error(`hasura http ${resp.status}`);
  const data = (await resp.json()) as {
    data?: { partners?: Array<{ id: string }> };
    errors?: Array<{ message?: string }>;
  };
  if (data.errors?.length) throw new Error(`hasura: ${data.errors[0]?.message ?? 'error'}`);
  return data.data?.partners?.[0]?.id ?? null;
}

/** Cryptographically-random opaque token (hex). Only its SHA-256 is ever stored. */
function mintToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Device registration. Gated by the shared app key, verifies the email belongs to a
 * real cravings-v2 partner, then mints a per-device token bound to that partner.
 * The app then uses the token for /ingest, so account attribution cannot be spoofed.
 */
async function handleRegister(request: Request, env: Env): Promise<Response> {
  const gate = bearer(request);
  if (!gate || !env.INGEST_APP_KEY || !timingSafeEqual(gate, env.INGEST_APP_KEY)) {
    return json({ error: 'unauthorized' }, 401);
  }

  const text = await readBodyCapped(request, 8192);
  if (text === null) return json({ error: 'payload too large' }, 413);
  let body: Record<string, unknown>;
  try {
    body = JSON.parse(text);
  } catch {
    return json({ error: 'invalid json' }, 400);
  }

  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  if (!isEmail(email)) return json({ error: 'invalid email' }, 400);
  const deviceId = asString(body.deviceId, 128);

  const { success } = await env.INGEST_RATE_LIMITER.limit({ key: `reg:${email}` });
  if (!success) return json({ error: 'rate limited' }, 429);

  const partnerId = await lookupPartnerId(env, email);
  if (!partnerId) return json({ error: 'not a partner' }, 404);

  const token = mintToken();
  const record: TokenRecord = { email, partnerId, deviceId, createdAt: Date.now() };
  await env.TOKENS.put(`tok:${await sha256Hex(token)}`, JSON.stringify(record));

  return json({ token, partnerId }, 201);
}

/** Producer: authenticate (per-device token), validate, enqueue. DB write is in the consumer. */
async function handleIngest(request: Request, env: Env): Promise<Response> {
  // Per-device token -> { email, partnerId }, minted by /register. The app never sends
  // account_email; it is derived server-side, so a device can only write to its own
  // partner account (no cross-tenant spoofing).
  const auth = await authDevice(request, env);
  if (!auth || !auth.email) return json({ error: 'unauthorized' }, 401);
  const email = auth.email;
  const partnerId = auth.partnerId ?? null;

  // Per-account throttle (bounds abuse/amplification).
  const { success } = await env.INGEST_RATE_LIMITER.limit({ key: email });
  if (!success) return json({ error: 'rate limited' }, 429);

  // Byte-accurate cap that streams and aborts — does not trust Content-Length.
  const text = await readBodyCapped(request, MAX_BODY_BYTES);
  if (text === null) return json({ error: 'payload too large' }, 413);

  let body: Record<string, unknown>;
  try {
    body = JSON.parse(text);
  } catch {
    return json({ error: 'invalid json' }, 400);
  }

  const calls = Array.isArray(body.calls) ? body.calls : null;
  if (!calls || calls.length === 0) return json({ error: 'no calls' }, 400);
  if (calls.length > MAX_CALLS) return json({ error: 'too many calls' }, 413);

  const deviceId = asString(body.deviceId, 128);
  const appVersion = asString(body.appVersion, 32);

  const records: CallRecord[] = [];
  for (const c of calls) {
    const rec = await normalizeCall(c, email, partnerId);
    if (rec) {
      rec.device_id = deviceId;
      rec.app_version = appVersion;
      records.push(rec);
    }
  }
  if (records.length === 0) return json({ error: 'no valid calls' }, 400);

  // Dedup within the request, then insert to Hasura inline (Free plan — no queue).
  const seen = new Set<string>();
  const deduped = records.filter((r) => {
    const k = `${r.account_email} ${r.event_key}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });

  try {
    const inserted = await insertCalls(env, deduped);
    // Trigger follow-up flows for newly-recorded inbound calls (best-effort).
    if (inserted.length) {
      try {
        await startFlowRunsForCalls(env, inserted);
      } catch (e) {
        console.log(`flow start failed: ${(e as Error).message}`);
      }
    }
  } catch (e) {
    // Storage temporarily unavailable — tell the app to retry (idempotent, nothing lost).
    console.log(`ingest insert failed: ${(e as Error).message}`);
    return json({ error: 'store unavailable' }, 503);
  }

  return json({ accepted: records.length }, 202);
}

/** Single idempotent, parameterized bulk insert into Hasura. Returns the NEW rows only. */
async function insertCalls(env: Env, objects: CallRecord[]): Promise<NewCall[]> {
  const mutation = `mutation Ingest($objects: [call_logs_insert_input!]!) {
  insert_call_logs(
    objects: $objects,
    on_conflict: { constraint: call_logs_account_email_event_key_key, update_columns: [] }
  ) { returning { id partner_id account_email number_e164 cached_name direction } }
}`;
  const data = await hasura<{ insert_call_logs: { returning: NewCall[] } }>(
    env,
    mutation,
    { objects },
    true
  );
  return data.insert_call_logs.returning;
}


// ---------------------------------------------------------------------------
// Flow builder + scheduler APIs (consumed by the app and the superadmin panel)
// ---------------------------------------------------------------------------

type FlowGraph = { nodes: Array<Record<string, unknown>>; edges: Array<Record<string, unknown>> };

/** Read + JSON-parse a small request body. */
async function parseJson(
  request: Request
): Promise<{ ok: true; body: Record<string, unknown> } | { ok: false; res: Response }> {
  const text = await readBodyCapped(request, 256_000);
  if (text === null) return { ok: false, res: json({ error: 'payload too large' }, 413) };
  try {
    return { ok: true, body: JSON.parse(text) as Record<string, unknown> };
  } catch {
    return { ok: false, res: json({ error: 'invalid json' }, 400) };
  }
}

/** Validate a flow graph: {nodes,edges}, unique ids, exactly one trigger, bounded, valid edges. */
function validateGraph(
  graph: unknown
): { ok: true; graph: FlowGraph } | { ok: false; error: string } {
  if (!graph || typeof graph !== 'object') return { ok: false, error: 'graph must be an object' };
  const g = graph as { nodes?: unknown; edges?: unknown };
  if (!Array.isArray(g.nodes) || !Array.isArray(g.edges)) {
    return { ok: false, error: 'graph needs nodes[] and edges[]' };
  }
  if (g.nodes.length > 100 || g.edges.length > 200) return { ok: false, error: 'graph too large' };
  const ids = new Set<string>();
  let triggers = 0;
  for (const n of g.nodes as Array<Record<string, unknown>>) {
    if (!n || typeof n.id !== 'string' || typeof n.type !== 'string') {
      return { ok: false, error: 'invalid node' };
    }
    if (ids.has(n.id)) return { ok: false, error: `duplicate node id ${n.id}` };
    ids.add(n.id);
    if (n.type === 'trigger') triggers++;
  }
  if (triggers !== 1) return { ok: false, error: 'exactly one trigger node required' };
  for (const e of g.edges as Array<Record<string, unknown>>) {
    if (!e || typeof e.from !== 'string' || !ids.has(e.from)) {
      return { ok: false, error: 'invalid edge source' };
    }
    if (e.to !== null && (typeof e.to !== 'string' || !ids.has(e.to as string))) {
      return { ok: false, error: 'invalid edge target' };
    }
  }
  return { ok: true, graph: { nodes: g.nodes as FlowGraph['nodes'], edges: g.edges as FlowGraph['edges'] } };
}

async function getFlowFor(env: Env, partnerId: string): Promise<unknown> {
  const data = await hasura<{
    call_flows: Array<{ id: string; name: string; graph: unknown; enabled: boolean; updated_at: string }>;
  }>(
    env,
    `query F($p: uuid!) { call_flows(where: { partner_id: { _eq: $p } }, limit: 1) { id name graph enabled updated_at } }`,
    { p: partnerId }
  );
  return data.call_flows[0] ?? { name: 'Call follow-up', graph: { nodes: [], edges: [] }, enabled: false };
}

async function upsertFlow(
  env: Env,
  partnerId: string,
  accountEmail: string | null,
  name: string,
  graph: FlowGraph,
  enabled: boolean,
  updatedBy: string
): Promise<void> {
  await hasura(
    env,
    `mutation U($o: call_flows_insert_input!) {
      insert_call_flows_one(object: $o,
        on_conflict: { constraint: call_flows_partner_key, update_columns: [name, graph, enabled, updated_by, updated_at] }
      ) { id }
    }`,
    {
      o: {
        partner_id: partnerId,
        account_email: accountEmail,
        name,
        graph,
        enabled,
        updated_by: updatedBy,
        updated_at: new Date().toISOString(),
      },
    }
  );
}

async function createSchedule(
  env: Env,
  partnerId: string,
  accountEmail: string | null,
  body: Record<string, unknown>,
  createdBy: string
): Promise<{ id: string } | { error: string }> {
  const template = asString(body.template, 128);
  if (!template) return { error: 'template required' };
  const scheduledAt = asString(body.scheduledAt, 40);
  if (!scheduledAt || Number.isNaN(Date.parse(scheduledAt))) return { error: 'valid scheduledAt required' };
  const language = asString(body.language, 10) || 'en';
  const name = asString(body.name, 120);
  const params = Array.isArray(body.params)
    ? body.params.slice(0, 20).map((p) => String(p).slice(0, 300))
    : [];
  const audience =
    body.audience && typeof body.audience === 'object' ? body.audience : { mode: 'all_called' };

  const data = await hasura<{ insert_scheduled_messages_one: { id: string } }>(
    env,
    `mutation Ins($o: scheduled_messages_insert_input!) { insert_scheduled_messages_one(object: $o) { id } }`,
    {
      o: {
        partner_id: partnerId,
        account_email: accountEmail,
        name,
        template_name: template,
        language,
        params,
        audience,
        scheduled_at: scheduledAt,
        created_by: createdBy,
      },
    }
  );
  return { id: data.insert_scheduled_messages_one.id };
}

async function listSchedule(env: Env, partnerId: string): Promise<unknown> {
  const data = await hasura<{ scheduled_messages: unknown[] }>(
    env,
    `query L($p: uuid!) {
      scheduled_messages(where: { partner_id: { _eq: $p } }, order_by: { created_at: desc }, limit: 50) {
        id name template_name language scheduled_at status targets_built created_at
      }
    }`,
    { p: partnerId }
  );
  return data.scheduled_messages;
}

/** GET /flow — partner reads their own flow. */
async function handleGetFlow(request: Request, env: Env): Promise<Response> {
  const auth = await authDevice(request, env);
  if (!auth?.partnerId) return json({ error: 'unauthorized' }, 401);
  return json(await getFlowFor(env, auth.partnerId));
}

/** PUT /flow — partner updates their own flow. */
async function handlePutFlow(request: Request, env: Env): Promise<Response> {
  const auth = await authDevice(request, env);
  if (!auth?.partnerId) return json({ error: 'unauthorized' }, 401);
  const parsed = await parseJson(request);
  if (!parsed.ok) return parsed.res;
  const v = validateGraph(parsed.body.graph);
  if (!v.ok) return json({ error: v.error }, 400);
  const name = asString(parsed.body.name, 120) || 'Call follow-up';
  await upsertFlow(env, auth.partnerId, auth.email, name, v.graph, !!parsed.body.enabled, `partner:${auth.email}`);
  return json({ ok: true });
}

/** GET/PUT /admin/flow?partner=<id> — superadmin manages any partner's flow. */
async function handleAdminFlow(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const partnerId = url.searchParams.get('partner');
  if (!partnerId) return json({ error: 'partner required' }, 400);
  if (request.method === 'GET') return json(await getFlowFor(env, partnerId));
  const parsed = await parseJson(request);
  if (!parsed.ok) return parsed.res;
  const v = validateGraph(parsed.body.graph);
  if (!v.ok) return json({ error: v.error }, 400);
  const name = asString(parsed.body.name, 120) || 'Call follow-up';
  await upsertFlow(env, partnerId, asString(parsed.body.accountEmail, 254), name, v.graph, !!parsed.body.enabled, 'admin');
  return json({ ok: true });
}

/** GET/POST /schedule — partner lists/creates their scheduled bulk messages. */
async function handleSchedule(request: Request, env: Env): Promise<Response> {
  const auth = await authDevice(request, env);
  if (!auth?.partnerId) return json({ error: 'unauthorized' }, 401);
  if (request.method === 'GET') return json({ items: await listSchedule(env, auth.partnerId) });
  const parsed = await parseJson(request);
  if (!parsed.ok) return parsed.res;
  const result = await createSchedule(env, auth.partnerId, auth.email, parsed.body, `partner:${auth.email}`);
  return 'error' in result ? json(result, 400) : json(result, 201);
}

/** GET/POST /admin/schedule?partner=<id> — superadmin lists/creates for any partner. */
async function handleAdminSchedule(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const partnerId = url.searchParams.get('partner');
  if (!partnerId) return json({ error: 'partner required' }, 400);
  if (request.method === 'GET') return json({ items: await listSchedule(env, partnerId) });
  const parsed = await parseJson(request);
  if (!parsed.ok) return parsed.res;
  const result = await createSchedule(env, partnerId, asString(parsed.body.accountEmail, 254), parsed.body, 'admin');
  return 'error' in result ? json(result, 400) : json(result, 201);
}

/** GET /admin/partners — accounts that use the Android Call Logger (one row per account). */
async function handleAdminPartners(request: Request, env: Env): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const data = await hasura<{
    call_logs: Array<{ account_email: string; partner_id: string | null; started_at: string }>;
  }>(
    env,
    `query { call_logs(distinct_on: account_email, order_by: [{ account_email: asc }, { started_at: desc }]) {
      account_email partner_id started_at
    } }`
  );
  const items = data.call_logs.map((r) => ({
    accountEmail: r.account_email,
    partnerId: r.partner_id,
    lastCallAt: r.started_at,
  }));
  return json({ items });
}

/** GET /admin/partner?partner=<id> — config summary (flow, WhatsApp, totals). */
async function handleAdminPartnerConfig(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const partnerId = url.searchParams.get('partner');
  if (!partnerId) return json({ error: 'partner required' }, 400);

  try {
    const flow = await getFlowFor(env, partnerId);
    const agg = await hasura<{
      call_logs_aggregate: { aggregate: { count: number } };
      partners_by_pk: { store_name: string | null } | null;
    }>(
      env,
      `query A($p: uuid!) {
        call_logs_aggregate(where: { partner_id: { _eq: $p } }) { aggregate { count } }
        partners_by_pk(id: $p) { store_name }
      }`,
      { p: partnerId }
    );
    let canSend = false;
    try {
      canSend = !!(await getWhatsAppCreds(env, partnerId));
    } catch {
      canSend = false; // never let a WhatsApp-creds read 500 the config view
    }
    return json({
      flow,
      storeName: agg.partners_by_pk?.store_name ?? null,
      totalCalls: agg.call_logs_aggregate.aggregate.count,
      whatsappReady: canSend,
    });
  } catch (e) {
    console.log(`admin/partner failed: ${(e as Error).message}`);
    return json({ error: 'config_failed', detail: (e as Error).message }, 500);
  }
}

/** GET /admin/calls?partner=<id>&from=<iso>&to=<iso>&limit= — a partner's call logs in a range. */
async function handleAdminCalls(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const partnerId = url.searchParams.get('partner');
  if (!partnerId) return json({ error: 'partner required' }, 400);
  const from = url.searchParams.get('from') || '1970-01-01T00:00:00Z';
  const to = url.searchParams.get('to') || new Date().toISOString();
  if (Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to))) {
    return json({ error: 'invalid from/to' }, 400);
  }
  const limit = Math.min(Number(url.searchParams.get('limit')) || 500, 2000);
  const data = await hasura<{ call_logs: unknown[] }>(
    env,
    `query C($p: uuid!, $from: timestamptz!, $to: timestamptz!, $limit: Int!) {
      call_logs(where: { partner_id: { _eq: $p }, started_at: { _gte: $from, _lte: $to } },
                order_by: { started_at: desc }, limit: $limit) {
        id number_raw number_e164 call_type direction duration_seconds started_at cached_name
      }
    }`,
    { p: partnerId, from, to, limit }
  );
  return json({ items: data.call_logs });
}

/** GET /admin/schedule/targets?id=<scheduledMessageId>&status=&limit= — per-number delivery. */
async function handleAdminScheduleTargets(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const id = url.searchParams.get('id');
  if (!id) return json({ error: 'id required' }, 400);
  const status = url.searchParams.get('status');
  const limit = Math.min(Number(url.searchParams.get('limit')) || 1000, 5000);
  const where = status
    ? `{ scheduled_message_id: { _eq: $id }, status: { _eq: $status } }`
    : `{ scheduled_message_id: { _eq: $id } }`;
  const data = await hasura<{ scheduled_message_targets: unknown[] }>(
    env,
    `query T($id: uuid!, $limit: Int!${status ? ', $status: String!' : ''}) {
      scheduled_message_targets(where: ${where}, order_by: { status: asc }, limit: $limit) {
        to_e164 contact_name status wa_message_id error created_at
      }
    }`,
    status ? { id, limit, status } : { id, limit }
  );
  return json({ items: data.scheduled_message_targets });
}

/** GET /admin/messages?partner=<id>&status=&source=&limit= — WhatsApp send log (wa_sends). */
async function handleAdminMessages(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isAdmin(request, env)) return json({ error: 'unauthorized' }, 401);
  const partnerId = url.searchParams.get('partner');
  if (!partnerId) return json({ error: 'partner required' }, 400);
  const status = url.searchParams.get('status'); // pending|sent|failed
  const source = url.searchParams.get('source'); // flow|schedule
  const limit = Math.min(Number(url.searchParams.get('limit')) || 200, 1000);

  const conds = ['partner_id: { _eq: $p }'];
  const varDefs = ['$p: uuid!', '$limit: Int!'];
  const vars: Record<string, unknown> = { p: partnerId, limit };
  if (status) { conds.push('status: { _eq: $status }'); varDefs.push('$status: String!'); vars.status = status; }
  if (source) { conds.push('source: { _eq: $source }'); varDefs.push('$source: String!'); vars.source = source; }

  const data = await hasura<{ wa_sends: unknown[] }>(
    env,
    `query M(${varDefs.join(', ')}) {
      wa_sends(where: { ${conds.join(', ')} }, order_by: { created_at: desc }, limit: $limit) {
        to_e164 template_name language source status wa_message_id error created_at
      }
    }`,
    vars
  );
  return json({ items: data.wa_sends });
}

// ---------------------------------------------------------------------------
// Meta WhatsApp inbound webhook: records replies (for flow conditions) + opt-outs.
// ---------------------------------------------------------------------------

function handleWebhookVerify(_request: Request, env: Env, url: URL): Response {
  const mode = url.searchParams.get('hub.mode');
  const token = url.searchParams.get('hub.verify_token');
  const challenge = url.searchParams.get('hub.challenge');
  if (mode === 'subscribe' && token && env.META_WEBHOOK_VERIFY_TOKEN && token === env.META_WEBHOOK_VERIFY_TOKEN) {
    return new Response(challenge ?? '', { status: 200, headers: { 'content-type': 'text/plain' } });
  }
  return new Response('forbidden', { status: 403 });
}

async function verifyMetaSignature(secret: string, raw: string, header: string | null): Promise<boolean> {
  if (!header || !header.startsWith('sha256=')) return false;
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(raw));
  const hex = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return timingSafeEqual(`sha256=${hex}`, header);
}

async function handleWebhookEvent(request: Request, env: Env): Promise<Response> {
  const raw = await request.text();
  if (env.META_APP_SECRET) {
    const ok = await verifyMetaSignature(env.META_APP_SECRET, raw, request.headers.get('x-hub-signature-256'));
    if (!ok) return json({ error: 'bad signature' }, 401);
  }
  let payload: unknown;
  try {
    payload = JSON.parse(raw);
  } catch {
    return json({ ok: true }); // ack malformed so Meta doesn't retry-storm
  }
  try {
    await processInbound(env, payload);
  } catch (e) {
    console.log(`webhook: ${(e as Error).message}`);
  }
  return json({ ok: true }); // always 200
}

async function processInbound(env: Env, payload: unknown): Promise<void> {
  const p = payload as { entry?: Array<{ changes?: Array<{ value?: Record<string, unknown> }> }> };
  const replies: Array<Record<string, unknown>> = [];
  const optouts: Array<Record<string, unknown>> = [];

  for (const entry of p.entry ?? []) {
    for (const ch of entry.changes ?? []) {
      const val = (ch.value ?? {}) as {
        metadata?: { phone_number_id?: string };
        messages?: Array<{ from?: string; id?: string; type?: string; text?: { body?: string }; button?: { text?: string } }>;
      };
      const pnid = val.metadata?.phone_number_id ?? null;
      for (const m of val.messages ?? []) {
        if (!m.from) continue;
        const from = '+' + String(m.from).replace(/[^0-9]/g, '');
        const bodyText = m.text?.body ?? m.button?.text ?? null;
        replies.push({
          phone_number_id: pnid,
          from_e164: from,
          wa_message_id: m.id ?? null,
          text_body: bodyText ? String(bodyText).slice(0, 1000) : null,
        });
        if (bodyText && /^\s*(stop|unsubscribe|stop all)\s*$/i.test(String(bodyText))) {
          optouts.push({ phone_e164: from, source: 'stop_reply' });
        }
      }
    }
  }

  if (replies.length) {
    await hasura(
      env,
      `mutation R($o: [wa_replies_insert_input!]!) { insert_wa_replies(objects: $o) { affected_rows } }`,
      { o: replies }
    );
  }
  if (optouts.length) {
    await hasura(
      env,
      `mutation O($o: [cl_optout_insert_input!]!) {
        insert_cl_optout(objects: $o, on_conflict: { constraint: cl_optout_pkey, update_columns: [] }) { affected_rows }
      }`,
      { o: optouts }
    );
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const p = url.pathname;
    const method = request.method;

    if (method === 'GET' && p === '/health') return json({ ok: true });
    if (method === 'POST' && p === '/register') return handleRegister(request, env);
    if (method === 'POST' && p === '/ingest') return handleIngest(request, env);

    // Meta WhatsApp inbound webhook
    if (method === 'GET' && p === '/webhooks/whatsapp') return handleWebhookVerify(request, env, url);
    if (method === 'POST' && p === '/webhooks/whatsapp') return handleWebhookEvent(request, env);

    // Partner (per-device token) — flow builder + scheduler
    if (p === '/flow' && method === 'GET') return handleGetFlow(request, env);
    if (p === '/flow' && method === 'PUT') return handlePutFlow(request, env);
    if (p === '/schedule' && (method === 'GET' || method === 'POST')) return handleSchedule(request, env);

    // Superadmin (ADMIN_API_KEY)
    if (p === '/admin/partners' && method === 'GET') return handleAdminPartners(request, env);
    if (p === '/admin/partner' && method === 'GET') return handleAdminPartnerConfig(request, env, url);
    if (p === '/admin/calls' && method === 'GET') return handleAdminCalls(request, env, url);
    if (p === '/admin/flow' && (method === 'GET' || method === 'PUT')) return handleAdminFlow(request, env, url);
    if (p === '/admin/schedule' && (method === 'GET' || method === 'POST')) return handleAdminSchedule(request, env, url);
    if (p === '/admin/schedule/targets' && method === 'GET') return handleAdminScheduleTargets(request, env, url);
    if (p === '/admin/messages' && method === 'GET') return handleAdminMessages(request, env, url);

    return json({ error: 'not found' }, 404);
  },

  /** Cron (every minute): advance due flow steps and dispatch due scheduled messages. */
  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      (async () => {
        const now = new Date();
        try {
          await advanceDueRuns(env, now);
        } catch (e) {
          console.log(`flow cron: ${(e as Error).message}`);
        }
        try {
          await dispatchDueScheduledMessages(env, now);
        } catch (e) {
          console.log(`sched cron: ${(e as Error).message}`);
        }
      })()
    );
  },
} satisfies ExportedHandler<Env>;
