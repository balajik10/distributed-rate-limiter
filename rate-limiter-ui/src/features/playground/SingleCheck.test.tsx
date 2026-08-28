import { StrictMode, useEffect, type ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { axe } from "jest-axe";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { acquirePermit, type AcquisitionRecord } from "../../api/client";
import { SessionProvider, useSession } from "../../app/session";
import {
  decision,
  record,
  slidingLogPolicy,
  tokenBucketPolicy,
} from "../../test/fixtures";
import { SingleCheck } from "./SingleCheck";

vi.mock("../../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/client")>();
  return { ...actual, acquirePermit: vi.fn() };
});

const mockedAcquire = vi.mocked(acquirePermit);

function CredentialSeed({ children }: { children: ReactNode }) {
  const { setApiKey } = useSession();
  useEffect(() => setApiKey("runtime-secret"), [setApiKey]);
  return children;
}

function renderCheck(options: { credential?: boolean; strict?: boolean } = {}) {
  const onUnknownPolicy = vi
    .fn<() => Promise<unknown>>()
    .mockResolvedValue(undefined);
  const content = (
    <SessionProvider>
      {options.credential ? (
        <CredentialSeed>
          <SingleCheck
            policies={[tokenBucketPolicy, slidingLogPolicy]}
            onUnknownPolicy={onUnknownPolicy}
          />
        </CredentialSeed>
      ) : (
        <SingleCheck
          policies={[tokenBucketPolicy, slidingLogPolicy]}
          onUnknownPolicy={onUnknownPolicy}
        />
      )}
    </SessionProvider>
  );
  return {
    ...render(options.strict ? <StrictMode>{content}</StrictMode> : content),
    onUnknownPolicy,
  };
}

beforeEach(() => {
  mockedAcquire.mockResolvedValue(record());
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: {
      writeText: vi
        .fn<(value: string) => Promise<void>>()
        .mockResolvedValue(undefined),
    },
  });
});

describe("SingleCheck", () => {
  it("renders associated fields and disables submission when no policies exist", async () => {
    const { container } = render(
      <SessionProvider>
        <SingleCheck policies={[]} onUnknownPolicy={() => Promise.resolve()} />
      </SessionProvider>,
    );

    expect(screen.getByLabelText("Policy")).toBeInTheDocument();
    expect(screen.getByLabelText("Logical key")).toBeInTheDocument();
    expect(screen.getByLabelText("Permits")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Check limit" })).toBeDisabled();
    expect((await axe(container)).violations).toEqual([]);
  });

  it("validates the key boundary and focuses the first invalid field", async () => {
    const user = userEvent.setup();
    renderCheck();
    const key = screen.getByLabelText("Logical key");

    await user.clear(key);
    await user.click(screen.getByRole("button", { name: "Check limit" }));
    expect(await screen.findByText("Logical key is required")).toBeVisible();
    expect(key).toHaveFocus();
    expect(mockedAcquire).not.toHaveBeenCalled();

    await user.type(key, "k".repeat(257));
    await user.click(screen.getByRole("button", { name: "Check limit" }));
    expect(await screen.findByText("Maximum length is 256")).toBeVisible();
    expect(mockedAcquire).not.toHaveBeenCalled();
  });

  it("enforces the selected policy maximum and focuses permits", async () => {
    const user = userEvent.setup();
    renderCheck();

    await user.selectOptions(screen.getByLabelText("Policy"), "login-strict");
    const permits = screen.getByLabelText("Permits");
    await user.clear(permits);
    await user.type(permits, "6");
    await user.click(screen.getByRole("button", { name: "Check limit" }));

    expect(
      await screen.findByText(
        "Selected policy allows at most 5 permits per request",
      ),
    ).toBeVisible();
    expect(permits).toHaveFocus();
    expect(mockedAcquire).not.toHaveBeenCalled();
  });

  it("makes exactly one POST per explicit click even under StrictMode", async () => {
    const user = userEvent.setup();
    renderCheck({ strict: true });

    await user.click(screen.getByRole("button", { name: "Check limit" }));

    expect(mockedAcquire).toHaveBeenCalledOnce();
    expect(mockedAcquire).toHaveBeenCalledWith(
      expect.objectContaining({
        policyId: "api-standard",
        permits: 1,
      }),
    );
    expect(mockedAcquire.mock.calls[0]?.[0].requestId).toBeUndefined();
    expect(
      screen.getByRole("heading", { name: "Request allowed" }),
    ).toBeVisible();
  });

  it("disables duplicate submission while the mutation is in flight", async () => {
    let resolveRequest: ((value: AcquisitionRecord) => void) | undefined;
    mockedAcquire.mockReturnValue(
      new Promise<AcquisitionRecord>((resolve) => {
        resolveRequest = resolve;
      }),
    );
    const user = userEvent.setup();
    renderCheck();

    const button = screen.getByRole("button", { name: "Check limit" });
    await user.click(button);
    expect(screen.getByRole("button", { name: "Checking…" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Checking…" }));
    expect(mockedAcquire).toHaveBeenCalledOnce();

    resolveRequest?.(record());
    expect(
      await screen.findByRole("heading", { name: "Request allowed" }),
    ).toBeVisible();
  });

  it("supports Ctrl+Enter without duplicate submission and clears a supplied request ID", async () => {
    const user = userEvent.setup();
    renderCheck();
    const requestId = screen.getByLabelText(/Client request ID/i);
    await user.type(requestId, "explicit-id");
    await user.keyboard("{Control>}{Enter}{/Control}");

    expect(mockedAcquire).toHaveBeenCalledOnce();
    expect(mockedAcquire).toHaveBeenCalledWith(
      expect.objectContaining({ requestId: "explicit-id" }),
    );
    expect(requestId).toHaveValue("");
  });

  it("forwards the session API key in memory but copies only a placeholder", async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, "writeText");
    renderCheck({ credential: true });

    await user.click(screen.getByRole("button", { name: "Check limit" }));
    expect(mockedAcquire).toHaveBeenCalledWith(
      expect.objectContaining({ apiKey: "runtime-secret" }),
    );

    await user.click(screen.getByRole("button", { name: "Copy cURL" }));
    const copied = writeText.mock.calls[0]?.[0] ?? "";
    expect(copied).toContain("X-API-Key: YOUR_API_KEY");
    expect(copied).not.toContain("runtime-secret");
  });

  it("copies the current non-secret JSON only on an explicit action", async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, "writeText");
    renderCheck();

    await user.clear(screen.getByLabelText("Logical key"));
    await user.type(screen.getByLabelText("Logical key"), "user:copy-test");
    await user.click(screen.getByRole("button", { name: "Copy request JSON" }));

    expect(writeText).toHaveBeenCalledOnce();
    expect(writeText.mock.calls[0]?.[0]).toContain('"key": "user:copy-test"');
    expect(screen.getByText("Request JSON copied")).toBeInTheDocument();
  });

  it("generates a new demo key without persisting it", async () => {
    const user = userEvent.setup();
    renderCheck();
    const key = screen.getByLabelText("Logical key");
    if (!(key instanceof HTMLInputElement))
      throw new Error("Logical key field is not an input");
    const initial = key.value;

    await user.click(screen.getByRole("button", { name: "Fresh key" }));

    expect(key.value).toMatch(/^ui-demo:api-standard:/);
    expect(key).not.toHaveValue(initial);
    expect(JSON.stringify(window.localStorage)).not.toContain("ui-demo");
    expect(JSON.stringify(window.sessionStorage)).not.toContain("ui-demo");
  });

  it("has no detectable accessibility violations in its initial and allowed states", async () => {
    const rendered = renderCheck();
    expect((await axe(rendered.container)).violations).toEqual([]);

    await userEvent.click(screen.getByRole("button", { name: "Check limit" }));
    expect(
      await screen.findByRole("heading", { name: "Request allowed" }),
    ).toBeVisible();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });

  it("renders a quota decision returned by the client as a normal domain outcome", async () => {
    mockedAcquire.mockResolvedValue(
      record({
        classification: "QUOTA_DENIED",
        httpStatus: 429,
        headers: { "Retry-After": "47" },
        decision: decision({
          allowed: false,
          remaining: 0,
          grantedPermits: 0,
          retryAfterMs: 46_981,
          reason: "LIMIT_EXCEEDED",
        }),
      }),
    );
    renderCheck();

    await userEvent.click(screen.getByRole("button", { name: "Check limit" }));

    expect(
      await screen.findByRole("heading", { name: "Rate limit reached" }),
    ).toBeVisible();
    expect(screen.getByText("Retry-After")).toBeVisible();
    expect(mockedAcquire).toHaveBeenCalledOnce();
  });

  it("maps validated backend field violations to the form and focuses the first field", async () => {
    const response = record({
      classification: "CLIENT_ERROR",
      httpStatus: 400,
      problem: {
        title: "Invalid request",
        detail: "Request validation failed",
        errors: [{ field: "key", message: "Server rejected this logical key" }],
      },
    });
    delete response.decision;
    mockedAcquire.mockResolvedValue(response);
    const user = userEvent.setup();
    renderCheck();

    await user.click(screen.getByRole("button", { name: "Check limit" }));

    expect(
      await screen.findByText("Server rejected this logical key"),
    ).toBeVisible();
    expect(screen.getByLabelText("Logical key")).toHaveFocus();
    expect(mockedAcquire).toHaveBeenCalledOnce();
  });

  it("refetches a stale policy list after 404 without replaying the acquisition", async () => {
    const response = record({
      classification: "CLIENT_ERROR",
      httpStatus: 404,
      problem: {
        title: "Unknown policy",
        detail: "The requested rate-limit policy does not exist",
      },
    });
    delete response.decision;
    mockedAcquire.mockResolvedValue(response);
    const user = userEvent.setup();
    const { onUnknownPolicy } = renderCheck();

    await user.click(screen.getByRole("button", { name: "Check limit" }));

    expect(onUnknownPolicy).toHaveBeenCalledOnce();
    expect(mockedAcquire).toHaveBeenCalledOnce();
    expect(
      await screen.findByRole("heading", { name: "Request rejected" }),
    ).toBeVisible();
  });
});
