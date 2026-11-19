import { describe, expect, it } from "vitest";
import { powerWireRefusal } from "./power-wire-refusal";

const project = { nodes: [{ id: "gen" }, { id: "lcr" }] } as Parameters<typeof powerWireRefusal>[0];
const eu = { kind: "power", nodeId: "gen" };

describe("an EU wire let go on a machine says why it is refused (fork issue 102)", () => {
  it("names the rule, what to do and where to read the balance, in one line of the bar", () => {
    const text = powerWireRefusal(project, eu, "lcr")!;
    expect(text).toBe(
      "Machines take EU from the whole plan, not a wire. Set the generator's count, then check Net.",
    );
    expect(text.length).toBeLessThanOrEqual(95);
  });

  it("says nothing on empty board, a drawer, the card the wire started from, or for any other resource", () => {
    expect(powerWireRefusal(project, eu, undefined)).toBeUndefined();
    expect(powerWireRefusal(project, eu, "drawer-1")).toBeUndefined();
    expect(powerWireRefusal(project, eu, "gen")).toBeUndefined();
    expect(powerWireRefusal(project, { kind: "fluid", nodeId: "gen" }, "lcr")).toBeUndefined();
  });
});
