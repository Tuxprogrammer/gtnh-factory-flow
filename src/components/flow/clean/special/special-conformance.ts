/**
 * The special cards' shared cases (conformance/view/special/): a card's facts and its kind's in the core's record shape
 * (`SpecialInput.Case`, jvm/core/core-view `core.view.special`) and the parts the website's own rules make of each
 * (`specialParts` over `cleanCardView`), as the core's `SpecialView` writes them. The core's `SpecialViews` must give the
 * same (`SpecialCasesTest`); the website is held to the file by special-conformance.test.ts. Text is measured six px a
 * character, as the card's cases are.
 *
 * Node only (tests and the generator); never import this from app code.
 */
import { viewEnvelope } from "@/test-support/view-corpus";
import {
  EU,
  MENU,
  cardCases,
  NAME_STEP,
  chipJson,
  clean,
  countJson,
  footJson,
  websiteCardView,
  withCaseDials,
  type CardCaseInput,
  type CardInputJson,
  type CardPortIn,
} from "../card-conformance";
import { specialParts, type SpecialFacts, type SpecialParts } from "./special-parts";

// region The input record (SpecialInput, on the wire)

export interface SpecialInputJson {
  kind: "SHARED" | "POWER" | "CUSTOM_RATE" | "TANK" | "CROP";
  holder?: {
    empty: boolean;
    dial?: { supply: boolean; perSecond: number; kind: string; showMode: boolean; showRate: boolean };
  };
  generator?: {
    name: string;
    tier?: { label: string; fixed: boolean };
    settings: Array<{
      id: string;
      label: string;
      kind: "CHOICE" | "NUMBER" | "TOGGLE";
      choice: string;
      number: number;
      unit?: string;
      on: boolean;
      enabled: boolean;
      pinned: boolean;
    }>;
    readings: Array<{ label: string; value: string; pinned: boolean }>;
    warnings: string[];
  };
}

export interface SpecialCaseInput extends CardCaseInput {
  special: SpecialInputJson;
}

const KIND_IN = {
  SHARED: "shared",
  POWER: "power",
  CUSTOM_RATE: "custom-rate",
  TANK: "tank",
  CROP: "crop",
} as const;

/** A case's special input as the website's facts. */
export function caseSpecialFacts(input: SpecialCaseInput): SpecialFacts {
  const h = input.special.holder;
  const g = input.special.generator;
  return {
    ...(g
      ? {
          generator: {
            name: g.name,
            ...(g.tier ? { tier: { ...g.tier } } : {}),
            settings: g.settings.map((x) => ({
              id: x.id,
              label: x.label,
              kind: x.kind.toLowerCase() as "choice" | "number" | "toggle",
              choice: x.choice,
              number: x.number,
              ...(x.unit !== undefined ? { unit: x.unit } : {}),
              on: x.on,
              enabled: x.enabled,
              pinned: x.pinned,
            })),
            readings: g.readings.map((r) => ({ ...r })),
            warnings: [...g.warnings],
          },
        }
      : {}),
    kind: KIND_IN[input.special.kind],
    ...(h
      ? {
          holder: {
            empty: h.empty,
            ...(h.dial
              ? {
                  dial: {
                    mode: h.dial.supply ? ("supply" as const) : ("request" as const),
                    perSecond: h.dial.perSecond,
                    kind: h.dial.kind,
                    showMode: h.dial.showMode,
                    showRate: h.dial.showRate,
                  },
                }
              : {}),
          },
        }
      : {}),
  };
}

// endregion

// region The cases

const N = "h";

function holderPort(output: boolean, name: string, kind: string, id: string, perSecond: number): CardPortIn {
  return {
    key: `${output ? "output" : "input"}:${id}`,
    handleId: `${output ? "output" : "input"}:${kind}:${encodeURIComponent(id)}`,
    output,
    name,
    kind,
    resourceId: id,
    perSecond,
    wired: true,
    alternatives: 1,
    free: false,
  };
}

/** A custom rate card supplying 4 Iron Ingot a second, Exactly 1 (its dial), or a fluid tank emptying cells. */
function holderCard(tank: boolean): CardInputJson {
  return {
    nodeId: N,
    name: tank ? "Fluid Tank" : "Custom Rate",
    hasPicker: false,
    tierFixed: false,
    hatchControl: false,
    parallel: 1,
    structuralParallel: 1,
    pinned: [],
    inputs: tank ? [holderPort(false, "Oxygen Cell", "item", "oxygen_cell", 2)] : [],
    outputs: tank
      ? [holderPort(true, "Oxygen", "fluid", "oxygen", 2000)]
      : [holderPort(true, "Iron Ingot", "item", "iron_ingot", 4)],
    count: {
      rule: tank ? "ANY" : "EXACT",
      ...(tank ? {} : { pin: 1 }),
      needed: 1,
      nodeParallel: 1,
      customRate: !tank,
      unset: false,
    },
    forced: [],
    leftovers: [],
    cardNames: {},
    selected: false,
    menu: { ...MENU, holder: true },
  };
}

type Dials = CardCaseInput["dials"];
const dials = (rate: Dials["rate"], power = "eu", averageDraw = true): Dials => ({
  rate,
  power,
  averageDraw,
});

export function specialCases(): Array<{ name: string; input: SpecialCaseInput }> {
  const out: Array<{ name: string; input: SpecialCaseInput }> = [];
  const dial = (
    perSecond: number,
    kind = "item",
    supply = true,
    show: { showMode?: boolean; showRate?: boolean } = {},
  ) => ({ supply, perSecond, kind, showMode: show.showMode ?? true, showRate: show.showRate ?? true });
  const custom = (
    name: string,
    holder: NonNullable<SpecialInputJson["holder"]>,
    d: Dials = EU,
    edit: (card: CardInputJson) => void = () => undefined,
  ) => {
    const card = holderCard(false);
    if (holder.empty) card.outputs = [];
    edit(card);
    out.push({ name, input: { dials: d, card, special: { kind: "CUSTOM_RATE", holder } } });
  };
  const tank = (name: string, empty: boolean, d: Dials = EU, edit: (card: CardInputJson) => void = () => undefined) => {
    const card = holderCard(true);
    if (empty) {
      card.inputs = [];
      card.outputs = [];
    }
    edit(card);
    out.push({ name, input: { dials: d, card, special: { kind: "TANK", holder: { empty } } } });
  };

  custom("custom rate: empty, its two sockets and its dial", { empty: true, dial: dial(1) });
  custom("custom rate: empty, requesting", { empty: true, dial: dial(1, "item", false) });
  custom("custom rate: holding an item", { empty: false, dial: dial(4) });
  custom("custom rate: an item per minute", { empty: false, dial: dial(4) }, dials("MINUTE"));
  custom("custom rate: a third a second per tick", { empty: false, dial: dial(1 / 3) }, dials("TICK"));
  custom("custom rate: an item per hour", { empty: false, dial: dial(12.5) }, dials("HOUR"));
  custom("custom rate: the energy reading dial", { empty: false, dial: dial(4) }, dials("EU"));
  custom("custom rate: a fluid per minute", { empty: false, dial: dial(720, "fluid") }, dials("MINUTE"), (c) => {
    c.outputs = [holderPort(true, "Sulfuric Acid", "fluid", "sulfuric_acid", 720)];
  });
  custom("custom rate: a fluid requested", { empty: false, dial: dial(144, "fluid", false) }, EU, (c) => {
    c.inputs = [holderPort(false, "Molten Tin", "fluid", "molten.tin", 144)];
    c.outputs = [];
  });
  for (const power of ["eu", "amps", "HV"]) {
    custom(`custom rate: EU under the ${power} dial`, { empty: false, dial: dial(2560, "power") }, dials("SECOND", power), (c) => {
      c.outputs = [holderPort(true, "EU", "power", "eu", 2560)];
    });
  }
  custom("custom rate: nothing a second", { empty: false, dial: dial(0) });
  custom("custom rate: a rate past a thousandth", { empty: false, dial: dial(0.12345678) });
  custom("custom rate: a large rate", { empty: false, dial: dial(123456789) });
  custom("custom rate: its mode unpinned", { empty: false, dial: dial(4, "item", true, { showMode: false }) });
  custom("custom rate: its dial unpinned", {
    empty: false,
    dial: dial(4, "item", true, { showMode: false, showRate: false }),
  });
  custom("custom rate: nothing sets it", { empty: false, dial: dial(4) }, EU, (c) => {
    c.count = { rule: "ANY", needed: 0, nodeParallel: 1, customRate: true, unset: true };
  });
  custom("custom rate: fed by a forced input", { empty: false, dial: dial(4, "item", false) }, EU, (c) => {
    c.inputs = [holderPort(false, "Iron Ingot", "item", "iron_ingot", 4)];
    c.outputs = [];
    c.forced = [{ portKey: `${N}|i|input:item:iron_ingot`, perSecond: 1.5 }];
  });
  custom("custom rate: empty with a forced input stays bare", { empty: true, dial: dial(4) }, EU, (c) => {
    c.forced = [{ portKey: `${N}|i|input:item:iron_ingot`, perSecond: 1.5 }];
  });
  custom("custom rate: selected", { empty: false, dial: dial(4) }, EU, (c) => {
    c.selected = true;
  });
  tank("fluid tank: empty", true);
  tank("fluid tank: emptying cells", false);
  tank("fluid tank: per minute", false, dials("MINUTE"));
  tank("fluid tank: Up to has no count", false, EU, (c) => {
    c.count = { rule: "UP_TO", pin: 2, needed: 1, nodeParallel: 1, customRate: false, unset: false };
  });

  // Generators: the card's own rows (the card cases' generator), its settings and readings by the plan's pins.
  const generatorCard = cardCases().find((c) => c.name === "generator: EU/t on the EU/t dial")!.input.card;
  type Gen = NonNullable<SpecialInputJson["generator"]>;
  type Setting = Gen["settings"][number];
  const setting = (
    id: string,
    label: string,
    kind: Setting["kind"],
    value: string | number | boolean,
    more: Partial<Setting> = {},
  ): Setting => ({
    id,
    label,
    kind,
    choice: kind === "CHOICE" ? String(value) : "",
    number: kind === "NUMBER" ? Number(value) : 0,
    on: kind === "TOGGLE" ? value === true : false,
    enabled: true,
    pinned: false,
    ...more,
  });
  const settings = (pinned: string[] = [], off: string[] = []): Setting[] =>
    [
      setting("fuel", "Fuel", "CHOICE", "Diesel"),
      setting("efficiency", "Efficiency", "NUMBER", 95, { unit: "%" }),
      setting("rotors", "Rotors", "NUMBER", 1250),
      setting("overclock", "Overclock", "TOGGLE", false),
      setting("boost", "Oxygen boost", "TOGGLE", true),
      setting("flow", "Fuel flow", "NUMBER", 0.125, { unit: "L/s" }),
    ].map((x) => ({ ...x, pinned: pinned.includes(x.id), enabled: !off.includes(x.id) }));
  const readings = (pinned: string[] = []) =>
    [
      { label: "Efficiency", value: "95%" },
      { label: "Fuel burned", value: "4 L/s" },
      { label: "Lifespan", value: "1h 20m" },
    ].map((r) => ({ ...r, pinned: pinned.includes(r.label) }));
  const generator = (
    name: string,
    gen: Partial<Gen>,
    d: Dials = EU,
    edit: (card: CardInputJson) => void = () => undefined,
  ) => {
    const card = JSON.parse(JSON.stringify(generatorCard)) as CardInputJson;
    edit(card);
    out.push({
      name,
      input: {
        dials: d,
        card,
        special: {
          kind: "POWER",
          generator: {
            name: "Combustion Generator",
            tier: { label: "LV", fixed: false },
            settings: settings(),
            readings: readings(),
            warnings: [],
            ...gen,
          },
        },
      },
    });
  };
  generator("generator: nothing pinned", {});
  generator("generator: a choice, numbers with and without a unit, a switch", {
    settings: settings(["fuel", "efficiency", "rotors", "overclock"]),
  });
  generator("generator: a pinned setting switched off by another is greyed", {
    settings: settings(["fuel", "efficiency", "boost"], ["efficiency", "boost"]),
  });
  generator("generator: a fraction reads as JavaScript spells it", { settings: settings(["flow"]) });
  generator("generator: pinned readings after the settings", {
    settings: settings(["fuel"]),
    readings: readings(["Fuel burned", "Lifespan"]),
  });
  generator("generator: a reading alone", { readings: readings(["Efficiency"]) });
  generator("generator: every chip, over two rows", {
    settings: settings(["fuel", "efficiency", "rotors", "overclock", "boost", "flow"]),
    readings: readings(["Efficiency", "Fuel burned", "Lifespan"]),
  });
  generator("generator: a fixed tier unlocks", { tier: { label: "EV", fixed: true } });
  generator("generator: no tier select", { name: "Solar Panel", tier: undefined });
  generator("generator: a long name beside its tier", { name: "Large Gas Turbine (Huge Turbine Rotor)" });
  generator("generator: its warnings", {
    warnings: ["Piped fuel never burns here: only cells do.", "Needs a dynamo hatch of its tier."],
  });
  generator("generator: amps on the Amps dial", { settings: settings(["fuel"]) }, { ...EU, power: "amps" });
  generator("generator: Exactly 2", {}, EU, (c) => {
    c.count = { ...c.count, rule: "EXACT", pin: 2 };
  });
  generator("generator: nothing sets it", {}, EU, (c) => {
    c.count = { ...c.count, unset: true };
  });
  return out;
}

// endregion

// region The parts on the wire (SpecialView)

const KIND_OUT = {
  shared: "SHARED",
  power: "POWER",
  "custom-rate": "CUSTOM_RATE",
  tank: "TANK",
  crop: "CROP",
} as const;

/** The website's parts for a case: its own card builders and special rules, the six-px measure, the case's dials. */
export function websiteSpecialParts(input: SpecialCaseInput): SpecialParts {
  const base = websiteCardView(input);
  return withCaseDials(input, () => specialParts(base, caseSpecialFacts(input)));
}

/** The website's parts as the core's `SpecialView` writes them: its JSON, key for key. */
export function specialPartsJson(parts: SpecialParts): unknown {
  if (parts.sections.length > 0) throw new Error("a shared machine's sections are not in the cases yet");
  return JSON.parse(
    JSON.stringify(
      clean({
        kind: KIND_OUT[parts.kind],
        name: parts.name,
        nameStep: NAME_STEP[parts.nameSize],
        nameTone: parts.nameTone.toUpperCase(),
        hasPicker: parts.hasPicker,
        tier: parts.tier,
        amps: parts.amps,
        empty: parts.empty,
        sections: [],
        power: parts.power,
        sockets: parts.sockets,
        gauge: parts.gauge,
        count: parts.count ? countJson(parts.count) : undefined,
        foot: parts.foot ? footJson(parts.foot) : undefined,
        chips: parts.chips.map((c) =>
          clean({
            chip: chipJson(c.chip),
            edit: c.edit ? { text: c.edit.text, scale: c.edit.scale, notes: c.edit.notes } : undefined,
            off: c.off === true,
          }),
        ),
        stripHeight: parts.stripHeight,
        warnings: parts.warnings,
        cannotRun: parts.cannotRun,
        unset: parts.unset,
        pick: parts.pick,
        cropKey: parts.cropKey,
      }),
    ),
  );
}

// endregion

/** The special family's files under conformance/view/. */
export function viewFiles(): Record<string, string> {
  const cases = specialCases().map(({ name, input }) => ({
    name,
    input,
    view: specialPartsJson(websiteSpecialParts(input)),
  }));
  return { "special/cases.json": viewEnvelope("special", "cases", "six-px", { cases }) };
}
