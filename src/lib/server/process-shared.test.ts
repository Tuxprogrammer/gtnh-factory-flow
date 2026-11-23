import { afterEach, describe, expect, it, vi } from "vitest";
import { processShared } from "./process-shared";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("processShared", () => {
  it("hands every bundle of a production server the same value for a key", () => {
    vi.stubEnv("NODE_ENV", "production");
    const key = `test-${Math.random()}`;
    const first = processShared(key, () => new Map<string, number>());
    first.set("warm", 1);
    // A second bundle's copy of the module asks with its own maker.
    const second = processShared(key, () => new Map<string, number>());
    expect(second).toBe(first);
    expect(second.get("warm")).toBe(1);
    expect(processShared(`${key}-other`, () => new Map())).not.toBe(first);
  });

  it("makes a fresh value each time outside production", () => {
    vi.stubEnv("NODE_ENV", "development");
    const key = `test-${Math.random()}`;
    expect(processShared(key, () => ({}))).not.toBe(processShared(key, () => ({})));
  });
});
