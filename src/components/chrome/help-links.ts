import { BUG_REPORT_URL, GITHUB_URL, SUPPORT_URL } from "@/components/HeaderLinks";
import { openWelcomeTab } from "@/lib/welcome/welcome-tab";
import { leaveLibrary } from "@/lib/library/library-tab";
import { APP_VERSION } from "@/lib/version";
import { modUrl } from "@/lib/site-url";

/** The help menu's outside doors, in one place. */
export const HelpLinks = {
  github: GITHUB_URL,
  /** The instance's help or chat page, or undefined when it sets none. */
  support: SUPPORT_URL,
  bugReport: BUG_REPORT_URL,
  /** The in-game planner's download page, or undefined when the build names none. */
  get mod(): string | undefined {
    return modUrl();
  },
  version: `v${APP_VERSION}`,
  open(url: string) {
    window.open(url, "_blank", "noopener,noreferrer");
  },
  openWelcome() {
    leaveLibrary();
    openWelcomeTab();
  },
};
