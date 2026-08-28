import AxeBuilder from "@axe-core/playwright";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
  type TestInfo,
} from "@playwright/test";

const uiBaseUrl = process.env.UI_BASE_URL ?? "http://127.0.0.1:3001";
const appBaseUrl = process.env.BASE_URL ?? "http://127.0.0.1:8080";
const prometheusBaseUrl =
  process.env.PROMETHEUS_BASE_URL ?? "http://127.0.0.1:9090";
const grafanaBaseUrl = process.env.GRAFANA_BASE_URL ?? "http://127.0.0.1:3000";
const composeProject = process.env.E2E_COMPOSE_PROJECT_NAME ?? "";
const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));

test.describe.configure({ mode: "serial" });

function freshKey(scope: string): string {
  return `e2e:${scope}:${crypto.randomUUID()}`;
}

function assertOwnedComposeProject(): void {
  if (
    !/^rate-limiter(?:-ui)?-e2e-[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(
      composeProject,
    )
  ) {
    throw new Error(
      `Refusing Compose mutation for unowned project: ${composeProject || "<missing>"}`,
    );
  }
}

function compose(...arguments_: string[]): void {
  assertOwnedComposeProject();
  execFileSync("docker", ["compose", ...arguments_], {
    cwd: repositoryRoot,
    env: { ...process.env, COMPOSE_PROJECT_NAME: composeProject },
    stdio: "pipe",
  });
}

async function sanitizeFailureScreenshot(
  page: Page,
  testInfo: TestInfo,
): Promise<void> {
  if (testInfo.status === testInfo.expectedStatus || page.isClosed()) return;
  await page.locator("input").evaluateAll((elements) => {
    for (const element of elements) {
      if (element instanceof HTMLInputElement) element.value = "[redacted]";
    }
  });
  await page.screenshot({
    path: testInfo.outputPath("sanitized-failure.png"),
    fullPage: true,
    animations: "disabled",
  });
}

function observeBrowserErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (["error", "warning"].includes(message.type())) {
      errors.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on("pageerror", (error) => errors.push(error.message));
  return errors;
}

async function assertNoAxeViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
}

async function assertNoPageOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
}

async function openSingleCheck(
  page: Page,
  policyId: string,
  key: string,
): Promise<void> {
  await page.goto("/console/playground");
  await expect(
    page.getByRole("heading", { name: "Acquire permits" }),
  ).toBeVisible();
  await page.getByLabel("Policy", { exact: true }).selectOption(policyId);
  await page.getByLabel("Logical key", { exact: true }).fill(key);
  await page.getByLabel("Permits", { exact: true }).fill("1");
}

async function submitSingleCheck(page: Page): Promise<number> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.request().method() === "POST" &&
        candidate.url() === `${uiBaseUrl}/api/v1/rate-limits/check`,
    ),
    page.getByRole("button", { name: "Check limit" }).click(),
  ]);
  await expect(page.getByRole("button", { name: "Check limit" })).toBeEnabled();
  return response.status();
}

async function waitForStatus(
  request: APIRequestContext,
  url: string,
  expectedStatus: number,
): Promise<void> {
  await expect
    .poll(
      async () => {
        try {
          return (await request.get(url, { timeout: 2_000 })).status();
        } catch {
          return 0;
        }
      },
      { timeout: 60_000 },
    )
    .toBe(expectedStatus);
}

async function prometheusDecisionTotal(
  request: APIRequestContext,
): Promise<number> {
  const response = await request.get(
    `${prometheusBaseUrl}/api/v1/query?query=sum%28ratelimiter_decisions_total%29`,
  );
  expect(response.ok()).toBe(true);
  const body = (await response.json()) as unknown;
  if (typeof body !== "object" || body === null || !("data" in body)) return 0;
  const data = body.data;
  if (typeof data !== "object" || data === null || !("result" in data))
    return 0;
  const result = data.result;
  if (!Array.isArray(result) || result.length === 0) return 0;
  const first = result[0] as unknown;
  if (typeof first !== "object" || first === null || !("value" in first))
    return 0;
  const value = first.value;
  return Array.isArray(value) && typeof value[1] === "string"
    ? Number(value[1])
    : 0;
}

test.afterEach(async ({ page }, testInfo) =>
  sanitizeFailureScreenshot(page, testInfo),
);

test("production image serves every SPA route without intercepting API errors", async ({
  page,
  request,
}) => {
  const browserErrors = observeBrowserErrors(page);
  const routes = [
    ["/console", "Test distributed limits. See every decision."],
    ["/console/playground", "Follow one permit from browser to Redis."],
    ["/console/policies", "Trusted policy configuration, read only."],
    ["/console/system", "Know what is reachable — and what is not."],
    [
      "/console/architecture",
      "A centralized decision, without a centralized explanation gap.",
    ],
  ] as const;

  for (const [route, heading] of routes) {
    const response = await page.goto(route);
    expect(response?.status()).toBe(200);
    await expect(
      page.getByRole("heading", { level: 1, name: heading }),
    ).toBeVisible();
  }

  const missingPolicy = await request.get(
    `${uiBaseUrl}/api/v1/policies/not-a-policy`,
  );
  expect(missingPolicy.status()).toBe(404);
  expect(missingPolicy.headers()["content-type"]).toContain(
    "application/problem+json",
  );
  expect(await missingPolicy.text()).not.toContain("<!doctype html>");

  const unknownRoute = await page.goto("/console/not-a-route");
  expect(unknownRoute?.status()).toBe(200);
  await expect(
    page.getByRole("heading", { level: 1, name: "Page not found" }),
  ).toBeVisible();
  expect(browserErrors).toEqual([]);
});

test("responsive layouts, keyboard navigation, and axe checks pass", async ({
  page,
}) => {
  const browserErrors = observeBrowserErrors(page);
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 768, height: 1024 },
    { width: 390, height: 844 },
    { width: 360, height: 800 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto("/console");
    await assertNoPageOverflow(page);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  const menu = page.getByRole("button", { name: "Open navigation" });
  await menu.focus();
  await page.keyboard.press("Enter");
  const drawer = page.getByRole("dialog", { name: "Navigation" });
  await expect(drawer).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
  await expect(menu).toBeFocused();

  const auditedRoutes = [
    "/console",
    "/console/playground",
    "/console/policies",
    "/console/system",
    "/console/architecture",
  ];
  for (const route of auditedRoutes) {
    await page.goto(route);
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await assertNoAxeViolations(page);
  }

  await page.goto("/console");
  await page.getByRole("button", { name: "Use light theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  for (const route of auditedRoutes) {
    await page.goto(route);
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
    await assertNoAxeViolations(page);
  }
  expect(browserErrors).toEqual([]);
});

test("real policies load through same-origin Nginx and never reveal Docker DNS", async ({
  page,
}) => {
  const browserErrors = observeBrowserErrors(page);
  const browserRequests: string[] = [];
  page.on("request", (request) => browserRequests.push(request.url()));
  await page.goto("/console/policies");
  await expect(page.getByText("api-standard", { exact: true })).toBeVisible();
  await expect(page.getByText("login-strict", { exact: true })).toBeVisible();
  await expect(page.getByText("search-default", { exact: true })).toBeVisible();
  expect(
    browserRequests.some((url) => url === `${uiBaseUrl}/api/v1/policies`),
  ).toBe(true);
  expect(browserRequests.every((url) => !url.includes("app:8080"))).toBe(true);
  expect(browserErrors).toEqual([]);
});

test("one api-standard acquisition renders the real decision and preserved headers", async ({
  page,
}) => {
  const browserErrors = observeBrowserErrors(page);
  const logicalKey = freshKey("api-standard");
  let postCount = 0;
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      request.url().endsWith("/api/v1/rate-limits/check")
    ) {
      postCount += 1;
    }
  });
  await openSingleCheck(page, "api-standard", logicalKey);
  expect(await submitSingleCheck(page)).toBe(200);
  await expect(
    page.getByRole("heading", { name: "Request allowed" }),
  ).toBeVisible();
  await expect(page.getByText("HTTP 200", { exact: true })).toBeVisible();
  await expect(
    page.getByText("X-RateLimit-Limit", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("X-RateLimit-Source", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("X-Request-Id", { exact: true })).toBeVisible();
  await expect(page.getByText("Cache-Control", { exact: true })).toBeVisible();
  expect(postCount).toBe(1);
  expect((await page.request.get(`${uiBaseUrl}/api/v1/policies`)).ok()).toBe(
    true,
  );
  await page.getByRole("link", { name: "Overview" }).click();
  await expect(page.locator("main")).not.toContainText(logicalKey);
  expect(browserErrors).toEqual([]);
});

test("six strict-login submissions produce five allows and one upstream 429", async ({
  page,
}) => {
  const browserErrors = observeBrowserErrors(page);
  const logicalKey = freshKey("login-strict");
  let postCount = 0;
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      request.url().endsWith("/api/v1/rate-limits/check")
    ) {
      postCount += 1;
    }
  });
  await openSingleCheck(page, "login-strict", logicalKey);
  for (let attempt = 1; attempt <= 6; attempt += 1) {
    expect(await submitSingleCheck(page)).toBe(attempt <= 5 ? 200 : 429);
  }
  await expect(
    page.getByRole("heading", { name: "Rate limit reached" }),
  ).toBeVisible();
  await expect(page.getByText("HTTP 429", { exact: true })).toBeVisible();
  for (const header of [
    "Retry-After",
    "X-RateLimit-Limit",
    "X-RateLimit-Remaining",
    "X-RateLimit-Reset",
    "X-RateLimit-Source",
    "X-Request-Id",
    "Cache-Control",
  ]) {
    await expect(page.getByText(header, { exact: true })).toBeVisible();
  }
  await page.getByText("Escaped raw JSON", { exact: true }).click();
  await expect(page.locator("pre")).toContainText("LIMIT_EXCEEDED");
  await assertNoAxeViolations(page);
  expect(postCount).toBe(6);
  expect(browserErrors).toHaveLength(1);
  expect(browserErrors[0]).toMatch(
    /^error: Failed to load resource:.*status of 429/,
  );
});

test("search-default truthfully presents approximation", async ({ page }) => {
  await openSingleCheck(page, "search-default", freshKey("search-default"));
  expect(await submitSingleCheck(page)).toBe(200);
  await expect(page.getByText("Approximate", { exact: true })).toBeVisible();
  await expect(
    page.getByText(/Remaining quota may be approximate/),
  ).toBeVisible();
});

test("bounded traffic schedules exactly the requested count and Stop prevents new work", async ({
  page,
}) => {
  await page.goto("/console/playground");
  await page.getByRole("tab", { name: "Demo Traffic Lab" }).click();
  await page.getByLabel("Policy", { exact: true }).selectOption("api-standard");
  await page.getByLabel("Total requests", { exact: true }).fill("8");
  await page.getByLabel("Target rate", { exact: true }).fill("10");
  await page.getByLabel("Concurrency", { exact: true }).fill("2");
  await page.getByRole("button", { name: "Fresh key" }).last().click();
  await page.getByRole("button", { name: "Start bounded run" }).click();

  const metric = (name: string) =>
    page
      .locator(".run-metrics > div")
      .filter({ hasText: name })
      .locator("strong");
  await expect(metric("Scheduled")).toHaveText("8", { timeout: 10_000 });
  await expect(metric("Responses")).toHaveText("8", { timeout: 10_000 });
  await expect(page.getByText("Complete", { exact: true })).toBeVisible();

  await page.getByLabel("Total requests", { exact: true }).fill("20");
  await page.getByLabel("Target rate", { exact: true }).fill("5");
  await page.getByRole("button", { name: "Fresh key" }).last().click();
  await page.getByRole("button", { name: "Start bounded run" }).click();
  await expect(page.getByText("Running", { exact: true })).toBeVisible();
  await expect(metric("Scheduled")).not.toHaveText("0", { timeout: 3_000 });
  const stopButton = page.getByRole("button", { name: "Stop run" });
  await stopButton.click();
  await expect(stopButton).toBeHidden();
  await expect(
    page.locator(".status-badge").filter({ hasText: /Stopping|Stopped/ }),
  ).toBeVisible();
  const scheduledAfterStop = await metric("Scheduled").textContent();
  expect(Number(scheduledAfterStop)).toBeGreaterThan(0);
  expect(Number(scheduledAfterStop)).toBeLessThan(20);
  await page.waitForTimeout(1_200);
  await expect(metric("Scheduled")).toHaveText(scheduledAfterStop ?? "");
});

test("task-owned Redis interruption renders fail-open, fail-closed, and recovery", async ({
  page,
  request,
}) => {
  assertOwnedComposeProject();
  compose("stop", "redis");
  try {
    await waitForStatus(
      request,
      `${appBaseUrl}/actuator/health/readiness`,
      503,
    );

    await openSingleCheck(page, "api-standard", freshKey("fail-open"));
    expect(await submitSingleCheck(page)).toBe(200);
    await expect(
      page.getByRole("heading", { name: "Allowed — degraded fail-open" }),
    ).toBeVisible();
    await expect(page.getByText("FAIL OPEN", { exact: true })).toBeVisible();
    await assertNoAxeViolations(page);

    await page
      .getByLabel("Policy", { exact: true })
      .selectOption("login-strict");
    await page
      .getByLabel("Logical key", { exact: true })
      .fill(freshKey("fail-closed"));
    expect(await submitSingleCheck(page)).toBe(503);
    await expect(
      page.getByRole("heading", {
        name: "Backend unavailable — request blocked",
      }),
    ).toBeVisible();
    await expect(page.getByText("FAIL CLOSED", { exact: true })).toBeVisible();
  } finally {
    compose("start", "redis");
    await waitForStatus(
      request,
      `${appBaseUrl}/actuator/health/readiness`,
      200,
    );
  }

  await page
    .getByLabel("Logical key", { exact: true })
    .fill(freshKey("recovered"));
  expect(await submitSingleCheck(page)).toBe(200);
  await expect(
    page.getByRole("heading", { name: "Request allowed" }),
  ).toBeVisible();
});

test("task-owned app pause produces a sanitized unknown outcome with zero retry", async ({
  page,
  request,
}) => {
  assertOwnedComposeProject();
  await openSingleCheck(page, "api-standard", freshKey("ambiguous"));
  let postCount = 0;
  page.on("request", (browserRequest) => {
    if (
      browserRequest.method() === "POST" &&
      browserRequest.url().endsWith("/api/v1/rate-limits/check")
    ) {
      postCount += 1;
    }
  });
  compose("pause", "app");
  try {
    const [gatewayResponse] = await Promise.all([
      page.waitForResponse(
        (candidate) =>
          candidate.request().method() === "POST" &&
          candidate.url() === `${uiBaseUrl}/api/v1/rate-limits/check`,
      ),
      page.getByRole("button", { name: "Check limit" }).click(),
    ]);
    expect([502, 504]).toContain(gatewayResponse.status());
    expect(gatewayResponse.headers()["content-type"]).toContain("text/html");
    await expect(
      page.getByRole("heading", {
        name: "Outcome unknown — do not retry blindly",
      }),
    ).toBeVisible({ timeout: 12_000 });
    await expect(page.locator("main")).toContainText(
      "may still have executed and consumed permits",
    );
    await expect(page.locator("main")).not.toContainText("<html");
    await expect(
      page.getByText(`HTTP ${String(gatewayResponse.status())}`, {
        exact: true,
      }),
    ).toBeVisible();
    await page.waitForTimeout(1_000);
    expect(postCount).toBe(1);
  } finally {
    compose("unpause", "app");
    await waitForStatus(
      request,
      `${appBaseUrl}/actuator/health/readiness`,
      200,
    );
  }
});

test("external links are browser-visible and Prometheus observes UI traffic", async ({
  page,
  request,
}) => {
  await page.goto("/console/system");
  await expect(page.getByRole("link", { name: /Swagger/ })).toHaveAttribute(
    "href",
    `${appBaseUrl}/swagger-ui/index.html`,
  );
  await expect(page.getByRole("link", { name: /Prometheus/ })).toHaveAttribute(
    "href",
    `${prometheusBaseUrl}/`,
  );
  await expect(page.getByRole("link", { name: /Grafana/ })).toHaveAttribute(
    "href",
    `${grafanaBaseUrl}/d/distributed-rate-limiter/distributed-rate-limiter`,
  );

  const baseline = await prometheusDecisionTotal(request);
  await openSingleCheck(page, "api-standard", freshKey("prometheus"));
  expect(await submitSingleCheck(page)).toBe(200);
  await expect
    .poll(() => prometheusDecisionTotal(request), {
      timeout: 30_000,
      intervals: [1_000, 2_000],
    })
    .toBeGreaterThan(baseline);

  const grafanaHealth = await request.get(`${grafanaBaseUrl}/api/health`);
  expect(grafanaHealth.ok()).toBe(true);
  const datasource = await request.get(
    `${grafanaBaseUrl}/api/datasources/uid/prometheus`,
  );
  expect(datasource.ok()).toBe(true);
  const dashboard = await request.get(
    `${grafanaBaseUrl}/api/dashboards/uid/distributed-rate-limiter`,
  );
  expect(dashboard.ok()).toBe(true);
});
