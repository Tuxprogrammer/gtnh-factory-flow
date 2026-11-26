// Every box the website opens, at small windows, with oversized content: is the box on screen, can all of it be
// reached (what does not fit scrolls inside the box), and are its action keys on screen without scrolling?
//
//   node tools/browser-tests/box-bounds-plans.mjs        (synthetic plans from the installed dataset, into PLANS_DIR)
//   BASE_URL=http://localhost:3000 node tools/browser-tests/box-bounds.mjs [WxH ...] [--only name,name] [--shots dir]
//
// Against a running dev server (`npm run dev:local`). Add a scenario to SCENARIOS for every new box.
//
// Prints one row per box per window and exits 1 when any measured box fails. A box that could not be opened is
// listed as "not opened" with the reason (exit 0 for those: a phone has no tour, for example).
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";

const base = process.env.BASE_URL ?? "http://localhost:3000";
const args = process.argv.slice(2);
const flag = (name) => {
  const at = args.indexOf(name);
  if (at < 0) return undefined;
  const value = args[at + 1];
  args.splice(at, 2);
  return value;
};
const only = flag("--only")?.split(",");
const shots = flag("--shots");
const viewports = (args.length ? args : ["1000x600", "1280x560", "390x700"]).map((s) => s.split("x").map(Number));
const plans = process.env.PLANS_DIR ?? "scratch/probes/box-bounds";
if (!fs.existsSync(path.join(plans, "shared.json"))) throw new Error(`run box-bounds-plans.mjs first (${plans})`);
const libPlans = fs.readdirSync(plans).filter((n) => /^lib-\d+\.json$/.test(n)).sort().slice(0, 12);

// ---- In-page measuring --------------------------------------------------------------------------------------------

const PAGE_HELPERS = () => {
  const TOOLTIP = ".tp, .mc-tip, [role=tooltip], .tooltip-root, [data-tooltip]";
  const OVERLAY = "[role=dialog],[role=menu],[role=listbox],[role=alertdialog]";
  const visible = (e) => {
    const r = e.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const cs = getComputedStyle(e);
    return cs.visibility !== "hidden" && cs.display !== "none" && cs.opacity !== "0";
  };
  const candidates = () => {
    const out = new Set();
    for (const e of document.body.querySelectorAll("*")) {
      if (e.closest(".react-flow__node")) continue;
      if (e.matches(OVERLAY) || getComputedStyle(e).position === "fixed") out.add(e);
    }
    return out;
  };
  window.__bxSnap = () => {
    window.__bxOld = candidates();
  };
  // The new boxes since the snapshot: outermost only; a full-window backdrop stands for the box inside it.
  window.__bxRoots = (opts) => {
    const VW = window.visualViewport?.width ?? innerWidth;
    const VH = window.visualViewport?.height ?? innerHeight;
    let found = [...candidates()].filter((e) => !window.__bxOld.has(e) && visible(e));
    if (opts?.select) found = [...document.querySelectorAll(opts.select)].filter(visible);
    else if (!opts?.tooltips) found = found.filter((e) => !e.matches(TOOLTIP) && !e.closest(TOOLTIP));
    else found = found.filter((e) => e.matches(TOOLTIP) || e.closest(TOOLTIP));
    found = found.filter((e) => !found.some((o) => o !== e && o.contains(e)));
    const fullWindow = (e) => {
      const r = e.getBoundingClientRect();
      return r.left <= 1 && r.top <= 1 && r.right >= VW - 1 && r.bottom >= VH - 1;
    };
    return found
      .map((e) => {
        let box = e;
        for (let i = 0; i < 4 && fullWindow(box) && !opts?.page; i += 1) {
          const kids = [...box.children].filter(visible);
          if (!kids.length) break;
          kids.sort((a, b) => {
            const ra = a.getBoundingClientRect();
            const rb = b.getBoundingClientRect();
            return rb.width * rb.height - ra.width * ra.height;
          });
          box = kids[0];
        }
        return box;
      })
      .filter((e, i, all) => all.indexOf(e) === i);
  };
  window.__bxMeasure = (root) => {
    const VW = window.visualViewport?.width ?? innerWidth;
    const VH = window.visualViewport?.height ?? innerHeight;
    const T = 1.5;
    const rr = root.getBoundingClientRect();
    const fmt = (r) => `${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.width)}x${Math.round(r.height)}`;
    const inBox = rr.left >= -T && rr.top >= -T && rr.right <= VW + T && rr.bottom <= VH + T;
    const scrolls = (e) => {
      const cs = getComputedStyle(e);
      return (
        (/(auto|scroll)/.test(cs.overflowY) && e.scrollHeight > e.clientHeight) ||
        (/(auto|scroll)/.test(cs.overflowX) && e.scrollWidth > e.clientWidth)
      );
    };
    const clips = (e) => {
      const cs = getComputedStyle(e);
      return cs.overflowX !== "visible" || cs.overflowY !== "visible";
    };
    const cut = (a, r) => ({ l: Math.max(a.l, r.left), t: Math.max(a.t, r.top), r: Math.min(a.r, r.right), b: Math.min(a.b, r.bottom) });
    const within = (r, c, ellipsis) =>
      r.top >= c.t - 2 && r.bottom <= c.b + 2 && (ellipsis || (r.left >= c.l - 2 && r.right <= c.r + 2));
    const ACTION = /^(save|cancel|delete|close|done|apply|import|open|copy|publish|share|send|ok|create|paste|download|remove|leave|sign in|update|invite|post|keep|discard|reset|confirm|take|back)\b/i;
    let unreachable = [];
    let hiddenActions = [];
    let hasScroller = scrolls(root);
    for (const el of root.querySelectorAll("*")) {
      if (!visible(el)) continue;
      // Item art is drawn at twice its box and clipped to it on purpose; the box it sits in is what is measured.
      if (el.matches("img,svg,svg *,canvas,picture")) continue;
      const control = el.matches("button,input,textarea,select,a,[role=button],[role=menuitem],[role=option],[role=tab]");
      if (el.children.length && !control) continue;
      if (control && el.parentElement?.closest("button,[role=button],[role=menuitem],[role=option]")) continue;
      const r = el.getBoundingClientRect();
      const ellipsis = getComputedStyle(el).textOverflow === "ellipsis";
      let view = { l: 0, t: 0, r: VW, b: VH };
      let scroller = null;
      for (let a = el.parentElement; a; a = a.parentElement) {
        if (!scroller && scrolls(a)) scroller = a;
        if (clips(a)) view = cut(view, a.getBoundingClientRect());
        if (a === root) break;
      }
      if (scroller) hasScroller = true;
      // Reachable: on screen as it stands, or inside a scroller whose own box is on screen (scroll it to reach).
      if (!within(r, view, ellipsis)) {
        let ok = false;
        if (scroller) {
          let sv = { l: 0, t: 0, r: VW, b: VH };
          for (let a = scroller.parentElement; a && a !== root.parentElement; a = a.parentElement) if (clips(a)) sv = cut(sv, a.getBoundingClientRect());
          ok = within(scroller.getBoundingClientRect(), sv, false);
        }
        const label = (el.textContent || el.getAttribute("aria-label") || "").trim();
        if (!ok && el.matches("button,[role=button]") && ACTION.test(label)) hiddenActions.push(`${label.slice(0, 20)}@${fmt(r)}`);
        else if (!ok) unreachable.push(`${el.tagName.toLowerCase()}.${String(el.className).split(" ")[0]}:${label.slice(0, 24)}@${fmt(r)}`);
      }
    }
    return {
      rect: fmt(rr),
      cls: `${root.tagName.toLowerCase()}.${String(root.className).split(" ").slice(0, 2).join(".")}`,
      inBox,
      unreachable,
      hiddenActions,
      hasScroller,
    };
  };
};

// ---- Scenarios ----------------------------------------------------------------------------------------------------

const click = (p, sel, opts) => p.locator(sel).first().click({ timeout: 4000, ...opts });
const pickRow = async (p, label) => p.locator('[role=menuitem]', { hasText: label }).first().click({ timeout: 4000 });
const card = (p) => p.locator(".react-flow__node").filter({ has: p.locator("[aria-label='Card actions']") }).first();
// The shared card's head in the middle of the board, so its keys are on screen and clear of the columns.
const centerBoard = async (p) =>
  p.evaluate(() => {
    const flow = window.__gtnhFlow;
    const node = flow?.getNode?.("lcr");
    if (!node) return;
    const pane = document.querySelector(".react-flow").getBoundingClientRect();
    const w = node.measured?.width ?? 380;
    flow.setCenter(node.position.x + w / 2, node.position.y + Math.min(pane.height, innerHeight) / 2 - 80, { zoom: 1, duration: 0 });
  });

// A point on the board with no card, key or column over it: scanned on a grid with elementFromPoint.
const emptyBoardSpot = async (p) => {
  // Zoomed out, so a phone's board shows floor round the tall card.
  await p.evaluate(() => {
    const flow = window.__gtnhFlow;
    const node = flow?.getNode?.("lcr");
    if (node && innerWidth < 600) flow.setCenter(node.position.x, node.position.y, { zoom: 0.25, duration: 0 });
  });
  await p.waitForTimeout(400);
  return p.evaluate(() => {
    const pane = document.querySelector(".react-flow").getBoundingClientRect();
    const vw = innerWidth;
    const vh = innerHeight;
    for (let y = Math.max(pane.top, 0) + 120; y < Math.min(pane.bottom, vh) - 60; y += 20) {
      for (let x = Math.max(pane.left, 0) + 40; x < Math.min(pane.right, vw) - 40; x += 20) {
        const hit = document.elementFromPoint(x, y);
        if (hit?.classList.contains("react-flow__pane")) return { x, y };
      }
    }
    throw new Error("no empty board spot");
  });
};

// name, open(p) -> options for __bxRoots. `needs` lists conditions: "desktop" skips compact windows.
const SCENARIOS = [
  { name: "tab plus menu", open: (p) => click(p, ".mt-plus") },
  {
    name: "tab menu",
    open: async (p) => {
      const tab = p.locator(".mt-strip [role=tab][aria-selected=true]").first();
      await tab.scrollIntoViewIfNeeded();
      await p.waitForTimeout(500);
      await tab.click({ button: "right", timeout: 4000 });
    },
  },
  {
    name: "tab delete question",
    open: async (p) => {
      const tab = p.locator(".mt-strip [role=tab][aria-selected=true]").first();
      await tab.scrollIntoViewIfNeeded();
      await p.waitForTimeout(500);
      await tab.click({ button: "right", timeout: 4000 });
      await pickRow(p, "Delete plan");
    },
  },
  { name: "share menu", open: (p) => click(p, "[aria-label=Share]") },
  {
    name: "screenshot editor",
    open: async (p) => {
      await click(p, "[aria-label=Share]");
      await pickRow(p, "Screenshot");
    },
  },
  { name: "sign in", open: (p) => click(p, "[aria-label='Sign in']") },
  { name: "feedback", open: (p) => click(p, "[aria-label=Feedback]") },
  { name: "settings", open: (p) => click(p, "[aria-label=Settings]") },
  { name: "help menu", open: (p) => click(p, "[aria-label=Help]") },
  {
    name: "controls list",
    open: async (p) => {
      await click(p, "[aria-label=Help]");
      await pickRow(p, "Controls and shortcuts");
    },
  },
  {
    name: "changelog",
    open: async (p) => {
      await click(p, "[aria-label=Help]");
      await pickRow(p, "What's new");
    },
  },
  { name: "dev menu", open: (p) => click(p, "[aria-label=Help]", { modifiers: ["Shift"] }) },
  {
    name: "paste plan",
    open: async (p) => {
      await click(p, ".mt-plus");
      await pickRow(p, "Paste");
    },
  },
  { name: "non-recipe picker", open: (p) => click(p, "[aria-label='Non-recipe machines']") },
  { name: "annotations menu", open: (p) => click(p, "[aria-label=Annotations]") },
  { name: "items show and sort", open: (p) => click(p, "[aria-label='Show and sort']") },
  {
    name: "board context menu",
    open: async (p) => {
      const at = await emptyBoardSpot(p);
      await p.mouse.click(at.x, at.y, { button: "right" });
    },
  },
  { name: "card actions", open: async (p) => card(p).locator("[aria-label='Card actions']").click({ timeout: 4000 }) },
  { name: "card settings", open: async (p) => card(p).locator("[aria-label='Machine settings']").click({ timeout: 4000 }) },
  { name: "card right click", open: async (p) => card(p).locator(".cc-name").click({ button: "right", timeout: 4000 }) },
  { name: "card tier chip", open: async (p) => card(p).locator("[aria-label='Tier HV']").first().click({ timeout: 4000 }) },
  {
    name: "recipe search",
    open: async (p) => card(p).locator(".react-flow__handle").first().click({ timeout: 4000 }),
    opts: { select: ".nrw" },
  },
  {
    name: "recipe browse menu",
    open: async (p) => {
      await card(p).locator(".react-flow__handle").first().click({ timeout: 4000 });
      await p.waitForTimeout(3000);
      await p.locator(".nrw .nrw-card").first().click({ button: "right", modifiers: ["Shift"], timeout: 6000 });
    },
  },
  { name: "overview machine menu", open: (p) => click(p, ".rail-sec ~ * .rail-name, [data-rail-machine]", { button: "right" }) },
  {
    name: "delete question",
    open: async (p) => {
      const at = await emptyBoardSpot(p);
      await p.mouse.click(at.x, at.y);
      await p.keyboard.press("Control+a");
      await p.keyboard.press("Delete");
    },
  },
  {
    name: "library",
    open: (p) => click(p, "[aria-label=Plans]"),
    opts: { select: ".ml", page: true },
  },
  {
    name: "library tile menu",
    open: async (p) => {
      await click(p, "[aria-label=Plans]");
      await p.locator(".ml-tile").nth(2).click({ button: "right", timeout: 6000 });
    },
  },
  {
    name: "tooltip long name",
    open: async (p) => {
      await p.locator(".rail-sec ~ * [class*=rail-name], [class*=rail-name]").nth(2).hover({ timeout: 4000 });
      await p.waitForTimeout(900);
    },
    opts: { tooltips: true },
  },
  {
    name: "item tooltip",
    open: async (p) => {
      await p.locator(".nei-slot").nth(6).hover({ timeout: 4000 });
      await p.waitForTimeout(900);
    },
    opts: { tooltips: true },
  },
];

// ---- Runner -------------------------------------------------------------------------------------------------------

const browser = await chromium.launch({ headless: true });
const rows = [];
const notOpened = [];
for (const [w, h] of viewports) {
  const vp = `${w}x${h}`;
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, hasTouch: w < 600, isMobile: false });
  await ctx.addInitScript(() => {
    if (document.visibilityState !== "visible") window.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);
  });
  await ctx.addInitScript(PAGE_HELPERS);
  const p = await ctx.newPage();
  p.setDefaultTimeout(60000);
  const dismiss = async () => {
    for (const n of ["Dismiss", "Not now", "Skip"]) await p.getByRole("button", { name: n, exact: true }).first().click({ timeout: 800 }).catch(() => {});
  };
  await p.goto(base + "/", { waitUntil: "load" });
  await p.waitForTimeout(5000);
  // A fresh profile is greeted by a notice (the mod's announcement): measured before it is dismissed.
  if (!only || only.includes("first-visit notice")) {
    const greet = await p.evaluateHandle(() => ((window.__bxOld = new Set()), window.__bxRoots({ select: "[role=dialog]" })));
    const n = await greet.evaluate((list) => list.length);
    if (!n) notOpened.push(`${vp} first-visit notice: none shown`);
    for (let i = 0; i < n; i += 1) {
      const m = await greet.evaluate((list, at) => window.__bxMeasure(list[at]), i);
      rows.push({ vp, name: "first-visit notice", ...m, pass: m.inBox && !m.unreachable.length && !m.hiddenActions.length });
    }
  }
  await dismiss();
  for (const file of [...libPlans, "shared.json"]) {
    await p.setInputFiles("input[data-plan-file-input]", path.join(plans, file));
    await p.waitForTimeout(file === "shared.json" ? 1500 : 700);
  }
  await p.waitForSelector(".react-flow__node", { timeout: 120000 });
  await p.waitForTimeout(5000);
  await dismiss();
  const reset = async () => {
    await p.reload({ waitUntil: "load" });
    await p.waitForSelector(".react-flow__node", { timeout: 120000 });
    await p.waitForTimeout(3500);
    await dismiss();
  };
  let dirty = false;
  for (const sc of SCENARIOS) {
    if (only && !only.includes(sc.name)) continue;
    if (dirty) {
      await p.keyboard.press("Escape");
      await p.keyboard.press("Escape");
      await p.waitForTimeout(300);
      const left = await p.evaluate(() => window.__bxRoots({}).length + (document.querySelector(".nrw, .ml") ? 1 : 0)).catch(() => 1);
      if (left) await reset();
      dirty = false;
    }
    await centerBoard(p).catch(() => {});
    await p.mouse.move(w / 2, h - 2);
    await p.evaluate(() => window.__bxSnap());
    try {
      await sc.open(p);
    } catch (error) {
      notOpened.push(`${vp} ${sc.name}: ${String(error.message ?? error).split("\n")[0].slice(0, 110)}`);
      dirty = true;
      continue;
    }
    await p.waitForTimeout(700);
    const handles = await p.evaluateHandle((opts) => window.__bxRoots(opts), sc.opts ?? {});
    const count = await handles.evaluate((list) => list.length);
    if (!count) {
      notOpened.push(`${vp} ${sc.name}: nothing new on screen`);
      if (shots) await p.screenshot({ path: path.join(shots, `${vp}-${sc.name.replace(/\W+/g, "-")}-none.png`) });
      dirty = true;
      continue;
    }
    for (let i = 0; i < count; i += 1) {
      const m = await handles.evaluate((list, at) => window.__bxMeasure(list[at]), i);
      const pass = m.inBox && !m.unreachable.length && !m.hiddenActions.length;
      rows.push({ vp, name: sc.name + (count > 1 ? ` #${i + 1}` : ""), ...m, pass });
    }
    if (shots) {
      fs.mkdirSync(shots, { recursive: true });
      await p.screenshot({ path: path.join(shots, `${vp}-${sc.name.replace(/\W+/g, "-")}.png`) });
    }
    dirty = true;
  }
  await ctx.close();
}
await browser.close();

const pad = (s, n) => String(s).slice(0, n).padEnd(n);
console.log(`${pad("window", 9)} ${pad("box", 24)} ${pad("root", 26)} ${pad("rect", 20)} on  reach act  verdict`);
for (const r of rows) {
  console.log(
    `${pad(r.vp, 9)} ${pad(r.name, 24)} ${pad(r.cls, 26)} ${pad(r.rect, 20)} ${r.inBox ? "ok" : "NO"}  ${pad(r.unreachable.length ? `NO${r.unreachable.length}` : "ok", 5)} ${pad(r.hiddenActions.length ? "NO" : "ok", 4)} ${r.pass ? "pass" : "FAIL"}`,
  );
  if (!r.pass) {
    for (const u of r.unreachable.slice(0, 3)) console.log(`          unreachable ${u}`);
    for (const a of r.hiddenActions.slice(0, 3)) console.log(`          hidden key  ${a}`);
  }
}
for (const n of notOpened) console.log(`not opened  ${n}`);
const failed = rows.filter((r) => !r.pass).length;
console.log(`\n${rows.length} measured, ${failed} failed, ${notOpened.length} not opened`);
process.exit(failed ? 1 : 0);
