"use client";

import { decodePlanCode, readPlanCodeFromHash } from "@/lib/import-export/plan-code";
import { fetchDeliveredPlan, readDeliveryKeyFromHash } from "@/lib/import-export/delivery-link";
import type { FactoryProject } from "@/lib/model/types";
import { applyPlanView } from "@/lib/plan-view";
import { looksLikePlanJson, openPlanJsonText } from "@/lib/import-export/open-plan-file";
import { useDesignStore } from "@/store/design-store";

/**
 * Open a plan code (or a link carrying one) as a NEW design tab, never over
 * the plan already on the board. Throws the decoder's plain-words error for
 * the caller to show.
 */
export async function openPlanCode(text: string): Promise<void> {
  // A plan file's JSON pasted as it is opens the same way a file does.
  if (looksLikePlanJson(text)) {
    await openPlanJsonText(text);
    return;
  }
  await openDecodedPlan(await decodePlanCode(text));
}

async function openDecodedPlan(project: FactoryProject): Promise<void> {
  await useDesignStore.getState().importProjectAsDesign(project, project.name);
  applyPlanView(project.view);
}

/**
 * The code this page load arrived with, captured at module load (like the
 * community link's id in shared-link.ts) because SharedAddressSync later
 * rewrites the address and drops the fragment.
 */
let arrivalPlanCode: string | undefined = (() => {
  try {
    return readPlanCodeFromHash(window.location.hash);
  } catch {
    return undefined;
  }
})();

let arrivalDeliveryId: string | undefined = (() => {
  try {
    return readDeliveryKeyFromHash(window.location.hash);
  } catch {
    return undefined;
  }
})();

/**
 * Open the code in the address (the one this load arrived with, or one a hash
 * change just brought), then remove it from the address so a reload does not
 * open a second copy.
 */
export async function openPlanCodeFromAddress(): Promise<void> {
  const code = arrivalPlanCode ?? readPlanCodeFromHash(window.location.hash);
  // A delivered plan's short link (#d=<id>) opens the same way, read from the delivery route.
  const deliveryId = arrivalDeliveryId ?? readDeliveryKeyFromHash(window.location.hash);
  arrivalPlanCode = undefined;
  arrivalDeliveryId = undefined;
  if (!code && deliveryId === undefined) {
    return;
  }
  if (readPlanCodeFromHash(window.location.hash) || readDeliveryKeyFromHash(window.location.hash) !== undefined) {
    window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
  }
  try {
    if (code) {
      await openPlanCode(code);
    } else {
      await openDecodedPlan(await fetchDeliveredPlan(deliveryId ?? ""));
    }
  } catch (error) {
    window.alert(error instanceof Error ? error.message : "That plan link could not be opened.");
  }
}
