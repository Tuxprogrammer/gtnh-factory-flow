import type { FactoryStorage, ResourceKind } from "@/lib/model/types";
import type { StorageRole } from "@/lib/model/storage-role";
import { storageTargetMode } from "@/lib/model/storage-target";
import {
  getActiveRateUnit,
  rateMultiplierForKind,
  rateSuffixForKind,
  setActiveRateUnit,
  type RateUnit,
} from "@/lib/format/rate-unit";
import { viewEnvelope } from "@/test-support/view-corpus";
import type { TipSpec } from "../interaction/tip-spec";
import {
  bufferThroughput,
  buildDrawerView,
  drawerKindFor,
  type CleanDrawerView,
  type DrawerMeasure,
  type DrawerViewInput,
} from "./drawer-model";

/**
 * The drawer's shared cases (`conformance/view/drawer/cases.json`): the record the core's `DrawerBuilder` takes
 * (`DrawerInput`) and the view the website's own `buildDrawerView` made of the same drawer, with the fixed six-pixel
 * measure (six pixels a character, nine for the row's figure) standing in for the faces.
 */

/** The fixed measure of the corpus (`Cards.SIX` in the core's tests). */
const glyphs = (text: string) => [...text].length;
export const SIX_PIXELS: DrawerMeasure = {
  figure: (number, unit) => 9 * glyphs(number) + 1 + 6 * glyphs(unit),
  name: (label) => 6 * glyphs(label),
  game: (label) => 6 * glyphs(label),
};

/** A drawer as the core takes it (`com.gtnhplanner.core.view.drawer.DrawerInput`). */
export interface DrawerInput {
  id: string;
  kind: "source" | "product" | "trash" | "buffer";
  resource: ResourceKind;
  label: string;
  rule: "ignore" | "at-least" | "exact" | "at-most";
  inert: boolean;
  target?: number;
  moved: number;
  unmet: boolean;
  unanchored: boolean;
  linked: boolean;
  spill?: number;
  multiplier: number;
  unit: string;
}

/** The drawer's wire form: `CleanDrawerView` with its tips as the core's `TipView` (all three lists present). */
export type DrawerWireView = Omit<CleanDrawerView, "spill" | "tips" | "flip"> & {
  spill?: { number: string; unit: string; tip: TipWire };
  tips: Record<"bar" | "del" | "rule" | "rate" | "body", TipWire>;
  flip?: TipWire;
};

export interface TipWire {
  id: string;
  title: string;
  sub?: string;
  rows: Array<{ label: string; value: string; tone?: string }>;
  notes: Array<{ text: string; tone?: string }>;
  actions: Array<{ input: string; label: string }>;
}

export interface DrawerCase {
  name: string;
  input: DrawerInput;
  view: DrawerWireView;
}

interface Spec {
  name: string;
  role: StorageRole;
  storage?: Partial<FactoryStorage>;
  net?: number;
  produced?: number;
  unreachable?: boolean;
  unanchored?: boolean;
  unmet?: boolean;
  linked?: boolean;
  spill?: number;
  label?: string;
  unit?: RateUnit;
}

function storageOf(spec: Spec): FactoryStorage {
  return {
    id: "d",
    kind: "item",
    resourceId: "x",
    displayName: "X",
    position: { x: 0, y: 0 },
    ...spec.storage,
  };
}

function viewInputOf(spec: Spec): DrawerViewInput {
  return {
    storage: storageOf(spec),
    role: spec.role,
    label: spec.label ?? "Iron Ingot",
    result: {
      netPerSecond: spec.net ?? 0,
      producedPerSecond: spec.produced ?? 0,
      targetUnreachable: spec.unreachable,
    } as never,
    unanchored: spec.unanchored ?? false,
    unmet: spec.unmet ?? false,
    linked: spec.linked ?? true,
    spill: spec.spill,
    measure: SIX_PIXELS,
  };
}

/** What a host hands the core for this drawer, read off the website's own model of it. */
export function drawerInputOf(input: DrawerViewInput): DrawerInput {
  const { storage, role } = input;
  const kind = drawerKindFor(role, storage);
  return {
    id: storage.id,
    kind,
    resource: storage.kind,
    label: input.label,
    rule: storageTargetMode(storage, role),
    inert: role === "byproduct" || role === "trash",
    ...(storage.targetPerSecond !== undefined ? { target: storage.targetPerSecond } : {}),
    moved:
      kind === "buffer"
        ? bufferThroughput(input.result)
        : Math.abs(input.result?.netPerSecond ?? 0),
    unmet: input.unmet || input.result?.targetUnreachable === true,
    unanchored: input.unanchored,
    linked: input.linked,
    ...(input.spill !== undefined ? { spill: input.spill } : {}),
    multiplier: rateMultiplierForKind(storage.kind),
    unit: rateSuffixForKind(storage.kind).trim(),
  };
}

function tipWire(tip: TipSpec): TipWire {
  const tone = (value: string | undefined) => (value === undefined || value === "plain" ? {} : { tone: value });
  return {
    id: tip.id,
    title: tip.title,
    ...(tip.sub !== undefined ? { sub: tip.sub } : {}),
    rows: (tip.rows ?? []).map((r) => ({ label: r.label, value: r.value, ...tone(r.tone) })),
    notes: (tip.notes ?? []).map((n) => ({ text: n.text, ...tone(n.tone) })),
    actions: (tip.actions ?? []).map((a) => ({ input: a.input, label: a.label })),
  };
}

/** The view as the core writes it: keys in the record's order, optionals absent. */
export function drawerWire(view: CleanDrawerView): DrawerWireView {
  return {
    id: view.id,
    kind: view.kind,
    label: view.label,
    number: view.number,
    unit: view.unit,
    zero: view.zero,
    numberSmall: view.numberSmall,
    nameSmall: view.nameSmall,
    nameClipped: view.nameClipped,
    hasRule: view.hasRule,
    rule: view.rule,
    ruleMark: view.ruleMark,
    ...(view.target ? { target: view.target } : {}),
    boxText: view.boxText,
    prompt: view.prompt,
    unmet: view.unmet,
    ...(view.reaches !== undefined ? { reaches: view.reaches } : {}),
    ...(view.progress !== undefined ? { progress: view.progress } : {}),
    tone: view.tone,
    linked: view.linked,
    glanceRate: view.glanceRate,
    ...(view.spill
      ? { spill: { number: view.spill.number, unit: view.spill.unit, tip: tipWire(view.spill.tip) } }
      : {}),
    tips: {
      bar: tipWire(view.tips.bar),
      del: tipWire(view.tips.del),
      rule: tipWire(view.tips.rule),
      rate: tipWire(view.tips.rate),
      body: tipWire(view.tips.body),
    },
    ...(view.flip ? { flip: tipWire(view.flip) } : {}),
  } as DrawerWireView;
}

const SPECS: Spec[] = [
  { name: "product, no rule, nothing moving", role: "product" },
  { name: "product, no rule, nothing set: prompts", role: "product", unanchored: true },
  { name: "product, at least 12/s, reached", role: "product", storage: { targetPerSecond: 12, targetMode: "at-least" }, net: 12 },
  { name: "product, at least 12/s, half reached", role: "product", storage: { targetPerSecond: 12, targetMode: "at-least" }, net: 6 },
  { name: "product, exactly 40/s, unreachable", role: "product", storage: { targetPerSecond: 40, targetMode: "exact" }, net: 25, unmet: true, unreachable: true },
  { name: "product, at most 8/s, calm", role: "product", storage: { targetPerSecond: 8, targetMode: "at-most" }, net: 3 },
  { name: "product, at most 0 holds nothing", role: "product", storage: { targetPerSecond: 0, targetMode: "at-most" }, net: 0 },
  { name: "product, at least 0 is no rate", role: "product", storage: { targetPerSecond: 0, targetMode: "at-least" }, net: 4 },
  { name: "product, rule ignore keeps the saved rate quiet", role: "product", storage: { targetPerSecond: 9, targetMode: "ignore" }, net: 4 },
  { name: "product, tiny flow", role: "product", net: 0.003 },
  { name: "product, a trickle reads nothing", role: "product", net: 0.000001 },
  { name: "product, 1234/s reads 1.23k", role: "product", net: 1234 },
  { name: "product, 98765/s reads 98.8k", role: "product", net: 98765 },
  { name: "product, 2.5 million", role: "product", net: 2_500_000 },
  { name: "product, per minute", role: "product", storage: { targetPerSecond: 2, targetMode: "exact" }, net: 2, unit: "minute" },
  { name: "product, per hour", role: "product", storage: { targetPerSecond: 0.5, targetMode: "at-least" }, net: 0.25, unit: "hour" },
  { name: "product, per tick", role: "product", net: 40, unit: "tick" },
  { name: "product, not wired", role: "product", linked: false, net: 1 },
  { name: "product, unwired and unmet", role: "product", linked: false, storage: { targetPerSecond: 5, targetMode: "exact" }, net: 1, unmet: true },
  { name: "byproduct reads as a product with no rule", role: "byproduct", storage: { targetPerSecond: 5, targetMode: "at-least" }, net: 3 },
  { name: "idle drawer with a negative goal is a source", role: "idle", storage: { targetPerSecond: -2 }, net: -2 },
  { name: "idle drawer reads as a product", role: "idle" },
  { name: "source, exactly 30/s", role: "source", storage: { targetPerSecond: -30, targetMode: "exact" }, net: -30 },
  { name: "source, at most 10/s", role: "source", storage: { targetPerSecond: -10, targetMode: "at-most" }, net: -4 },
  { name: "source, nothing set: prompts", role: "source", unanchored: true },
  { name: "source, fluid in litres", role: "source", storage: { kind: "fluid", targetPerSecond: -250, targetMode: "exact" }, net: -250 },
  { name: "source, fluid per hour", role: "source", storage: { kind: "fluid", targetPerSecond: -0.5, targetMode: "at-least" }, net: -0.5, unit: "hour" },
  { name: "power product in EU/t", role: "product", storage: { kind: "power", targetPerSecond: 640, targetMode: "at-least" }, net: 640 },
  { name: "power product, a trickle", role: "product", storage: { kind: "power", resourceId: "eu" }, net: 0.1 },
  { name: "trash", role: "trash", net: 7 },
  { name: "trash, nothing", role: "trash" },
  { name: "trash ignores a saved rule", role: "trash", storage: { targetPerSecond: 5, targetMode: "exact" }, net: 2, unanchored: true },
  { name: "buffer passes 12/s with no rule", role: "buffer", produced: 12 },
  { name: "buffer, nothing passes", role: "buffer" },
  { name: "buffer, at least 20/s, half reached", role: "buffer", storage: { targetPerSecond: 20, targetMode: "at-least" }, produced: 10 },
  { name: "buffer, exactly 15/s, reached", role: "buffer", storage: { targetPerSecond: 15, targetMode: "exact" }, produced: 15 },
  { name: "buffer, at most 9/s, unreachable", role: "buffer", storage: { targetPerSecond: 9, targetMode: "at-most" }, produced: 4, unmet: true },
  { name: "buffer, nothing set: prompts", role: "buffer", produced: 3, unanchored: true },
  { name: "buffer forced out 80/s", role: "buffer", produced: 20, spill: 80 },
  { name: "buffer forced out a fluid", role: "buffer", storage: { kind: "fluid" }, produced: 1500, spill: 12_500 },
  { name: "buffer forced out a trickle shows nothing", role: "buffer", produced: 5, spill: 0.000001 },
  { name: "buffer, forced out narrows the words", role: "buffer", produced: 123456, spill: 5, label: "Hydrochloric Acid" },
  { name: "buffer, forced out per minute with a rule", role: "buffer", storage: { targetPerSecond: 3, targetMode: "at-least" }, produced: 3, spill: 1.5, unit: "minute" },
  { name: "a spill on a product is not shown", role: "product", net: 5, spill: 9 },
  { name: "buffer, not wired", role: "buffer", produced: 2, linked: false },
  { name: "a long name steps down and clips", role: "product", net: 2, label: "Distilled Hydrogen Sulfide Solution" },
  { name: "a name just over the step-down width", role: "product", net: 2, label: "Aluminium Dust!" },
  { name: "a name that fits", role: "product", net: 2, label: "Aluminium Du" },
  { name: "a wide figure steps down", role: "product", storage: { targetPerSecond: 99999, targetMode: "exact" }, net: 98765.4 },
];

export function drawerCases(): DrawerCase[] {
  const before = getActiveRateUnit();
  try {
    return SPECS.map((spec) => {
      setActiveRateUnit(spec.unit ?? "second");
      const input = viewInputOf(spec);
      return { name: spec.name, input: drawerInputOf(input), view: drawerWire(buildDrawerView(input)) };
    });
  } finally {
    setActiveRateUnit(before);
  }
}

/** The website's own code, run for a stored case's name: the input and view it makes now. */
export function drawerCaseNamed(name: string): { input: DrawerInput; view: DrawerWireView } {
  const spec = SPECS.find((s) => s.name === name);
  if (!spec) throw new Error(`no drawer case named ${name}`);
  const before = getActiveRateUnit();
  try {
    setActiveRateUnit(spec.unit ?? "second");
    const input = viewInputOf(spec);
    return { input: drawerInputOf(input), view: drawerWire(buildDrawerView(input)) };
  } finally {
    setActiveRateUnit(before);
  }
}

/** The files of `conformance/view/` this family writes, by path under it (`conformance/view/README.md`). */
export function viewFiles(): Record<string, string> {
  return {
    "drawer/cases.json": viewEnvelope("drawer", "cases", "six-px", { cases: drawerCases() }),
  };
}
