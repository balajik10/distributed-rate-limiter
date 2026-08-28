import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { AcquisitionRecord } from "../../api/client";
import type { TrafficConfig } from "../../api/contracts";
import { useSession } from "../../app/session";
import { TrafficScheduler, type SchedulerSnapshot } from "./scheduler";

type RunState = SchedulerSnapshot & {
  records: AcquisitionRecord[];
};

const initialState: RunState = {
  running: false,
  scheduled: 0,
  completed: 0,
  inFlight: 0,
  records: [],
};

type TrafficLabContextValue = {
  state: RunState;
  start: (config: TrafficConfig) => void;
  stop: () => void;
};

const TrafficLabContext = createContext<TrafficLabContextValue | null>(null);

export function TrafficLabProvider({ children }: { children: ReactNode }) {
  const { addRecord, apiKey } = useSession();
  const scheduler = useRef<TrafficScheduler | null>(null);
  const [state, setState] = useState(initialState);

  useEffect(() => () => scheduler.current?.dispose(), []);

  const stop = useCallback(() => scheduler.current?.stop(), []);
  const start = useCallback(
    (config: TrafficConfig) => {
      const active = scheduler.current?.snapshot();
      if (active?.running || (active?.inFlight ?? 0) > 0) return;
      setState(initialState);
      const next = new TrafficScheduler({
        config,
        ...(apiKey ? { apiKey } : {}),
        onResult: (record) => {
          addRecord(record);
          setState((current) => ({
            ...current,
            records: [...current.records, record],
          }));
        },
        onState: (snapshot) =>
          setState((current) => ({ ...current, ...snapshot })),
      });
      scheduler.current = next;
      next.start();
    },
    [addRecord, apiKey],
  );

  const value = useMemo(() => ({ state, start, stop }), [start, state, stop]);
  return (
    <TrafficLabContext.Provider value={value}>
      {children}
    </TrafficLabContext.Provider>
  );
}

export function useTrafficLab(): TrafficLabContextValue {
  const context = useContext(TrafficLabContext);
  if (context === null)
    throw new Error("useTrafficLab must be used inside TrafficLabProvider");
  return context;
}
