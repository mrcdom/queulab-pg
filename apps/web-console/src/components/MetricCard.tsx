interface MetricCardProps {
  label: string;
  value: string;
  tone?: 'default' | 'accent' | 'danger';
}

export function MetricCard({ label, value, tone = 'default' }: MetricCardProps) {
  return (
    <article className={`metric-card metric-card--${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}