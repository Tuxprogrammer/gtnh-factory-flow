import type {
  Recipe,
  RecipeInput,
  RecipeOutput,
  ResourceAmount,
  ResourceIconAtlasRef,
} from "../model/types";

export interface DatasetSourceInfo {
  sourceId: "nesql" | "recex" | "nerd" | "gtnh-oracle" | "unknown";
  sourceVersion?: string;
  generatedAt: string;
  gitCommit?: string;
  notes?: string;
}

export interface DatasetResource {
  id: string;
  kind: "item" | "fluid" | "aspect";
  displayName: string;
  iconPath?: string;
  iconAtlas?: ResourceIconAtlasRef;
  dominantColor?: string;
  modId?: string;
  tooltip?: string[];
  oreDictionary?: string[];
  alternatives?: ResourceAmount["alternatives"];
}

export interface DatasetResourceIndexEntry {
  id: string;
  kind: "item" | "fluid" | "aspect";
  displayName?: string;
  iconPath?: string;
  iconAtlas?: ResourceIconAtlasRef;
  dominantColor?: string;
  tooltip?: string[];
  recipeCount: number;
  oreDictionary?: string[];
  alternatives?: ResourceAmount["alternatives"];
}

export interface RecipeMapIconEntry {
  recipeMap: string;
  resource: Pick<
    ResourceAmount,
    "kind" | "id" | "displayName" | "iconPath" | "iconAtlas" | "dominantColor" | "modId" | "tooltip"
  > &
    Partial<Pick<ResourceAmount, "amount">>;
}

/**
 * The item that represents a machine handler family (its lowest-tier
 * variant), and, for a tiered singleblock family, every tier's own item so
 * a card can wear the machine of the tier it is set to. Tier order.
 */
export interface MachineHandlerIconEntry {
  familyId: string;
  resource: RecipeMapIconEntry["resource"];
  tiers?: Array<{ tier: string; resource: RecipeMapIconEntry["resource"] }>;
}

/**
 * What the pack's rate mod (GTNHRates) does to this dataset's game, read by the pipeline from the live server's
 * gtnhrates.cfg. Every field is a factor and is optional: a dataset with no record, or a record missing a field,
 * reads as 1 (a pack with no rate mod). The app never holds these as constants, only through getGameRates().
 */
export interface GameRates {
  /** cfg `beesYield`: items a Forestry bee housing (Apiary, Magic Apiary, Alveary) gets from each produce roll. */
  beesYield?: number;
  /** cfg `ic2CropsYield`: the Crop Manager's drop-round multiplier. The Industrial Farm and IC2 crops do not use it. */
  ic2CropsYield?: number;
  /** cfg `gtRecipesEnergyDiscount`: what recipe durations were divided by (rounded up, at least 1 tick). */
  recipeDurationDivisor?: number;
}

export interface DatasetVersion {
  id: string;
  gtnhVersion: string;
  channel: "stable" | "daily" | "experimental";
  publishedAt: string;
  manifestPath: string;
  recipeDatasetPath: string;
  resourceIndexPath?: string;
  recipeIndexPath?: string;
  recipeLookupIndexPath?: string;
  checksumSha256?: string;
  sourceInfo: DatasetSourceInfo;
  /** Copied from the dataset directory's game-rates.json by the manifest step; not part of the checksum. */
  gameRates?: GameRates;
}

export interface DatasetManifest {
  schemaVersion: 1;
  latestStableVersion?: string;
  latestDailyVersion?: string;
  versions: DatasetVersion[];
}

export interface RecipeDataset {
  schemaVersion: 1;
  datasetVersionId: string;
  gtnhVersion: string;
  sourceInfo: DatasetSourceInfo;
  resources: DatasetResource[];
  resourceIndex?: DatasetResourceIndexEntry[];
  recipes: Recipe[];
  oreDictionary: Record<string, string[]>;
  recipeMaps: string[];
  recipeMapIcons?: RecipeMapIconEntry[];
  machineHandlerIcons?: MachineHandlerIconEntry[];
  /** The version's manifest record, passed through by the catalog so the client needs no second request. */
  gameRates?: GameRates;
  generatedAt: string;
}

export interface RecipeSummary {
  id: string;
  name: string;
  kind?: Recipe["kind"];
  category?: string;
  recipeMap: string;
  machineType: string;
  minimumTier: string;
  durationTicks: number;
  eut: number;
  programmedCircuit?: string;
  specialValue?: number;
  machineHandlers?: Recipe["machineHandlers"];
  machineConfigControls?: Recipe["machineConfigControls"];
  inputs: RecipeInput[];
  outputs: RecipeOutput[];
  source?: Recipe["source"];
  metadata?: Recipe["metadata"];
  nei?: Recipe["nei"];
  slots: RecipeSummarySlot[];
}

export interface RecipeSummarySlot {
  side: "input" | "output";
  kind: "item" | "fluid";
  resourceIndex: number;
  x: number;
  y: number;
}
