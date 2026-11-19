"use client";

import { useEffect } from "react";
import { warmPowerCore } from "@/lib/core/power-adopt";

/** Starts loading the compiled core once the page is up, so power cards compute through it from then on. Draws nothing. */
export function PowerCoreWarm() {
  useEffect(() => {
    warmPowerCore();
  }, []);
  return null;
}
