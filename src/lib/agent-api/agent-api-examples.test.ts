import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import type { z } from "zod";
import {
  recipesGetRequest,
  recipesGetResponse,
  recipesSearchRequest,
  recipesSearchResponse,
  resourcesSearchRequest,
  resourcesSearchResponse,
} from "./schemas";
import { RESPONSE_AIM_BYTES } from "./size";
import {
  plansAddCardsRequest,
  plansAddCardsResponse,
  plansConnectRequest,
  plansConnectResponse,
  plansCreateRequest,
  plansCreateResponse,
  plansDisconnectRequest,
  plansDisconnectResponse,
  plansGetRequest,
  plansGetResponse,
  plansRemoveCardsRequest,
  plansRemoveCardsResponse,
  plansSetTargetsRequest,
  plansSetTargetsResponse,
  plansSolveRequest,
  plansSolveResponse,
} from "./plan-schemas";
import { plansExpandInputsRequest, plansExpandInputsResponse } from "./expand-schemas";
import {
  plansDeliverRequest,
  plansDeliverResponse,
  plansDiffRequest,
  plansDiffResponse,
  plansImportRequest,
  plansImportResponse,
} from "./delivery-schemas";

/**
 * The committed examples (docs/agent-api/examples) are what the assistant's tool server tests against, so each must
 * still be a valid request and response of its endpoint. They are recorded from real calls against the pack dataset.
 */
const SCHEMAS: Record<string, { request: z.ZodType; response: z.ZodType }> = {
  "resources/search": { request: resourcesSearchRequest, response: resourcesSearchResponse },
  "recipes/search": { request: recipesSearchRequest, response: recipesSearchResponse },
  "recipes/get": { request: recipesGetRequest, response: recipesGetResponse },
  "plans/create": { request: plansCreateRequest, response: plansCreateResponse },
  "plans/add-cards": { request: plansAddCardsRequest, response: plansAddCardsResponse },
  "plans/remove-cards": { request: plansRemoveCardsRequest, response: plansRemoveCardsResponse },
  "plans/set-targets": { request: plansSetTargetsRequest, response: plansSetTargetsResponse },
  "plans/connect": { request: plansConnectRequest, response: plansConnectResponse },
  "plans/disconnect": { request: plansDisconnectRequest, response: plansDisconnectResponse },
  "plans/get": { request: plansGetRequest, response: plansGetResponse },
  "plans/expand-inputs": { request: plansExpandInputsRequest, response: plansExpandInputsResponse },
  "plans/solve": { request: plansSolveRequest, response: plansSolveResponse },
  "plans/deliver": { request: plansDeliverRequest, response: plansDeliverResponse },
  "plans/import": { request: plansImportRequest, response: plansImportResponse },
  "plans/diff": { request: plansDiffRequest, response: plansDiffResponse },
};

const DIR = path.join(process.cwd(), "docs", "agent-api", "examples");
const files = readdirSync(DIR).filter((file) => file.endsWith(".json"));

describe("agent API examples", () => {
  it("has one example per built endpoint", () => {
    const endpoints = files.map((file) => JSON.parse(readFileSync(path.join(DIR, file), "utf8")).endpoint);
    expect(endpoints.sort()).toEqual(Object.keys(SCHEMAS).sort());
  });

  it.each(files)("%s matches its endpoint's schemas", (file) => {
    const text = readFileSync(path.join(DIR, file), "utf8");
    const example = JSON.parse(text) as { endpoint: string; request: unknown; status: number; response: unknown };
    const schemas = SCHEMAS[example.endpoint];
    expect(schemas, `unknown endpoint ${example.endpoint}`).toBeDefined();
    expect(example.status).toBe(200);
    schemas!.request.parse(example.request);
    schemas!.response.parse(example.response);
    expect(Buffer.byteLength(JSON.stringify(example.response))).toBeLessThanOrEqual(RESPONSE_AIM_BYTES);
    expect(text).not.toMatch(/https?:\/\/|Bearer|AGENT_API_TOKEN|\/home\/|\/srv\//);
  });
});
