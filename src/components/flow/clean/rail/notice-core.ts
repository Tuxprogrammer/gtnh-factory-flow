/**
 * The notices bar's list worked out from plain records: the website's side of the list the Java core computes
 * (`core-view` `Notices`). The input is what the solver said, pre-digested by the host (`notices.ts` reads it from the
 * plan's extras); the output is each notice's id, severity, word key and arguments, most pressing first. The words come
 * from a key table (`NOTICE_WORDS`), the same keys the mod's language file carries. Both sides run the cases in
 * `conformance/view/` and must give the same list. Pure: no store, no project.
 */

export interface NoticeHolder {
  /** exact, full, starved, clogged, unused, limited or drawer. */
  kind: string;
  name: string;
}

/** A failed solve: NO_PIN, EMPTY_GRAPH, PIN_CONFLICT, CANNOT_RUN, NO_WAY or FAILED. */
export interface NoticeFailure {
  kind: string;
  names: string[];
  focus: string[];
}

/** A dead loop or clog lock (DEAD_LOOP or CLOG_LOCK) and its cards. */
export interface NoticeLoop {
  kind: string;
  cards: string[];
}

/** One of the solver's notes: UNMET, OVERSHOOT, PORT_TAKEN, NOT_CONNECTED, WIRED_IGNORED or CHOICE. */
export interface NoticeNote {
  type: string;
  id: string;
  label: string;
  /** The resource kind (item, fluid, power) the rates name. */
  resource: string;
  supply: boolean;
  exact: boolean;
  asked: number;
  reached: number;
  made: number;
  target: number;
  hasShortfall: boolean;
  holders: NoticeHolder[];
  subject: string;
  focus: string[];
}

export interface NoticeInput {
  failure?: NoticeFailure;
  hasNodes: boolean;
  loops: NoticeLoop[];
  notes: NoticeNote[];
}

export type NoticeArgCore =
  | { kind: "text"; text: string }
  | { kind: "rate"; value: number; unit: string }
  | { kind: "holders"; holders: NoticeHolder[] }
  | { kind: "names"; list: string[] };

export interface CoreNotice {
  id: string;
  severity: "error" | "warn" | "info";
  key: string;
  args: NoticeArgCore[];
  focus: string[];
  /** The key of an alarm's name; only on a loop. */
  tag?: string;
  loop?: "dead" | "clog";
}

const text = (value: string): NoticeArgCore => ({ kind: "text", text: value });
const rate = (value: number, unit: string): NoticeArgCore => ({ kind: "rate", value, unit });

/** Where a notice goes in the list, lowest first: a failed solve, then alarms, then warnings, then the rest. */
export function rankOf(notice: Pick<CoreNotice, "severity" | "tag">): number {
  if (notice.severity === "error") return notice.tag ? 1 : 0;
  return notice.severity === "warn" ? 2 : 3;
}

/** Most pressing first; notices of one rank keep their order. */
export function byUrgencyOf<T extends Pick<CoreNotice, "severity" | "tag">>(
  list: readonly T[],
): T[] {
  return [...list].sort((a, b) => rankOf(a) - rankOf(b));
}

function failureNotice(f: NoticeFailure): CoreNotice {
  switch (f.kind) {
    case "PIN_CONFLICT":
      return {
        id: "pin-conflict",
        severity: "error",
        key: "gtnhplanner.notice.pinConflict",
        args: [{ kind: "names", list: f.names }],
        focus: f.focus,
      };
    case "CANNOT_RUN":
      return {
        id: "cannot-run",
        severity: "error",
        key: "gtnhplanner.notice.cannotRun",
        args: [],
        focus: [],
      };
    case "NO_WAY":
      return {
        id: "no-way",
        severity: "error",
        key: "gtnhplanner.notice.noWay",
        args: [],
        focus: [],
      };
    default:
      return {
        id: "failed",
        severity: "error",
        key: "gtnhplanner.notice.failed",
        args: [],
        focus: [],
      };
  }
}

function unmet(n: NoticeNote): CoreNotice {
  const who = n.supply ? "supply" : "drawer";
  const id = `unmet:${n.id}`;
  if (n.hasShortfall) {
    return {
      id,
      severity: "warn",
      key: `gtnhplanner.notice.unmet.short.${who}`,
      args: [
        text(n.label),
        rate(n.reached, n.resource),
        rate(n.asked, n.resource),
        { kind: "holders", holders: n.holders },
      ],
      focus: n.focus,
    };
  }
  if (n.reached > n.asked) {
    return {
      id,
      severity: "warn",
      key: `gtnhplanner.notice.unmet.over.${who}`,
      args: [text(n.label), rate(n.asked, n.resource), rate(n.reached, n.resource)],
      focus: n.focus,
    };
  }
  return {
    id,
    severity: "warn",
    key: `gtnhplanner.notice.unmet.under.${who}${n.exact ? "Exact" : ""}`,
    args: [text(n.label), rate(n.asked, n.resource), rate(n.reached, n.resource)],
    focus: n.focus,
  };
}

/** What makes two notices the same sentence: their key and everything that goes into it. */
function sameness(n: CoreNotice): string {
  return JSON.stringify([n.key, n.args]);
}

/** The list: the failure, the rings, the drawers that fall short, then the other notes; most pressing first. */
export function noticeList(input: NoticeInput): CoreNotice[] {
  const out: CoreNotice[] = [];
  const failure = input.failure;
  if (failure) {
    if (failure.kind === "NO_PIN") {
      if (input.hasNodes)
        out.push({
          id: "no-pin",
          severity: "info",
          key: "gtnhplanner.notice.noPin",
          args: [],
          focus: [],
        });
    } else if (failure.kind !== "EMPTY_GRAPH") {
      out.push(failureNotice(failure));
    }
  }
  for (const loop of input.loops) {
    if (loop.cards.length === 0) continue;
    const tone = loop.kind === "DEAD_LOOP" ? "dead" : "clog";
    out.push({
      id: `loop:${loop.kind}:${loop.cards.join(",")}`,
      severity: "error",
      key: `gtnhplanner.loop.${tone}.${loop.cards.length === 1 ? "one" : "many"}`,
      args: [],
      focus: loop.cards,
      tag: `gtnhplanner.loop.${tone}.tag`,
      loop: tone,
    });
  }
  for (const note of input.notes) if (note.type === "UNMET") out.push(unmet(note));
  for (const note of input.notes) {
    switch (note.type) {
      case "OVERSHOOT":
        out.push({
          id: `overshoot:${note.id}`,
          severity: "warn",
          key: "gtnhplanner.notice.overshoot",
          args: [
            text(note.subject),
            rate(note.made, note.resource),
            text(note.label),
            rate(note.target, note.resource),
          ],
          focus: note.focus,
        });
        break;
      case "PORT_TAKEN":
        out.push({
          id: `unmet:${note.id}`,
          severity: "warn",
          key: `gtnhplanner.notice.portTaken.${note.supply ? "supply" : "drawer"}${note.exact ? "Exact" : ""}`,
          args: [text(note.label), rate(note.asked, note.resource)],
          focus: note.focus,
        });
        break;
      case "NOT_CONNECTED":
        out.push({
          id: `unwired:${note.id}`,
          severity: "warn",
          key: "gtnhplanner.notice.unwired",
          args: [text(note.label)],
          focus: note.focus,
        });
        break;
      case "WIRED_IGNORED":
        out.push({
          id: `ignored:${note.id}`,
          severity: "warn",
          key: "gtnhplanner.notice.ignored",
          args: [text(note.label)],
          focus: note.focus,
        });
        break;
      case "CHOICE":
        out.push({
          id: "choice",
          severity: "warn",
          key: "gtnhplanner.notice.choice",
          args: [],
          focus: [],
        });
        break;
      default:
        break;
    }
  }
  const seen = new Set<string>();
  return byUrgencyOf(out).filter((n) => {
    const same = sameness(n);
    if (seen.has(same)) return false;
    seen.add(same);
    return true;
  });
}

/** The sentences, by key, with `%s` where an argument goes. The mod's language file holds the same keys. */
export const NOTICE_WORDS: Readonly<Record<string, string>> = {
  "gtnhplanner.notice.noPin":
    "Nothing to calculate yet: set a rate on a drawer, or pin a machine count",
  "gtnhplanner.notice.pinConflict": "The pinned counts on %s can't all be met at once: unpin one",
  "gtnhplanner.notice.cannotRun": "Some machines can't run with these rates and pinned counts",
  "gtnhplanner.notice.noWay":
    "There's no way to meet all these rates and pinned counts: change a rate or unpin a count",
  "gtnhplanner.notice.failed":
    "The planner couldn't solve this plan: please report it with Feedback in the top bar",
  "gtnhplanner.notice.and": "and",
  "gtnhplanner.notice.unwired": "The %s drawer has a rate, but nothing is wired to it",
  "gtnhplanner.notice.ignored": "The %s drawer's rate does nothing: the ports it's on are wired",
  "gtnhplanner.notice.choice":
    "Your saved choice no longer fits this plan, so the planner's own answer is shown",
  "gtnhplanner.notice.overshoot":
    "The %s makes %s of %s, more than its %s target, because another of its outputs needs more",
  "gtnhplanner.notice.unmet.short.supply": "The %s supply gets %s of %s.%s",
  "gtnhplanner.notice.unmet.short.drawer": "The %s drawer gets %s of %s.%s",
  "gtnhplanner.notice.unmet.over.supply": "The %s supply allows at most %s, but the plan needs %s",
  "gtnhplanner.notice.unmet.over.drawer": "The %s drawer allows at most %s, but the plan makes %s",
  "gtnhplanner.notice.unmet.under.supply":
    "The %s supply should give %s, but the plan can only use %s",
  "gtnhplanner.notice.unmet.under.supplyExact":
    "The %s supply should give exactly %s, but the plan can only use %s",
  "gtnhplanner.notice.unmet.under.drawer": "The %s drawer wants %s, but the plan can only make %s",
  "gtnhplanner.notice.unmet.under.drawerExact":
    "The %s drawer wants exactly %s, but the plan can only make %s",
  "gtnhplanner.notice.portTaken.supply":
    "The %s supply should give %s, but the input takes from the other drawer wired to it",
  "gtnhplanner.notice.portTaken.supplyExact":
    "The %s supply should give exactly %s, but the input takes from the other drawer wired to it",
  "gtnhplanner.notice.portTaken.drawer":
    "The %s drawer wants %s, but the output goes to the other drawer wired to it",
  "gtnhplanner.notice.portTaken.drawerExact":
    "The %s drawer wants exactly %s, but the output goes to the other drawer wired to it",
  "gtnhplanner.notice.holder.exact": "The %s is set exactly.",
  "gtnhplanner.notice.holder.full": "The %s is full.",
  "gtnhplanner.notice.holder.starved": "The %s is starved.",
  "gtnhplanner.notice.holder.clogged": "The %s is clogged.",
  "gtnhplanner.notice.holder.unused": "The %s is unused.",
  "gtnhplanner.notice.holder.limited": "The %s is limited.",
  "gtnhplanner.notice.holder.drawer": "The %s drawer's rate holds it back.",
  "gtnhplanner.loop.dead.one": "This machine starves itself in a downward spiral.",
  "gtnhplanner.loop.dead.many": "These machines starve each other in a downward spiral.",
  "gtnhplanner.loop.clog.one": "This machine clogs itself in a downward spiral.",
  "gtnhplanner.loop.clog.many": "These machines clog each other in a downward spiral.",
  "gtnhplanner.loop.dead.tag": "DEAD LOOP",
  "gtnhplanner.loop.clog.tag": "CLOG LOCK",
};

/** `%s` and `%N$s` filled in order, as the mod's language table spells its arguments. */
export function fillWords(template: string, args: readonly string[]): string {
  let next = 0;
  return template.replace(/%(?:(\d+)\$)?s/g, (_m, index?: string) => {
    const at = index ? Number(index) - 1 : next++;
    return args[at] ?? "";
  });
}

/** A host's number format: a rate in units a second of a resource kind, with its unit. */
export type RateFormat = (perSecond: number, resource: string) => string;

function wordOf(arg: NoticeArgCore, rates: RateFormat): string {
  switch (arg.kind) {
    case "rate":
      return rates(arg.value, arg.unit);
    case "names":
      return arg.list.join(` ${NOTICE_WORDS["gtnhplanner.notice.and"]} `);
    case "holders":
      return arg.holders
        .map(
          (h) =>
            ` ${fillWords(NOTICE_WORDS[`gtnhplanner.notice.holder.${h.kind}`] ?? "", [h.name])}`,
        )
        .join("");
    default:
      return arg.text;
  }
}

/** The sentence of a notice, with `rates` spelling the rates. */
export function noticeText(notice: CoreNotice, rates: RateFormat): string {
  return fillWords(
    NOTICE_WORDS[notice.key] ?? notice.key,
    notice.args.map((arg) => wordOf(arg, rates)),
  );
}

/** An alarm's name. */
export function noticeTag(notice: CoreNotice): string | undefined {
  return notice.tag === undefined ? undefined : (NOTICE_WORDS[notice.tag] ?? notice.tag);
}
