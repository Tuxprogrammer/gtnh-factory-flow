import type { FactoryNodeColorTag } from "@/lib/model/types";
import { viewEnvelope } from "@/test-support/view-corpus";
import { GT_NODE_COLORS } from "../../node-colors";
import { NOTE_COLOR_MENU, notePalette, type NotePalette } from "./note-model";

/**
 * The sticky note's colours as shared cases (`conformance/view/note/look.json`): a colour tag in, the five colours of
 * the note out (what the core's `NoteLookBuilder` makes). Every tag the app knows is a case, and so are no tag and a tag
 * nobody knows (both read as yellow). No text goes through a measure, so the measure is `none`.
 */

export interface NoteLookInput {
  tag?: string;
}

export interface NoteLookCase {
  name: string;
  input: NoteLookInput;
  view: NotePalette;
}

const tags = (): Array<string | undefined> => [
  ...Object.keys(GT_NODE_COLORS),
  undefined,
  "no-such-colour",
];

export function noteLookCases(): NoteLookCase[] {
  return tags().map((tag) => ({
    name: tag === undefined ? "no tag" : tag,
    input: tag === undefined ? {} : { tag },
    view: notePalette(tag as FactoryNodeColorTag | undefined),
  }));
}

/** The colour menu, in order: `out` is the name the menu shows for the tag (the core's `NoteMenu`). */
export function menuVectors() {
  return { menu: NOTE_COLOR_MENU.map(([tag, out], index) => ({ index, tag, out })) };
}

export function viewFiles(): Record<string, string> {
  return {
    "note/look.json": viewEnvelope("note", "look", "none", { cases: noteLookCases() }),
    "note/menu.json": viewEnvelope("note", "menu", "none", { vectors: menuVectors() }),
  };
}
