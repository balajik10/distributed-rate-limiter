import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useState,
  type ReactNode,
} from "react";
import type { AcquisitionRecord } from "../api/client";

export const MAX_SESSION_RECORDS = 200;

export type SessionSummary = {
  decisions: number;
  allowed: number;
  denied: number;
  degraded: number;
  unknown: number;
  latency: { p50: number; p95: number; p99: number };
};

export function appendRecord(
  records: AcquisitionRecord[],
  record: AcquisitionRecord,
): AcquisitionRecord[] {
  return [record, ...records].slice(0, MAX_SESSION_RECORDS);
}

export function percentile(values: number[], quantile: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.max(0, Math.ceil(sorted.length * quantile) - 1);
  return sorted[index] ?? 0;
}

export function summarizeSession(records: AcquisitionRecord[]): SessionSummary {
  const latencyValues = records
    .filter((record) => record.httpStatus !== null)
    .map((record) => record.latencyMs);
  return {
    decisions: records.length,
    allowed: records.filter((record) =>
      ["NORMAL_ALLOWED", "DEGRADED_ALLOWED"].includes(record.classification),
    ).length,
    denied: records.filter((record) =>
      ["QUOTA_DENIED", "BACKEND_DENIED"].includes(record.classification),
    ).length,
    degraded: records.filter((record) => record.decision?.degraded === true)
      .length,
    unknown: records.filter(
      (record) => record.classification === "UNKNOWN_OUTCOME",
    ).length,
    latency: {
      p50: percentile(latencyValues, 0.5),
      p95: percentile(latencyValues, 0.95),
      p99: percentile(latencyValues, 0.99),
    },
  };
}

type SessionContextValue = {
  records: AcquisitionRecord[];
  summary: SessionSummary;
  addRecord: (record: AcquisitionRecord) => void;
  apiKey: string;
  apiKeyRevision: number;
  setApiKey: (apiKey: string) => void;
  theme: "dark" | "light";
  toggleTheme: () => void;
};

const SessionContext = createContext<SessionContextValue | null>(null);

function recordReducer(
  records: AcquisitionRecord[],
  record: AcquisitionRecord,
): AcquisitionRecord[] {
  return appendRecord(records, record);
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [records, addRecord] = useReducer(recordReducer, []);
  const [credential, setCredential] = useState({ value: "", revision: 0 });
  const [theme, setTheme] = useState<"dark" | "light">(() => {
    const saved = window.localStorage.getItem("rate-limiter-theme");
    return saved === "light" ? "light" : "dark";
  });
  useEffect(() => {
    document.documentElement.dataset["theme"] = theme;
    window.localStorage.setItem("rate-limiter-theme", theme);
  }, [theme]);

  const value = useMemo<SessionContextValue>(
    () => ({
      records,
      summary: summarizeSession(records),
      addRecord,
      apiKey: credential.value,
      apiKeyRevision: credential.revision,
      setApiKey: (apiKey) =>
        setCredential((current) =>
          current.value === apiKey
            ? current
            : { value: apiKey, revision: current.revision + 1 },
        ),
      theme,
      toggleTheme: () =>
        setTheme((current) => (current === "dark" ? "light" : "dark")),
    }),
    [credential, records, theme],
  );

  return (
    <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  );
}

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext);
  if (context === null)
    throw new Error("useSession must be used inside SessionProvider");
  return context;
}
