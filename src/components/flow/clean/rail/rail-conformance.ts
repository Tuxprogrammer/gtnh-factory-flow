/**
 * The cases of `conformance/view/`: what the rail and the notices bar print for a plan, as input records and the view
 * the website's own code makes of them. The Java core (`core-view`) must give the same view for the same input. This
 * module only builds the cases (never imported by app code); `tools/conformance/view/generate.ts` writes them and
 * `rail-conformance.test.ts` keeps the files honest.
 *
 * Three groups: the mock plans of the solver's fixtures read through the rail's own adapters at several views (so the
 * adapters are covered too, not only the pure code), hand-written records for each rule the mocks do not reach, and
 * the arithmetic vectors of `rail-math.ts` and the power dial.
 */
import {
  mockPlans,
  mockUnneeded,
  type MockPlan,
} from "@/lib/solver/plan/__fixtures__/mock-extras";
import type { PlanNote, PlanSolveExtras } from "@/lib/solver/plan/types";
import type { ThroughputResult } from "@/lib/model/types";
import { peakCount } from "@/lib/model/peak-count";
import { GT_VOLTAGE_TIERS } from "@/lib/model/tiers";
import { noticeInput } from "./notices";
import {
  noticeList,
  type CoreNotice,
  type NoticeFailure,
  type NoticeInput,
  type NoticeNote,
} from "./notice-core";
import { railSource } from "./rail-model";
import { spareInputs } from "./spare-model";
import {
  dialDivisor,
  dialSuffix,
  dialValue,
  railViewOf,
  type RailInBearer,
  type RailInDrawer,
  type RailInFlow,
  type RailInMachine,
  type RailInPort,
  type RailInSpare,
  type RailInput,
  type RailViewJson,
} from "./rail-core";
import {
  allAt,
  evenShares,
  nudged,
  parseRateInput,
  sameShares,
  shareAtX,
  steppedRate,
  wheelStep,
} from "./rail-math";

export interface RailCase {
  name: string;
  input: RailInput;
  view: RailViewJson;
}

export interface NoticeCase {
  name: string;
  input: NoticeInput;
  notices: CoreNotice[];
}

type View = RailInput["view"];

const VIEWS: Array<[string, View]> = [
  ["peak eu", { filter: "", peak: true, power: "eu" }],
  ["average eu", { filter: "", peak: false, power: "eu" }],
  ["peak amps", { filter: "", peak: true, power: "amps" }],
  ["peak HV", { filter: "", peak: true, power: "HV" }],
  ["filter iron", { filter: "iron", peak: true, power: "eu" }],
  ["filter none", { filter: "zzzz", peak: false, power: "eu" }],
];

/** A plan's rail input as the adapters read it, spares included. */
export function mockRailInput(mock: MockPlan, view: View): RailInput {
  const { input, machines } = railSource(mock.project, mock.result, view);
  const { spares } = spareInputs(mock.project, mock.result, { machines });
  return { ...input, spares };
}

// region Hand-written rail inputs

const port = (over: Partial<RailInPort> & Pick<RailInPort, "output" | "key">): RailInPort => {
  const colon = over.key.indexOf(":");
  return {
    kind: over.key.slice(0, colon),
    id: over.key.slice(colon + 1),
    label: over.key.slice(colon + 1),
    perSecond: 1,
    handle: `${over.output ? "o" : "i"}-${over.key}`,
    wired: false,
    set: 0,
    ...over,
  };
};

const flow = (card: string, ports: RailInPort[], over: Partial<RailInFlow> = {}): RailInFlow => ({
  card,
  machine: "Mixer",
  machines: 1,
  utilization: 1,
  ports,
  ...over,
});

const drawer = (over: Partial<RailInDrawer> & Pick<RailInDrawer, "id" | "role">): RailInDrawer => ({
  kind: "item",
  resourceId: over.id,
  label: over.id,
  rule: "ignore",
  set: false,
  rate: 0,
  unmet: false,
  eaten: 0,
  ...over,
});

const machine = (over: Partial<RailInMachine> & Pick<RailInMachine, "nodeId">): RailInMachine => ({
  label: "Mixer",
  count: 1,
  tank: false,
  pinned: false,
  rule: "ignore",
  multiblock: false,
  state: "ok",
  ...over,
});

const bearer = (
  over: Partial<RailInBearer> & Pick<RailInBearer, "key" | "nodeId" | "output">,
): RailInBearer => ({
  handle: `h-${over.key}`,
  name: "Iron Ingot",
  machine: "Mixer",
  resource: "item:iron",
  max: 4,
  solverPick: false,
  energy: false,
  share: 0.5,
  ports: [{ nodeId: over.nodeId, handle: `h-${over.key}` }],
  ...over,
});

const base = (over: Partial<RailInput> = {}): RailInput => ({
  answered: true,
  aliases: {},
  flows: [],
  drawers: [],
  machines: [],
  names: {},
  spares: [],
  cards: [],
  wires: [],
  view: { filter: "", peak: true, power: "eu" },
  ...over,
});

const withView = (input: RailInput, view: Partial<View>): RailInput => ({
  ...input,
  view: { ...input.view, ...view },
});

function powerCase(): RailInput {
  return base({
    flows: [
      flow("a", [port({ output: true, key: "item:plate", perSecond: 2, wired: true })], {
        machines: 2.5,
        utilization: 0.4,
      }),
    ],
    machines: [
      machine({
        nodeId: "a",
        label: "Bender",
        count: 2.5,
        tier: "MV",
        amps: 2,
        euT: 240,
        avgEuT: 96,
      }),
      machine({
        nodeId: "g",
        label: "Steam Turbine",
        count: 1,
        tier: "HV",
        madeEuT: 1024,
        avgMadeEuT: 512,
        madeTier: "EV",
        multiblock: true,
        state: "ok",
      }),
      machine({
        nodeId: "b",
        label: "Boiler",
        count: 0.5,
        steamLs: 600,
        avgSteamLs: 300,
      }),
      machine({
        nodeId: "c",
        label: "Centrifuge",
        count: 3,
        tier: "LV",
        state: "under-powered",
        euT: 99,
        avgEuT: 99,
      }),
      machine({ nodeId: "t", label: "Fluid Tank", count: 1, tank: true }),
      machine({ nodeId: "d", label: "Dehydrator", count: 1, euT: 0, avgEuT: 0, tier: "DEMO" }),
      machine({ nodeId: "e", label: "Mystery", count: 1 }),
    ],
  });
}

function setterCase(): RailInput {
  return base({
    aliases: { "item:log_alt": "item:log", "item:plank_a": "item:plank" },
    flows: [
      flow(
        "m",
        [
          port({ output: true, key: "item:plank_a", label: "Plank", perSecond: 6, set: 3 }),
          port({ output: true, key: "item:sawdust", label: "Sawdust", perSecond: 2, wired: true }),
          port({ output: false, key: "item:log_alt", label: "Log", perSecond: 4, wired: false }),
          port({ output: false, key: "item:iron", label: "Iron Ingot", perSecond: 9, wired: true }),
        ],
        { machine: "Saw", machines: 2, utilization: 0.5 },
      ),
      flow(
        "n",
        [
          port({ output: true, key: "item:iron", label: "Iron Ingot", perSecond: 9, wired: true }),
          port({ output: false, key: "item:coal", label: "Coal", perSecond: 1, set: 0.5 }),
          port({ output: false, key: "fluid:water", label: "Water", perSecond: 100 }),
        ],
        { machine: "Furnace", machines: 1, utilization: 1 },
      ),
      flow("z", [port({ output: false, key: "item:ghost", perSecond: 7 })], {
        machine: "Idle",
        machines: 0,
      }),
    ],
    drawers: [
      drawer({
        id: "d-log",
        role: "source",
        resourceId: "log",
        label: "Log",
        rule: "at-most",
        set: true,
        rate: 12,
      }),
      drawer({ id: "d-log2", role: "source", resourceId: "log_alt", label: "Log", unmet: true }),
      drawer({
        id: "d-plank",
        role: "product",
        resourceId: "plank",
        label: "Plank",
        rule: "exact",
        set: true,
        rate: 6,
        unmet: true,
      }),
      drawer({ id: "d-dust", role: "byproduct", resourceId: "sawdust", label: "Sawdust" }),
      drawer({
        id: "d-trash",
        role: "trash",
        resourceId: "sawdust",
        label: "Sawdust",
        eaten: 0.5,
      }),
      drawer({ id: "d-gold", role: "source", resourceId: "gold", label: "Gold", rule: "at-least" }),
      drawer({
        id: "d-void",
        role: "other",
        resourceId: "void",
        label: "Void",
      }),
    ],
    names: { "item:log": "Any Log" },
    machines: [
      machine({
        nodeId: "m",
        label: "Saw",
        count: 2,
        pinned: true,
        rule: "at-most",
        pin: 3,
        upTo: { reason: "STARVED", reached: 1.5, capacity: 3 },
        euT: 30,
        avgEuT: 15,
        tier: "LV",
      }),
      machine({
        nodeId: "n",
        label: "Furnace",
        count: 1,
        pinned: true,
        rule: "exact",
        pin: 1,
        upTo: { reached: 0.25, capacity: 1 },
        euT: 8,
        avgEuT: 8,
        tier: "ULV",
      }),
    ],
  });
}

function sparesCase(): RailInput {
  const two = (key: string, importing: boolean, extra: Partial<RailInSpare> = {}): RailInSpare => ({
    key,
    importing,
    custom: false,
    bearers: [
      bearer({ key: `${key}|late`, nodeId: "c3", output: !importing, share: 0.25, max: 8 }),
      bearer({ key: `${key}|early`, nodeId: "c1", output: !importing, share: 0.75, max: 4 }),
      bearer({
        key: `${key}|mid`,
        nodeId: "c2",
        output: importing,
        name: "Steel Plate",
        resource: "item:steel",
        share: 0,
        max: 2,
        solverPick: true,
      }),
    ],
    ...extra,
  });
  return base({
    aliases: { "item:iron_alias": "item:iron" },
    cards: ["c1", "c2", "c3", "lone"],
    wires: [
      { from: "c1", to: "c2" },
      { from: "c2", to: "c3" },
      { from: "nowhere", to: "c1" },
    ],
    flows: [
      flow("c1", [port({ output: false, key: "item:iron", perSecond: 12, wired: true })], {
        machine: "A",
      }),
    ],
    spares: [
      two("item:iron", true),
      two("item:iron|out", false, {
        stored: { "item:iron|out|late": 3, "item:iron|out|early": 1, "item:iron|out|mid": 0 },
      }),
      {
        key: "item:solo",
        importing: true,
        custom: true,
        bearers: [bearer({ key: "solo|1", nodeId: "lone", output: false, share: 1 })],
      },
      {
        key: "item:unplaced",
        importing: false,
        custom: false,
        bearers: [
          bearer({ key: "u|1", nodeId: "gone", output: true, share: 0.5, resource: "item:iron_alias" }),
          bearer({
            key: "u|2",
            nodeId: "c1",
            output: true,
            share: 0.5,
            energy: true,
            resource: "power:eu",
            name: "Energy",
          }),
        ],
        stored: { "u|1": 0, "u|2": 0 },
      },
      { key: "item:none", importing: true, custom: false, bearers: [] },
    ],
  });
}

function handCases(): RailCase[] {
  const cases: Array<[string, RailInput]> = [
    ["empty plan", base({ answered: true })],
    ["plan with no answer", base({ answered: false, flows: setterCase().flows, drawers: setterCase().drawers })],
    ["power: made, steam, too low, tank, dials peak eu", powerCase()],
    ["power: average amps", withView(powerCase(), { peak: false, power: "amps" })],
    ["power: peak amps", withView(powerCase(), { power: "amps" })],
    ["power: tier dial EV", withView(powerCase(), { power: "EV" })],
    ["power: filter on machine name", withView(powerCase(), { filter: "  BOIL " })],
    ["power: filter with no machine", withView(powerCase(), { filter: "nothing" })],
    ["setters and open ports", setterCase()],
    ["setters, filter on a renamed line", withView(setterCase(), { filter: "ANY LOG" })],
    ["setters, filter with spaces", withView(setterCase(), { filter: " plank " })],
    ["setters, average amps", withView(setterCase(), { peak: false, power: "amps" })],
    ["setters, a host that sets open ports keeps them at zero", withView(setterCase(), { keepOpen: true })],
    [
      "setters, keep open ports with a filter",
      withView(setterCase(), { keepOpen: true, filter: "ghost" }),
    ],
    ["spares with a stored split and depths", sparesCase()],
    ["spares, filter on a place's name", withView(sparesCase(), { filter: "steel" })],
    ["spares, filter that hides them", withView(sparesCase(), { filter: "zzz" })],
  ];
  return cases.map(([name, input]) => ({ name, input, view: railViewOf(input) }));
}

// endregion

export function railCases(): RailCase[] {
  const cases: RailCase[] = [];
  for (const mock of mockPlans) {
    for (const [label, view] of VIEWS) {
      const input = mockRailInput(mock, view);
      cases.push({ name: `${mock.name}: ${label}`, input, view: railViewOf(input) });
    }
  }
  return [...cases, ...handCases()];
}

// region Notices

const note = (message: PlanNote["message"], ...args: PlanNote["args"]): PlanNote => ({
  message,
  args,
});

const withExtras = (mock: MockPlan, extras: Partial<PlanSolveExtras>): ThroughputResult => ({
  ...mock.result,
  plan: { ...mock.result.plan!, ...extras },
});

const blank: NoticeNote = {
  type: "UNMET",
  id: "",
  label: "",
  resource: "item",
  supply: false,
  exact: false,
  asked: 0,
  reached: 0,
  made: 0,
  target: 0,
  hasShortfall: false,
  holders: [],
  subject: "",
  focus: [],
};

const noteOf = (over: Partial<NoticeNote>): NoticeNote => ({ ...blank, ...over });

const failure = (kind: string, names: string[] = [], focus: string[] = []): NoticeFailure => ({
  kind,
  names,
  focus,
});

function handNoticeCases(): Array<[string, NoticeInput]> {
  const some = (over: Partial<NoticeInput>): NoticeInput => ({
    hasNodes: true,
    loops: [],
    notes: [],
    ...over,
  });
  return [
    ["nothing to say", some({})],
    ["no pin on a plan with cards", some({ failure: failure("NO_PIN") })],
    ["no pin on an empty plan", some({ failure: failure("NO_PIN"), hasNodes: false })],
    ["empty graph", some({ failure: failure("EMPTY_GRAPH") })],
    [
      "pin conflict names one",
      some({ failure: failure("PIN_CONFLICT", ["Saw"], ["a"]) }),
    ],
    [
      "pin conflict names three",
      some({ failure: failure("PIN_CONFLICT", ["Saw", "Mixer", "Kiln"], ["a", "b"]) }),
    ],
    ["cannot run", some({ failure: failure("CANNOT_RUN") })],
    ["no way", some({ failure: failure("NO_WAY") })],
    ["unknown failure", some({ failure: failure("FAILED") })],
    [
      "loops of one and many cards, a loop with none",
      some({
        loops: [
          { kind: "DEAD_LOOP", cards: ["a"] },
          { kind: "CLOG_LOCK", cards: ["b", "c"] },
          { kind: "DEAD_LOOP", cards: [] },
          { kind: "CLOG_LOCK", cards: ["d"] },
          { kind: "DEAD_LOOP", cards: ["e", "f"] },
        ],
      }),
    ],
    [
      "unmet in every wording",
      some({
        notes: [
          noteOf({ id: "s1", label: "Iron", supply: true, asked: 10, reached: 6, resource: "item" }),
          noteOf({ id: "s2", label: "Iron", supply: true, exact: true, asked: 10, reached: 6 }),
          noteOf({ id: "s3", label: "Gold", supply: true, asked: 4, reached: 7 }),
          noteOf({ id: "d1", label: "Plate", asked: 10, reached: 6, resource: "item" }),
          noteOf({ id: "d2", label: "Plate", exact: true, asked: 10, reached: 6 }),
          noteOf({ id: "d3", label: "Rod", asked: 4, reached: 7 }),
          noteOf({ id: "f1", label: "Water", asked: 1500, reached: 900, resource: "fluid" }),
          noteOf({ id: "p1", label: "Power", asked: 4096, reached: 1000, resource: "power" }),
        ],
      }),
    ],
    [
      "unmet with holders of every kind",
      some({
        notes: [
          noteOf({
            id: "h",
            label: "Steel",
            asked: 10,
            reached: 4,
            hasShortfall: true,
            holders: [
              { kind: "exact", name: "Saw" },
              { kind: "full", name: "Mixer" },
              { kind: "starved", name: "Kiln" },
              { kind: "clogged", name: "Press" },
              { kind: "unused", name: "Cutter" },
              { kind: "limited", name: "Bender" },
              { kind: "drawer", name: "Coal" },
            ],
            focus: ["h", "a", "b"],
          }),
          noteOf({
            id: "h2",
            label: "Steel",
            supply: true,
            asked: 10,
            reached: 4,
            hasShortfall: true,
            holders: [],
          }),
        ],
      }),
    ],
    [
      "overshoot, port taken, unwired, ignored, choice",
      some({
        notes: [
          noteOf({
            type: "OVERSHOOT",
            id: "m:Plate",
            label: "Plate",
            subject: "Bender",
            made: 12,
            target: 8,
            resource: "item",
            focus: ["m"],
          }),
          noteOf({
            type: "OVERSHOOT",
            id: "m:Oil",
            label: "Oil",
            subject: "Cracker",
            made: 1200,
            target: 1000,
            resource: "fluid",
            focus: ["m"],
          }),
          noteOf({ type: "PORT_TAKEN", id: "p1", label: "Iron", supply: true, asked: 3 }),
          noteOf({ type: "PORT_TAKEN", id: "p2", label: "Iron", supply: true, exact: true, asked: 3 }),
          noteOf({ type: "PORT_TAKEN", id: "p3", label: "Plate", asked: 3 }),
          noteOf({ type: "PORT_TAKEN", id: "p4", label: "Plate", exact: true, asked: 3, resource: "fluid" }),
          noteOf({ type: "NOT_CONNECTED", id: "u1", label: "Coal", focus: ["u1"] }),
          noteOf({ type: "WIRED_IGNORED", id: "w1", label: "Sand", focus: ["w1", "x"] }),
          noteOf({ type: "CHOICE" }),
          noteOf({ type: "SOMETHING_ELSE", id: "q" }),
        ],
      }),
    ],
    [
      "the order: failure, alarms, warnings, then the rest",
      some({
        failure: failure("NO_WAY"),
        loops: [{ kind: "DEAD_LOOP", cards: ["a"] }],
        notes: [
          noteOf({ type: "CHOICE" }),
          noteOf({ id: "s1", label: "Iron", supply: true, asked: 10, reached: 6 }),
          noteOf({ type: "NOT_CONNECTED", id: "u1", label: "Coal" }),
        ],
      }),
    ],
    [
      "the same sentence twice is said once",
      some({
        notes: [
          noteOf({ id: "a", label: "Iron", asked: 10, reached: 6 }),
          noteOf({ id: "b", label: "Iron", asked: 10, reached: 6 }),
          noteOf({ id: "c", label: "Iron", asked: 10, reached: 7 }),
          noteOf({ type: "CHOICE" }),
          noteOf({ type: "CHOICE" }),
        ],
      }),
    ],
  ];
}

function mockNoticeCases(): Array<[string, NoticeInput]> {
  const out: Array<[string, NoticeInput]> = [];
  for (const mock of mockPlans) {
    const input = noticeInput(mock.project, mock.result);
    if (input) out.push([`${mock.name}: as solved`, input]);
  }
  const unmet = note("DRAWER_UNMET", "Steel Plate", note("RULE_EXACTLY"), 10, 6);
  const extras: Array<[string, Partial<PlanSolveExtras>]> = [
    ["exact unmet", { notes: [unmet] }],
    [
      "overshoot",
      {
        notes: [note("OVERSHOOTS_TARGET", "Compressor", "Steel Plate", 12, 8, "compressor")],
      },
    ],
    ["failure no pin", { failure: note("NO_PIN") }],
    ["failure empty graph", { failure: note("EMPTY_GRAPH") }],
    ["failure cannot run", { failure: note("MACHINES_CANNOT_RUN") }],
    ["failure no way", { failure: note("SOLVER_UNSATISFIABLE") }],
    ["failure unknown", { failure: note("STAGE_FAILED_UNKNOWN" as PlanNote["message"]) }],
    ["choice no longer fits", { notes: [note("CHOICE_NO_LONGER_FITS")] }],
  ];
  for (const [label, extra] of extras) {
    const input = noticeInput(mockUnneeded.project, withExtras(mockUnneeded, extra));
    if (input) out.push([`${mockUnneeded.name}: ${label}`, input]);
  }
  return out;
}

export function noticeCases(): NoticeCase[] {
  return [...handNoticeCases(), ...mockNoticeCases()].map(([name, input]) => ({
    name,
    input,
    notices: noticeList(input),
  }));
}

// endregion

// region Arithmetic vectors

const TIERS = GT_VOLTAGE_TIERS.map((entry): string => entry.tier);

export function mathVectors() {
  const orNull = (n: number | undefined) => (n === undefined ? null : n);
  const rates = [
    "2.5k", "1M", "1/3", "10k/3", "3,200", ".5", "  7  ", "−4", "-4", "+4", "1g", "2G", "5K", "1/0", "1/", "/2",
    "", "   ", "abc", "1e3", "1.2.3", "0", "0/5", "1k/1k", "1,0/2", "1 k", "9999999999999g", "- 4", "4.", "1/2/3",
  ];
  const shares: Array<[number[], number, number]> = [
    [[0.5, 0.5], 0, 0.1],
    [[0.5, 0.5], 1, -0.2],
    [[0.25, 0.25, 0.5], 2, 0.2],
    [[0.25, 0.25, 0.5], 0, 1],
    [[1, 0, 0], 0, -0.5],
    [[1, 0, 0], 1, 0.3],
    [[0, 0, 1], 2, 0],
    [[0.2, 0.3, 0.5], 1, -1],
    [[1], 0, 0.5],
    [[0.6, 0.4], 0, 0.5],
  ];
  const pairs: Array<[number[], number[]]> = [
    [[0.5, 0.5], [0.5, 0.5]],
    [[0.5, 0.5], [0.5, 0.5000000001]],
    [[0.5, 0.5], [0.6, 0.4]],
    [[0.5, 0.5], [0.5, 0.25, 0.25]],
    [[], []],
  ];
  const presses: Array<[number, number, number]> = [
    [50, 0, 100], [0, 0, 100], [100, 0, 100], [150, 0, 100], [-20, 0, 100], [33, 10, 100], [51, 0, 100],
    [10, 0, 0], [10, 0, -5], [25.4, 0, 100], [75, 25, 100],
  ];
  const steps: Array<[number, 1 | -1, boolean, boolean]> = [
    [5, 1, false, false], [5, -1, false, false], [0, -1, false, false], [0.4, -1, false, false], [2.5, 1, false, false],
    [3.5, 1, false, false], [-2.5, 1, false, false], [5, 1, true, false], [5, -1, true, false], [5, 1, false, true],
    [5, -1, false, true], [5, 1, true, true], [99.5, -1, false, true], [1e6, 1, false, false],
  ];
  const peaks: Array<[number, number | undefined, boolean]> = [
    [0.25, undefined, false], [1.5, undefined, false], [2, undefined, false], [2.0000000001, undefined, false],
    [2.000001, undefined, false], [0, undefined, false], [-0.5, undefined, false], [0.25, 0, false], [0.25, -1, false],
    [0.25, 1, false], [1.5, 1, false], [1.5, 3, false], [3, 3, false], [0.25, undefined, true], [1.5, undefined, true],
    [1.5, 3, true], [0, 2, true], [0, 2, false], [1e-10, undefined, false], [100.4, undefined, false],
  ];
  const dials: Array<[string, string | undefined]> = [];
  for (const unit of ["eu", "amps", ...TIERS]) {
    dials.push([unit, undefined]);
    for (const tier of [...TIERS, "DEMO", "bogus"])
      if (unit === "amps" || tier === "HV") dials.push([unit, tier]);
  }
  const euts = [0, 1, 32, 480, 2000, 1e6];
  return {
    parseRateInput: rates.map((text) => ({ text, value: orNull(parseRateInput(text)) })),
    nudged: shares.map(([s, index, step]) => ({ shares: s, index, step, out: nudged(s, index, step) })),
    evenShares: [0, 1, 2, 3, 7].map((count) => ({ count, out: evenShares(count) })),
    allAt: [[0, 0], [1, 0], [3, 0], [3, 1], [3, 2], [3, 5]].map(([count, index]) => ({
      count: count!,
      index: index!,
      out: allAt(count!, index!),
    })),
    shareAtX: presses.map(([x, left, width]) => ({ x, left, width, out: shareAtX(x, left, width) })),
    sameShares: pairs.map(([a, b]) => ({ a, b, out: sameShares(a, b) })),
    wheelStep: [false, true].flatMap((ctrl) =>
      [false, true].map((shift) => ({ ctrl, shift, out: wheelStep(ctrl, shift) })),
    ),
    steppedRate: steps.map(([shown, direction, ctrl, shift]) => ({
      shown,
      direction,
      ctrl,
      shift,
      out: steppedRate(shown, direction, ctrl, shift),
    })),
    peakCount: peaks.map(([solved, set, keepSolved]) => ({
      solved,
      set: orNull(set),
      keepSolved,
      out: peakCount(solved, set, keepSolved),
    })),
    dial: dials.flatMap(([unit, tier]) =>
      euts.map((euT) => ({
        unit,
        ownTier: tier ?? null,
        euT,
        divisor: orNull(dialDivisor(unit, tier)),
        value: dialValue(euT, unit, tier),
        suffix: dialSuffix(unit, tier),
      })),
    ),
  };
}

// endregion

/** The files of `conformance/view/` as the code makes them now, by name. */
export function viewFiles(): Record<string, string> {
  const text = (value: unknown) => `${JSON.stringify(value, null, 1)}\n`;
  return {
    "rail-cases.json": text(railCases()),
    "notice-cases.json": text(noticeCases()),
    "rail-math.json": text(mathVectors()),
  };
}
