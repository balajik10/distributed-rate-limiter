import { execFileSync } from "node:child_process";
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const snapshotPath = join(root, "openapi", "openapi.json");
const generatedPath = join(root, "src", "api", "generated.ts");
const baseUrl = process.env["OPENAPI_BASE_URL"] ?? "http://127.0.0.1:8080";

function normalized(value) {
  if (Array.isArray(value)) return value.map(normalized);
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value)
      .filter(([key]) => key !== "servers")
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => [key, normalized(child)]);
    return Object.fromEntries(entries);
  }
  return value;
}

function snapshotText(value) {
  return `${JSON.stringify(normalized(value), null, 2)}\n`;
}

async function readLive() {
  const response = await fetch(`${baseUrl.replace(/\/$/, "")}/v3/api-docs`, {
    headers: process.env["API_KEY"]
      ? { "X-API-Key": process.env["API_KEY"] }
      : {},
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok)
    throw new Error(`OpenAPI request failed with HTTP ${response.status}`);
  return snapshotText(await response.json());
}

function generate(output = generatedPath) {
  mkdirSync(dirname(output), { recursive: true });
  execFileSync(
    join(root, "node_modules", ".bin", "openapi-typescript"),
    [snapshotPath, "-o", output],
    {
      cwd: root,
      stdio: "inherit",
    },
  );
}

const command = process.argv[2];

if (command === "generate") {
  generate();
} else if (command === "refresh") {
  mkdirSync(dirname(snapshotPath), { recursive: true });
  writeFileSync(snapshotPath, await readLive(), "utf8");
  generate();
} else if (command === "check") {
  const temporaryDirectory = mkdtempSync(
    join(tmpdir(), "rate-limiter-openapi-"),
  );
  try {
    const candidate = join(temporaryDirectory, "generated.ts");
    generate(candidate);
    if (
      readFileSync(candidate, "utf8") !== readFileSync(generatedPath, "utf8")
    ) {
      throw new Error(
        "Generated API types are stale. Run npm run api:generate.",
      );
    }
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
} else if (command === "verify-live") {
  if ((await readLive()) !== readFileSync(snapshotPath, "utf8")) {
    throw new Error(
      "Live OpenAPI differs from the committed normalized snapshot.",
    );
  }
} else {
  throw new Error(`Unknown command: ${command ?? "<missing>"}`);
}
