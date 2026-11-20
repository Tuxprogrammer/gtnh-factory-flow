"use client";

import { useRef, useState } from "react";
import { useDropdownDismiss } from "@/lib/hooks/use-dropdown-dismiss";

import { Bug, ChevronDown, Compass, Library, MessageCircle, Route } from "lucide-react";
import { openFeedback } from "@/components/flow/clean/feedback/feedback-store";
import { startTour } from "@/components/flow/clean/tour/tour-controller";
import { useIsCompactViewport } from "@/lib/compact-view";
import { leaveLibrary, openLibrary } from "@/lib/library/library-tab";
import { openWelcomeTab } from "@/lib/welcome/welcome-tab";
import { supportUrl } from "@/lib/site-url";
import { APP_VERSION } from "@/lib/version";

export const GITHUB_URL = "https://github.com/Tuxprogrammer/gtnh-factory-flow";

/**
 * A help or chat page for players, when the instance sets one (`NEXT_PUBLIC_SUPPORT_URL`). Without it the Help menu
 * and the Feedback box show no link.
 */
export const SUPPORT_URL = supportUrl();

/**
 * The bug report form with the app version pre-filled, since reporters rarely
 * think to include the build.
 */
export const BUG_REPORT_URL = `${GITHUB_URL}/issues/new?template=bug_report.yml&version=${encodeURIComponent(
  APP_VERSION,
)}`;

/** The header's "Help" dropdown: the auxiliary set of MenuLinks. */
export function HeaderLinks() {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  useDropdownDismiss(open, { refs: [root], onClose: () => setOpen(false) });
  return (
    <div ref={root} className="relative shrink-0">
      <button type="button" aria-expanded={open} aria-label="Help and links"
        onClick={() => setOpen(!open)}
        className="inline-flex h-6 items-center gap-1 rounded px-1.5 text-fg-muted hover:bg-surface-raised hover:text-fg">
        Help <ChevronDown className="h-3 w-3" />
      </button>
      {open ? <div className="absolute right-0 top-full z-[100] mt-1 w-52 rounded border border-line-strong bg-surface p-1 text-sm shadow-xl">
        <MenuLinks auxiliary onAction={() => setOpen(false)} />
      </div> : null}
    </div>
  );
}

/**
 * The app's links as labelled rows (touch has no hover tooltips), for the
 * compact menu. `auxiliary` (the header's Help dropdown) leaves out Library.
 */
export function MenuLinks({ onAction, auxiliary = false }: { onAction?: () => void; auxiliary?: boolean }) {
  // The tour needs the full layout: on a compact one it is not offered rather than doing nothing.
  const compact = useIsCompactViewport();
  return (
    <div className="flex flex-col">
      <button
        type="button"
        onClick={() => {
          leaveLibrary();
          openWelcomeTab();
          onAction?.();
        }}
        className="flex h-10 items-center gap-2.5 rounded px-2 text-left text-sm text-fg-subtle hover:bg-surface-sunken"
      >
        <span className="flex h-4 w-4 shrink-0 items-center justify-center">
          <Compass className="h-3.5 w-3.5" aria-hidden />
        </span>
        <span className="truncate">Welcome</span>
      </button>
      {!auxiliary ? <button
        type="button"
        onClick={() => {
          openLibrary();
          onAction?.();
        }}
        className="flex h-10 items-center gap-2.5 rounded px-2 text-left text-sm text-fg-subtle hover:bg-surface-sunken"
      >
        <span className="flex h-4 w-4 shrink-0 items-center justify-center">
          <Library className="h-3.5 w-3.5" aria-hidden />
        </span>
        <span className="truncate">Community</span>
      </button> : null}
      {compact ? null : <button
        type="button"
        onClick={() => {
          onAction?.();
          void startTour();
        }}
        className="flex h-10 items-center gap-2.5 rounded px-2 text-left text-sm text-fg-subtle hover:bg-surface-sunken"
      >
        <span className="flex h-4 w-4 shrink-0 items-center justify-center">
          <Route className="h-3.5 w-3.5" aria-hidden />
        </span>
        <span className="truncate">Take the tour</span>
      </button>}
      <MenuLink href={GITHUB_URL} label="Source on GitHub">
        <GithubMark />
      </MenuLink>
      {SUPPORT_URL ? <MenuLink href={SUPPORT_URL} label="Get help">
        <MessageCircle className="h-3.5 w-3.5" aria-hidden />
      </MenuLink> : null}
      <button
        type="button"
        onClick={() => {
          onAction?.();
          openFeedback();
        }}
        className="flex h-10 items-center gap-2.5 rounded px-2 text-left text-sm text-fg-subtle hover:bg-surface-sunken"
      >
        <span className="flex h-4 w-4 shrink-0 items-center justify-center">
          <Bug className="h-3.5 w-3.5" aria-hidden />
        </span>
        <span className="truncate">Send feedback</span>
      </button>
      <MenuLink href={BUG_REPORT_URL} label="Report a bug">
        <Bug className="h-3.5 w-3.5" aria-hidden />
      </MenuLink>
    </div>
  );
}

function MenuLink({
  href,
  label,
  tone,
  umamiEvent,
  children,
}: {
  href: string;
  label: string;
  tone?: "danger" | "support";
  /** Umami auto-tracks clicks on elements carrying this event name. */
  umamiEvent?: string;
  children: React.ReactNode;
}) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer noopener"
      data-umami-event={umamiEvent}
      data-umami-event-source={umamiEvent ? "menu" : undefined}
      className={[
        "flex h-10 items-center gap-2.5 rounded px-2 text-sm hover:bg-surface-sunken",
        tone === "danger" ? "text-red-300" : tone === "support" ? "text-pink-300" : "text-fg-subtle",
      ].join(" ")}
    >
      <span className="flex h-4 w-4 shrink-0 items-center justify-center">{children}</span>
      <span className="truncate">{label}</span>
    </a>
  );
}

/* The brand mark is drawn inline: lucide dropped its brand icons, and the logo
   is what people scan for rather than read, so a generic glyph would cost more
   than the markup does. */

function GithubMark() {
  return (
    <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden className="h-3.5 w-3.5">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.6 7.6 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
    </svg>
  );
}
