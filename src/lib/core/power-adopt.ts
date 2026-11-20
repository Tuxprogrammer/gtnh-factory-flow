/**
 * The compiled core behind the power bridge (src/lib/power/power-core.ts). Plan code under src/lib/power may not reach
 * the loader, so the core's side lives here: importing this file installs the bridge, and every answer is undefined
 * until the power area is adopted (src/lib/core/adopt.ts) and the core is loaded, when the TypeScript answers instead.
 * One PowerExports.powerCard call returns what a card shows and what the solve reads.
 */
import type { CorePowerCard } from "@/lib/power/power-core";
import { setPowerBridge } from "@/lib/power/power-core";
import type { TurbineFlowCheck } from "@/lib/power/turbine-flow";
import type { PowerFlowLine, PowerStatLine } from "@/lib/power/types";
import { coreFor, isAdopted } from "./adopt";
import { loadCore, moduleState } from "./loader";

type Wire = Record<string, unknown>;

/** A number as the core writes it: non-finite values travel as the strings "Infinity", "-Infinity" and "NaN". */
function num(value: unknown): number {
  return typeof value === "string" ? Number(value) : (value as number);
}

function flowLine(wire: Wire): PowerFlowLine {
  const alternatives = wire.alternatives as PowerFlowLine["alternatives"];
  return {
    name: wire.name as string,
    perSecond: num(wire.perSecond),
    unit: wire.unit as "L" | "item",
    // A flow with none leaves the field out, as the TypeScript sources do.
    ...(alternatives && alternatives.length > 0 ? { alternatives } : {}),
  };
}

function checkOf(wire: Wire | null): TurbineFlowCheck | undefined {
  if (!wire) return undefined;
  return {
    turbines: num(wire.turbines),
    flow: num(wire.flow),
    setFlow: num(wire.setFlow),
    optimal: num(wire.optimal),
    unit: wire.unit as "L/t" | "L/s",
    euMade: num(wire.euMade),
    euCounted: num(wire.euCounted),
  };
}

/**
 * The core's answers by request. A card's answer is a pure function of its source, settings, running count and pin, and a
 * board asks again for the same cards on every re-render, so the text is kept and parsed fresh each time: callers get
 * objects of their own. Dropped whole when the core changes or the table fills.
 */
const ANSWER_LIMIT = 2000;
const answers = new Map<string, string>();
let cachedFor: object | undefined;

/**
 * One power card through the core: its source at the stored `settings`, and, for a turbine card the solve gave
 * `running` turbines' worth (and `pin` whole turbines when its count is Exactly), the flow check.
 */
function card(
  sourceId: string,
  settings: Record<string, string> | undefined,
  running = 0,
  pin?: number,
): CorePowerCard | undefined {
  const core = coreFor("power");
  if (!core) return undefined;
  try {
    const request = JSON.stringify({ settings: settings ?? {}, running, pin: pin ?? null });
    const key = `${sourceId}\n${request}`;
    if (core !== cachedFor) {
      answers.clear();
      cachedFor = core;
    }
    let text = answers.get(key);
    if (text === undefined) {
      const fresh = core.PowerExports.powerCard(sourceId, request);
      if (fresh === null) return undefined;
      if (answers.size >= ANSWER_LIMIT) answers.clear();
      answers.set(key, fresh);
      text = fresh;
    }
    const wire = JSON.parse(text) as Wire;
    const inputs = (wire.inputs as Wire[]).map(flowLine);
    const outputs = (wire.outputs as Wire[]).map(flowLine);
    const warnings = wire.warnings as string[];
    return {
      model: {
        euPerTick: num(wire.euPerTick),
        inputs,
        outputs,
        stats: wire.stats as PowerStatLine[],
        // The sources leave warnings off when there are none.
        ...(warnings.length > 0 ? { warnings } : {}),
      },
      resolved: {
        inputs: (wire.inputs as Wire[]).map((flow) => flow.resolved === true),
        outputs: (wire.outputs as Wire[]).map((flow) => flow.resolved === true),
      },
      recipeStats: wire.recipeStats as PowerStatLine[],
      cardTier: (wire.cardTier as string | null) ?? undefined,
      turbines: num(wire.turbines),
      may: wire.may === true,
      check: checkOf(wire.check as Wire | null),
    };
  } catch {
    // A core that throws is a core that is not there: the TypeScript answers.
    return undefined;
  }
}

/** The Amps dial's tier for an EU/t figure through the core: a name, null for none, undefined when the TypeScript answers. */
function tier(euPerTick: number): string | null | undefined {
  const core = coreFor("power");
  if (!core) return undefined;
  try {
    return core.PowerExports.powerCardTier(euPerTick);
  } catch {
    return undefined;
  }
}

setPowerBridge({ card, tier });

/**
 * Starts loading the core in the background when the power area is adopted. Nothing waits on it: until it is ready every
 * function above answers undefined and the TypeScript runs; a failed load leaves it that way. The two paths give the same
 * numbers, so a board built before the load finishes needs no rebuild after it.
 */
export function warmPowerCore(): void {
  if (!isAdopted("power") || moduleState("core") !== "idle") return;
  loadCore().catch(() => {});
}
