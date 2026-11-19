// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { OPEN_CHANGELOG_EVENT } from "@/components/chrome/chrome-events";
import { APP_VERSION } from "@/lib/version";
import { VersionChip } from "./VersionChip";

const NOTES_READ_KEY = "gtnh-factory-flow.changelog-read.v1";

beforeEach(() => window.localStorage.clear());
afterEach(cleanup);

it("shows the version and asks for the release notes when clicked", () => {
  let knocks = 0;
  const listen = () => {
    knocks += 1;
  };
  window.addEventListener(OPEN_CHANGELOG_EVENT, listen);
  render(<VersionChip />);
  fireEvent.click(screen.getByRole("button", { name: `What's new in version ${APP_VERSION}` }));
  window.removeEventListener(OPEN_CHANGELOG_EVENT, listen);
  expect(screen.getByText(`v${APP_VERSION}`)).toBeTruthy();
  expect(knocks).toBe(1);
});

it("wears a dot only while a shipped release has notes this browser has not opened", () => {
  window.localStorage.setItem(NOTES_READ_KEY, "0.0.1");
  const { container, unmount } = render(<VersionChip />);
  expect(container.querySelector("[data-unread-dot]")).not.toBeNull();
  unmount();
  window.localStorage.setItem(NOTES_READ_KEY, APP_VERSION);
  const next = render(<VersionChip />);
  expect(next.container.querySelector("[data-unread-dot]")).toBeNull();
});
