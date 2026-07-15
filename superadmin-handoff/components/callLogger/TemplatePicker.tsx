'use client';

import { useEffect, useMemo, useState } from 'react';
import { CallLoggerApi, type TemplateRow, type TemplateComponent } from '@/lib/callLogger';

// --- helpers ---------------------------------------------------------------

/** Normalize a template's components to an array (Hasura jsonb may arrive as a string). */
function comps(t: TemplateRow | null): TemplateComponent[] {
  if (!t) return [];
  const c = t.components as unknown;
  if (Array.isArray(c)) return c as TemplateComponent[];
  if (typeof c === 'string') {
    try {
      const parsed = JSON.parse(c);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

/** The BODY component's text for a template (empty string if none). */
export function bodyOf(t: TemplateRow | null): string {
  return comps(t).find((c) => c.type === 'BODY')?.text ?? '';
}

/** Replace {{1}}, {{2}} … in a body with the supplied params (keeps the token if missing). */
export function fillPlaceholders(body: string, params: string[]): string {
  return body.replace(/\{\{(\d+)\}\}/g, (_m, idx) => {
    const i = parseInt(idx, 10) - 1;
    return params[i] ? params[i] : `{{${idx}}}`;
  });
}

/** Count distinct {{n}} placeholders in a body — how many params the template needs. */
export function variableCount(body: string): number {
  const matches = body.match(/\{\{(\d+)\}\}/g) || [];
  const indices = new Set<number>();
  for (const m of matches) {
    const n = parseInt(m.replace(/[{}]/g, ''), 10);
    if (!Number.isNaN(n)) indices.add(n);
  }
  return indices.size;
}

/** Load a partner's WhatsApp templates once. */
export function useTemplates(partnerId: string) {
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    CallLoggerApi.templates(partnerId)
      .then((r) => {
        if (alive) {
          setTemplates(r.items || []);
          setError(null);
        }
      })
      .catch((e) => {
        if (alive) setError((e as Error).message);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [partnerId]);

  return { templates, loading, error };
}

// --- preview ---------------------------------------------------------------

/** WhatsApp-style rendering of a template's header/body/footer/buttons. */
export function TemplatePreview({ template, params = [] }: { template: TemplateRow; params?: string[] }) {
  const list = comps(template);
  const header = list.find((c) => c.type === 'HEADER');
  const body = list.find((c) => c.type === 'BODY');
  const footer = list.find((c) => c.type === 'FOOTER');
  const buttons = list.find((c) => c.type === 'BUTTONS');

  return (
    <div className="rounded-lg p-3" style={{ background: '#e5ddd5' }}>
      <div className="max-w-[260px] rounded-lg bg-white shadow-sm px-3 py-2 text-sm text-gray-800 space-y-1.5">
        {header?.format === 'TEXT' && header.text ? (
          <div className="font-semibold">{header.text}</div>
        ) : header?.format && header.format !== 'TEXT' ? (
          <div className="flex items-center justify-center h-20 rounded bg-gray-100 text-[10px] font-medium tracking-wide text-gray-400 uppercase">
            {header.format}
          </div>
        ) : null}

        {body?.text ? (
          <div className="whitespace-pre-wrap">{fillPlaceholders(body.text, params)}</div>
        ) : (
          <div className="text-gray-400 italic">No body text</div>
        )}

        {footer?.text && <div className="text-xs text-gray-400">{footer.text}</div>}
      </div>

      {buttons?.buttons?.length ? (
        <div className="mt-1.5 space-y-1 max-w-[260px]">
          {buttons.buttons.map((b, i) => (
            <div
              key={i}
              className="rounded-lg bg-white text-center text-sm py-1.5 shadow-sm"
              style={{ color: '#00a5f4' }}
            >
              {b.text || b.type}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

// --- picker ----------------------------------------------------------------

/**
 * Choose a WhatsApp template from the partner's approved list and preview it,
 * instead of typing the name. Falls back to a free-text input when the partner
 * has no templates synced or the list can't be loaded, so nothing regresses.
 */
export default function TemplatePicker({
  partnerId,
  template,
  language,
  params = [],
  onChange,
}: {
  partnerId: string;
  template: string;
  language: string;
  params?: string[];
  onChange: (next: { template: string; language: string }) => void;
}) {
  const { templates, loading, error } = useTemplates(partnerId);
  const [manual, setManual] = useState(false);

  const selected = useMemo(
    () =>
      templates.find((t) => t.name === template && t.language === language) ||
      templates.find((t) => t.name === template) ||
      null,
    [templates, template, language],
  );

  // Use free-text entry when explicitly toggled, when the list is empty, or on error.
  const useManual = manual || (!loading && templates.length === 0) || !!error;
  const varsNeeded = selected ? variableCount(bodyOf(selected)) : 0;

  return (
    <div className="space-y-2">
      {useManual ? (
        <input
          className="border rounded px-2 py-1 w-full"
          placeholder="Approved template name"
          value={template}
          onChange={(e) => onChange({ template: e.target.value, language })}
        />
      ) : (
        <select
          className="border rounded px-2 py-1 w-full"
          value={selected ? `${selected.name}::${selected.language}` : ''}
          onChange={(e) => {
            const [name, lang] = e.target.value.split('::');
            onChange({ template: name, language: lang || 'en' });
          }}
        >
          <option value="" disabled>
            {loading ? 'Loading templates…' : 'Select a template'}
          </option>
          {templates.map((t) => (
            <option key={`${t.name}::${t.language}`} value={`${t.name}::${t.language}`}>
              {t.name} · {t.language}
              {t.status && t.status.toUpperCase() !== 'APPROVED' ? ` (${t.status})` : ''}
            </option>
          ))}
        </select>
      )}

      <div className="flex items-center gap-2 text-xs">
        {error ? (
          <span className="text-amber-600">Couldn&apos;t load templates — enter the name manually.</span>
        ) : !loading && templates.length === 0 ? (
          <span className="text-gray-500">No templates found for this partner — type the name manually.</span>
        ) : (
          <button
            type="button"
            className="text-blue-600 underline"
            onClick={() => setManual((m) => !m)}
          >
            {useManual ? 'Pick from list' : 'Type manually'}
          </button>
        )}
      </div>

      {selected && <TemplatePreview template={selected} params={params} />}

      {selected && varsNeeded > 0 && (
        <p className="text-xs text-gray-400">
          This template needs {varsNeeded} body variable{varsNeeded > 1 ? 's' : ''} ({'{{1}}'}
          {varsNeeded > 1 ? `…{{${varsNeeded}}}` : ''}) — fill them in Body params below.
        </p>
      )}
    </div>
  );
}
