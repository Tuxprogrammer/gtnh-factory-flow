import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { renameLookup, renameResourceId, resourceRenamesFromExport } from "./resource-renames.mjs";

const fixture = JSON.parse(
  fs.readFileSync(path.join(import.meta.dirname, "__fixtures__", "postea-renames-gt-ic2-2.9.1.raw.json"), "utf8"),
);

describe("resource renames", () => {
  const table = resourceRenamesFromExport(fixture);
  const lookup = renameLookup(table);

  it("turns Postea's raw rows into resource ids", () => {
    expect(table.renames).toContainEqual({
      from: "ic2:itemfuelplantball@*",
      kind: "item",
      to: "gregtech:gt.metaitem.02@32020",
    });
    expect(table.renames).toContainEqual({
      from: "ic2:itemcellempty@6",
      kind: "item",
      to: "gregtech:gt.metaitem.01@30704",
    });
    expect(table.handlers).toEqual([
      { from: "gregtech:gt.blockmachines", handler: "gregtech.loaders.postload.PosteaTransformers$$Lambda" },
    ]);
  });

  it("names a lambda handler without the address the JVM gave it that launch", () => {
    const launch = (address) =>
      resourceRenamesFromExport({
        items: [],
        handlers: [
          { from: "gregtech:gt.blockmachines", handler: `gregtech.loaders.postload.PosteaTransformers$$Lambda/0x${address}2` },
          { from: "gregtech:gt.blockmachines", handler: `gregtech.loaders.postload.PosteaTransformers$$Lambda/0x${address}1` },
          { from: "dreamcraft:item.a", handler: "com.dreammaster.Converter$$Lambda$12/34567" },
        ],
      }).handlers;
    expect(launch("000076a0eb94")).toEqual(launch("0000773feb96"));
    expect(launch("000076a0eb94")).toEqual([
      { from: "dreamcraft:item.a", handler: "com.dreammaster.Converter$$Lambda" },
      { from: "gregtech:gt.blockmachines", handler: "gregtech.loaders.postload.PosteaTransformers$$Lambda" },
      { from: "gregtech:gt.blockmachines", handler: "gregtech.loaders.postload.PosteaTransformers$$Lambda" },
    ]);
  });

  it("applies a meta's own row before the item's wildcard, and only to items", () => {
    expect(renameResourceId(lookup, "ic2:itemfuelplantball")).toBe("gregtech:gt.metaitem.02@32020");
    expect(renameResourceId(lookup, "ic2:itembiochaff")).toBe("gregtech:gt.metaitem.02@32021");
    expect(renameResourceId(lookup, "ic2:itemcellempty@7")).toBe("gregtech:gt.metaitem.01@30245");
    expect(renameResourceId(lookup, "ic2:itemcellempty@8")).toBe("ic2:itemcellempty@8");
    expect(renameResourceId(lookup, "ic2:itemcellempty")).toBe("ic2:itemcellempty");
    expect(renameResourceId(lookup, "ic2:itemfuelplantball", "fluid")).toBe("ic2:itemfuelplantball");
  });

  it("keeps the stack's meta when the target says so", () => {
    const keep = renameLookup(
      resourceRenamesFromExport({
        items: [{ from: "miscutils:item.BioRecipeSelector", fromMeta: 32767, to: "gregtech:gt.integrated_circuit", toMeta: 32767 }],
      }),
    );
    expect(renameResourceId(keep, "miscutils:item.biorecipeselector@12")).toBe("gregtech:gt.integrated_circuit@12");
    expect(renameResourceId(keep, "miscutils:item.biorecipeselector")).toBe("gregtech:gt.integrated_circuit");
  });

  it("is absent for an export without the table", () => {
    expect(resourceRenamesFromExport(undefined)).toBeUndefined();
  });
});
