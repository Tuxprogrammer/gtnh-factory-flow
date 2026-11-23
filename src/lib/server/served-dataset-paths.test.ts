import { describe, expect, it, vi } from "vitest";

// The server's manifest lists only "new"; a post row saved on "old" is read from "new".
vi.mock("./dataset-query", () => ({
  loadManifest: async () => ({ schemaVersion: 1, versions: [{ id: "new" }, { id: "daily" }], latestStableVersion: "new" }),
  getDefaultDatasetVersionId: async () => "new",
}));

const { onServerDataset } = await import("./served-dataset-paths");

const path = (version: string, file: string) => `/datasets/gtnh/${version}/textures/rendered/${file}`;

describe("stored rows the server draws from", () => {
  it("moves the face and stats of a row saved on an unserved version onto the default version", async () => {
    const row = {
      name: "Steel",
      icon: { kind: "item", resourceId: "steel", iconPath: path("old", "steel-1.png") },
      outputs: [{ kind: "item", resourceId: "steel", iconPath: path("old", "steel-1.png") }],
      needs: [{ kind: "fluid", resourceId: "oxygen", iconPath: path("daily", "oxygen-2.png") }],
    };
    const moved = await onServerDataset(row);
    expect(moved.icon.iconPath).toBe(path("new", "steel-1.png"));
    expect(moved.outputs[0]!.iconPath).toBe(path("new", "steel-1.png"));
    // A version the server still serves is left alone.
    expect(moved.needs).toBe(row.needs);
  });

  it("returns a missing row as it is", async () => {
    expect(await onServerDataset(undefined)).toBeUndefined();
  });
});
