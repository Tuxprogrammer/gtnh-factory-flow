/**
 * The record families of the view golden corpus: each entry is a module's `viewFiles()`, the files (path under
 * `conformance/view/`, text) it makes now. `tools/conformance/view/generate.ts` writes them all and
 * `view-corpus.test.ts` fails when the tree differs. A new record family is one line here (`conformance/view/README.md`).
 */
import { viewFiles as drawerFiles } from "@/components/flow/clean/drawer/drawer-conformance";
import { viewFiles as groupFiles } from "@/components/flow/clean/group/group-conformance";
import { viewFiles as noteFiles } from "@/components/flow/clean/note/note-conformance";
import { viewFiles as railFiles } from "@/components/flow/clean/rail/rail-conformance";

export const VIEW_FAMILIES: Array<() => Record<string, string>> = [railFiles, drawerFiles, groupFiles, noteFiles];

/** Every file the families make now, by path; a path made twice is an error. */
export function allViewFiles(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const family of VIEW_FAMILIES) {
    for (const [name, text] of Object.entries(family())) {
      if (name in out) throw new Error(`two view families make ${name}`);
      out[name] = text;
    }
  }
  return out;
}
