import type { z } from "zod";
import { parseResourceHandleId } from "@/components/flow/resource-handles";
import { makeResourceKey } from "@/lib/model/resources";
import { listNodeSections } from "@/lib/model/shared-machine";
import { getVoltageTierIndex } from "@/lib/model/tiers";
import type { MachineTier, Recipe, ThroughputResult } from "@/lib/model/types";
import type { RecipeSummary } from "@/lib/datasets/types";
import { queryDatasetResources } from "@/lib/server/dataset-query";
import { planCard } from "./cards";
import { STEAM_LITRES_PER_EU, steamPerTick } from "./power-source";
import { loadRecipe, resolveResource, withDataset, type ResolvedResource } from "./dataset";
import { getDraft, type Draft } from "./drafts";
import { AgentApiError } from "./errors";
import type { expandCandidate, expandedInput, plansExpandInputsRequest, plansExpandInputsResponse } from "./expand-schemas";
import { formatInput, formatOutput, nameOf, secondsOf } from "./format";
import { applyTargets } from "./plans";
import { materialOf, materialPattern, recycles, searchRecipes } from "./recipes";
import { jsonBytes, RESPONSE_AIM_BYTES } from "./size";
import { parsePortKey } from "@/lib/solver/plan/port-key";
import { boundaryFlows, figure, solveProject, type BoundaryFlow } from "./solve";
import { autoConnect } from "./wiring";
import type { AgentConstraints } from "./schemas";

/**
 * plans/expand-inputs: for each input the draft still needs from outside, the recipes that could make it, ranked by
 * trying each one. A candidate is added to a copy of the draft (the board's Auto-connect wires it, targets re-seated)
 * and the copy is solved on the site's plan solver; what changed is what the candidate costs: the inputs it brings in
 * that the draft did not need before (`newRaws`), the power it adds per unit made, the machines it needs. The real
 * draft is never changed.
 *
 * Ranking (docs/agent-api.md, "plans/expand-inputs"): candidates the solve could use first; then fewer new inputs that
 * are made (they need recipes of their own; crafting tools too), then fewer that are nearly raw (ore processing steps,
 * dusts with no ore), then fewer raw ones (ores, ore dusts, water, air gases, `free` resources); then less added to the
 * draft's leftovers (`spareCrafts`); then less EU per unit; then fewer machines; then the search's own order (main
 * product before byproduct, producing before giving back).
 */

type ExpandRequest = z.infer<typeof plansExpandInputsRequest>;
type ExpandResponse = z.infer<typeof plansExpandInputsResponse>;
type Candidate = z.infer<typeof expandCandidate>;
type ExpandedInput = z.infer<typeof expandedInput>;

/** Recipes the search is asked for per input. */
const SEARCH_LIMIT = 12;
/** Candidates trial-solved per input, at most: the best of the search's order after the skips. */
export const TRIALS_PER_INPUT = 6;
/** Trial solves per call, at most; inputs past it are left for the next call (`remaining`). */
export const TRIALS_PER_CALL = 36;
/** Inputs expanded per call, at most. */
export const INPUTS_PER_CALL = 6;
/** No new input is started after this long, so an answer stays well under the 5 s a solve may take. */
export const EXPAND_DEADLINE_MS = 3500;

/** Raw to a player: water, air and its gases, lava, the world's blocks, wood. Ores are matched apart. */
const RAW_NAME =
  /^(?:water|distilled water|salt water|sea ?water|steam|lava|air|compressed air|liquid air|oxygen|nitrogen|argon|helium|neon|carbon dioxide|cobblestone|stone|stone dust|sand|red sand|gravel|dirt|clay|clay ball|clay dust|snow|snowball|ice|netherrack|netherrack dust|end ?stone|end ?stone dust|soul sand|obsidian|flint|coal|charcoal|sugar ?cane|wheat|seeds|.* log|.* wood|.* sapling)(?: gas)?$/i;
/** A step of ore processing (crushed, washed, centrifuged ore, impure dust): made from the ore, not dug up. */
const ORE_STEP_NAME =
  /^(?:crushed|purified|centrifuged|refined) (.+?) ore$|^(?:impure|purified) pile of (.+?) dust$|^(.+?) (?:shard|clump|dirty dust)$/i;
/** An ore block, in any stone ("End Rutile Ore"), or a raw ore dug up. */
const ORE_NAME = /\bore$|^Ore Dictionary: ore/i;
const TOOL_NAME = /craftingTool/i;
const DUST_NAME = /^(?:(?:small|tiny) pile of )?(.+?) dust$/i;

/**
 * How raw a new input is, lowest best: 0 raw to a player (an ore, an ore's dust, water, an air gas, a `free`
 * resource); 1 nearly so (a step of ore processing of a material that has an ore, a plain dust of a material with no
 * ore); 2 made (everything else, crafting tools too: it needs a recipe of its own).
 */
type RawLevel = 0 | 1 | 2;
interface Rawness {
  level: RawLevel;
  label: string;
}

const oreCache = new Map<string, boolean>();
const ORE_CACHE_MAX = 4096;

/**
 * True when the dataset has an ore block of this material ("Rutile Ore"). The dataset has no world generation, so a
 * material with an ore block that never spawns still counts.
 */
async function hasOre(versionId: string, material: string): Promise<boolean> {
  const key = `${versionId}|${material}`;
  const known = oreCache.get(key);
  if (known !== undefined) {
    return known;
  }
  const page = await queryDatasetResources(versionId, { query: `${material} ore`, offset: 0, limit: 10, kind: "item" });
  const found = page.resources.some((resource) => nameOf(resource).toLowerCase() === `${material} ore`);
  if (oreCache.size >= ORE_CACHE_MAX) {
    oreCache.clear();
  }
  oreCache.set(key, found);
  return found;
}

function isFree(resource: { id: string; name: string }, free: Set<string>): boolean {
  return free.has(resource.id.toLowerCase()) || free.has(resource.name.toLowerCase());
}

async function rawness(versionId: string, resource: { id: string; name: string }, free: Set<string>): Promise<Rawness> {
  const name = resource.name;
  if (isFree(resource, free)) {
    return { level: 0, label: "free" };
  }
  if (RAW_NAME.test(name)) {
    return { level: 0, label: "raw" };
  }
  const step = ORE_STEP_NAME.exec(name);
  if (step) {
    const material = (step[1] ?? step[2] ?? step[3]!).toLowerCase();
    return (await hasOre(versionId, material)) ? { level: 1, label: "ore step" } : { level: 2, label: "made" };
  }
  if (ORE_NAME.test(name)) {
    return { level: 0, label: "ore" };
  }
  if (TOOL_NAME.test(name) || TOOL_NAME.test(resource.id)) {
    return { level: 2, label: "tool" };
  }
  const dust = DUST_NAME.exec(name);
  if (dust) {
    return (await hasOre(versionId, dust[1]!.toLowerCase()))
      ? { level: 0, label: "ore dust" }
      : { level: 1, label: "dust, no ore" };
  }
  // Mined under its own name ("Salt" has Salt Ore, as "Coal" and "Redstone" do).
  return (await hasOre(versionId, name.toLowerCase())) ? { level: 0, label: "ore" } : { level: 2, label: "made" };
}

function tierIndex(tier: string): number {
  return getVoltageTierIndex(tier as Exclude<MachineTier, "DEMO">);
}

/**
 * The draft's constraints with the call's: the lower tier cap and heat, banned and free lists joined; the call's
 * `steam` when it gives one, else the draft's.
 */
export function mergeConstraints(
  draft: AgentConstraints | undefined,
  call: AgentConstraints | undefined,
): AgentConstraints | undefined {
  if (!draft || !call) {
    return draft ?? call;
  }
  const union = (a?: string[], b?: string[]) => (a || b ? [...new Set([...(a ?? []), ...(b ?? [])])] : undefined);
  const tierCap =
    draft.tierCap && call.tierCap
      ? tierIndex(draft.tierCap) <= tierIndex(call.tierCap)
        ? draft.tierCap
        : call.tierCap
      : (call.tierCap ?? draft.tierCap);
  const heat = draft.heat !== undefined && call.heat !== undefined ? Math.min(draft.heat, call.heat) : (call.heat ?? draft.heat);
  const merged: AgentConstraints = {
    ...(tierCap ? { tierCap } : {}),
    ...(heat !== undefined ? { heat } : {}),
    ...((call.cleanroom ?? draft.cleanroom) !== undefined ? { cleanroom: call.cleanroom ?? draft.cleanroom } : {}),
    ...((call.lowgrav ?? draft.lowgrav) !== undefined ? { lowgrav: call.lowgrav ?? draft.lowgrav } : {}),
    ...((call.steam ?? draft.steam) !== undefined ? { steam: call.steam ?? draft.steam } : {}),
  };
  const banned = union(draft.banned, call.banned);
  const free = union(draft.free, call.free);
  return { ...merged, ...(banned ? { banned } : {}), ...(free ? { free } : {}) };
}

type Key = ReturnType<typeof makeResourceKey>;

function key(resource: { kind: string; id: string }): Key {
  return makeResourceKey(resource.kind as ResolvedResource["kind"], resource.id);
}

/** What a recipe turns into what, at what power and time, whatever machine runs it. */
function signature(recipe: Recipe): string {
  const side = (slots: { kind: string; id: string; amount: number }[]) =>
    slots
      .map((slot) => `${key(slot)}*${slot.amount}`)
      .sort()
      .join(",");
  return `${side(recipe.inputs.filter((input) => input.consumed !== false))}>${side(recipe.outputs)}@${recipe.eut}x${recipe.durationTicks}`;
}

/** What a resource is regardless of its container or state: "Chlorine Cell", "Chlorine Plasma" and "Chlorine". */
function substance(name: string): string {
  return name.replace(/ (?:cell|plasma)$/i, "").trim().toLowerCase();
}

function consumedKeys(recipe: Recipe): Key[] {
  return recipe.inputs.filter((input) => input.consumed !== false).map(key);
}

/**
 * Why a candidate is not worth trying, or undefined: it takes what it makes (a loop), it undoes the draft's work (it
 * takes a main product the draft makes from the resource, as macerating the ingot back to the dust a furnace card
 * smelts), or its recipe is a card already. One that takes only a byproduct is a use for it, not a reverse.
 */
function skipReason(recipe: Recipe, makes: Key, makesName: string, draft: Draft): string | undefined {
  const takes = consumedKeys(recipe);
  if (takes.includes(makes)) {
    return "loop";
  }
  const stuff = substance(makesName);
  if (recipe.inputs.some((input) => input.consumed !== false && substance(nameOf(input)) === stuff)) {
    return "form";
  }
  if (draft.project.nodes.some((node) => node.recipeId === recipe.id)) {
    return "in draft";
  }
  // What the draft makes from the resource along main products (dust to hot ingot to ingot): a recipe that takes any
  // of it to make the resource turns the draft's own work back.
  const recipes = new Map(draft.project.recipes.map((entry) => [entry.id, entry]));
  const chain = new Set<Key>([makes]);
  const names = new Map<Key, string>();
  for (let grown = true; grown; ) {
    grown = false;
    for (const node of draft.project.nodes) {
      const card = recipes.get(node.recipeId);
      const main = card?.outputs[0] ? key(card.outputs[0]) : undefined;
      if (card && main && !chain.has(main) && consumedKeys(card).some((input) => chain.has(input))) {
        chain.add(main);
        names.set(main, nameOf(card.outputs[0]!));
        grown = true;
      }
    }
  }
  if (takes.some((input) => input !== makes && chain.has(input))) {
    return "reverse";
  }
  // The same by material: taking titanium dust to make the titanium tetrachloride a titanium furnace card takes. Ore
  // forms are exempt: they are where the material comes from.
  const products = new Set(
    [...chain]
      .filter((entry) => entry !== makes)
      .map((entry) => materialOf(names.get(entry) ?? ""))
      .filter((material): material is string => Boolean(material)),
  );
  for (const input of recipe.inputs) {
    const name = nameOf(input);
    const material = materialOf(name);
    if (input.consumed !== false && material && products.has(material) && !ORE_NAME.test(name) && !ORE_STEP_NAME.test(name)) {
      return "reverse";
    }
  }
  return undefined;
}

/**
 * What leaves the plan unused, in runs per second, as the plan solver trades it: each output flow out of the plan that
 * is not a target, over what one run of the recipe that makes it puts out (so 2000 L of a fluid a run and 1 dust a run
 * weigh the same). The solver settles what the plan buys before what it leaves over, so this ranks after the new
 * inputs: a candidate whose byproducts the draft takes, or that takes the draft's spares, leaves less.
 */
function spareCrafts(project: Draft["project"], result: ThroughputResult, targets: Set<Key>): number {
  const recipes = new Map(project.recipes.map((recipe) => [recipe.id, recipe]));
  const nodes = new Map(project.nodes.map((node) => [node.id, node]));
  let total = 0;
  for (const flow of [...(result.plan?.terminals ?? []), ...(result.plan?.forced ?? [])]) {
    const port = parsePortKey(flow.portKey);
    const handle = parseResourceHandleId(port?.handle);
    if (flow.side !== "out" || !port || !handle || !(flow.perSecond > 0)) continue;
    const wanted = key({ kind: handle.kind, id: handle.resourceId });
    if (targets.has(wanted)) continue;
    const node = nodes.get(port.nodeId);
    const section = node ? listNodeSections(node).find((entry) => entry.section === handle.section)?.node : undefined;
    const recipe = section ? recipes.get(section.recipeId) : undefined;
    let perRun = 0;
    for (const output of recipe?.outputs ?? []) {
      if (key(output) === wanted) {
        perRun += output.amount * (output.chance !== undefined && output.chance > 0 && output.chance <= 1 ? output.chance : 1);
      }
    }
    total += flow.perSecond / (perRun > 0 ? perRun : 1);
  }
  return total;
}

interface Trial {
  candidate: Candidate;
  /** Lower is better, compared in order. */
  rank: number[];
}

/** How much of a resource still comes in from outside, per second. */
function boundaryLeft(flows: BoundaryFlow[], wanted: Key): number {
  return flows.find((flow) => flow.side === "in" && key(flow) === wanted)?.perSecond ?? 0;
}

function amount(value: number): string {
  return String(figure(value));
}

function energy(value: number): string {
  const rounded = Math.abs(value) >= 100 ? Math.round(value) : figure(value);
  return rounded.toLocaleString("en-US");
}

function list(names: string[]): string {
  return names.length <= 1 ? (names[0] ?? "") : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

/** The copy of the draft with the candidate's card added and wired, solved. The real draft is not touched. */
async function tryCandidate(
  draft: Draft,
  recipe: Recipe,
  constraints: AgentConstraints | undefined,
): Promise<{
  result: ThroughputResult;
  cardId: string;
  machine: string;
  tier: string;
  project: Draft["project"];
  steamPerTick: number;
}> {
  const planned = planCard(recipe, { recipeId: recipe.id }, constraints, draft.cardTier, draft.minTier);
  const cardId = `c${draft.nextCard}`;
  const known = draft.project.recipes.some((entry) => entry.id === recipe.id);
  const copy: Draft = {
    ...draft,
    targets: [...draft.targets],
    cut: new Set(draft.cut),
    manual: new Set(draft.manual),
    project: {
      ...draft.project,
      recipes: known ? draft.project.recipes : [...draft.project.recipes, recipe],
      nodes: [...draft.project.nodes, { ...planned.node, id: cardId, position: { x: 0, y: 0 } }],
      edges: [...draft.project.edges],
    },
  };
  autoConnect(copy, [cardId]);
  applyTargets(copy);
  const result = await solveProject(copy.project);
  const node = copy.project.nodes.at(-1)!;
  return {
    result,
    cardId,
    machine: planned.handler.label,
    tier: String(planned.node.overclockTier),
    project: copy.project,
    steamPerTick: steamPerTick(recipe, node),
  };
}

interface Base {
  result: ThroughputResult;
  targets: Set<Key>;
  unsupplied: Map<string, BoundaryFlow>;
  byproducts: Map<string, BoundaryFlow>;
  /** `spareCrafts` of the draft as it is. */
  spare: number;
}

async function expandOne(
  draft: Draft,
  base: Base,
  input: BoundaryFlow,
  constraints: AgentConstraints | undefined,
  free: Set<string>,
  max: number,
  trialBudget: number,
): Promise<{ entry: ExpandedInput; trials: number }> {
  const makesKey = key(input);
  const kind = input.kind as ResolvedResource["kind"];
  const search = await searchRecipes({
    makes: { id: input.id, kind },
    constraints,
    limit: SEARCH_LIMIT,
    offset: 0,
    detail: false,
  });
  const material = materialPattern(materialOf(input.name));
  const steamOnly = search.steamOnly ?? 0;
  let skipped = steamOnly;
  const picked: Recipe[] = [];
  const reactions = new Set<string>();
  for (const summary of search.results) {
    if (picked.length >= Math.min(TRIALS_PER_INPUT, trialBudget)) {
      break;
    }
    const recipe = await withDataset(() => loadRecipe(draft.dataset, summary.recipeId));
    // The same reaction on another machine (Chemical Reactor and Large Chemical Reactor) is tried once.
    const reaction = signature(recipe);
    if (reactions.has(reaction) || skipReason(recipe, makesKey, input.name, draft)) {
      skipped += 1;
      continue;
    }
    reactions.add(reaction);
    picked.push(recipe);
  }

  const trials: Trial[] = [];
  let tried = 0;
  for (const [order, recipe] of picked.entries()) {
    let run: Awaited<ReturnType<typeof tryCandidate>>;
    try {
      run = await tryCandidate(draft, recipe, constraints);
    } catch (error) {
      if (error instanceof AgentApiError && error.code !== "internal") {
        skipped += 1;
        continue;
      }
      throw error;
    }
    tried += 1;
    const { result, cardId } = run;
    const flows = boundaryFlows(run.project, result);
    const node = result.nodes[cardId];
    const made = node?.outputs[makesKey]?.amountPerSecond ?? 0;
    const machines = node?.theoreticalMachinesRequired ?? 0;
    const failure = result.plan?.failure;
    const left = boundaryLeft(flows, makesKey);
    // What it takes off the need: usually what the card makes, but a card can also make a step before it redundant.
    const covered = Math.max(0, input.perSecond - left);
    const works = !failure && covered > input.perSecond * 0.01;
    const after = new Map(flows.filter((flow) => flow.side === "in").map((flow) => [key(flow), flow]));
    const newRaws = [...after.values()]
      .filter((flow) => !base.unsupplied.has(key(flow)))
      .map((flow) => ({ id: flow.id, kind: flow.kind, name: flow.name, perSecond: flow.perSecond }));
    const kinds = await Promise.all(newRaws.map((raw) => rawness(draft.dataset, raw, free)));
    const levels = kinds.map((entry) => entry.level);
    const alsoSupplied = [...base.unsupplied.values()].filter(
      (flow) => key(flow) !== makesKey && !after.has(key(flow)),
    );
    const usesByproducts = recipe.inputs
      .filter((entry) => entry.consumed !== false && base.byproducts.has(key(entry)))
      .map((entry) => nameOf(entry));
    const spares = flows
      .filter((flow) => flow.side === "out" && !base.byproducts.has(key(flow)) && !base.targets.has(key(flow)))
      .map((flow) => ({ id: flow.id, kind: flow.kind, name: flow.name, perSecond: flow.perSecond }));
    // What it adds to the draft's leftovers; solver dust is no difference.
    const spareDelta = spareCrafts(run.project, result, base.targets) - base.spare;
    const spareRank = Math.abs(spareDelta) > 1e-6 * Math.max(1, base.spare) ? figure(spareDelta) : 0;
    // Steam cards draw no EU (the site bills litres instead): their steam counts at 2 L per EU so they compare fairly.
    const steamPerSecond = run.steamPerTick * machines * 20;
    const euDelta = result.totalEuT - base.result.totalEuT + (run.steamPerTick * machines) / STEAM_LITRES_PER_EU;
    const euPerItem = works ? (euDelta * 20) / covered : 0;

    const unit = input.kind === "fluid" ? "L" : "item";
    let reason: string;
    if (!works) {
      reason = failure
        ? `The solve fails with it added (${String(failure.message)}).`
        : "The solve does not use it: wired in, it makes none of what the draft needs.";
    } else {
      const parts = [
        made > covered * 0.99
          ? `${amount(made)}/s from ${amount(machines)} ${run.machine} at ${run.tier}, ${energy(euPerItem)} EU per ${unit}.`
          : `Supplies ${amount(covered)}/s by another route (the card makes ${amount(made)}/s) with ${amount(machines)} ${run.machine} at ${run.tier}, ${energy(euPerItem)} EU per ${unit}.`,
      ];
      if (steamPerSecond > 0) {
        parts.push(`Burns ${amount(steamPerSecond)} L/s of steam (counted at 2 L per EU).`);
      }
      if (newRaws.length === 0) {
        parts.push("Needs nothing new from outside.");
      } else {
        const named = newRaws.map((raw, index) => `${raw.name} (${kinds[index]!.label})`);
        parts.push(`Brings in ${list(named)}.`);
      }
      if (usesByproducts.length) {
        parts.push(`Uses the draft's spare ${list(usesByproducts)}.`);
      }
      if (spares.length) {
        const spare = spares.map((flow) => flow.name);
        parts.push(`Leaves spare ${list(spare.slice(0, 4))}${spare.length > 4 ? " and more" : ""}.`);
      }
      if (alsoSupplied.length) {
        parts.push(`Also supplies ${list(alsoSupplied.map((flow) => flow.name))}.`);
      }
      if (covered < input.perSecond * 0.99) {
        parts.push(`Covers only ${Math.round((covered / input.perSecond) * 100)}% of the need.`);
      }
      if (recycles(recipe as unknown as RecipeSummary, material, { id: input.id, kind, name: input.name })) {
        parts.push("It gives back material made elsewhere (recycling).");
      }
      reason = parts.join(" ");
    }
    const count = (level: RawLevel) => levels.filter((entry) => entry === level).length;
    trials.push({
      candidate: {
        recipeId: recipe.id,
        name: recipe.name,
        machine: run.machine,
        tier: run.tier,
        euT: recipe.eut,
        seconds: secondsOf(recipe.durationTicks),
        inputs: recipe.inputs.map((entry) => formatInput(entry, false)),
        outputs: recipe.outputs.map(formatOutput),
        reason,
        newRaws,
        ...(usesByproducts.length ? { usesByproducts } : {}),
        ...(works && spares.length ? { spares } : {}),
        machines: figure(machines),
        euPerItem: figure(euPerItem),
        ...(steamPerSecond > 0 ? { steamPerSecond: figure(steamPerSecond) } : {}),
        ...(works ? {} : { works: false }),
      },
      rank: [works ? 0 : 1, count(2), count(1), count(0), works ? spareRank : 0, works ? euPerItem : 0, machines, order],
    });
  }
  trials.sort((left, right) => {
    for (let index = 0; index < left.rank.length; index += 1) {
      const delta = left.rank[index]! - right.rank[index]!;
      if (delta !== 0) {
        return delta;
      }
    }
    return 0;
  });
  const candidates = trials.slice(0, max).map((trial) => trial.candidate);
  const note =
    candidates.length === 0
      ? search.results.length === 0 && steamOnly > 0
        ? `Only steam or primitive machines make it under these constraints (${steamOnly} recipes): set constraints.steam to see them, or treat it as raw (constraints.free).`
        : search.results.length === 0
        ? "Nothing makes it under these constraints: treat it as raw (constraints.free) or relax tierCap or banned."
        : "Every recipe found was skipped (it takes what it makes, undoes a card, is in the draft or cannot be built)."
      : undefined;
  const raw = (await rawness(draft.dataset, input, free)).level === 0;
  return {
    entry: {
      id: input.id,
      kind: input.kind,
      name: input.name,
      perSecond: input.perSecond,
      ...(raw ? { raw: true } : {}),
      found: search.total,
      candidates,
      ...(skipped ? { skipped } : {}),
      ...(steamOnly ? { steamOnly } : {}),
      ...(note ? { note } : {}),
    },
    trials: tried,
  };
}

export async function expandInputs(body: ExpandRequest): Promise<ExpandResponse> {
  const started = performance.now();
  const draft = getDraft(body.planId);
  if (draft.targets.length === 0) {
    throw new AgentApiError(
      "bad_request",
      "The draft has no target, so it needs nothing yet: set one with plans/set-targets first.",
    );
  }
  const constraints = mergeConstraints(draft.constraints, body.constraints);
  const free = new Set((constraints?.free ?? []).map((entry) => entry.trim().toLowerCase()));

  const result = await solveProject(draft.project);
  const flows = boundaryFlows(draft.project, result);
  const targetKeys = new Set(draft.targets.map(key));
  const base: Base = {
    result,
    targets: targetKeys,
    unsupplied: new Map(flows.filter((flow) => flow.side === "in").map((flow) => [key(flow), flow])),
    byproducts: new Map(
      flows.filter((flow) => flow.side === "out" && !targetKeys.has(key(flow))).map((flow) => [key(flow), flow]),
    ),
    spare: spareCrafts(draft.project, result, targetKeys),
  };

  const accepted: ExpandResponse["accepted"] = [];
  const queue: BoundaryFlow[] = [];
  const unneeded: ExpandedInput[] = [];
  if (body.resources) {
    for (const ref of body.resources) {
      const resource = await withDataset(() => resolveResource(draft.dataset, ref));
      const flow = base.unsupplied.get(key(resource));
      if (!flow) {
        unneeded.push({
          ...resource,
          perSecond: 0,
          found: 0,
          candidates: [],
          note: "The draft does not need it from outside: a card supplies it, or no card takes it.",
        });
      } else if (!queue.includes(flow)) {
        queue.push(flow);
      }
    }
  } else {
    queue.push(...base.unsupplied.values());
  }
  const toExpand = queue.filter((flow) => {
    if (isFree(flow, free)) {
      accepted.push({ id: flow.id, kind: flow.kind, name: flow.name, perSecond: flow.perSecond });
      return false;
    }
    return true;
  });

  const inputs: ExpandedInput[] = [...unneeded];
  let budget = TRIALS_PER_CALL;
  let next = 0;
  while (
    next < toExpand.length &&
    next < INPUTS_PER_CALL &&
    budget > 0 &&
    (next === 0 || performance.now() - started < EXPAND_DEADLINE_MS)
  ) {
    const { entry, trials } = await expandOne(draft, base, toExpand[next]!, constraints, free, body.max, budget);
    budget -= trials;
    inputs.push(entry);
    next += 1;
  }
  // The real draft must come out as it went in: a trial works on a copy.
  getDraft(body.planId);

  const notEnforced = (["cleanroom", "lowgrav"] as const).filter((field) => constraints?.[field] !== undefined);
  const answer = (kept: ExpandedInput[], rest: BoundaryFlow[]): ExpandResponse => {
    const remaining = [
      ...inputs.slice(kept.length).map(({ id, kind, name }) => ({ id, kind, name })),
      ...rest.map(({ id, kind, name }) => ({ id, kind, name })),
    ];
    return {
      dataset: draft.dataset,
      planId: draft.id,
      inputs: kept,
      accepted,
      more: remaining.length > 0,
      ...(remaining.length ? { remaining } : {}),
      ...(notEnforced.length ? { notEnforced: [...notEnforced] } : {}),
    };
  };
  const rest = toExpand.slice(next);
  let kept = inputs;
  let response = answer(kept, rest);
  // Over the 12 KB aim: whole inputs move to `remaining` from the end, keeping at least one.
  while (kept.length > 1 && jsonBytes(response) > RESPONSE_AIM_BYTES) {
    kept = kept.slice(0, -1);
    response = answer(kept, rest);
  }
  return jsonBytes(response) > RESPONSE_AIM_BYTES ? { ...response, truncated: true } : response;
}
