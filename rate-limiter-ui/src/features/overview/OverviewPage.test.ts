import { describe, expect, it } from "vitest";

import { deriveHealthPresentation } from "./OverviewPage";

describe("overview health presentation", () => {
  it("prioritizes failed refreshes and pending states truthfully", () => {
    expect(deriveHealthPresentation("UP", "UP", false, true)).toMatchObject({
      title: "Status stale",
      tone: "warning",
    });
    expect(
      deriveHealthPresentation(undefined, undefined, true, false),
    ).toMatchObject({
      title: "Checking service",
      tone: "neutral",
    });
  });

  it("distinguishes ready, alive-but-not-ready, and unreachable", () => {
    expect(deriveHealthPresentation("UP", "UP", false, false)).toMatchObject({
      title: "Ready to enforce limits",
      tone: "success",
    });
    expect(deriveHealthPresentation("UP", "DOWN", false, false)).toMatchObject({
      title: "Service alive; backend not ready",
      tone: "warning",
    });
    expect(
      deriveHealthPresentation(undefined, undefined, false, true),
    ).toMatchObject({
      title: "Cannot reach the service",
      tone: "danger",
    });
    expect(deriveHealthPresentation("DOWN", "UP", false, false)).toMatchObject({
      title: "Cannot reach the service",
      tone: "danger",
    });
  });
});
