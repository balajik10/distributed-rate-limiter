import { Activity } from "lucide-react";

export function EmptyState({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="empty-state">
      <Activity aria-hidden="true" size={28} />
      <strong>{title}</strong>
      <p>{description}</p>
    </div>
  );
}
