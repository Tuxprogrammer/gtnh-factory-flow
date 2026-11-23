// @vitest-environment jsdom
import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { IconPicker } from "@/components/IconPicker";
import { Face } from "@/components/library/LibraryTile";
import type { EntryIcon } from "@/lib/community/types";
import type { DatasetManifest, RecipeDataset } from "@/lib/datasets/types";
import { useFactoryStore } from "./factory-store";

// A design or post saved on dataset "old", drawn on a site that serves only "new": its face comes from "new".
const OLD = "/datasets/gtnh/old/textures/rendered/steel_ingot-0a1b2c3d4e5f.png";
const NEW = "/datasets/gtnh/new/textures/rendered/steel_ingot-0a1b2c3d4e5f.png";
const icon: EntryIcon = { kind: "item", resourceId: "item:gregtech:steel", displayName: "Steel Ingot", iconPath: OLD };

const dataset = { schemaVersion: 1, datasetVersionId: "new", recipes: [], resources: [] } as unknown as RecipeDataset;
const manifest = { schemaVersion: 1, versions: [{ id: "new" }], latestStableVersion: "new" } as unknown as DatasetManifest;
const initial = useFactoryStore.getState();

afterEach(() => {
  cleanup();
  useFactoryStore.setState(initial, true);
});

const sources = (root: HTMLElement) => [...root.querySelectorAll("img")].map((img) => img.getAttribute("src"));

describe("faces drawn from stored summaries", () => {
  it("draws a face saved on a version the site no longer serves from the served version", () => {
    const { container } = render(<Face icon={icon} size={44} />);
    // Before the dataset loads nothing is known about what is served.
    expect(sources(container)).toEqual([OLD]);
    act(() => useFactoryStore.setState({ dataset, datasetManifest: manifest }));
    expect(sources(container)).toEqual([NEW]);
  });

  it("leaves a face on a served version alone", () => {
    useFactoryStore.setState({ dataset, datasetManifest: manifest });
    const { container } = render(<Face icon={{ ...icon, iconPath: NEW }} size={44} />);
    expect(sources(container)).toEqual([NEW]);
  });

  it("offers a post's ins and outs as faces on the served version, so a picked one is saved there", () => {
    useFactoryStore.setState({ dataset, datasetManifest: manifest });
    const { container } = render(<IconPicker suggestions={[icon]} onPick={() => undefined} onClose={() => undefined} />);
    expect(sources(container.ownerDocument.body)).toContain(NEW);
    expect(sources(container.ownerDocument.body)).not.toContain(OLD);
  });

  it("keeps a face adopted into the open plan on the served version", () => {
    useFactoryStore.setState({ dataset, datasetManifest: manifest });
    useFactoryStore.getState().setProjectIdentity({ icon });
    expect(useFactoryStore.getState().project.icon?.iconPath).toBe(NEW);
  });
});
