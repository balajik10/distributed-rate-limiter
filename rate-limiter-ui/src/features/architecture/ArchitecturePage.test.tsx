import { screen } from "@testing-library/react";
import { axe } from "jest-axe";
import { describe, expect, it } from "vitest";

import { renderWithProviders } from "../../test/render";
import { ArchitecturePage } from "./ArchitecturePage";

describe("ArchitecturePage", () => {
  it("pairs the request-flow visual with equivalent interview-ready text", async () => {
    const rendered = renderWithProviders(<ArchitecturePage />);

    expect(
      screen.getByRole("img", {
        name: /Client calls Spring API.*atomic Redis Lua script/i,
      }),
    ).toBeVisible();
    expect(
      screen.getByText("A client calls the Spring HTTP API."),
    ).toBeVisible();
    expect(
      screen.getByRole("heading", {
        name: "Cache timing discrepancy ≤ M × (B − 1)",
      }),
    ).toBeVisible();
    expect(
      screen.getByText(/Fail-open outage traffic is outside this bound/i),
    ).toBeVisible();
    expect(
      screen.getByText(/not an exact “L in every rolling window” rule/i),
    ).toBeVisible();
    expect((await axe(rendered.container)).violations).toEqual([]);
  });
});
