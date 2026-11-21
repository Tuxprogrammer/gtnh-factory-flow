import { loadBiodieselDemoProject } from "@/examples";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const importProjectAsDesign = vi.fn(async () => {});
const setProject = vi.fn();
const applyPlanView = vi.fn();

vi.mock("@/store/design-store", () => ({
  useDesignStore: { getState: () => ({ importProjectAsDesign, setProject }) },
}));
vi.mock("@/lib/plan-view", () => ({ applyPlanView }));
vi.mock("@/lib/import-export/open-plan-file", () => ({
  looksLikePlanJson: () => false,
  openPlanJsonText: vi.fn(),
}));

const ID = "dah736n59pg78vns";
const PLAN = { ...loadBiodieselDemoProject(), name: "Delivered titanium" };

type Win = {
  location: { hash: string; pathname: string; search: string };
  history: { replaceState: ReturnType<typeof vi.fn> };
  alert: ReturnType<typeof vi.fn>;
};

function stubAddress(hash: string): Win {
  const win: Win = {
    location: { hash, pathname: "/", search: "" },
    history: { replaceState: vi.fn() },
    alert: vi.fn(),
  };
  vi.stubGlobal("window", win);
  return win;
}

async function arrive(hash: string, fetchMock: ReturnType<typeof vi.fn>) {
  vi.resetModules();
  const win = stubAddress(hash);
  vi.stubGlobal("fetch", fetchMock);
  const module = await import("./open-plan-code");
  await module.openPlanCodeFromAddress();
  return win;
}

beforeEach(() => {
  importProjectAsDesign.mockClear();
  setProject.mockClear();
  applyPlanView.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("arriving at #d=<id>", () => {
  it("opens the delivery as a new design and leaves the open one alone", async () => {
    const fetchMock = vi.fn(
      async (_url: string) => new Response(JSON.stringify({ id: ID, kind: "delivery", name: PLAN.name, plan: PLAN }), { status: 200 }),
    );
    const win = await arrive(`#d=${ID}`, fetchMock);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]![0])).toBe(`/api/deliveries/${ID}`);
    expect(importProjectAsDesign).toHaveBeenCalledTimes(1);
    const [project, name] = importProjectAsDesign.mock.calls[0] as unknown as [{ name: string }, string];
    expect(project.name).toBe("Delivered titanium");
    expect(name).toBe("Delivered titanium");
    expect(setProject).not.toHaveBeenCalled();
    expect(win.alert).not.toHaveBeenCalled();
    expect(win.history.replaceState).toHaveBeenCalledWith(null, "", "/");
  });

  it("says so plainly for an unknown or expired id, and clears the address", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ error: "Plan not found." }), { status: 404 }));
    const win = await arrive(`#d=${ID}`, fetchMock);
    expect(importProjectAsDesign).not.toHaveBeenCalled();
    expect(win.alert).toHaveBeenCalledTimes(1);
    expect(String(win.alert.mock.calls[0]![0])).toMatch(/not valid, or it has expired/);
    expect(win.history.replaceState).toHaveBeenCalledWith(null, "", "/");
  });

  it("refuses a proposal id without a request", async () => {
    const fetchMock = vi.fn();
    const win = await arrive("#d=pah736n59pg78vns", fetchMock);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(importProjectAsDesign).not.toHaveBeenCalled();
    expect(win.alert).toHaveBeenCalledTimes(1);
    expect(win.history.replaceState).toHaveBeenCalledWith(null, "", "/");
  });

  it("does nothing for an address with neither a code nor a delivery id", async () => {
    const fetchMock = vi.fn();
    const win = await arrive("#somewhere", fetchMock);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(win.alert).not.toHaveBeenCalled();
    expect(win.history.replaceState).not.toHaveBeenCalled();
  });
});
