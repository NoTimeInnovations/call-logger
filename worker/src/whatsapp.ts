/// <reference types="@cloudflare/workers-types" />
/**
 * Sends WhatsApp template messages via the Meta Graph API using the PARTNER'S own
 * connected number+token (read from cravings-v2 Hasura `partners.whatsapp_numbers`),
 * falling back to the shared Menuthere number if the partner has none — mirroring
 * cravings-v2's send_whatsapp_message behavior.
 *
 * ⚠️ CONFIRM the `whatsapp_numbers` JSON shape against your Hasura data. The extractor
 * below is defensive (handles array or object maps, common key names), but the exact
 * field names (`access_token`, `phone_number_id`, primary flag) should be verified once.
 */
import { hasura } from './hasura';

export interface WhatsAppEnv {
  HASURA_GRAPHQL_URL: string;
  HASURA_ADMIN_SECRET: string;
  HASURA_ROLE?: string;
  GRAPH_API_VERSION?: string; // e.g. "v20.0"
  // Shared fallback number (Worker secrets), used when a partner has no connected number.
  WHATSAPP_PHONE_NUMBER_ID?: string;
  WHATSAPP_ACCESS_TOKEN?: string;
}

export interface WaCreds {
  phoneNumberId: string;
  accessToken: string;
  businessName: string;
}

interface PartnerRow {
  id: string;
  store_name?: string | null;
  whatsapp_numbers?: unknown;
  primary_whatsapp_number?: string | null;
}

/** Pull the best (primary) connected number's credentials for a partner. */
export async function getWhatsAppCreds(env: WhatsAppEnv, partnerId: string): Promise<WaCreds | null> {
  const data = await hasura<{
    partners_by_pk: PartnerRow | null;
    whatsapp_business_integrations: Array<{ phone_number_id: string | null; access_token: string | null }>;
  }>(
    env,
    `query P($id: uuid!) {
      partners_by_pk(id: $id) { id store_name whatsapp_numbers }
      whatsapp_business_integrations(where: { partner_id: { _eq: $id } }, order_by: { is_primary: desc }, limit: 1) {
        phone_number_id
        access_token
      }
    }`,
    { id: partnerId }
  );
  const p = data.partners_by_pk;
  const businessName = p?.store_name?.trim() || 'us';

  // Primary source of truth: the partner's connected number (matches cravings-v2's send path).
  // The stored integration token is a Coexistence token for that partner's WABA; fall back to
  // the shared system token only if the integration has none.
  const integ = data.whatsapp_business_integrations[0];
  if (integ?.phone_number_id) {
    return {
      phoneNumberId: integ.phone_number_id,
      accessToken: integ.access_token || env.WHATSAPP_ACCESS_TOKEN || '',
      businessName,
    };
  }

  // Secondary: creds embedded in partners.whatsapp_numbers (legacy shape).
  const extracted = extractCreds(p?.whatsapp_numbers, null);
  if (extracted) return { ...extracted, businessName };

  // Fallback: shared Menuthere number.
  if (env.WHATSAPP_PHONE_NUMBER_ID && env.WHATSAPP_ACCESS_TOKEN) {
    return {
      phoneNumberId: env.WHATSAPP_PHONE_NUMBER_ID,
      accessToken: env.WHATSAPP_ACCESS_TOKEN,
      businessName,
    };
  }
  return null;
}

/** Defensive extraction from the whatsapp_numbers jsonb (array or object map). */
function extractCreds(
  raw: unknown,
  primaryKey: string | null
): { phoneNumberId: string; accessToken: string } | null {
  if (!raw || typeof raw !== 'object') return null;

  const entries: Array<Record<string, unknown>> = Array.isArray(raw)
    ? (raw as Array<Record<string, unknown>>)
    : Object.values(raw as Record<string, unknown>).filter(
        (v): v is Record<string, unknown> => !!v && typeof v === 'object'
      );

  const pick = (e: Record<string, unknown>) => {
    const phoneNumberId =
      str(e.phone_number_id) ?? str(e.phoneNumberId) ?? str(e.number_id) ?? str(e.id);
    const accessToken =
      str(e.access_token) ?? str(e.accessToken) ?? str(e.token);
    return phoneNumberId && accessToken ? { phoneNumberId, accessToken } : null;
  };

  // Prefer the primary/is_primary/flow_enabled entry, else the first usable one.
  const primary =
    entries.find((e) => e.is_primary === true || e.primary === true) ??
    (primaryKey
      ? entries.find(
          (e) => str(e.phone_number_id) === primaryKey || str(e.display_phone_number) === primaryKey
        )
      : undefined);

  const candidate = (primary && pick(primary)) || entries.map(pick).find(Boolean);
  return candidate ?? null;
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

export interface TemplateSend {
  template: string;
  language: string;
  bodyParams?: string[];
  /** Link for a template whose HEADER is an IMAGE (required by Meta to send such templates). */
  headerImage?: string;
}

/** Send one approved template message. Returns the Meta message id. Throws on failure. */
export async function sendTemplate(
  env: WhatsAppEnv,
  creds: WaCreds,
  toE164: string,
  msg: TemplateSend
): Promise<string> {
  const version = env.GRAPH_API_VERSION || 'v20.0';
  const url = `https://graph.facebook.com/${version}/${creds.phoneNumberId}/messages`;

  // Meta requires the components in order (header before body). A media-header
  // template MUST carry its header image at send time or Meta rejects it; a
  // body with {{n}} vars needs exactly that many text params.
  const components: Array<Record<string, unknown>> = [];
  if (msg.headerImage && msg.headerImage.trim()) {
    components.push({
      type: 'header',
      parameters: [{ type: 'image', image: { link: msg.headerImage.trim() } }],
    });
  }
  if (msg.bodyParams && msg.bodyParams.length) {
    components.push({
      type: 'body',
      parameters: msg.bodyParams.map((t) => ({ type: 'text', text: t })),
    });
  }

  const payload = {
    messaging_product: 'whatsapp',
    to: toE164.replace(/[^\d+]/g, ''),
    type: 'template',
    template: {
      name: msg.template,
      language: { code: msg.language || 'en' },
      ...(components.length ? { components } : {}),
    },
  };

  const resp = await fetch(url, {
    method: 'POST',
    headers: { authorization: `Bearer ${creds.accessToken}`, 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const body = (await resp.json().catch(() => ({}))) as {
    messages?: Array<{ id: string }>;
    error?: { message?: string; code?: number };
  };
  if (!resp.ok || body.error) {
    throw new Error(`meta ${resp.status}: ${body.error?.message ?? 'send failed'}`);
  }
  return body.messages?.[0]?.id ?? '';
}

/** True if the number is on the global opt-out list (STOP reply). */
export async function isOptedOut(env: WhatsAppEnv, e164: string): Promise<boolean> {
  const d = await hasura<{ cl_optout_by_pk: { phone_e164: string } | null }>(
    env,
    `query O($p: String!) { cl_optout_by_pk(phone_e164: $p) { phone_e164 } }`,
    { p: e164 }
  );
  return !!d.cl_optout_by_pk;
}
