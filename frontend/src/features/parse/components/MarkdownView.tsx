import { useEffect, useState } from 'react';
import { Button } from '@/components/Button';
import { CodeBlock } from '@/components/CodeBlock';
import { copyToClipboard, downloadText } from '@/lib/format';

const mdName = (filename: string | undefined) => `${(filename ?? 'document').replace(/\.[^.]+$/, '') || 'document'}.md`;

export function MarkdownView({ markdown, filename }: { markdown: string; filename?: string }) {
  const [feedback, setFeedback] = useState('');
  useEffect(() => {
    if (!feedback) return;
    const id = setTimeout(() => setFeedback(''), 2000);
    return () => clearTimeout(id);
  }, [feedback]);

  return (
    <div className="stack">
      <div className="md-actions">
        <Button
          variant="secondary"
          size="sm"
          onClick={async () => setFeedback((await copyToClipboard(markdown)) ? 'Copied' : 'Copy failed')}
        >
          Copy
        </Button>
        <Button variant="secondary" size="sm" onClick={() => downloadText(mdName(filename), markdown, 'text/markdown')}>
          Download .md
        </Button>
        <span className="muted" aria-live="polite">
          {feedback}
        </span>
      </div>
      <CodeBlock label={`Markdown of ${filename ?? 'document'}`}>{markdown}</CodeBlock>
    </div>
  );
}
