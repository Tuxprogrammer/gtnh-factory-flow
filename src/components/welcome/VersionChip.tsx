"use client";

import { useSyncExternalStore } from "react";
import { knock, OPEN_CHANGELOG_EVENT } from "@/components/chrome/chrome-events";
import { subscribeToNotesRead, unseenEntries } from "@/lib/whats-new";
import { APP_VERSION } from "@/lib/version";

/**
 * The version on the Welcome page. It opens the release notes, the same dialog Help > What's new opens, and wears a
 * dot while a shipped release has notes this browser has not opened.
 */
export function VersionChip() {
  const hasUnread = useSyncExternalStore(
    subscribeToNotesRead,
    () => unseenEntries().length > 0,
    () => false,
  );
  return (
    <button
      type="button"
      onClick={() => knock(OPEN_CHANGELOG_EVENT)}
      aria-label={`What's new in version ${APP_VERSION}`}
      className="relative border border-line-strong px-1.5 py-0.5 text-[11px] text-fg-muted hover:border-cyan-300 hover:text-white focus-visible:outline focus-visible:outline-1"
    >
      v{APP_VERSION}
      {hasUnread ? (
        <span
          aria-hidden
          data-unread-dot
          className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-cyan-300"
        />
      ) : null}
    </button>
  );
}
