import { useMemo, useState, type FormEvent } from 'react';
import type { ExtractRequest, Tier } from '@/api/types';
import { Card } from '@/components/Card';
import { PRESETS, pretty } from '../presets';
import { validateRules, validateSchema } from '../validateInput';
import { FileDropzone } from './FileDropzone';

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
  const [schema, setSchema] = useState(pretty(PRESETS[0].schema));
  const [rules, setRules] = useState(pretty(PRESETS[0].rules));
  const [tier, setTier] = useState<Tier>('auto');
  const [includeText, setIncludeText] = useState(false);
  const [touched, setTouched] = useState(false);

  const schemaError = useMemo(() => validateSchema(schema), [schema]);
  const rulesError = useMemo(() => validateRules(rules), [rules]);
  const canSubmit = files.length > 0 && !schemaError && !rulesError && !loading;

  const applyPreset = (id: string) => {
    const p = PRESETS.find((x) => x.id === id);
    if (!p) return;
    setSchema(pretty(p.schema));
    setRules(pretty(p.rules));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!canSubmit) return;
    onSubmit({ files, schema, rules, tier, includeText });
  };

  return (
    <form onSubmit={submit} className="stack">
      <Card title="1. Documents">
        <FileDropzone files={files} onChange={setFiles} />
        {touched && files.length === 0 && <p className="error-text">Add at least one document.</p>}
      </Card>

      <Card
        title="2. Schema"
        actions={
          <select aria-label="Load preset" defaultValue="" onChange={(e) => applyPreset(e.target.value)}>
            <option value="" disabled>
              Load preset…
            </option>
            {PRESETS.map((p) => (
              <option key={p.id} value={p.id}>
                {p.label}
              </option>
            ))}
          </select>
        }
      >
        <label className="field">
          <span className="field__label">JSON Schema</span>
          <textarea className="code" rows={14} spellCheck={false} value={schema} onChange={(e) => setSchema(e.target.value)} />
          {schemaError && <span className="error-text">{schemaError}</span>}
        </label>
        <label className="field">
          <span className="field__label">
            Business rules <span className="muted">(optional JSON array)</span>
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
          <button type="button" className="btn btn--secondary" onClick={onCancel}>
            Cancel
          </button>
        ) : null}
        <button type="submit" className="btn btn--primary" disabled={loading}>
          {loading ? 'Extracting…' : `Extract ${files.length || ''} ${files.length === 1 ? 'file' : 'files'}`.replace(/\s+/g, ' ')}
        </button>
      </div>
    </form>
  );
}
