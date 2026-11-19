/**
 * The recipe card's shared cases (conformance/view/card/): card inputs in the core's record shape (`CardInput.Case`,
 * jvm/core/core-view) and the view the website's own builders make of each (`cleanCardView`, clean-model.ts), plus the
 * count's arithmetic as vectors. The core's `CardViews` must give the same views (`CardCasesTest`); the website is held
 * to the file by card-conformance.test.ts. The cases are the builder tests' (clean-model.test.ts, glance, loops,
 * idle-trade, turbine-foot, clean-model-amps, parallel-reading) made whole cards. Text is measured six px a character
 * (`SIX_PX_MEASURE`), the core's `Cards.SIX`.
 *
 * Node only (tests and the generator); never import this from app code.
 */
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { CUSTOM_RATE_MACHINE_TYPE } from "@/lib/model/custom-rate";
import { pinRuleOf, type PinRule } from "@/lib/model/pin-rule";
import { getVoltageTierMaxEuT } from "@/lib/model/tiers";
import { powerCardTier } from "@/lib/power/power-recipe";
import {
  setActivePowerDisplayUnit,
  setActiveRateUnit,
  type PowerDisplayUnit,
  type RateUnit,
} from "@/lib/format/rate-unit";
import type { PlanSolveExtras } from "@/lib/solver/plan/types";
import { viewEnvelope } from "@/test-support/view-corpus";
import type { RailPort } from "../node-verdict";
import {
  circuitNumber,
  cleanCardView,
  frameTone,
  parseCountText,
  wheelCount,
  wheelDraft,
  type CleanCardView,
  type CleanFootView,
  type CleanPortView,
} from "./clean-model";
import { SIX_PX_MEASURE, setFixedTextMeasure } from "./text-measure";

// region The input record (jvm/core/core-view CardInput, on the wire)

type Rule = "ANY" | "UP_TO" | "EXACT";
type Reason = "FULL" | "BOTTLENECK" | "STARVED" | "CLOGGED" | "UNUSED" | "LIMITED";

export interface CardPortIn {
  key: string;
  handleId: string;
  output: boolean;
  name: string;
  kind: string;
  resourceId: string;
  perSecond: number;
  energyPerUnit?: number;
  wired: boolean;
  chance?: number;
  consumeChance?: number;
  alternatives: number;
  free: boolean;
  setRate?: number;
}

export interface CardInputJson {
  nodeId: string;
  name: string;
  hasPicker: boolean;
  tier?: string;
  tierFixed: boolean;
  hatchControl: boolean;
  power?: {
    ok: boolean;
    tier: string;
    amps: number;
    drawEuT: number;
    parallels: number;
    gateReason?: string;
    multiblock: boolean;
    hatchChip?: string;
  };
  steam?: { drawSteamPerTick: number; highPressure: boolean; parallels: number };
  parallel: number;
  structuralParallel: number;
  pinned: Array<{ id: string; label: string; value: string; icon?: string; warn: boolean }>;
  art?: string;
  glanceArt?: string;
  product?: { icon: string; fluid: boolean };
  circuit?: { icon?: string; number?: string };
  inputs: CardPortIn[];
  outputs: CardPortIn[];
  count: {
    rule: Rule;
    pin?: number;
    needed: number;
    utilization?: number;
    nodeParallel: number;
    customRate: boolean;
    unset: boolean;
  };
  powerTier?: string;
  upTo?: {
    capacity: number;
    reached: number;
    reason?: Reason;
    portKey?: string;
    resourceKey?: string;
    tied: string[];
    wants?: number;
    upstream: string[];
  };
  forced: Array<{ portKey: string; perSecond: number; drawerId?: string }>;
  loop?: "DEAD_LOOP" | "CLOG_LOCK";
  leftovers: Array<{
    bearers: Array<{ nodeId?: string; side?: string; share: number; resourceKey?: string }>;
  }>;
  turbine?: {
    turbines: number;
    flow: number;
    setFlow: number;
    optimal: number;
    unit: string;
    euMade: number;
    euCounted: number;
  };
  cardNames: Record<string, string>;
  selected: boolean;
  menu: {
    calm: boolean;
    locked: boolean;
    ownsRecipe: boolean;
    shared: boolean;
    holder: boolean;
    emptyCropFarm: boolean;
    tasks: boolean;
  };
}

export interface CardCaseInput {
  dials: { rate: "TICK" | "SECOND" | "MINUTE" | "HOUR" | "EU"; power: string; averageDraw: boolean };
  card: CardInputJson;
}

// endregion

// region The cases

const N = "n";

function port(
  output: boolean,
  name: string,
  perSecond: number,
  extra: Partial<CardPortIn> = {},
): CardPortIn {
  const kind = extra.kind ?? "item";
  const id = extra.resourceId ?? name.toLowerCase().replace(/ /g, "_");
  const handleId = extra.handleId ?? `${output ? "output" : "input"}:${kind}:${encodeURIComponent(id)}`;
  return {
    key: `${output ? "output" : "input"}:${id}`,
    handleId,
    output,
    name,
    kind,
    resourceId: id,
    perSecond,
    wired: true,
    alternatives: 1,
    free: false,
    ...extra,
  };
}

const OXYGEN = (perSecond = 720, extra: Partial<CardPortIn> = {}) =>
  port(false, "Oxygen", perSecond, { kind: "fluid", resourceId: "oxygen", ...extra });
const SULFUR = (perSecond = 6, extra: Partial<CardPortIn> = {}) =>
  port(false, "Sulfur Dust", perSecond, { resourceId: "sulfur_dust", ...extra });
const ACID = (perSecond = 720, extra: Partial<CardPortIn> = {}) =>
  port(true, "Sulfuric Acid", perSecond, { kind: "fluid", resourceId: "sulfuric_acid", ...extra });

const MENU = {
  calm: false,
  locked: false,
  ownsRecipe: false,
  shared: false,
  holder: false,
  emptyCropFarm: false,
  tasks: false,
};

/** A chemical reactor on HV, solved at 2.4 machines, nothing set. */
function base(): CardInputJson {
  return {
    nodeId: N,
    name: "Chemical Reactor",
    hasPicker: false,
    tier: "HV",
    tierFixed: false,
    hatchControl: false,
    power: {
      ok: true,
      tier: "HV",
      amps: 1,
      drawEuT: 480,
      parallels: 1,
      multiblock: false,
    },
    parallel: 1,
    structuralParallel: 1,
    pinned: [],
    glanceArt: "icons/chemical_reactor.png",
    product: { icon: "icons/sulfuric_acid.png", fluid: true },
    inputs: [OXYGEN(), SULFUR()],
    outputs: [ACID()],
    count: { rule: "ANY", needed: 2.4, nodeParallel: 1, customRate: false, unset: false },
    forced: [],
    leftovers: [],
    cardNames: {},
    selected: false,
    menu: MENU,
  };
}

const EU = { rate: "SECOND" as const, power: "eu", averageDraw: true };

type Edit = (card: CardInputJson) => void;

function card(edit: Edit, dials: CardCaseInput["dials"] = EU): CardCaseInput {
  const c = base();
  edit(c);
  return { dials, card: c };
}

/** A count set: the rule as the website reads it from the pin (`pinRuleOf`). */
function pin(c: CardInputJson, count: number, exact = false) {
  const rule = pinRuleOf(
    { solvePin: count, solvePinExact: exact },
    { machineType: c.count.customRate ? CUSTOM_RATE_MACHINE_TYPE : "x" },
  );
  c.count.rule = RULE_OUT[rule];
  c.count.pin = count;
}

const upTo = (
  reached: number,
  capacity: number,
  reason: Reason | undefined,
  extra: Partial<NonNullable<CardInputJson["upTo"]>> = {},
): NonNullable<CardInputJson["upTo"]> => ({
  capacity,
  reached,
  ...(reason ? { reason } : {}),
  tied: [],
  upstream: [],
  ...extra,
});

const key = (side: "i" | "o", handle: string, node = N) => `${node}|${side}|${handle}`;
const OXYGEN_KEY = key("i", "input:fluid:oxygen");
const SULFUR_KEY = key("i", "input:item:sulfur_dust");
const ACID_KEY = key("o", "output:fluid:sulfuric_acid");

const TURBINE = { turbines: 1, flow: 57, setFlow: 100, optimal: 100, unit: "L/t", euMade: 342636, euCounted: 400000 };

const LONG = "Sodium Hydroxide Solution With A Very Long Name Indeed";

export function cardCases(): Array<{ name: string; input: CardCaseInput }> {
  const out: Array<{ name: string; input: CardCaseInput }> = [];
  const add = (name: string, input: CardCaseInput) => out.push({ name, input });

  // The count (clean-model.test.ts "the count").
  add("any, solved 2.4", card(() => {}));
  add("any, nothing sets it: the box asks", card((c) => {
    c.count.needed = 0;
    c.count.unset = true;
  }));
  add("any, a trickle under a thousandth", card((c) => (c.count.needed = 0.0003)));
  add("any, a hundred or more rounds up", card((c) => (c.count.needed = 150.2)));
  add("up to 24 with its read-out", card((c) => {
    pin(c, 24);
    c.upTo = upTo(18.2, 24, "STARVED", { resourceKey: "fluid:oxygen" });
  }));
  add("up to with no read-out yet", card((c) => pin(c, 24)));
  add("exactly 24", card((c) => pin(c, 24, true)));
  add("exactly a fractional pin", card((c) => pin(c, 2.5, true)));
  add("a pin with more than three decimals", card((c) => pin(c, 1.23456)));
  add("custom rate: always Exactly", card((c) => {
    c.count.customRate = true;
    pin(c, 3);
  }));

  // The bar along the foot (clean-model.test.ts "the bar along the foot").
  const foot = (name: string, read: NonNullable<CardInputJson["upTo"]>, edit: Edit = () => {}) =>
    add(name, card((c) => {
      pin(c, read.capacity > 0 ? read.capacity : 24);
      c.upTo = read;
      edit(c);
    }));
  foot("foot: starved names its input", upTo(18.2, 24, "STARVED", { resourceKey: "fluid:oxygen" }));
  foot("foot: full", upTo(24, 24, "FULL"));
  foot("foot: clogged names its output", upTo(12, 24, "CLOGGED", { resourceKey: "fluid:sulfuric_acid" }));
  foot("foot: unused", upTo(0, 24, "UNUSED", { resourceKey: "fluid:sulfuric_acid" }));
  foot("foot: limited", upTo(20, 24, "LIMITED"));
  foot("foot: dead loop", upTo(0, 24, "STARVED", { resourceKey: "fluid:oxygen" }), (c) => {
    c.loop = "DEAD_LOOP";
  });
  foot("foot: clog lock says what backs up", upTo(0, 24, "UNUSED", { resourceKey: "fluid:sulfuric_acid" }), (c) => {
    c.loop = "CLOG_LOCK";
  });
  foot("foot: bottleneck runs on to what the line wants", upTo(24, 24, "BOTTLENECK", { wants: 31 }));
  foot("foot: bottleneck wanting no more than the count", upTo(24, 24, "BOTTLENECK", { wants: 20 }));
  foot(
    "foot: ties and the full cards upstream",
    upTo(12, 24, "STARVED", {
      resourceKey: "fluid:oxygen",
      tied: [SULFUR_KEY],
      upstream: ["e1", "gone"],
    }),
    (c) => (c.cardNames = { e1: "Electrolyzer" }),
  );
  foot("foot: shortens the line rather than cutting a word", upTo(12, 24, "CLOGGED", { resourceKey: `item:${LONG.toLowerCase().replace(/ /g, "_")}` }), (c) => {
    c.outputs = [port(true, LONG, 3)];
  });
  foot("foot: no reason worked out", upTo(4.5, 6, undefined));
  foot("foot: capacity zero falls back to the count set", upTo(3, 0, "LIMITED"));
  add("foot: idle, its input sent out instead", card((c) => {
    c.count.needed = 0;
    c.cardNames = { m: "Mixer" };
    c.leftovers = [
      {
        bearers: [
          { nodeId: N, side: "out", share: 0, resourceKey: "fluid:sulfuric_acid" },
          { nodeId: "m#r1", side: "out", share: 1, resourceKey: "fluid:oxygen" },
        ],
      },
    ];
  }));
  add("foot: no idle for an input it does not take", card((c) => {
    c.count.needed = 0;
    c.leftovers = [
      {
        bearers: [
          { nodeId: N, side: "out", share: 0, resourceKey: "fluid:sulfuric_acid" },
          { nodeId: "m", side: "out", share: 1, resourceKey: "item:salt" },
        ],
      },
    ];
  }));
  add("foot: forced in keeps all running", card((c) => {
    pin(c, 24, true);
    c.forced = [{ portKey: OXYGEN_KEY, perSecond: 720 }];
  }));
  add("foot: forced in keeps it running", card((c) => {
    pin(c, 1, true);
    c.forced = [{ portKey: OXYGEN_KEY, perSecond: 36 }];
  }));
  add("foot: two forced inputs", card((c) => {
    pin(c, 4, true);
    c.forced = [
      { portKey: OXYGEN_KEY, perSecond: 720 },
      { portKey: SULFUR_KEY, perSecond: 2 },
    ];
  }));
  add("foot: exactly with nothing forced has no bar", card((c) => pin(c, 24, true)));
  add("foot: any with a forced input has no bar", card((c) => {
    c.forced = [{ portKey: OXYGEN_KEY, perSecond: 720 }];
  }));

  // The rows a reason names (clean-model.test.ts "the rows a reason names").
  foot("lit: starved's input by port key, and its ties", upTo(1, 2, "STARVED", { portKey: OXYGEN_KEY, tied: [SULFUR_KEY] }));
  foot("lit: clogged's output", upTo(1, 2, "CLOGGED", { portKey: ACID_KEY }));
  foot("lit: nothing for full", upTo(2, 2, "FULL", { portKey: ACID_KEY }));
  foot("lit: a shared machine's recipe by its section", upTo(1, 2, "STARVED", { portKey: key("i", "input:fluid:oxygen", `${N}#r1`) }), (c) => {
    c.inputs = [OXYGEN(720, { handleId: "r1:input:fluid:oxygen" }), SULFUR()];
  });
  foot("lit: falls back to the row of its resource", upTo(1, 2, "UNUSED", { resourceKey: "fluid:sulfuric_acid" }));
  foot("lit: never a free row", upTo(1, 2, "STARVED", { resourceKey: "fluid:lava" }), (c) => {
    c.inputs = [port(false, "Lava", 0, { kind: "fluid", resourceId: "lava", free: true, wired: false }), SULFUR()];
  });

  // Turbines (turbine-foot.test.ts).
  const turbine = (name: string, t: typeof TURBINE, edit: Edit = () => {}, dials = EU) =>
    add(name, card((c) => {
      c.name = "Large Gas Turbine";
      c.power = undefined;
      c.tier = undefined;
      c.powerTier = "EV";
      c.turbine = t;
      c.inputs = [port(false, "Benzene", 5, { kind: "fluid" })];
      c.outputs = [port(true, "EU", 400000 * 20, { kind: "power", resourceId: "eu" })];
      edit(c);
    }, dials));
  turbine("turbine: one at 57% of optimal", TURBINE);
  turbine("turbine: three at 80% each", { ...TURBINE, turbines: 3, flow: 80, euMade: 1200000 });
  turbine("turbine: above optimal", { ...TURBINE, flow: 125, euMade: 410000 });
  turbine("turbine: follows the Amps dial in its own tier", TURBINE, () => {}, { ...EU, power: "amps" });
  turbine("turbine: a ring's bar comes first", TURBINE, (c) => {
    pin(c, 2);
    c.upTo = upTo(0, 2, "STARVED");
    c.loop = "DEAD_LOOP";
  });

  // A generator's EU row follows the power dial (clean-model-amps.test.ts).
  const generator = (name: string, dials: CardCaseInput["dials"], edit: Edit = () => {}) =>
    add(name, card((c) => {
      c.name = "Combustion Generator";
      c.power = undefined;
      c.tier = undefined;
      c.powerTier = "LV";
      c.product = undefined;
      c.inputs = [port(false, "Diesel", 4, { kind: "fluid" })];
      c.outputs = [port(true, "EU", 640, { kind: "power", resourceId: "eu" })];
      edit(c);
    }, dials));
  generator("generator: EU/t on the EU/t dial", EU);
  generator("generator: amps of its own tier on the Amps dial", { ...EU, power: "amps" });
  generator("generator: a fixed tier dial", { ...EU, power: "HV" });
  generator("generator: its forced row converts too", { ...EU, power: "amps" }, (c) => {
    c.forced = [{ portKey: key("o", "output:power:eu"), perSecond: 320 }];
  });

  // The power row.
  add("power: amps of its tier on the Amps dial", card(() => {}, { ...EU, power: "amps" }));
  add("power: peak bills every machine it has", card((c) => (c.count.utilization = 0.5), { ...EU, averageDraw: false }));
  add("power: average bills its use", card((c) => (c.count.utilization = 0.5)));
  add("power: an Up to card's use is reached over its count", card((c) => {
    pin(c, 4);
    c.upTo = upTo(1, 4, "LIMITED");
  }));
  add("power: nothing running bills nothing at peak", card((c) => (c.count.needed = 0), { ...EU, averageDraw: false }));
  add("power: the node's parallel multiplies it", card((c) => (c.count.nodeParallel = 4)));
  add("power: TIER! with the machine's reason", card((c) => {
    c.power = { ...c.power!, ok: false, gateReason: "Needs a laser source of UV or higher." };
  }));
  add("power: TIER! with no reason", card((c) => (c.power = { ...c.power!, ok: false })));
  add("power: a steam machine burns litres", card((c) => {
    c.power = undefined;
    c.tier = undefined;
    c.steam = { drawSteamPerTick: 8, highPressure: false, parallels: 1 };
  }));
  add("power: a high-pressure steam machine", card((c) => {
    c.power = undefined;
    c.tier = undefined;
    c.steam = { drawSteamPerTick: 16, highPressure: true, parallels: 8 };
  }));
  add("power: no power row on a card that draws nothing", card((c) => {
    c.power = undefined;
    c.tier = undefined;
  }));

  // The head: a multiblock's chips, a fixed tier, the name's size.
  add("head: a multiblock on a hatch chip", card((c) => {
    c.name = "Large Chemical Reactor";
    c.hatchControl = true;
    c.power = { ...c.power!, tier: "EV", amps: 4, multiblock: true, drawEuT: 1920 };
  }, { ...EU, power: "amps" }));
  add("head: an exotic hatch's own chip", card((c) => {
    c.hatchControl = true;
    c.power = { ...c.power!, tier: "LuV", amps: 16, multiblock: true, hatchChip: "16A" };
  }));
  add("head: a multiblock with no hatch chip", card((c) => {
    c.power = { ...c.power!, tier: "IV", amps: 2, multiblock: true };
  }));
  add("head: a fusion reactor's fixed tier", card((c) => {
    c.name = "Fusion Control Computer Mark I";
    c.tier = "LuV";
    c.tierFixed = true;
    c.power = { ...c.power!, tier: "LuV", multiblock: false };
  }));
  add("head: a long name steps down", card((c) => (c.name = "Electrolytic Separator Of Many Things")));
  add("head: a longer name stays at its smallest", card((c) => {
    c.name = "Industrial Electrolytic Separator Of Very Many Things Indeed";
    c.hasPicker = true;
  }));
  add("head: a name that fits a picker's room", card((c) => {
    c.name = "Assembler";
    c.hasPicker = true;
  }));

  // Ports.
  add("ports: a chanced output and a sometimes used input", card((c) => {
    c.inputs = [OXYGEN(720, { consumeChance: 0.25 }), SULFUR()];
    c.outputs = [ACID(), port(true, "Tiny Pile of Ash", 0.12, { chance: 0.0333 })];
  }));
  add("ports: per minute and per hour", card((c) => (c.outputs = [ACID(1.5)]), { ...EU, rate: "MINUTE" }));
  add("ports: per hour", card(() => {}, { ...EU, rate: "HOUR" }));
  add("ports: per tick", card(() => {}, { ...EU, rate: "TICK" }));
  add("ports: EU each under the energy unit", card((c) => {
    c.outputs = [ACID(720, { energyPerUnit: 13.3333 })];
    c.inputs = [OXYGEN(), SULFUR(6, { energyPerUnit: 200 })];
  }, { ...EU, rate: "EU" }));
  add("ports: EU each in a fixed tier", card((c) => {
    c.outputs = [ACID(720, { energyPerUnit: 200 })];
  }, { rate: "EU", power: "LV", averageDraw: true }));
  add("ports: a set rate is gold", card((c) => (c.inputs = [OXYGEN(720, { setRate: 720 }), SULFUR()])));
  add("ports: a long name drops a size, a longer one is cut", card((c) => {
    c.inputs = [port(false, "Sodium Hydroxide Dust", 3), port(false, LONG, 1)];
  }));
  add("ports: a big number drops off figure size", card((c) => (c.outputs = [ACID(123456789)])));
  add("ports: a slot several resources fill", card((c) => (c.inputs = [OXYGEN(), SULFUR(6, { alternatives: 3 })])));
  add("ports: a row that runs nothing, unwired", card((c) => (c.outputs = [ACID(0, { wired: false })])));
  add("ports: an output's forced row, and a spill into a buffer", card((c) => {
    c.outputs = [ACID(720), port(true, "Hydrogen", 50, { kind: "fluid" })];
    c.forced = [
      { portKey: ACID_KEY, perSecond: 120 },
      { portKey: key("o", "output:fluid:hydrogen"), perSecond: 20, drawerId: "buffer-1" },
    ];
  }));
  add("ports: forced dust is no row", card((c) => (c.forced = [{ portKey: ACID_KEY, perSecond: 0 }])));

  // The strip (parallel-reading.test.ts and the pins).
  add("strip: pinned settings, a picture and a warning", card((c) => {
    c.pinned = [
      { id: "heatingCoil", label: "Coil", value: "Cupronickel", icon: "icons/coil.png", warn: true },
      { id: "voltage", label: "Voltage", value: "HV", warn: false },
    ];
  }));
  add("strip: chips wrap onto a second row", card((c) => {
    c.pinned = ["Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"].map((label) => ({
      id: label.toLowerCase(),
      label,
      value: "Setting value",
      warn: false,
    }));
  }));
  add("strip: the supply caps the parallels", card((c) => {
    c.power = { ...c.power!, parallels: 3 };
    c.structuralParallel = 8;
  }));
  add("strip: the structure's own parallels", card((c) => {
    c.power = { ...c.power!, parallels: 8 };
    c.structuralParallel = 8;
  }));
  add("strip: one capped parallel", card((c) => (c.structuralParallel = 4)));
  add("strip: a steam machine's parallels, never capped", card((c) => {
    c.power = undefined;
    c.tier = undefined;
    c.steam = { drawSteamPerTick: 8, highPressure: false, parallels: 4 };
    c.structuralParallel = 8;
  }));
  add("strip: a machine of one has no chip", card((c) => (c.parallel = 1)));

  // The picture, the circuit, the zoomed-out face.
  add("picture: a structure render and an item product", card((c) => {
    c.art = "power-art/assembly_line.png";
    c.glanceArt = c.art;
    c.product = { icon: "icons/circuit.png", fluid: false };
  }));
  add("picture: no product", card((c) => (c.product = undefined)));
  add("circuit: by its setting", card((c) => (c.circuit = { icon: "icons/circuit.png", number: "24" })));
  add("glance: a ring's name in its tone", card((c) => {
    pin(c, 4);
    c.upTo = upTo(2, 4, "UNUSED");
    c.loop = "CLOG_LOCK";
  }));
  add("glance: a bottleneck fills to what it wants", card((c) => {
    pin(c, 4);
    c.upTo = upTo(4, 4, "BOTTLENECK", { wants: 8 });
  }));

  // The menu (menus/card-recipe-rows.test.tsx).
  add("menu: every row", card((c) => (c.menu = { ...MENU, tasks: true })));
  add("menu: calm or locked takes the recipe rows", card((c) => (c.menu = { ...MENU, calm: true })));
  add("menu: locked", card((c) => (c.menu = { ...MENU, locked: true })));
  add("menu: a machine that owns its recipe adds none", card((c) => (c.menu = { ...MENU, ownsRecipe: true })));
  add("menu: a shared machine replaces none", card((c) => (c.menu = { ...MENU, shared: true })));
  add("menu: a holder replaces none", card((c) => (c.menu = { ...MENU, holder: true })));
  add("menu: an empty crop farm replaces none", card((c) => (c.menu = { ...MENU, emptyCropFarm: true })));
  add("selected", card((c) => (c.selected = true)));
  return out;
}

// endregion

// region The website's builders over a case

const RULE_OUT: Record<PinRule, Rule> = { any: "ANY", upTo: "UP_TO", exact: "EXACT" };
const RATE_IN: Record<CardCaseInput["dials"]["rate"], RateUnit> = {
  TICK: "tick",
  SECOND: "second",
  MINUTE: "minute",
  HOUR: "hour",
  EU: "eu",
};
const LOOP_OUT = { dead: "DEAD_LOOP", clog: "CLOG_LOCK" } as const;

function railPort(p: CardPortIn): RailPort {
  return {
    key: p.key,
    handleId: p.handleId,
    side: p.output ? "output" : "input",
    displayName: p.name,
    kind: p.kind,
    resourceId: p.resourceId,
    currentPerSecond: p.perSecond,
    ...(p.energyPerUnit !== undefined ? { energyPerUnit: p.energyPerUnit } : {}),
    connected: p.wired,
    resource: {
      ...(p.chance !== undefined ? { chance: p.chance } : {}),
      ...(p.consumeChance !== undefined ? { consumeChance: p.consumeChance } : {}),
      alternatives: Array.from({ length: p.alternatives }, (_, i) => ({ id: `${p.resourceId}#${i}` })),
    },
    ...(p.free ? { free: true } : {}),
  } as unknown as RailPort;
}

/** The website's card for a case: its own builders, the six-px measure, the case's dials. */
export function websiteCardView(input: CardCaseInput): CleanCardView {
  const c = input.card;
  setFixedTextMeasure(SIX_PX_MEASURE);
  setActiveRateUnit(RATE_IN[input.dials.rate]);
  setActivePowerDisplayUnit(input.dials.power as PowerDisplayUnit);
  try {
    const portRates: Record<string, number> = {};
    for (const p of [...c.inputs, ...c.outputs]) if (p.setRate !== undefined) portRates[p.handleId] = p.setRate;
    const projectNode = {
      id: c.nodeId,
      ...(c.count.pin !== undefined ? { solvePin: c.count.pin } : {}),
      ...(c.count.rule === "EXACT" ? { solvePinExact: true } : {}),
      parallel: c.count.nodeParallel,
      portRates,
    } as FactoryNode;
    const recipe = {
      id: "r",
      machineType: c.count.customRate ? CUSTOM_RATE_MACHINE_TYPE : c.name,
      outputs: c.product
        ? [{ kind: c.product.fluid ? "fluid" : "item", id: "p", amount: 1, iconPath: c.product.icon }]
        : [],
      ...(c.powerTier
        ? { power: { euPerTick: getVoltageTierMaxEuT(c.powerTier as never) } }
        : {}),
    } as unknown as Recipe;
    if (powerCardTier(recipe) !== c.powerTier) {
      throw new Error(`case's power tier ${c.powerTier} is not the recipe's ${powerCardTier(recipe)}`);
    }
    const plan = {
      forced: c.forced
        .filter((f) => f.drawerId === undefined)
        .map((f) => ({ nodeId: c.nodeId, portKey: f.portKey, side: "in", resourceKey: "", perSecond: f.perSecond })),
      spills: c.forced
        .filter((f) => f.drawerId !== undefined)
        .map((f) => ({ nodeId: c.nodeId, portKey: f.portKey, resourceKey: "", perSecond: f.perSecond, drawerId: f.drawerId })),
      leftovers: c.leftovers.map((l, i) => ({
        key: `l${i}`,
        importing: false,
        custom: false,
        bearers: l.bearers.map((b) => ({ portKey: "", maxPerSecond: 0, solverPick: false, flows: [], ...b })),
      })),
      unneeded: [],
      unanchored: c.count.unset ? [c.nodeId] : [],
      terminals: [],
      notes: [],
      openGates: 0,
      ...(c.upTo
        ? {
            upTo: {
              [c.nodeId]: {
                capacity: c.upTo.capacity,
                reached: c.upTo.reached,
                reason: c.upTo.reason ?? null,
                ...(c.upTo.portKey ? { portKey: c.upTo.portKey } : {}),
                ...(c.upTo.resourceKey ? { resourceKey: c.upTo.resourceKey } : {}),
                tied: c.upTo.tied,
                ...(c.upTo.wants !== undefined ? { wants: c.upTo.wants } : {}),
                upstream: c.upTo.upstream,
              },
            },
          }
        : {}),
      loops: c.loop ? [{ kind: c.loop, cards: [c.nodeId], portKeys: [], resourceKeys: [] }] : [],
    } as unknown as PlanSolveExtras;
    return cleanCardView({
      projectNode,
      recipe,
      result: {
        theoreticalMachinesRequired: c.count.needed,
        ...(c.count.utilization !== undefined ? { utilization: c.count.utilization } : {}),
      } as never,
      plan,
      rails: { inputs: c.inputs.map(railPort), outputs: c.outputs.map(railPort) },
      name: c.name,
      hasPicker: c.hasPicker,
      art: c.art,
      glanceArt: c.glanceArt,
      tierControl: c.tier !== undefined ? { current: c.tier, fixed: c.tierFixed } : undefined,
      powerReport: c.power
        ? {
            state: c.power.ok ? "ok" : "stalled",
            tier: c.power.tier,
            amps: c.power.amps,
            drawEuT: c.power.drawEuT,
            parallels: c.power.parallels,
            recipeGateReason: c.power.gateReason,
            isMultiblock: c.power.multiblock,
            hatchChip: c.power.hatchChip,
          } as never
        : undefined,
      steamReport: c.steam,
      showHatchControl: c.hatchControl,
      parallel: c.parallel,
      structuralParallel: c.structuralParallel,
      circuit: c.circuit
        ? ({
            ...(c.circuit.number !== undefined ? { setting: c.circuit.number } : {}),
            ...(c.circuit.icon !== undefined ? { resource: { id: "circuit", iconPath: c.circuit.icon } } : {}),
          } as never)
        : undefined,
      pinnedChips: c.pinned.map((s) => ({
        id: s.id,
        label: s.label,
        value: s.value,
        icon: s.icon,
        warn: s.warn || undefined,
        title: `${s.label}: ${s.value}`,
      })),
      turbineCheck: c.turbine as never,
      averageDraw: input.dials.averageDraw,
      selected: c.selected,
      cardName: (id) => c.cardNames[id],
    });
  } finally {
    setFixedTextMeasure(undefined);
    setActiveRateUnit("second");
    setActivePowerDisplayUnit("eu");
  }
}

// endregion

// region The view on the wire (jvm/core/core-view CardView)

const upper = (tone: string | undefined) => tone?.toUpperCase();
const NAME_STEP: Record<number, number> = { 10: 0, 9: 1, 8: 2 };

/** Drops undefined keys, so a missing optional is a missing key (the wire has no nulls). */
function clean<T extends object>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as T;
}

function portJson(p: CleanPortView) {
  return clean({
    key: p.key,
    handleId: p.handleId,
    output: p.side === "output",
    number: p.number,
    unit: p.unit,
    name: p.name,
    title: p.title,
    zero: p.zero,
    gold: p.gold,
    rate: p.rate,
    numberSmall: p.numberSmall,
    nameSmall: p.nameSmall,
    nameClipped: p.nameClipped,
    wired: p.wired,
    choice: p.choice,
    resourceKey: p.resourceKey,
    free: p.port.free === true,
    forced: p.forced ? { number: p.forced.number, unit: p.forced.unit } : undefined,
    spillTo: p.spillTo,
    why: upper(p.why),
  });
}

function footJson(f: CleanFootView) {
  const { turbine } = f.facts;
  return clean({
    tone: upper(f.tone),
    word: f.word,
    say: f.say,
    frac: f.frac,
    fill: f.fill,
    ghost: f.ghost ? { left: f.ghost.left, width: f.ghost.width } : undefined,
    facts: clean({
      reason: f.facts.reason ?? undefined,
      loop: f.facts.loop ? LOOP_OUT[f.facts.loop] : undefined,
      rule: RULE_OUT[f.facts.rule],
      running: f.facts.running,
      capacity: f.facts.capacity,
      wants: f.facts.wants,
      resource: f.facts.resource,
      tied: f.facts.tied,
      upstream: f.facts.upstream,
      forced: f.facts.forced,
      sentOut: f.facts.sentOut,
      turbine: turbine
        ? {
            turbines: turbine.turbines,
            flow: turbine.flow,
            setFlow: turbine.setFlow,
            optimal: turbine.optimal,
            unit: turbine.unit,
            euMade: turbine.euMade,
            euCounted: turbine.euCounted,
          }
        : undefined,
      turbineTier: turbine?.tier,
    }),
  });
}

/** The card's menu rows as `CardRows` lists them (menus/CardActionsMenu.tsx) under `cardRecipeRows`' rules. */
function menuJson(m: CardInputJson["menu"]) {
  const open = !m.calm && !m.locked;
  return [
    { id: "clone", label: "Clone node", danger: false },
    ...(open && !m.ownsRecipe ? [{ id: "addRecipe", label: "Add another recipe", danger: false }] : []),
    ...(open && !m.shared && !m.holder && !m.emptyCropFarm
      ? [{ id: "replace", label: "Replace the recipe", danger: false }]
      : []),
    ...(m.tasks ? [{ id: "addToTask", label: "Add to a task", danger: false }] : []),
    { id: "delete", label: "Delete node", danger: true },
  ];
}

/** The website's card as the core's `CardView` writes it: its JSON, key for key. */
export function cardViewJson(view: CleanCardView, input: CardCaseInput): unknown {
  const count = view.count;
  return JSON.parse(
    JSON.stringify(
      clean({
        nodeId: view.nodeId,
        name: view.name,
        nameStep: NAME_STEP[view.nameSize],
        hasPicker: view.hasPicker,
        tier: view.tier,
        amps: view.amps,
        inputs: view.inputs.map(portJson),
        outputs: view.outputs.map(portJson),
        power: view.power,
        art: view.art,
        glanceArt: view.glanceArt,
        glanceProduct: view.glanceProduct
          ? { icon: view.glanceProduct.image.replace(/^url\((.*)\)$/, "$1"), fluid: view.glanceProduct.fluid }
          : undefined,
        count: clean({
          text: count.text,
          unit: count.unit,
          value: count.value,
          pinned: count.pinned,
          rule: RULE_OUT[count.rule],
          ruleWord: count.ruleWord,
          ruleFixed: count.ruleFixed,
          set: count.set,
          setText: count.setText,
          box: count.box,
          prompt: count.prompt,
          zero: count.zero,
          title: count.title,
        }),
        foot: view.foot ? footJson(view.foot) : undefined,
        loop: view.loop ? LOOP_OUT[view.loop] : undefined,
        glance: view.glance ? { tone: upper(view.glance.tone), fill: view.glance.fill, word: view.glance.word } : undefined,
        circuit: view.circuit
          ? clean({ icon: view.circuit.resource?.iconPath, number: circuitNumber(view.circuit) })
          : undefined,
        chips: view.chips.map((chip) =>
          clean({
            id: chip.id,
            label: chip.label,
            value: chip.value,
            icon: chip.icon,
            reading: chip.reading === true,
            powerCap: chip.powerCap,
            warn: chip.warn === true,
            title: chip.title,
            x: chip.x,
            y: chip.y,
            width: chip.width,
          }),
        ),
        stripHeight: view.stripHeight,
        selected: view.selected,
        cannotRun: view.cannotRun,
        unset: view.unset,
        frame: upper(frameTone(view.foot)),
        menu: menuJson(input.card.menu),
      }),
    ),
  );
}

// endregion

// region The count's arithmetic

/** Vectors of the count's wheel, typed text and the open box's wheel (`wheelCount`, `parseCountText`, `wheelDraft`). */
export function countVectors() {
  const wheel: Array<{ rule: Rule; set?: number; value: number; direction: 1 | -1; out?: number }> = [];
  for (const [rule, set, value] of [
    ["ANY", undefined, 2.4],
    ["ANY", undefined, 0],
    ["ANY", undefined, 3.0000005],
    ["UP_TO", 3, 2],
    ["UP_TO", 2.5, 2],
    ["UP_TO", 1, 1],
    ["EXACT", 1.4, 1],
    ["EXACT", 0.5, 1],
  ] as const) {
    for (const direction of [1, -1] as const) {
      const out = wheelCount(
        { rule: rule === "ANY" ? "any" : rule === "UP_TO" ? "upTo" : "exact", set, value },
        direction,
      );
      wheel.push({ rule, ...(set !== undefined ? { set } : {}), value, direction, ...(out !== undefined ? { out } : {}) });
    }
  }
  const parse = ["12", " ×3 ", "x 4.5", "", "  ", "0", "-2", "abc", "2.5k", "1e3", ".5", "5.", "+7", "Infinity", "×"].map(
    (text) => {
      const out = parseCountText(text);
      return { text, ...(out !== undefined ? { out } : {}) };
    },
  );
  const draft: Array<{ draft: string; value: number; direction: 1 | -1; out: string }> = [];
  for (const [text, value] of [
    ["", 2.4],
    ["3", 1],
    ["1", 1],
    ["abc", 0],
    ["2.6", 5],
  ] as const) {
    for (const direction of [1, -1] as const) {
      draft.push({ draft: text, value, direction, out: wheelDraft(text, { value }, direction) });
    }
  }
  return { wheel, parse, draft };
}

// endregion

/** The card family's files under conformance/view/. */
export function viewFiles(): Record<string, string> {
  const cases = cardCases().map(({ name, input }) => ({
    name,
    input,
    view: cardViewJson(websiteCardView(input), input),
  }));
  return {
    "card/cases.json": viewEnvelope("card", "cases", "six-px", { cases }),
    "card/count.json": viewEnvelope("card", "count", "none", { vectors: countVectors() }),
  };
}

