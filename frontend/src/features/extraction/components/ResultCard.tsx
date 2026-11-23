import { useState } from 'react';
import type { FileExtractionResult, FileStatus, RuleOutcome } from '@/api/types';
import { Badge, type BadgeTone } from '@/components/Badge';
import { Card } from '@/components/Card';
import { CodeBlock } from '@/components/CodeBlock';
import { DataTable } from '@/components/DataTable';
import { Segmented } from '@/components/Segmented';
import { formatDuration, formatPercent, formatValue } from '@/lib/format';

const STATUS_TONE: Record<FileStatus, BadgeTone> = {
  OK: 'success',
  TIER_NOT_CONFIGURED: 'warning',
  UNSUPPORTED: 'warning',
  ERROR: 'danger',
};

const RULE_TONE: Record<RuleOutcome, BadgeTone> = { PASSED: 'success', FAILED: 'danger', SKIPPED: 'neutral' };

function confidenceTone(c: number | undefined): BadgeTone {
  if (c === undefined) return 'neutral';
  if (c >= 0.75) return 'success';
  if (c >= 0.5) return 'warning';
  return 'danger';
}

type View = 'table' | 'json' | 'text';

export function ResultCard({ result }: { result: FileExtractionResult }) {
  const [view, setView] = useState<View>('table');
  const fields = result.fields ?? {};
  const fieldNames = Object.keys(fields);
  const rows = fieldNames.flatMap((name) => {
    const ok = result.fieldValidation?.[name];
    const conf = result.confidence?.[name];
    return flattenRows(name, fields[name]).map(([path, value]) => ({
      invalid: ok === false,
      cells: [
        <span key="p" className="mono">{path}</span>,
        <span key="v" className="value">{formatValue(value)}</span>,
        <Badge key="c" tone={confidenceTone(conf)}>{formatPercent(conf)}</Badge>,
        ok === undefined ? '—' : ok ? '✓' : '✗',
      ],
    }));
  });

  return (
    <Card
      title={
        <span className="result-title">
          {result.filename}
          <Badge tone={STATUS_TONE[result.status]}>{result.status}</Badge>
          {result.valid !== undefined && (
            <Badge tone={result.valid ? 'success' : 'danger'}>{result.valid ? 'valid' : 'invalid'}</Badge>
          )}
        </span>
      }
      actions={
        result.status === 'OK' && (
          <Segmented<View>
            label={`View for ${result.filename}`}
            size="sm"
            value={view}
            onChange={setView}
            options={[
              { value: 'table', label: 'Table' },
              { value: 'json', label: 'JSON' },
              ...(result.text !== undefined ? [{ value: 'text' as const, label: 'Text' }] : []),
            ]}
          />
        )
      }
    >
      <dl className="meta">
        {result.detectedType && <Meta k="Type" v={result.detectedType} />}
        {result.documentType && <Meta k="Detected as" v={result.documentType} />}
        {result.schemaId && !result.documentType && <Meta k="Schema" v={result.schemaId} />}
        {result.tier && <Meta k="Tier" v={result.tier} />}
        {result.engine && <Meta k="Engine" v={result.engine} />}
        {result.routing && <Meta k="Routing" v={result.routing} />}
        <Meta k="Cost" v={`${result.costUnits} units`} />
        {result.elapsedMs !== undefined && <Meta k="Time" v={formatDuration(result.elapsedMs)} />}
      </dl>

      {result.error && <p className="error-text" role="alert">{result.error}</p>}

      {result.status === 'OK' && view === 'table' && (
        <>
          {fieldNames.length === 0 ? (
            <p className="muted">No fields extracted.</p>
          ) : (
            <DataTable head={['Field', 'Value', 'Confidence', 'Valid']} rows={rows.map((r) => r.cells)} rowClassName={(i) => (rows[i].invalid ? 'row--invalid' : undefined)} />
          )}

          {!!result.violations?.length && (
            <div className="section">
              <h3>Violations</h3>
              <ul className="issues">
                {result.violations.map((v, i) => (
                  <li key={i}>
                    <span className="mono">{v.field ?? '(document)'}</span> · <span className="muted">{v.rule}</span> — {v.message}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {!!result.rules?.length && (
            <div className="section">
              <h3>Rules</h3>
              <ul className="issues">
                {result.rules.map((r, i) => (
                  <li key={i}>
                    <Badge tone={RULE_TONE[r.outcome]}>{r.outcome}</Badge> <code>{r.expression}</code>
                    {r.detail && <span className="muted"> — {r.detail}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}

      {view === 'json' && <CodeBlock label={`JSON for ${result.filename}`}>{JSON.stringify(result, null, 2)}</CodeBlock>}
      {view === 'text' && <CodeBlock label={`Text read from ${result.filename}`}>{result.text ?? ''}</CodeBlock>}
    </Card>
  );
}

/** Nested objects (e.g. vendor.name, vendor.address) get a row per leaf; arrays stay one row. */
function flattenRows(path: string, value: unknown): [string, unknown][] {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return Object.entries(value as Record<string, unknown>).flatMap(([k, v]) => flattenRows(`${path}.${k}`, v));
  }
  return [[path, value]];
}

function Meta({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt>{k}</dt>
      <dd>{v}</dd>
    </div>
  );
}
