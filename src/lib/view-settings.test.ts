import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";
import { VIEW_SETTINGS } from "./view-settings.generated";
import { VIEW_SOUNDS } from "./view-sounds.generated";
import { SFX_NAMES } from "./sound/sfx";
import { useFactoryStore } from "@/store/factory-store";
import { writeWorkspaceView } from "./workspace-view";

// The settings schema (jvm/core/core-view/settings) is the one list of what a player can set. These tests hold the
// website to it: nothing is persisted without a control, and the generated constants are the schema's own values.

const ROOT = process.cwd();
const SCHEMA = JSON.parse(
  readFileSync(join(ROOT, "jvm/core/core-view/settings/settings.json"), "utf8"),
) as Schema;
const SOUNDS = JSON.parse(
  readFileSync(join(ROOT, "jvm/core/core-view/settings/sounds.json"), "utf8"),
) as SoundTable;

/**
 * The control that changes a setting, found in source: a file holding a marker, a row of the website's settings box
 * ("Section/Label"), a row of the mod's settings panel, or the mod's Forge config file.
 */
interface Control {
  file?: string;
  marker?: string;
  box?: string;
  panel?: string;
  configFile?: boolean;
}
interface Spec {
  type: string;
  parity: "shared" | "drift" | "web" | "mod";
  display?: boolean;
  default: unknown;
  web?: { store?: string; readIn?: string; key?: string; field?: string; control: Control };
  mod?: { key: string; control: Control; store?: string };
}
interface StoreEntry {
  kind: "setting" | "memory" | "dev" | "state";
  control?: Control;
  reason?: string;
}
interface Schema {
  settings: Record<string, Spec>;
  webStores: { files: Record<string, StoreEntry> };
}
interface SoundTable {
  sounds: { id: string; hosts: string[] }[];
  ladders: Record<string, { sound: string }>;
  pitches: Record<string, number>;
  events: {
    id: string;
    sounds: string[];
    volume?: string;
    pitch?: string;
    pitchUp?: string;
    pitchDown?: string;
    ladders?: string[];
  }[];
}

function read(path: string): string {
  return readFileSync(join(ROOT, path), "utf8");
}

const WEB_BOX = "src/components/chrome/ModSettings.tsx";
const MOD_PANEL = "jvm/mod/src/main/java/com/gtnhplanner/ui/popup/SettingsPanel.java";
const MOD_CONFIG = "jvm/mod/src/main/java/com/gtnhplanner/Config.java";

function upper(name: string): string {
  return name.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();
}

/** Fails unless the control is really in the source. */
function expectControl(id: string, control: Control | undefined): void {
  expect(control, `${id} names its control`).toBeDefined();
  const c = control!;
  if (c.file !== undefined) {
    expect(existsSync(join(ROOT, c.file)), `${id}: ${c.file}`).toBe(true);
    expect(read(c.file), `${id}: ${c.marker}`).toContain(c.marker!);
  } else if (c.box !== undefined || c.panel !== undefined) {
    const [file, row] = c.box !== undefined ? [WEB_BOX, c.box] : [MOD_PANEL, c.panel!];
    const at = row.lastIndexOf("/");
    const text = read(file);
    expect(text, `${id}: section ${row.slice(0, at)}`).toContain(`"${row.slice(0, at)}"`);
    expect(text, `${id}: row ${row.slice(at + 1)}`).toContain(`"${row.slice(at + 1)}"`);
  } else {
    expect(c.configFile, id).toBe(true);
    const key = `ViewSettings.${upper(id.split(" ")[0])}_KEY`;
    expect(read(MOD_CONFIG).includes(key), `${id} reads ${key} in Config.java`).toBe(true);
  }
}

function sourceFilesCalling(call: string): string[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (/\.(ts|tsx)$/.test(name) && !/\.(test|local\.test)\.(ts|tsx)$/.test(name)) {
        if (readFileSync(full, "utf8").includes(call)) found.push(relative(ROOT, full));
      }
    }
  };
  walk(join(ROOT, "src"));
  return found.sort();
}

describe("a setting the website persists has a control", () => {
  it("names every source file that writes to storage, so a new one has to be classed", () => {
    expect(sourceFilesCalling("setItem(")).toEqual(Object.keys(SCHEMA.webStores.files).sort());
  });

  it("shows each stored thing's control in the file that holds it", () => {
    for (const [file, entry] of Object.entries(SCHEMA.webStores.files)) {
      if (entry.kind === "state") {
        expect(entry.reason, `${file} is state and says why it has no control`).toBeTruthy();
        continue;
      }
      if (entry.kind === "setting") {
        const owned = Object.values(SCHEMA.settings).filter((s) => s.web?.store === file);
        expect(owned.length, `${file} holds a setting the schema lists`).toBeGreaterThan(0);
        continue;
      }
      expectControl(file, entry.control);
    }
  });

  it("gives every setting a control the player can reach, found in the source of its host", () => {
    for (const [id, spec] of Object.entries(SCHEMA.settings)) {
      if (spec.web) {
        expectControl(`${id} (website)`, spec.web.control);
        if (spec.web.store) {
          expect(existsSync(join(ROOT, spec.web.store)), `${id}: ${spec.web.store}`).toBe(true);
          expect(
            SCHEMA.webStores.files[spec.web.store]?.kind,
            `${id}: ${spec.web.store} is a classed store`,
          ).toBe("setting");
          expect(
            read(spec.web.readIn ?? spec.web.store),
            `${id} takes its key and default from the schema`,
          ).toContain("view-settings.generated");
        }
      }
      if (spec.mod) expectControl(`${id} (mod)`, spec.mod.control);
    }
  });

  it("keeps parity honest: a shared or drift setting has both hosts, a one-host one has only its own", () => {
    for (const [id, spec] of Object.entries(SCHEMA.settings)) {
      if (spec.parity === "web") expect(spec.mod, id).toBeUndefined();
      else if (spec.parity === "mod") expect(spec.web, id).toBeUndefined();
      else {
        expect(spec.web, id).toBeDefined();
        expect(spec.mod, id).toBeDefined();
      }
    }
  });
});

describe("the generated constants are the schema's values", () => {
  it("resolves every setting for the website with its default", () => {
    for (const [id, spec] of Object.entries(SCHEMA.settings)) {
      if (!spec.web) {
        expect((VIEW_SETTINGS as Record<string, unknown>)[id], id).toBeUndefined();
        continue;
      }
      const made = (VIEW_SETTINGS as unknown as Record<string, { default: unknown }>)[id];
      expect(made, id).toBeDefined();
      const own = spec.default as { web?: unknown };
      const expected =
        own !== null && typeof own === "object" && !Array.isArray(own) && "web" in own
          ? own.web
          : spec.default;
      const divisor = (made as { divisor?: number }).divisor;
      expect(made.default, id).toEqual(expected);
      if (divisor) expect(divisor).toBeGreaterThan(0);
    }
  });

  it("keeps a choice's default among its ids and its labels one per id", () => {
    for (const [id, spec] of Object.entries(VIEW_SETTINGS) as [
      string,
      { type: string; ids?: string[]; labels?: string[]; default: unknown; defaultIndex?: number },
    ][]) {
      if (spec.type !== "choice") continue;
      expect(spec.ids, id).toContain(spec.default);
      expect(spec.labels?.length, id).toBe(spec.ids?.length);
      expect(spec.ids![spec.defaultIndex!], id).toBe(spec.default);
    }
  });

  it("keeps a number's default inside its range", () => {
    for (const [id, spec] of Object.entries(VIEW_SETTINGS) as [
      string,
      { type: string; min?: number; max?: number; default: number },
    ][]) {
      if (spec.type !== "int") continue;
      if (spec.min !== undefined) expect(spec.default, id).toBeGreaterThanOrEqual(spec.min);
      if (spec.max !== undefined) expect(spec.default, id).toBeLessThanOrEqual(spec.max);
    }
  });
});

describe("a display setting repaints and never re-solves", () => {
  const displayIds = Object.entries(SCHEMA.settings)
    .filter(([, spec]) => spec.display)
    .map(([id]) => id)
    .sort();

  it("marks the rate unit, the energy view, and the power display and sense", () => {
    expect(displayIds).toEqual(["powerDisplay", "powerSense", "rateEnergy", "rateUnit"].sort());
  });

  it("leaves the solve and the project alone when the store's dials change", () => {
    const before = useFactoryStore.getState();
    const { lastResult, project } = before;
    before.setRateUnit("minute");
    before.setPowerDisplayUnit("amps");
    const after = useFactoryStore.getState();
    expect(after.rateUnit).toBe("minute");
    expect(after.lastResult).toBe(lastResult);
    expect(after.project).toBe(project);
    before.setRateUnit("second");
    before.setPowerDisplayUnit("eu");
  });

  it("leaves the solve and the project alone when the workspace's display switches change", () => {
    const { lastResult, project } = useFactoryStore.getState();
    writeWorkspaceView({ averageMachineDraw: false });
    const after = useFactoryStore.getState();
    expect(after.lastResult).toBe(lastResult);
    expect(after.project).toBe(project);
    writeWorkspaceView({ averageMachineDraw: true });
  });
});

describe("the sound rule table", () => {
  const known = new Set(SOUNDS.sounds.map((s) => s.id));

  it("lists the website's sounds as the ones the player hears on the website", () => {
    const web = SOUNDS.sounds.filter((s) => s.hosts.includes("web")).map((s) => s.id);
    expect([...SFX_NAMES]).toEqual(web);
    expect(new Set(web).size).toBe(web.length);
    expect([...VIEW_SOUNDS.ids]).toEqual(web);
  });

  it("has every event, ladder and pitch refer to a sound or pitch that exists", () => {
    const eventIds = SOUNDS.events.map((e) => e.id);
    expect(new Set(eventIds).size).toBe(eventIds.length);
    for (const event of SOUNDS.events) {
      expect(event.sounds.length, event.id).toBeGreaterThan(0);
      for (const sound of event.sounds) expect(known.has(sound), `${event.id}: ${sound}`).toBe(true);
      for (const key of [event.volume, event.pitch, event.pitchUp, event.pitchDown]) {
        if (key) expect(SOUNDS.pitches[key], `${event.id}: ${key}`).toBeDefined();
      }
      for (const ladder of event.ladders ?? []) {
        expect(SOUNDS.ladders[ladder], `${event.id}: ${ladder}`).toBeDefined();
        expect(event.sounds, `${event.id} plays its ladder's sound`).toContain(
          SOUNDS.ladders[ladder].sound,
        );
      }
    }
  });

  it("plays every sound from some event, and every ladder sound exists", () => {
    const played = new Set(SOUNDS.events.flatMap((e) => e.sounds));
    for (const id of known) expect(played.has(id), id).toBe(true);
    for (const [name, ladder] of Object.entries(SOUNDS.ladders)) {
      expect(known.has(ladder.sound), name).toBe(true);
    }
  });

  it("is gated by the Sounds setting, which the schema has", () => {
    expect(SCHEMA.settings[(SOUNDS as unknown as { gatedBy: string }).gatedBy]).toBeDefined();
  });
});

describe("the settings page", () => {
  it("lists every setting of the schema", () => {
    const page = read("docs/view-settings.md");
    for (const id of Object.keys(SCHEMA.settings)) {
      expect(page, `docs/view-settings.md lists ${id}`).toContain("`" + id + "`");
    }
  });
});
