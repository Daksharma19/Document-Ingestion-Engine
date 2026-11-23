import { useState, type FormEvent } from 'react';
import type { ParseRequest } from '@/api/types';
import { Button } from '@/components/Button';
import { Card } from '@/components/Card';
import { FileDropzone, batchProblem } from '@/components/FileDropzone';

export const PARSE_MAX_FILES = 50;

interface Props {
  loading: boolean;
  onSubmit: (req: ParseRequest) => void;
  onCancel: () => void;
}

export function ParseForm({ loading, onSubmit, onCancel }: Props) {
  const [files, setFiles] = useState<File[]>([]);
  const [includeMarkdown, setIncludeMarkdown] = useState(true);
  const [figures, setFigures] = useState(true);
  const [touched, setTouched] = useState(false);
  const problem = batchProblem(files, PARSE_MAX_FILES);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (problem || loading) return;
    onSubmit({ files, includeMarkdown, figures });
  };

  return (
    <form onSubmit={submit} className="stack" noValidate>
      <Card title="1. Documents">
        <FileDropzone files={files} onChange={setFiles} maxFiles={PARSE_MAX_FILES} />
        {touched && problem && <p className="error-text">{problem}</p>}
      </Card>

      <Card title="2. Options">
        <label className="checkbox">
          <input type="checkbox" checked={includeMarkdown} onChange={(e) => setIncludeMarkdown(e.target.checked)} />
          Include Markdown
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={figures} onChange={(e) => setFigures(e.target.checked)} />
          Read text inside figures (OCR on images in native pages)
        </label>
      </Card>

      <div className="form-actions">
        {loading && (
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" variant="primary" busy={loading} disabled={loading}>
          {loading ? 'Parsing…' : `Parse ${files.length || ''} ${files.length === 1 ? 'file' : 'files'}`.replace(/\s+/g, ' ')}
        </Button>
      </div>
    </form>
  );
}
