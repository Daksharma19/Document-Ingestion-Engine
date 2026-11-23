import { useState, type DragEvent } from 'react';
import { formatBytes } from '@/lib/format';

export const MAX_FILE_BYTES = 20 * 1024 * 1024; // spring.servlet.multipart.max-file-size
export const MAX_REQUEST_BYTES = 25 * 1024 * 1024; // spring.servlet.multipart.max-request-size

interface Props {
  files: File[];
  onChange: (files: File[]) => void;
  maxFiles: number;
}

/** Client-side validation for an upload batch; null when it can be sent. */
export function batchProblem(files: File[], maxFiles: number): string | null {
  if (files.length === 0) return 'Add at least one document.';
  if (files.length > maxFiles) return `At most ${maxFiles} files per request.`;
  if (totalBytes(files) > MAX_REQUEST_BYTES) return 'Total size is over 25 MB per request; remove some files.';
  return null;
}

const totalBytes = (files: File[]) => files.reduce((s, f) => s + f.size, 0);

export function FileDropzone({ files, onChange, maxFiles }: Props) {
  const [dragging, setDragging] = useState(false);
  const [rejected, setRejected] = useState<string[]>([]);

  const add = (incoming: FileList | null) => {
    if (!incoming) return;
    const ok: File[] = [];
    const tooBig: string[] = [];
    Array.from(incoming).forEach((f) => (f.size > MAX_FILE_BYTES ? tooBig.push(f.name) : ok.push(f)));
    setRejected(tooBig);
    // De-duplicate by name+size so dropping the same file twice is harmless.
    const key = (f: File) => `${f.name}:${f.size}`;
    const existing = new Set(files.map(key));
    onChange([...files, ...ok.filter((f) => !existing.has(key(f)))]);
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    add(e.dataTransfer.files);
  };

  const total = totalBytes(files);
  const overTotal = total > MAX_REQUEST_BYTES;
  const overCount = files.length > maxFiles;

  return (
    <div>
      <label
        className={`dropzone${dragging ? ' dropzone--active' : ''}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <strong>Drop documents here</strong> or click to browse
        <span className="dropzone__hint muted">
          PDF, DOCX, images, spreadsheets · up to {maxFiles} files, 20 MB each, 25 MB total
        </span>
        <input
          type="file"
          multiple
          className="visually-hidden"
          aria-label="Choose documents"
          onChange={(e) => {
            add(e.target.files);
            e.target.value = '';
          }}
        />
      </label>

      {rejected.length > 0 && (
        <p className="error-text" role="alert">
          Too large (max 20 MB): {rejected.join(', ')}
        </p>
      )}

      {files.length > 0 && (
        <>
          <p className={`file-list__count${overTotal || overCount ? ' error-text' : ' muted'}`}>
            {files.length} {files.length === 1 ? 'file' : 'files'} · {formatBytes(total)}
            {overCount && ` · over the ${maxFiles}-file limit`}
            {overTotal && ' · over the 25 MB request limit'}
          </p>
          <ul className="file-list">
            {files.map((f, i) => (
              <li key={`${f.name}:${f.size}`}>
                <span className="file-list__name">{f.name}</span>
                <span className="file-list__size muted">{formatBytes(f.size)}</span>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  aria-label={`Remove ${f.name}`}
                  onClick={() => onChange(files.filter((_, j) => j !== i))}
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
