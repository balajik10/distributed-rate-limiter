import { ArrowLeft, SearchX } from "lucide-react";
import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="not-found">
      <SearchX aria-hidden="true" size={42} />
      <p className="eyebrow">404 · Console route</p>
      <h1>Page not found</h1>
      <p>The API has not been called and no request was consumed.</p>
      <Link className="button primary" to="/">
        <ArrowLeft aria-hidden="true" size={16} /> Return to overview
      </Link>
    </div>
  );
}
