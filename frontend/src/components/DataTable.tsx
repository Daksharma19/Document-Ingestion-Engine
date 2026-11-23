import type { ReactNode } from 'react';

interface DataTableProps {
  head?: string[];
  rows: ReactNode[][];
  caption?: string;
  rowClassName?: (i: number) => string | undefined;
}

export function DataTable({ head, rows, caption, rowClassName }: DataTableProps) {
  return (
    <div className="table-scroll" tabIndex={0} role="region" aria-label={caption ?? 'Table'}>
      <table className="table">
        {caption && <caption className="table__caption">{caption}</caption>}
        {head && head.length > 0 && (
          <thead>
            <tr>{head.map((h, i) => <th key={i} scope="col">{h}</th>)}</tr>
          </thead>
        )}
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className={rowClassName?.(i)}>
              {r.map((c, j) => <td key={j}>{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
