import { afterEach, describe, expect, it, vi } from "vitest";
import { parseSiteUrl, siteHost, siteUrl, supportUrl } from "./site-url";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("the site's own address", () => {
  it("is nothing until the build names one", () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "");
    expect(siteUrl()).toBeUndefined();
    expect(siteHost()).toBeUndefined();
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "   ");
    expect(siteUrl()).toBeUndefined();
  });

  it("is the origin of an http or https address, without a path or a trailing slash", () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://planner.test/");
    expect(siteUrl()).toBe("https://planner.test");
    expect(siteHost()).toBe("planner.test");
    expect(parseSiteUrl(" http://localhost:3000/some/path?x=1 ")).toBe("http://localhost:3000");
  });

  it("ignores a value that is not a web address", () => {
    expect(parseSiteUrl("planner.test")).toBeUndefined();
    expect(parseSiteUrl("javascript:alert(1)")).toBeUndefined();
    expect(parseSiteUrl("ftp://planner.test")).toBeUndefined();
  });
});

describe("the help page", () => {
  it("is nothing until the build names one, and then the whole address", () => {
    vi.stubEnv("NEXT_PUBLIC_SUPPORT_URL", "");
    expect(supportUrl()).toBeUndefined();
    vi.stubEnv("NEXT_PUBLIC_SUPPORT_URL", "https://chat.test/room/42");
    expect(supportUrl()).toBe("https://chat.test/room/42");
    vi.stubEnv("NEXT_PUBLIC_SUPPORT_URL", "not a url");
    expect(supportUrl()).toBeUndefined();
  });
});
