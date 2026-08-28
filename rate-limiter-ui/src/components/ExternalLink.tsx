import { ExternalLink as ExternalLinkIcon } from "lucide-react";

export function ExternalLink({
  href,
  children,
  className = "",
  onClick,
}: React.PropsWithChildren<{
  href: string;
  className?: string;
  onClick?: (() => void) | undefined;
}>) {
  return (
    <a
      className={className}
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      onClick={onClick}
    >
      {children}
      <ExternalLinkIcon aria-hidden="true" size={15} />
      <span className="sr-only"> (opens in a new tab)</span>
    </a>
  );
}
