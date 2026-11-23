interface CodeBlockProps {
  children: string;
  maxHeight?: number;
  label?: string;
}

export function CodeBlock({ children, maxHeight, label = 'Code' }: CodeBlockProps) {
  return (
    <pre className="code-block" tabIndex={0} aria-label={label} style={maxHeight ? { maxHeight } : undefined}>
      {children}
    </pre>
  );
}
