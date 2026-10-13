import { useState } from 'react';
import type { FileExtractionResult, FileStatus, RuleOutcome } from '@/api/types';
import { Badge, type BadgeTone } from '@/components/Badge';
import { Card } from '@/components/Card';
import { formatPercent, formatValue } from '@/lib/format';

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

export function ResultCard({ result }: { result: FileExtractionResult }) {
  const [view, setView] = useState<'table' | 'json' | 'text'>('table');
  const fields = result.fields ?? {};
  const fieldNames = Object.keys(fields);

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
          <div className="segmented" role="tablist">
            {(['table', 'json', ...(result.text !== undefined ? ['text'] : [])] as const).map((v) => (
              <button
                key={v}
                type="button"
                role="tab"
                aria-selected={view === v}
                className={view === v ? 'active' : ''}
                onClick={() => setView(v as typeof view)}
              >
                {v}
              </button>
            ))}
          </div>
        )
      }
    >
      <dl className="meta">
        {result.detectedType && <Meta k="Type" v={result.detectedType} />}
        {result.tier && <Meta k="Tier" v={result.tier} />}
        {result.routing && <Meta k="Routing" v={result.routing} />}
        <Meta k="Cost" v={`${result.costUnits} units`} />
      </dl>

      {result.error && <p className="error-text">{result.error}</p>}

      {result.status === 'OK' && view === 'table' && (
        <>
          {fieldNames.length === 0 ? (
            <p className="muted">No fields extracted.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Value</th>
                  <th>Confidence</th>
                  <th>Valid</th>
                </tr>
              </thead>
              <tbody>
                {fieldNames.map((name) => {
                  const ok = result.fieldValidation?.[name];
                  const conf = result.confidence?.[name];
                  return (
                    <tr key={name} className={ok === false ? 'row--invalid' : ''}>
                      <td className="mono">{name}</td>
                      <td className="value">{formatValue(fields[name])}</td>
                      <td>
                        <Badge tone={confidenceTone(conf)}>{formatPercent(conf)}</Badge>
                      </td>
                      <td>{ok === undefined ? '—' : ok ? '✓' : '✗'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
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

      {view === 'json' && <pre className="code-block">{JSON.stringify(result, null, 2)}</pre>}
      {view === 'text' && <pre className="code-block">{result.text}</pre>}
    </Card>
  );
}

function Meta({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt>{k}</dt>
      <dd>{v}</dd>
    </div>
  );
}
