// A card stored at 0 machines reads as the same card stored at 1: the solver chooses the count, so the stored number
// is never a control. The game's export writes the count it solved (0 for a card it did not need) into a Solve plan.
//
//   BASE_URL=http://localhost:3000 node tools/browser-tests/zero-count-card.mjs
//
// Against a running dev server (`npm run dev:local`). Builds a synthetic plan (raws -> A -> B -> a drawer wanting at
// least 6 p/s), with A stored at 0 and at 1, in a Solve plan and in a plan-mode plan, opens each as a new tab, and
// compares card A's and card B's faces (rate, EU/t, port flows, machines) and the overview's text across the four.
// Exits 1 when any differs from A stored at 1 in a plan-mode plan, or when card A does not read the solved numbers.
import { chromium } from "playwright-core";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const base = process.env.BASE_URL ?? "http://localhost:3000";
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "zero-count-"));

const recipe = (id, name, inputs, outputs) => ({
  id,
  name,
  machineType: "Mock Machine",
  minimumTier: "LV",
  durationTicks: 20,
  eut: 30,
  inputs: inputs.map(([item, amount]) => ({ kind: "item", id: item, amount, displayName: item })),
  outputs: outputs.map(([item, amount]) => ({ kind: "item", id: item, amount, displayName: item })),
});
const card = (id, recipeId, x, machineCount) => ({
  id,
  recipeId,
  machineCount,
  parallel: 1,
  overclockTier: "LV",
  enabled: true,
  position: { x, y: 200 },
});
const drawer = (id, item, x, y, extra = {}) => ({
  id,
  kind: "item",
  resourceId: item,
  displayName: item,
  position: { x, y },
  ...extra,
});
const wire = (id, source, target, item, { fromDrawer = false, toDrawer = false } = {}) => ({
  id,
  source,
  target,
  resourceKind: "item",
  resourceId: item,
  sourceHandle: fromDrawer ? undefined : `output:item:${item}`,
  targetHandle: toDrawer ? undefined : `input:item:${item}`,
});
const plans = [];
for (const mode of ["plan", "solve"]) {
  for (const count of [1, 0]) {
    const file = path.join(dir, `${mode}-${count}.json`);
    fs.writeFileSync(
      file,
      JSON.stringify({
        schemaVersion: 1,
        id: `zero-count-${mode}-${count}`,
        name: `Zero count ${mode} ${count}`,
        ...(mode === "solve" ? { solveMode: true } : { planMode: true }),
        recipes: [
          recipe("ra", "Make Intermediate", [["raw", 2]], [["i", 1]]),
          recipe(
            "rb",
            "Make Product",
            [["i", 1]],
            [
              ["p", 3],
              ["q", 1],
            ],
          ),
        ],
        nodes: [card("a", "ra", 300, count), card("b", "rb", 800, 1)],
        storages: [
          drawer("raws", "raw", 0, 220),
          drawer("pd", "p", 1300, 220, { targetMode: "at-least", targetPerSecond: 6 }),
          drawer("qd", "q", 1300, 420),
        ],
        edges: [
          wire("raws-a", "raws", "a", "raw", { fromDrawer: true }),
          wire("a-b", "a", "b", "i"),
          wire("b-pd", "b", "pd", "p", { toDrawer: true }),
          wire("b-qd", "b", "qd", "q", { toDrawer: true }),
        ],
        fuelProfiles: [],
      }),
    );
    plans.push({ name: `${mode} plan, A stored at ${count}`, file });
  }
}

const browser = await chromium.launch({ headless: true });
const readings = [];
for (const plan of plans) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript(() => {
    if (document.visibilityState !== "visible")
      window.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);
  });
  const p = await ctx.newPage();
  p.setDefaultTimeout(60000);
  const dismiss = async () => {
    for (const n of ["Dismiss", "Not now"])
      await p
        .getByRole("button", { name: n, exact: true })
        .first()
        .click({ timeout: 800 })
        .catch(() => {});
  };
  await p.goto(base + "/", { waitUntil: "load" });
  await p.waitForTimeout(5000);
  await dismiss();
  await p.setInputFiles("input[data-plan-file-input]", plan.file);
  await p.waitForSelector('.react-flow__node[data-id="a"]', { timeout: 120000 });
  await p.waitForTimeout(4000);
  await dismiss();
  const overview = await p.evaluate(
    () => document.querySelector(".rail-root")?.innerText.replace(/\s+/g, " ").trim() ?? "",
  );
  const faces = {};
  for (const id of ["a", "b"]) {
    // Close enough that the card draws its full face, not the glance.
    await p.evaluate((id) => window.__gtnhFlow?.fitView({ nodes: [{ id }], maxZoom: 1.6 }), id);
    await p.waitForTimeout(3000);
    faces[id] = await p.evaluate(
      (id) =>
        document
          .querySelector(`.react-flow__node[data-id="${id}"]`)
          ?.innerText.replace(/\s+/g, " ")
          .trim() ?? "",
      id,
    );
  }
  readings.push({ ...plan, overview, faces });
  await ctx.close();
}
await browser.close();
fs.rmSync(dir, { recursive: true, force: true });

const [baseline] = readings;
let failed = 0;
for (const r of readings) {
  const same =
    r.overview === baseline.overview &&
    r.faces.a === baseline.faces.a &&
    r.faces.b === baseline.faces.b;
  // A makes 2 i/s for B's 2 crafts a second: 4 raw/s in, 2 i/s out, 60 EU/t on 2 machines; the overview counts 4
  // machines (A's 2 and B's 2) and 120 EU/t.
  const solved =
    /\b4 raw\b/.test(r.faces.a) &&
    /\b60 Power\b/.test(r.faces.a) &&
    /\b2 i\b/.test(r.faces.a) &&
    /MACHINES 4 120 EU\/t/.test(r.overview);
  if (!same || !solved) failed += 1;
  console.log(`${same && solved ? "pass" : "FAIL"}  ${r.name}`);
  console.log(`      card A: ${r.faces.a}`);
  console.log(`      card B: ${r.faces.b}`);
  console.log(`      overview: ${r.overview.slice(0, 200)}`);
}
console.log(`\n${readings.length} plans, ${failed} failed`);
process.exit(failed ? 1 : 0);
