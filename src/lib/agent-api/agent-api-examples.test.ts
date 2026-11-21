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

/**
 * The committed examples (docs/agent-api/examples) are what the assistant's tool server tests against, so each must
 * still be a valid request and response of its endpoint. They are recorded from real calls against the pack dataset.
 */
const SCHEMAS: Record<string, { request: z.ZodType; response: z.ZodType }> = {
  "resources/search": { request: resourcesSearchRequest, response: resourcesSearchResponse },
  "recipes/search": { request: recipesSearchRequest, response: recipesSearchResponse },
  "recipes/get": { request: recipesGetRequest, response: recipesGetResponse },
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
