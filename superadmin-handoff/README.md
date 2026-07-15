# Android Call Logger — cravings-v2 superadmin section (handoff)

Drop-in React/Next.js code to add an **"Android Call Logger"** section to the cravings-v2
superadmin. It lists partners using the app, and per partner shows their **config**, a
**visual flow builder**, their **call logs** (today / yesterday / custom) and their
**scheduled messages** with per-number delivery status (who didn't get it).

It talks to the Cloudflare Worker's `/admin/*` API **through a server-side Next.js proxy**
so the `ADMIN_API_KEY` never reaches the browser.

## Install

```bash
npm i reactflow        # visual flow builder (only new dependency)
```

Add to the cravings-v2 server env (NOT NEXT_PUBLIC):
```
CALL_LOGGER_WORKER_URL=https://call-logger-ingest.<your>.workers.dev
CALL_LOGGER_ADMIN_KEY=<the same value you set via: wrangler secret put ADMIN_API_KEY>
```

Copy these into the cravings-v2 app (App Router assumed):
```
app/api/call-logger/[...path]/route.ts     # server-side proxy (keeps the admin key secret)
lib/callLogger.ts                           # browser client (calls the proxy)
components/callLogger/AndroidCallLoggerSection.tsx
components/callLogger/PartnerDetail.tsx
components/callLogger/CallLogsTab.tsx
components/callLogger/SchedulesTab.tsx
components/callLogger/FlowBuilder.tsx
```

Then render `<AndroidCallLoggerSection />` from a new superadmin route, e.g.
`app/(superadmin)/android-call-logger/page.tsx`:
```tsx
import AndroidCallLoggerSection from '@/components/callLogger/AndroidCallLoggerSection';
export default function Page() { return <AndroidCallLoggerSection />; }
```

## Security
- **Guard the proxy route** with your existing superadmin auth (session/role check) — the
  snippet marks the spot. The proxy injects `ADMIN_API_KEY` server-side; the browser only
  ever calls `/api/call-logger/*` on your own origin (no CORS, no key exposure).
- Styling uses Tailwind classes (cravings-v2 already uses Tailwind); restyle as needed.

## What maps to what
| UI | Worker endpoint (via proxy) |
|---|---|
| Partner list | `GET /admin/partners` |
| Partner config | `GET /admin/partner?partner=<id>` |
| Call logs (date range) | `GET /admin/calls?partner=<id>&from=&to=` |
| Load / save flow | `GET/PUT /admin/flow?partner=<id>` |
| Scheduled messages | `GET/POST /admin/schedule?partner=<id>` |
| Per-number delivery | `GET /admin/schedule/targets?id=<messageId>` |
| Template picker + preview | `GET /admin/templates?partner=<id>` |

The **template picker** (`components/callLogger/TemplatePicker.tsx`) replaces the
free-text template-name inputs in the flow builder's *Send* node and the *New
schedule* form with a dropdown of the partner's WhatsApp templates plus a live
preview. Templates are read from the shared cravings-v2 Hasura mirror
(`whatsapp_message_templates`), scoped to the partner's primary WABA (where sends
go). If none are synced, it falls back to manual name entry.
