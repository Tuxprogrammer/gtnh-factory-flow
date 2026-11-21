// @vitest-environment jsdom
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ModAnnouncement } from "./ModAnnouncement";

beforeEach(() => {
  window.localStorage.clear();
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllEnvs();
});

function openAnnouncement() {
  render(<ModAnnouncement />);
  act(() => {
    vi.advanceTimersByTime(2000);
  });
}

describe("the mod announcement", () => {
  it("offers the download the build names", () => {
    vi.stubEnv("NEXT_PUBLIC_MOD_URL", "https://mods.test/planner");
    openAnnouncement();
    expect(screen.getByRole("dialog", { name: /in the game/i })).toBeTruthy();
    const link = screen.getByRole("link", { name: "Get the mod" });
    expect(link.getAttribute("href")).toBe("https://mods.test/planner");
  });

  it("keeps the rest of the dialog and drops the button when the build names no download", () => {
    vi.stubEnv("NEXT_PUBLIC_MOD_URL", "");
    openAnnouncement();
    expect(screen.getByRole("dialog", { name: /in the game/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Dismiss" })).toBeTruthy();
    expect(screen.queryByText("Get the mod")).toBeNull();
  });
});
