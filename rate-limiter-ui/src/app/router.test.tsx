import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ConsoleHydrationFallback } from "./router";

describe("ConsoleHydrationFallback", () => {
  it("announces initial route loading accessibly", () => {
    render(<ConsoleHydrationFallback />);

    expect(screen.getByRole("status")).toHaveTextContent(
      "Loading Operations Console",
    );
    expect(document.querySelector("img")).toHaveAttribute(
      "src",
      "/console/console-mark.svg",
    );
  });
});
