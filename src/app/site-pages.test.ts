import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/server/community", () => ({
  isCommunityConfigured: () => false,
  getCommunityDb: () => {
    throw new Error("no database in this test");
  },
  PLAN_SUMMARY_COLUMNS: "",
}));

import robots from "./robots";
import sitemap from "./sitemap";
import { GET as llms } from "./llms.txt/route";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("the crawler pages", () => {
  it("name the configured site", async () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://planner.test");
    expect(robots().sitemap).toBe("https://planner.test/sitemap.xml");
    expect((await sitemap()).map((entry) => entry.url)).toEqual(["https://planner.test/"]);
    const text = await (await llms()).text();
    expect(text).toContain("[The planner](https://planner.test/)");
    expect(text).toContain("https://planner.test/api/version");
  });

  it("name no site when none is configured", async () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "");
    expect(robots().sitemap).toBeUndefined();
    expect(await sitemap()).toEqual([]);
    const text = await (await llms()).text();
    expect(text).toContain("[The planner](/)");
    expect(text).not.toMatch(/https?:\/\//);
  });
});
