import { randomInt } from "node:crypto";
import { getCommunityDb, isCommunityConfigured } from "@/lib/server/community";
import { draftNow } from "./drafts";
import { AgentApiError } from "./errors";

/**
 * The durable store of what the assistant hands a player: deliveries (a whole plan, `d...`) and proposals (what to add
 * to the player's open plan, `p...`), in the `agent_deliveries` table (supabase/migrations/2026-11-21-agent-deliveries.sql).
 * Create-only: nothing here updates or deletes a row. A row is served for 30 days; an expired row answers as unknown.
 * Nothing here reads or writes accounts, sessions, library designs or community posts.
 *
 * Time comes from the draft clock (`setDraftClock`), so tests move both together.
 */
export const DELIVERY_TABLE = "agent_deliveries";
export const DELIVERY_TTL_MS = 30 * 24 * 60 * 60 * 1000;
export const DELIVERIES_PER_DAY = 100;
export const PROPOSALS_PER_DAY = 300;

export type DeliveryKind = "delivery" | "proposal";

/** `d` or `p` and 15 characters without 0, 1, i, l, o: about 74 bits, never guessed. */
export const DELIVERY_ID_PATTERN = /^[dp][a-hjkmnp-z2-9]{15}$/;
const ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

export function newDeliveryId(kind: DeliveryKind): string {
  let id = kind === "delivery" ? "d" : "p";
  for (let i = 0; i < 15; i += 1) {
    id += ID_ALPHABET[randomInt(ID_ALPHABET.length)];
  }
  return id;
}

export interface StoredDelivery {
  id: string;
  kind: DeliveryKind;
  name: string;
  payload: unknown;
  basePlanId?: string;
  createdAt: string;
  expiresAt: string;
}

function requireDatabase(): void {
  if (!isCommunityConfigured()) {
    throw new AgentApiError("internal", "This server has no database for deliveries.");
  }
}

/** Refuses with rate_limited once `kind` has used its day (UTC). */
async function checkDailyCap(kind: DeliveryKind, now: number): Promise<void> {
  const cap = kind === "delivery" ? DELIVERIES_PER_DAY : PROPOSALS_PER_DAY;
  const dayStart = `${new Date(now).toISOString().slice(0, 10)}T00:00:00.000Z`;
  const { count, error } = await getCommunityDb()
    .from(DELIVERY_TABLE)
    .select("id", { count: "exact", head: true })
    .eq("kind", kind)
    .gte("created_at", dayStart);
  if (error) {
    throw new Error(`Delivery count failed: ${error.message}`);
  }
  if ((count ?? 0) >= cap) {
    const what = kind === "delivery" ? "deliveries" : "proposals";
    throw new AgentApiError("rate_limited", `At most ${cap} ${what} a day; the count starts again at 00:00 UTC.`);
  }
}

/** Stores one entry under a fresh id and returns it. */
export async function storeDelivery(entry: {
  kind: DeliveryKind;
  name: string;
  payload: unknown;
  basePlanId?: string;
  player?: { name: string; uuid?: string };
}): Promise<StoredDelivery> {
  requireDatabase();
  const now = draftNow();
  await checkDailyCap(entry.kind, now);
  const createdAt = new Date(now).toISOString();
  const expiresAt = new Date(now + DELIVERY_TTL_MS).toISOString();
  const name = entry.name.trim().slice(0, 80) || "Plan";
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const id = newDeliveryId(entry.kind);
    const { error } = await getCommunityDb()
      .from(DELIVERY_TABLE)
      .insert({
        id,
        kind: entry.kind,
        name,
        payload: entry.payload,
        base_plan_id: entry.basePlanId ?? null,
        player_name: entry.player?.name ?? null,
        player_uuid: entry.player?.uuid ?? null,
        created_at: createdAt,
        expires_at: expiresAt,
      });
    if (!error) {
      return { id, kind: entry.kind, name, payload: entry.payload, basePlanId: entry.basePlanId, createdAt, expiresAt };
    }
    if (error.code !== "23505") {
      throw new Error(`Delivery insert failed: ${error.message}`);
    }
  }
  throw new Error("Delivery insert failed: no free id after 3 tries.");
}

interface DeliveryRow {
  id: string;
  kind: DeliveryKind;
  name: string;
  payload: unknown;
  base_plan_id: string | null;
  created_at: string;
  expires_at: string;
}

/**
 * One entry by id, or undefined for an id of the wrong shape, an unknown id or an expired one. Never selects the
 * player columns, so nothing that reads this can serve them.
 */
export async function readDelivery(id: string): Promise<StoredDelivery | undefined> {
  if (!DELIVERY_ID_PATTERN.test(id)) {
    return undefined;
  }
  const { data, error } = await getCommunityDb()
    .from(DELIVERY_TABLE)
    .select("id, kind, name, payload, base_plan_id, created_at, expires_at")
    .eq("id", id)
    .gt("expires_at", new Date(draftNow()).toISOString())
    .maybeSingle<DeliveryRow>();
  if (error) {
    throw new Error(`Delivery read failed: ${error.message}`);
  }
  if (!data) {
    return undefined;
  }
  return {
    id: data.id,
    kind: data.kind,
    name: data.name,
    payload: data.payload,
    ...(data.base_plan_id ? { basePlanId: data.base_plan_id } : {}),
    createdAt: new Date(data.created_at).toISOString(),
    expiresAt: new Date(data.expires_at).toISOString(),
  };
}
