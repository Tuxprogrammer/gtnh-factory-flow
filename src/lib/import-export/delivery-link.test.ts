import { describe, expect, it, vi } from "vitest";
import {
  DELIVERY_NOT_FOUND,
  DELIVERY_UNREACHABLE,
  deliveryLink,
  fetchDeliveredPlan,
  readDeliveryKeyFromHash,
} from "./delivery-link";

const ID = "dah736n59pg78vns";

describe("delivery link", () => {
  it("reads the id after #d=", () => {
    expect(readDeliveryKeyFromHash(`#d=${ID}`)).toBe(ID);
    expect(readDeliveryKeyFromHash(`d=${ID}`)).toBe(ID);
    expect(deliveryLink(ID, "https://planner.test")).toBe(`https://planner.test/#d=${ID}`);
    expect(readDeliveryKeyFromHash(deliveryLink(ID, "https://planner.test").replace("https://planner.test/", ""))).toBe(
      ID,
    );
  });

  it("ignores other fragments and passes a malformed value through to be refused", () => {
    expect(readDeliveryKeyFromHash("")).toBeUndefined();
    expect(readDeliveryKeyFromHash("#p=gtnh1.abc")).toBeUndefined();
    expect(readDeliveryKeyFromHash("#somewhere")).toBeUndefined();
    expect(readDeliveryKeyFromHash("#d=")).toBe("");
  });

  it("makes no request for a proposal id or a malformed one", async () => {
    const fetchMock = vi.fn();
    for (const bad of ["pah736n59pg78vns", "dah736n59pg78vn", "dah736n59pg78vnsx", "dAh736n59pg78vns", "dah736n59pg78vn1", "", "../x"]) {
      await expect(fetchDeliveredPlan(bad, fetchMock as unknown as typeof fetch)).rejects.toThrow(DELIVERY_NOT_FOUND);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("refuses an unknown id, a proposal answer and a broken answer in the same words", async () => {
    const answer = (status: number, body: unknown) =>
      vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
    await expect(fetchDeliveredPlan(ID, answer(404, { error: "Plan not found." }))).rejects.toThrow(DELIVERY_NOT_FOUND);
    await expect(fetchDeliveredPlan(ID, answer(200, { kind: "proposal", proposal: {} }))).rejects.toThrow(
      DELIVERY_NOT_FOUND,
    );
    await expect(fetchDeliveredPlan(ID, answer(200, { kind: "delivery", plan: { nonsense: true } }))).rejects.toThrow(
      DELIVERY_NOT_FOUND,
    );
    await expect(fetchDeliveredPlan(ID, answer(503, { error: "down" }))).rejects.toThrow(DELIVERY_UNREACHABLE);
    const offline = vi.fn(async () => {
      throw new TypeError("offline");
    }) as unknown as typeof fetch;
    await expect(fetchDeliveredPlan(ID, offline)).rejects.toThrow(DELIVERY_UNREACHABLE);
  });

  it("copy has no em dashes", () => {
    expect(DELIVERY_NOT_FOUND + DELIVERY_UNREACHABLE).not.toContain("—");
  });
});
