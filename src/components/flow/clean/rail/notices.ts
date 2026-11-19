import { formatCompact } from "@/lib/model/resources";
import { getStorageRoles } from "@/lib/model/storage-role";
import type { FactoryProject, ResourceKind, ThroughputResult } from "@/lib/model/types";
import { rateMultiplierForKind, rateSuffixForKind } from "@/lib/model/rate-unit";
import { pinRuleOf } from "@/lib/model/pin-rule";
import type { LoopTone } from "../loops";
import type { Note, NoteArg, PlanNote, PlanShortfall } from "@/lib/solver/plan/types";
import {
  byUrgencyOf,
  noticeList,
  noticeTag,
  rankOf,
  noticeText,
  type NoticeHolder,
  type NoticeFailure,
  type NoticeInput,
  type NoticeLoop,
  type NoticeNote,
} from "./notice-core";
import { splitResultNodeId } from "./rail-model";

/**
 * The notices bar's list: the solver's notes in the mod's words
 * (`BoardSession.buildNotices`), most important first. Each is
 * `{ severity, text, focus }` where `focus` are the card and drawer ids
 * "Show me" frames. Pure; the sentences are the catalogue of the UI spec
 * section 11.2, exactly.
 */

export type NoticeSeverity = "error" | "warn" | "info";

export interface BoardNotice {
  /** Stable across solves while the same sentence stands (keys the per-notice "Show me" counter). */
  id: string;
  severity: NoticeSeverity;
  text: string;
  focus: string[];
  /** A notice the player can put away (the plan conversion's): the row shows a Dismiss key. */
  dismiss?: () => void;
  /** An alarm's name ("DEAD LOOP"): a chip before the sentence, blinking on the prompts' clock. */
  tag?: string;
  /** The ring an alarm is about: its row and chip take the ring's tone, as its cards' outlines do. */
  loop?: LoopTone;
}

/** Where a notice goes in the list, lowest first: a failed solve, then alarms, then warnings, then the rest. */
export function noticeRank(notice: Pick<BoardNotice, "severity" | "tag">): number {
  return rankOf(notice);
}

/** Most pressing first; notices of one rank keep their order. */
export function byUrgency<T extends Pick<BoardNotice, "severity" | "tag">>(
  list: readonly T[],
): T[] {
  return byUrgencyOf(list);
}

function isNote(arg: NoteArg | undefined): arg is Note {
  return typeof arg === "object" && arg !== null && !Array.isArray(arg);
}

/** Every note of a kind inside a note's arguments, nested notes and lists included. */
function collect(arg: NoteArg | PlanNote, kind: string, into: Note[]): void {
  if (Array.isArray(arg)) {
    for (const entry of arg) collect(entry, kind, into);
    return;
  }
  if (!isNote(arg)) return;
  if (arg.message === kind) into.push(arg);
  for (const nested of arg.args) collect(nested, kind, into);
}

function contains(note: Note, ...kinds: string[]): boolean {
  return kinds.some((kind) => {
    const found: Note[] = [];
    collect(note, kind, found);
    return found.length > 0;
  });
}

const text = (arg: NoteArg | undefined): string =>
  typeof arg === "string" || typeof arg === "number" ? String(arg) : "";
const num = (arg: NoteArg | undefined): number => (typeof arg === "number" ? arg : 0);

const RULE_BY_NOTE: Record<string, "at-least" | "exact" | "at-most" | "ignore"> = {
  RULE_AT_LEAST: "at-least",
  RULE_EXACTLY: "exact",
  RULE_AT_MOST: "at-most",
  RULE_ANY: "ignore",
};

/** Reads the solver's notes into the records the list is worked out from (`notice-core.ts`). */
export function noticeInput(
  project: FactoryProject,
  result: ThroughputResult | undefined,
): NoticeInput | undefined {
  const extras = result?.plan;
  if (!extras) return undefined;
  const nodeIds = new Set(project.nodes.map((n) => n.id));
  const storages = project.storages ?? [];
  const known = new Set([...nodeIds, ...storages.map((s) => s.id)]);
  const idsIn = (note: Note): string[] => {
    const ids: string[] = [];
    const walk = (arg: NoteArg) => {
      if (Array.isArray(arg)) arg.forEach(walk);
      else if (isNote(arg)) arg.args.forEach(walk);
      else if (typeof arg === "string") {
        const { cardId } = splitResultNodeId(arg);
        if (known.has(cardId)) ids.push(cardId);
      }
    };
    note.args.forEach(walk);
    return [...new Set(ids)];
  };

  const failure = extras.failure ? failureOf(extras.failure, idsIn) : undefined;

  const recipeOf = new Map(project.recipes.map((r) => [r.id, r]));
  const cardName = (id: string): string => {
    const node = project.nodes.find((n) => n.id === id);
    return recipeOf.get(node?.recipeId ?? "")?.machineType ?? "machine";
  };

  // Old Build's rings: a dead loop's cards starve each other, a clog lock's clog each other on a surplus.
  const loops: NoticeLoop[] = (extras.loops ?? []).map((loop) => ({
    kind: loop.kind,
    cards: loop.cards.filter((id) => nodeIds.has(id)),
  }));

  // The cards and drawers that hold a drawer's rate back.
  const holdersOf = (s: PlanShortfall): NoticeHolder[] => {
    const parts: NoticeHolder[] = [];
    for (const id of s.limitingCards) {
      const node = project.nodes.find((n) => n.id === id);
      const reason = extras.upTo?.[id]?.reason;
      const name = cardName(id);
      if (node && pinRuleOf(node) === "exact") parts.push({ kind: "exact", name });
      else if (reason && reason !== "FULL" && reason !== "BOTTLENECK")
        parts.push({ kind: reason.toLowerCase(), name });
      else parts.push({ kind: "full", name });
    }
    for (const id of s.limitingDrawers) {
      const other = storages.find((o) => o.id === id);
      parts.push({ kind: "drawer", name: other?.displayName ?? other?.resourceId ?? "other" });
    }
    return parts;
  };

  const roles = getStorageRoles(project);
  const unmet = (extras.unmetDrawers ?? [])
    .map((id) => storages.find((s) => s.id === id))
    .filter((s): s is NonNullable<typeof s> => s !== undefined);
  const blank = {
    resource: "item",
    supply: false,
    exact: false,
    asked: 0,
    reached: 0,
    made: 0,
    target: 0,
    hasShortfall: false,
    holders: [] as NoticeHolder[],
    subject: "",
    focus: [] as string[],
  };
  const notes: NoticeNote[] = [];
  for (const note of extras.notes) {
    if (note.message === "DRAWER_UNMET") {
      const label = text(note.args[0]);
      const ruleNote = note.args[1];
      const rule = isNote(ruleNote) ? RULE_BY_NOTE[ruleNote.message] : undefined;
      const storage =
        unmet.find((s) => (s.displayName ?? s.resourceId) === label) ??
        unmet.find((s) => s.resourceId === label);
      const shortfall = storage
        ? extras.shortfalls?.find((s) => s.drawerId === storage.id && s.reachable < s.target)
        : undefined;
      notes.push({
        ...blank,
        type: "UNMET",
        id: storage?.id ?? label,
        label,
        resource: storage?.kind ?? "item",
        supply: storage ? roles.get(storage.id) === "source" : false,
        exact: rule === "exact",
        asked: num(note.args[2]),
        reached: num(note.args[3]),
        hasShortfall: shortfall !== undefined,
        holders: shortfall ? holdersOf(shortfall) : [],
        focus: storage
          ? [
              storage.id,
              ...(shortfall ? [...shortfall.limitingCards, ...shortfall.limitingDrawers] : []),
            ]
          : [],
      });
    }
  }
  for (const note of extras.notes) {
    if (note.message === "OVERSHOOTS_TARGET") {
      const label = text(note.args[1]);
      const recipe = recipeOf.get(
        project.nodes.find((n) => n.id === text(note.args[4]))?.recipeId ?? "",
      );
      const made = recipe?.outputs.find((o) => (o.displayName ?? o.id) === label);
      notes.push({
        ...blank,
        type: "OVERSHOOT",
        id: `${text(note.args[4])}:${label}`,
        label,
        resource: made?.kind ?? "item",
        made: num(note.args[2]),
        target: num(note.args[3]),
        subject: text(note.args[0]),
        focus: idsIn(note),
      });
    } else if (note.message === "DRAWER_PORT_TAKEN") {
      const id = text(note.args[1]);
      const ruleNote = note.args[2];
      notes.push({
        ...blank,
        type: "PORT_TAKEN",
        id,
        label: text(note.args[0]),
        resource: storages.find((s) => s.id === id)?.kind ?? "item",
        supply: roles.get(id) === "source",
        exact: isNote(ruleNote) && RULE_BY_NOTE[ruleNote.message] === "exact",
        asked: num(note.args[3]),
        focus: [id],
      });
    } else if (note.message === "DRAWER_NOT_CONNECTED") {
      notes.push({
        ...blank,
        type: "NOT_CONNECTED",
        id: text(note.args[1]),
        label: text(note.args[0]),
        focus: idsIn(note),
      });
    } else if (note.message === "DRAWER_WIRED_IGNORED") {
      notes.push({
        ...blank,
        type: "WIRED_IGNORED",
        id: text(note.args[1]),
        label: text(note.args[0]),
        focus: idsIn(note),
      });
    } else if (
      note.message === "CHOICE_NO_LONGER_FITS" ||
      note.message === "CHOICE_NEEDS_MORE_GATES"
    ) {
      notes.push({ ...blank, type: "CHOICE", id: "", label: "" });
    }
  }
  return { failure, hasNodes: project.nodes.length > 0, loops, notes };
}

/** The sentence a rate is spelled in: the board's number and its unit under the active dials. */
function rate(perSecond: number, kind: string): string {
  const k = kind as ResourceKind;
  const value = perSecond * rateMultiplierForKind(k);
  const suffix = rateSuffixForKind(k);
  return `${formatCompact(value)}${suffix.startsWith(" ") ? suffix : ` ${suffix}`}`;
}

export function buildNotices(
  project: FactoryProject,
  result: ThroughputResult | undefined,
): BoardNotice[] {
  const input = noticeInput(project, result);
  if (!input) return [];
  return noticeList(input).map((n) => ({
    id: n.id,
    severity: n.severity,
    text: noticeText(n, rate),
    focus: n.focus,
    ...(n.tag !== undefined ? { tag: noticeTag(n), loop: n.loop } : {}),
  }));
}

/** A solve that found no answer, as what the player can do about it. */
function failureOf(note: Note, idsIn: (note: Note) => string[]): NoticeFailure {
  if (note.message === "NO_PIN" || note.message === "EMPTY_GRAPH")
    return { kind: note.message, names: [], focus: [] };
  const clashing: Note[] = [];
  collect(note, "PIN_DROPPED", clashing);
  if (clashing.length > 0) {
    const names: string[] = [];
    const ids: string[] = [];
    for (const pin of clashing) {
      const name = text(pin.args[0]);
      if (name && !names.includes(name)) names.push(name);
      ids.push(...idsIn(pin));
    }
    return { kind: "PIN_CONFLICT", names, focus: [...new Set(ids)] };
  }
  if (contains(note, "MACHINES_CANNOT_RUN")) return { kind: "CANNOT_RUN", names: [], focus: [] };
  if (contains(note, "SOLVER_UNSATISFIABLE", "SOLVER_NO_SOLUTION", "SOLVER_BUDGET", "STAGE_FAILED"))
    return { kind: "NO_WAY", names: [], focus: [] };
  return { kind: "FAILED", names: [], focus: [] };
}
