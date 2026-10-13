import { useRef, useState, type DragEvent } from 'react';
import { formatBytes } from '@/lib/format';

const MAX_FILE_BYTES = 20 * 1024 * 1024; // matches spring.servlet.multipart.max-file-size

interface Props {
  files: File[];
  onChange: (files: File[]) => void;
}

export function FileDropzone({ files, onChange }: Props) {
  const input = useRef<HTMLInputElement>(null);
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

  return (
    <div>
      <div
        className={`dropzone ${dragging ? 'dropzone--active' : ''}`}
        role="button"
        tabIndex={0}
        onClick={() => input.current?.click()}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <strong>Drop documents here</strong> or click to browse
        <div className="muted">PDF, DOCX, images, spreadsheets · up to 20 MB each</div>
        <input
          ref={input}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            add(e.target.files);
            e.target.value = '';
          }}
        />
      </div>

      {rejected.length > 0 && <p className="error-text">Too large (max 20 MB): {rejected.join(', ')}</p>}

      {files.length > 0 && (
        <ul className="file-list">
          {files.map((f, i) => (
            <li key={`${f.name}:${f.size}`}>
              <span className="file-list__name">{f.name}</span>
              <span className="muted">{formatBytes(f.size)}</span>
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
      )}
    </div>
  );
}
