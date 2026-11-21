/**
 * A special card's own parts (the core's `SpecialView`, jvm/core/core-view `core.view.special`), from the card's own
 * view and its kind's facts: the website's rules, translated function for function by the core's `SpecialViews`. Pure:
 * the hooks in use-special.ts read the facts, the shared cases (special-conformance.ts) and the shadow run these, and
 * `specialViewFrom` puts the parts and the card's view together as the `SpecialView` SpecialCard.tsx draws.
 *
 * Kinds here so far: the custom rate card and the fluid tank. A shared machine, a generator and a crop card build their
 * whole view in their hooks.
 */
import { rateMultiplierForKind, rateSuffixForKind } from "@/lib/format/rate-unit";
import { FLUID_TANK_NAME } from "@/lib/model/fluid-tank";
import {
  cardNameSize,
  layoutStrip,
  type CleanCardView,
  type CleanChipView,
  type CleanCountView,
  type CleanFootView,
  type CleanPowerView,
} from "../clean-model";
import type {
  NameTone,
  SpecialChip,
  SpecialKind,
  SpecialSection,
  SpecialView,
} from "./special-model";

/** A custom rate card's dial: which way it faces, its rate a second, the held resource's kind, and what it shows. */
export interface DialFacts {
  mode: "supply" | "request";
  perSecond: number;
  /** `item`, `fluid` or `power`; `item` while it holds nothing. */
  kind: string;
  showMode: boolean;
  showRate: boolean;
}

/** A holder's facts: it holds nothing yet (two sockets), and a custom rate card's dial. */
export interface HolderFacts {
  empty: boolean;
  dial?: DialFacts;
}

/** A special card's kind and its kind's facts (the core's `SpecialInput`). */
export interface SpecialFacts {
  kind: SpecialKind;
  holder?: HolderFacts;
}

/** How a chip is typed into: the text it opens with, what a typed number is divided by to be stored, tip notes. */
export interface ChipEdit {
  text: string;
  scale: number;
  notes: string[];
}

/** The parts a special card shows beyond what it keeps of its card's view (the core's `SpecialView`, field for field). */
export interface SpecialParts {
  kind: SpecialKind;
  name: string;
  nameSize: number;
  nameTone: NameTone;
  hasPicker: boolean;
  tier?: CleanCardView["tier"];
  amps?: CleanCardView["amps"];
  /** No rails: an empty holder's sockets or an empty crop farm's key stand there. */
  empty: boolean;
  /** A shared machine's recipes; none on another kind. */
  sections: SpecialSection[];
  power?: CleanPowerView;
  sockets?: { take: string; give: string; takeHint: string; giveHint: string };
  gauge: boolean;
  count?: CleanCountView;
  foot?: CleanFootView;
  chips: Array<{ chip: CleanChipView; edit?: ChipEdit }>;
  stripHeight: number;
  warnings: string[];
  cannotRun: boolean;
  unset: boolean;
  pick: boolean;
  cropKey: boolean;
}

/** Whether the shared rules draw this kind's own parts (the core's `SpecialViews.covers`). */
export function coveredKind(kind: SpecialKind): boolean {
  return kind === "custom-rate" || kind === "tank";
}

/** A special card's own parts, from its card's view and its kind's facts. */
export function specialParts(base: CleanCardView, facts: SpecialFacts): SpecialParts {
  switch (facts.kind) {
    case "custom-rate":
    case "tank":
      return holderParts(base, facts);
    default:
      throw new Error(`no shared rules yet for a ${facts.kind} card`);
  }
}

/**
 * A custom rate card or a fluid tank: a blue name, two sockets until it holds something, the custom rate's gauge and
 * its dial on chips (mode and rate, the rate in the board's unit). A fluid tank has no count; a custom rate card's is
 * always Exactly (its dial).
 */
function holderParts(base: CleanCardView, facts: SpecialFacts): SpecialParts {
  const tank = facts.kind === "tank";
  const holder = facts.holder;
  const empty = !holder || holder.empty;
  const name = tank ? FLUID_TANK_NAME : "Custom Rate";
  const count = tank ? undefined : base.count;
  const chips: SpecialParts["chips"] = [];
  let stripHeight = 0;
  const dial = tank ? undefined : holder?.dial;
  if (dial) {
    const multiplier = rateMultiplierForKind(dial.kind);
    const step = 1000 / Math.min(multiplier, 1);
    const shown = String(Math.round(dial.perSecond * multiplier * step) / step);
    const laid = layoutStrip([
      ...(dial.showMode
        ? [
            {
              id: "mode",
              label: "Mode",
              value: dial.mode === "supply" ? "Supply" : "Request",
              title: "Which way the card faces.",
            },
          ]
        : []),
      ...(dial.showRate
        ? [
            {
              id: "rate",
              label: `Rate (${rateSuffixForKind(dial.kind).trim() || "/s"})`,
              value: shown,
              title: "The rate it holds.",
            },
          ]
        : []),
    ]);
    stripHeight = laid.height;
    for (const chip of laid.chips) {
      chips.push(
        chip.id === "rate" ? { chip, edit: { text: shown, scale: multiplier, notes: [] } } : { chip },
      );
    }
  }
  return {
    kind: facts.kind,
    name,
    nameSize: cardNameSize(name, { tier: false, amps: false, picker: false }),
    nameTone: "holder",
    hasPicker: false,
    empty,
    sections: [],
    sockets: empty
      ? {
          take: tank ? "TAKE CELL/FLUID" : "DRAIN ANY",
          give: tank ? "GIVE CELL/FLUID" : "SUPPLY ANY",
          takeHint: "takes an output",
          giveHint: "feeds an input",
        }
      : undefined,
    gauge: !tank,
    count,
    // An Exactly custom rate card fed by a forced input says so, as any Exactly card does.
    foot: count && !empty ? base.foot : undefined,
    chips,
    stripHeight,
    warnings: [],
    cannotRun: false,
    unset: !tank && base.unset,
    pick: false,
    cropKey: false,
  };
}

/** What the host adds to a special card's parts: its pictures, its handles, and how a typed chip is stored. */
export interface SpecialHost {
  art?: string;
  glanceArt?: string;
  icon?: SpecialView["icon"];
  /** An empty holder's socket handles. */
  socketHandles?: { take: string; give: string };
  /** Every port's handle id, for React Flow's handle bookkeeping. */
  handleIds: string[];
  /** Stores a number typed into a chip, already divided by the chip's scale. */
  commit?: (chipId: string, value: number) => void;
}

/** A special card's whole view: its parts in place of its card's own, the rest of the card's view kept. */
export function specialViewFrom(
  parts: SpecialParts,
  base: CleanCardView,
  host: SpecialHost,
): SpecialView {
  const chips: SpecialChip[] = parts.chips.map(({ chip, edit }) =>
    edit
      ? {
          ...chip,
          edit: {
            text: edit.text,
            ...(edit.notes.length > 0 ? { notes: edit.notes } : {}),
            commit: (text: string) => {
              const parsed = Number.parseFloat(text.replace(/,/g, "").trim());
              if (Number.isFinite(parsed) && parsed >= 0) host.commit?.(chip.id, parsed / edit.scale);
            },
          },
        }
      : chip,
  );
  const section0: SpecialSection = parts.empty
    ? { section: 0, inputs: [], outputs: [] }
    : { section: 0, inputs: base.inputs, outputs: base.outputs };
  return {
    kind: parts.kind,
    nodeId: base.nodeId,
    name: parts.name,
    nameSize: parts.nameSize,
    nameTone: parts.nameTone,
    hasPicker: parts.hasPicker,
    tier: parts.tier,
    amps: parts.amps,
    sections: parts.sections.length > 0 ? parts.sections : [section0],
    power: parts.power,
    sockets:
      parts.sockets && host.socketHandles
        ? {
            ...parts.sockets,
            takeHandle: host.socketHandles.take,
            giveHandle: host.socketHandles.give,
          }
        : undefined,
    glyph: parts.gauge ? "gauge" : undefined,
    art: host.art,
    glanceArt: host.glanceArt,
    icon: host.icon,
    count: parts.count,
    foot: parts.foot,
    loop: base.loop,
    glance: base.glance,
    pick: parts.pick || undefined,
    cropKey: parts.cropKey || undefined,
    chips,
    stripHeight: parts.stripHeight,
    warnings: parts.warnings,
    selected: base.selected,
    cannotRun: parts.cannotRun,
    unset: parts.unset,
    handleIds: host.handleIds,
  };
}
