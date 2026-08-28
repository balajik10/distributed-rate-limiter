import {
  Activity,
  BookOpen,
  Boxes,
  ChevronLeft,
  CircleGauge,
  KeyRound,
  Menu,
  Moon,
  Network,
  PlayCircle,
  Settings2,
  Sun,
  X,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { useHealthQueries } from "../api/queries";
import { ExternalLink } from "../components/ExternalLink";
import { StatusBadge } from "../components/StatusBadge";
import { useSession } from "./session";
import { externalLinks } from "./links";

const navigation = [
  { to: "/", end: true, label: "Overview", icon: CircleGauge },
  { to: "/playground", label: "Playground", icon: PlayCircle },
  { to: "/policies", label: "Policies", icon: BookOpen },
  { to: "/system", label: "System", icon: Settings2 },
  { to: "/architecture", label: "Architecture", icon: Network },
] as const;

const pageTitles: Record<string, string> = {
  "/": "Overview",
  "/playground": "Playground",
  "/policies": "Policies",
  "/system": "System",
  "/architecture": "Architecture",
};

function PrimaryNavigation({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav aria-label="Primary navigation" className="primary-navigation">
      <p className="nav-label">Console</p>
      {navigation.map(({ to, label, icon: Icon }) => (
        <NavLink
          className={({ isActive }) => `nav-link${isActive ? " active" : ""}`}
          key={to}
          to={to}
          end={to === "/"}
          onClick={onNavigate}
        >
          <Icon aria-hidden="true" size={19} />
          <span>{label}</span>
        </NavLink>
      ))}
      <p className="nav-label external-label">External tools</p>
      <ExternalLink
        className="nav-link"
        href={externalLinks.swagger}
        onClick={onNavigate}
      >
        <BookOpen aria-hidden="true" size={19} />
        <span>Swagger</span>
      </ExternalLink>
      <ExternalLink
        className="nav-link"
        href={externalLinks.grafana}
        onClick={onNavigate}
      >
        <Activity aria-hidden="true" size={19} />
        <span>Grafana</span>
      </ExternalLink>
    </nav>
  );
}

function Brand() {
  return (
    <div className="brand">
      <img src="/console/console-mark.svg" alt="" width="42" height="42" />
      <div>
        <strong>Distributed Rate Limiter</strong>
        <span>Operations Console</span>
      </div>
    </div>
  );
}

function CredentialControl() {
  const { apiKey, setApiKey } = useSession();
  const [draftApiKey, setDraftApiKey] = useState(apiKey);

  return (
    <details className="credential-control">
      <summary className="icon-button credential-summary">
        <KeyRound aria-hidden="true" size={18} />
        <span className="desktop-only">Session key</span>
        {apiKey ? (
          <span className="credential-dot" aria-label="API key is set" />
        ) : null}
      </summary>
      <form
        className="credential-popover"
        onSubmit={(event) => {
          event.preventDefault();
          setApiKey(draftApiKey);
        }}
      >
        <label htmlFor="session-api-key">Optional X-API-Key</label>
        <p>
          Used only from memory for this tab. It is never stored or copied into
          generated commands.
        </p>
        <input
          id="session-api-key"
          type="password"
          autoComplete="off"
          value={draftApiKey}
          onChange={(event) => setDraftApiKey(event.target.value)}
        />
        <div className="credential-actions">
          <button
            className="button primary small"
            type="submit"
            disabled={draftApiKey === apiKey}
          >
            Apply key
          </button>
          <button
            className="button secondary small"
            type="button"
            onClick={() => {
              setDraftApiKey("");
              setApiKey("");
            }}
            disabled={!apiKey && !draftApiKey}
          >
            Clear key
          </button>
        </div>
      </form>
    </details>
  );
}

export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const { pathname } = useLocation();
  const { theme, toggleTheme } = useSession();
  const health = useHealthQueries();
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false);
    queueMicrotask(() => menuButtonRef.current?.focus());
  }, []);

  useEffect(() => {
    document.title = `${pageTitles[pathname] ?? "Not found"} · Distributed Rate Limiter`;
  }, [pathname]);

  useEffect(() => {
    if (!drawerOpen) return;
    const drawer = drawerRef.current;
    const focusable = drawer?.querySelectorAll<HTMLElement>(
      "a[href], button:not([disabled]), input:not([disabled])",
    );
    focusable?.[0]?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        closeDrawer();
      }
      if (event.key !== "Tab" || !focusable?.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [closeDrawer, drawerOpen]);

  const lastCheck = Math.max(
    health.liveness.data?.checkedAtEpochMs ?? 0,
    health.readiness.data?.checkedAtEpochMs ?? 0,
  );
  const healthFailed = health.liveness.isError || health.readiness.isError;
  const healthPending = health.liveness.isPending || health.readiness.isPending;
  const globallyReady =
    !healthFailed &&
    health.liveness.data?.status === "UP" &&
    health.readiness.data?.status === "UP";

  return (
    <div
      className={`app-layout${sidebarCollapsed ? " sidebar-collapsed" : ""}`}
    >
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <aside className="sidebar desktop-sidebar">
        <Brand />
        <PrimaryNavigation />
        <button
          className="collapse-button"
          type="button"
          onClick={() => setSidebarCollapsed((value) => !value)}
          aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
        >
          <ChevronLeft aria-hidden="true" size={18} />
          <span>Collapse</span>
        </button>
      </aside>

      {drawerOpen ? (
        <div
          className="drawer-backdrop"
          role="presentation"
          onMouseDown={closeDrawer}
        >
          <div
            className="mobile-drawer"
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-label="Navigation"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="drawer-heading">
              <Brand />
              <button
                className="icon-button"
                type="button"
                onClick={closeDrawer}
                aria-label="Close navigation"
              >
                <X aria-hidden="true" size={20} />
              </button>
            </div>
            <PrimaryNavigation onNavigate={closeDrawer} />
          </div>
        </div>
      ) : null}

      <div className="app-column">
        <header className="topbar">
          <button
            className="icon-button mobile-menu"
            ref={menuButtonRef}
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-label="Open navigation"
          >
            <Menu aria-hidden="true" size={20} />
          </button>
          <span className="environment-badge">
            <span aria-hidden="true" /> LOCAL DEMO
          </span>
          <div className="connection-state">
            <StatusBadge
              tone={
                globallyReady
                  ? "success"
                  : healthPending
                    ? "neutral"
                    : "warning"
              }
            >
              {globallyReady
                ? "Connected"
                : healthPending
                  ? "Checking"
                  : "Needs attention"}
            </StatusBadge>
            <span className="last-check desktop-only">
              {lastCheck
                ? `${healthFailed ? "Last successful" : "Checked"} ${new Date(lastCheck).toLocaleTimeString()}`
                : "No health check yet"}
            </span>
          </div>
          <div className="topbar-actions">
            <CredentialControl />
            <button
              className="icon-button"
              type="button"
              onClick={toggleTheme}
              aria-label={`Use ${theme === "dark" ? "light" : "dark"} theme`}
            >
              {theme === "dark" ? (
                <Sun aria-hidden="true" size={19} />
              ) : (
                <Moon aria-hidden="true" size={19} />
              )}
            </button>
          </div>
        </header>
        <main id="main-content" tabIndex={-1}>
          <Outlet />
        </main>
        <footer>
          <Boxes aria-hidden="true" size={16} />
          Local developer and interview tooling — not a public production
          control plane.
        </footer>
      </div>
    </div>
  );
}
