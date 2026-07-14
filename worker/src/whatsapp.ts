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
  const data = await hasura<{ partners_by_pk: PartnerRow | null }>(
    env,
    `query P($id: uuid!) { partners_by_pk(id: $id) { id store_name whatsapp_numbers primary_whatsapp_number } }`,
    { id: partnerId }
  );
  const p = data.partners_by_pk;
  const businessName = p?.store_name?.trim() || 'us';
  const extracted = extractCreds(p?.whatsapp_numbers, p?.primary_whatsapp_number ?? null);
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
  const components =
    msg.bodyParams && msg.bodyParams.length
      ? [{ type: 'body', parameters: msg.bodyParams.map((t) => ({ type: 'text', text: t })) }]
      : undefined;

  const payload = {
    messaging_product: 'whatsapp',
    to: toE164.replace(/[^\d+]/g, ''),
    type: 'template',
    template: {
      name: msg.template,
      language: { code: msg.language || 'en' },
      ...(components ? { components } : {}),
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
