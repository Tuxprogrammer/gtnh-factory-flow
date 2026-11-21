/**
 * A special card's own parts (the core's `SpecialView`, jvm/core/core-view `core.view.special`), from the card's own
 * view and its kind's facts: the website's rules, translated function for function by the core's `SpecialViews`. Pure:
 * the hooks in use-special.ts read the facts, the shared cases (special-conformance.ts) and the shadow run these, and
 * `specialViewFrom` puts the parts and the card's view together as the `SpecialView` SpecialCard.tsx draws.
 *
 * Kinds here so far: the custom rate card, the fluid tank and the generator. A shared machine and a crop card build their
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

/**
 * One of a generator's settings: how it is changed, its value (a select's option label, a number and its unit, a
 * switch), whether the others leave it meaning anything, and whether the plan pins it to the card.
 */
export interface GeneratorSettingFacts {
  id: string;
  label: string;
  kind: "choice" | "number" | "toggle";
  choice: string;
  number: number;
  unit?: string;
  on: boolean;
  enabled: boolean;
  pinned: boolean;
}

/**
 * A generator's facts: its source's name, its tier select (none when it has none), every other setting in its source's
 * order, its readings, its warnings.
 */
export interface GeneratorFacts {
  name: string;
  tier?: { label: string; fixed: boolean };
  settings: GeneratorSettingFacts[];
  readings: Array<{ label: string; value: string; pinned: boolean }>;
  warnings: string[];
}

/** A special card's kind and its kind's facts (the core's `SpecialInput`). */
export interface SpecialFacts {
  kind: SpecialKind;
  holder?: HolderFacts;
  generator?: GeneratorFacts;
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
  /** `off`: a setting the card's other settings leave meaning nothing, greyed. */
  chips: Array<{ chip: CleanChipView; edit?: ChipEdit; off?: boolean }>;
  stripHeight: number;
  warnings: string[];
  cannotRun: boolean;
  unset: boolean;
  pick: boolean;
  cropKey: boolean;
}

/** Whether the shared rules draw this kind's own parts (the core's `SpecialViews.covers`). */
export function coveredKind(kind: SpecialKind): boolean {
  return kind === "custom-rate" || kind === "tank" || kind === "power";
}

/** A special card's own parts, from its card's view and its kind's facts. */
export function specialParts(base: CleanCardView, facts: SpecialFacts): SpecialParts {
  switch (facts.kind) {
    case "custom-rate":
    case "tank":
      return holderParts(base, facts);
    case "power":
      return generatorParts(base, facts);
    default:
      throw new Error(`no shared rules yet for a ${facts.kind} card`);
  }
}

/** A pinned reading's key in the plan's pins and its chip's id. */
export const READING_PIN = "reading:";

/** A generator's setting as its chip reads it: the option's label, "N unit", On or Off. */
export function generatorSettingValue(s: GeneratorSettingFacts): string {
  if (s.kind === "choice") return s.choice;
  if (s.kind === "number") return `${s.number}${s.unit ? ` ${s.unit}` : ""}`;
  return s.on ? "On" : "Off";
}

/**
 * A generator: its source's name in amber, its tier select on the head's tier chip ("Unlocks at" when it has only the
 * one), and on its strip the settings and readings the plan pins to the card, settings first, each in its source's
 * order. A pinned setting the others leave meaning nothing stays on the strip, greyed (`off`). A number reads "N unit"
 * and is typed into; the rows, count and bar are the card's own.
 */
function generatorParts(base: CleanCardView, facts: SpecialFacts): SpecialParts {
  const g = facts.generator;
  if (!g) throw new Error("a generator's card needs its generator's facts");
  const settings = g.settings.filter((s) => s.pinned);
  const laid = layoutStrip([
    ...settings.map((s) => {
      const value = generatorSettingValue(s);
      return { id: s.id, label: s.label, value, title: `${s.label}: ${value}` };
    }),
    ...g.readings
      .filter((r) => r.pinned)
      .map((r) => ({
        id: READING_PIN + r.label,
        label: r.label,
        value: r.value,
        reading: true,
        title: `${r.label}: ${r.value}`,
      })),
  ]);
  const chips: SpecialParts["chips"] = laid.chips.map((chip, i) => {
    const s = settings[i];
    const off = s !== undefined && !s.enabled;
    return {
      chip,
      ...(s && !off && s.kind === "number" ? { edit: { text: String(s.number), scale: 1, notes: [] } } : {}),
      off,
    };
  });
  const tier = g.tier;
  return {
    kind: facts.kind,
    name: g.name,
    nameSize: cardNameSize(g.name, { tier: Boolean(tier), amps: false, picker: false }),
    nameTone: "power",
    hasPicker: false,
    tier: tier
      ? {
          label: tier.label,
          fixed: tier.fixed,
          title: tier.fixed ? `Unlocks at ${tier.label}` : `Tier ${tier.label}. Click up, right click down.`,
        }
      : undefined,
    empty: false,
    sections: [],
    gauge: false,
    count: base.count,
    foot: base.foot,
    chips,
    stripHeight: laid.height,
    warnings: g.warnings,
    cannotRun: false,
    unset: base.unset,
    pick: false,
    cropKey: false,
  };
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
        chip.id === "rate"
          ? { chip, edit: { text: shown, scale: multiplier, notes: [] }, off: false }
          : { chip, off: false },
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
  const chips: SpecialChip[] = parts.chips.map(({ chip, edit, off }) =>
    edit
      ? {
          ...chip,
          ...(off ? { off } : {}),
          edit: {
            text: edit.text,
            ...(edit.notes.length > 0 ? { notes: edit.notes } : {}),
            commit: (text: string) => {
              const parsed = Number.parseFloat(text.replace(/,/g, "").trim());
              if (Number.isFinite(parsed) && parsed >= 0) host.commit?.(chip.id, parsed / edit.scale);
            },
          },
        }
      : off
        ? { ...chip, off }
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
