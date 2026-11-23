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

vi.mock("@/lib/agent-api/deliveries", () => ({
  DELIVERY_ID_PATTERN: /^[dp][a-z2-9]{15}$/,
  readDelivery: async (id: string) => ({
    id,
    kind: "delivery",
    name: "Steel",
    createdAt: "2026-01-01T00:00:00Z",
    expiresAt: "2026-02-01T00:00:00Z",
    payload: { nodes: [{ id: "n1", outputs: [{ iconPath: path("old", "steel-1.png") }] }] },
  }),
}));

vi.mock("@/lib/server/community", () => ({
  isCommunityConfigured: () => true,
  clientAddress: () => "test",
  saltedKey: (value: string) => value,
}));

describe("a stored delivery read back", () => {
  it("answers with its paths on the version the server serves", async () => {
    const { GET } = await import("@/app/api/deliveries/[id]/route");
    const response = await GET(new Request("http://localhost/api/deliveries/dabcdefghjkmnpqr"), {
      params: Promise.resolve({ id: "dabcdefghjkmnpqr" }),
    });
    expect(response.status).toBe(200);
    const body = (await response.json()) as { plan: { nodes: Array<{ outputs: Array<{ iconPath: string }> }> } };
    expect(body.plan.nodes[0]!.outputs[0]!.iconPath).toBe(path("new", "steel-1.png"));
  });
});
