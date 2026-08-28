import {
  AlertTriangle,
  CheckCircle2,
  CircleHelp,
  XCircle,
  type LucideIcon,
} from "lucide-react";

type Tone = "success" | "danger" | "warning" | "info" | "neutral" | "violet";

const icons: Record<Tone, LucideIcon> = {
  success: CheckCircle2,
  danger: XCircle,
  warning: AlertTriangle,
  info: CheckCircle2,
  neutral: CircleHelp,
  violet: CheckCircle2,
};

export function StatusBadge({
  tone,
  children,
}: {
  tone: Tone;
  children: React.ReactNode;
}) {
  const Icon = icons[tone];
  return (
    <span className={`status-badge status-${tone}`}>
      <Icon aria-hidden="true" size={14} />
      {children}
    </span>
  );
}
