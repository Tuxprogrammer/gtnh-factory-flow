import { beforeEach, describe, expect, it, vi } from "vitest";
import { createEmptyProject } from "@/examples";
import { unwrapNamedPlan } from "./named-plan";

const mocks = vi.hoisted(() => ({ importProjectAsDesign: vi.fn() }));
vi.mock("@/store/design-store", () => ({
  useDesignStore: { getState: () => mocks },
}));
import { openPlanJsonText } from "./open-plan-file";

// A library post's download is `{ name, plan }`; a saved copy of it opens like any plan file, as a new design.

beforeEach(() => vi.clearAllMocks());

describe("a { name, plan } file", () => {
  it("is the plan inside, named by the post", () => {
    const plan = { ...createEmptyProject(), name: "Inner" };
    const named = unwrapNamedPlan(JSON.stringify({ name: "Platline", plan }));
    expect(JSON.parse(named.text)).toEqual(plan);
    expect(named.name).toBe("Platline");
  });

  it("leaves a bare plan and text that is not JSON as they were", () => {
    const bare = JSON.stringify({ ...createEmptyProject(), plan: { stray: true } });
    expect(unwrapNamedPlan(bare)).toEqual({ text: bare });
    expect(unwrapNamedPlan("not json")).toEqual({ text: "not json" });
    expect(unwrapNamedPlan('{"name":"x","plan":[1]}')).toEqual({ text: '{"name":"x","plan":[1]}' });
  });

  it("opens as a new design under the post's name", async () => {
    const plan = {
      ...createEmptyProject(),
      name: "Inner",
      metadata: { ...createEmptyProject().metadata, communityPlanId: "post" },
    };
    await openPlanJsonText(JSON.stringify({ name: "Platline", plan }), "platline-download");
    expect(mocks.importProjectAsDesign).toHaveBeenCalledTimes(1);
    const [project, name] = mocks.importProjectAsDesign.mock.calls[0]!;
    expect(name).toBe("Platline");
    expect(project.id).toBe(plan.id);
    expect(project.metadata?.communityPlanId).toBeUndefined();
  });
});
