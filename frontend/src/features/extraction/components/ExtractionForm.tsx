import { useMemo, useState, type FormEvent } from 'react';
import type { ExtractRequest, Tier } from '@/api/types';
import { Card } from '@/components/Card';
import { AUTO_SCHEMA, FALLBACK_PRESETS, groupByCategory, pretty, useSchemaPresets } from '../presets';
import { validateRules, validateSchema } from '../validateInput';
import { Button } from '@/components/Button';
import { FileDropzone, batchProblem } from '@/components/FileDropzone';

export const EXTRACT_MAX_FILES = 20;

const TIERS: { value: Tier; label: string; hint: string }[] = [
  { value: 'auto', label: 'Auto', hint: 'Route by detected file type' },
  { value: 'native-text', label: 'Native text', hint: 'Cheapest; needs a text layer' },
  { value: 'ocr', label: 'OCR', hint: 'Scans and images' },
  { value: 'vision-llm', label: 'Vision LLM', hint: 'Needs OPENAI_API_KEY on the server' },
];

interface Props {
  loading: boolean;
  onSubmit: (req: ExtractRequest) => void;
  onCancel: () => void;
}

export function ExtractionForm({ loading, onSubmit, onCancel }: Props) {
  const [files, setFiles] = useState<File[]>([]);
  const { presets } = useSchemaPresets();
  const [presetId, setPresetId] = useState(FALLBACK_PRESETS[0].id);
  const [schema, setSchema] = useState(pretty(FALLBACK_PRESETS[0].schema));
  const [rules, setRules] = useState(pretty(FALLBACK_PRESETS[0].rules));
  const [tier, setTier] = useState<Tier>('auto');
  const [includeText, setIncludeText] = useState(false);
  const [touched, setTouched] = useState(false);

  const auto = presetId === AUTO_SCHEMA;
  const schemaError = useMemo(() => (auto ? null : validateSchema(schema)), [auto, schema]);
  const rulesError = useMemo(() => validateRules(rules), [rules]);
  const filesProblem = batchProblem(files, EXTRACT_MAX_FILES);
  const canSubmit = !filesProblem && !schemaError && !rulesError && !loading;
  const selected = presets.find((p) => p.id === presetId);

  const applyPreset = (id: string) => {
    setPresetId(id);
    if (id === AUTO_SCHEMA) {
      setRules('');
      return;
    }
    const p = presets.find((x) => x.id === id);
    if (!p) return;
    setSchema(pretty(p.schema));
    setRules(pretty(p.rules));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!canSubmit) return;
    onSubmit({ files, schema: auto ? AUTO_SCHEMA : schema, rules, tier, includeText });
  };

  return (
    <form onSubmit={submit} className="stack" noValidate>
      <Card title="1. Documents">
        <FileDropzone files={files} onChange={setFiles} maxFiles={EXTRACT_MAX_FILES} />
        {touched && filesProblem && <p className="error-text">{filesProblem}</p>}
      </Card>

      <Card
        title="2. Schema"
        actions={
          <select aria-label="Schema preset" value={presetId} onChange={(e) => applyPreset(e.target.value)}>
            <option value={AUTO_SCHEMA}>Auto-detect document type</option>
            {groupByCategory(presets).map(([category, group]) => (
              <optgroup key={category} label={category}>
                {group.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        }
      >
        {auto ? (
          <p className="muted preset-hint">
            Each document is classified (invoice, receipt, bank statement, payslip, résumé, …) and extracted with the
            matching built-in schema and its default rules. Pick a preset to see or edit a schema.
          </p>
        ) : (
          <>
            {selected?.description && <p className="muted preset-hint">{selected.description}</p>}
            <label className="field">
              <span className="field__label">JSON Schema</span>
              <textarea className="code" rows={14} spellCheck={false} value={schema} onChange={(e) => setSchema(e.target.value)} />
              {schemaError && <span className="error-text">{schemaError}</span>}
            </label>
          </>
        )}
        <label className="field">
          <span className="field__label">
            Business rules{' '}
            <span className="muted">(optional JSON array{auto ? "; empty uses each type's defaults" : ''})</span>
          </span>
          <textarea className="code" rows={3} spellCheck={false} value={rules} onChange={(e) => setRules(e.target.value)} />
          {rulesError && <span className="error-text">{rulesError}</span>}
        </label>
      </Card>

      <Card title="3. Options">
        <fieldset className="tiers">
          <legend className="field__label">Extraction tier</legend>
          {TIERS.map((t) => (
            <label key={t.value} className={`tier ${tier === t.value ? 'tier--selected' : ''}`}>
              <input type="radio" name="tier" value={t.value} checked={tier === t.value} onChange={() => setTier(t.value)} />
              <span>
                <strong>{t.label}</strong>
                <span className="muted tier__hint">{t.hint}</span>
              </span>
            </label>
          ))}
        </fieldset>
        <label className="checkbox">
          <input type="checkbox" checked={includeText} onChange={(e) => setIncludeText(e.target.checked)} />
          Include raw text the tier read (useful for debugging OCR)
        </label>
      </Card>

      <div className="form-actions">
        {loading ? (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        ) : null}
        <Button type="submit" variant="primary" busy={loading} disabled={!canSubmit}>
          {loading ? 'Extracting…' : `Extract ${files.length || ''} ${files.length === 1 ? 'file' : 'files'}`.replace(/\s+/g, ' ')}
        </Button>
      </div>
    </form>
  );
}
