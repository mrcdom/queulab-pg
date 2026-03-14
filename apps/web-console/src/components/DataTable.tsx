import { PropsWithChildren, ReactNode } from 'react';

interface DataTableProps extends PropsWithChildren {
  columns: ReactNode;
}

export function DataTable({ columns, children }: DataTableProps) {
  return (
    <div className="table-shell">
      <table>
        <thead>{columns}</thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}