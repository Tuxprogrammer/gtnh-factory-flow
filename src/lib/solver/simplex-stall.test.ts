import { describe, expect, it } from "vitest";
import { solveLp, type LinearProgram } from "./simplex";
import tinyPivotLp from "./__fixtures__/simplex-tiny-pivot.lp.json";

/**
 * The stand-in engine must end every walk with the right verdict under float
 * rounding, never at its iteration cap. Both systems are feasible (x = 0 in
 * the first) and bounded (every cost is <= 0), so "unbounded" or "infeasible"
 * from either is the engine failing, not the model.
 */

/** Largest violation of x >= 0 and of the rows, relative to each row's scale. */
function worstViolation(lp: LinearProgram, x: number[]): number {
  let worst = Math.max(0, ...x.map((v) => -v));
  const rows = [
    ...lp.equalities.map((row) => ({ row, eq: true })),
    ...lp.upperBounds.map((row) => ({ row, eq: false })),
  ];
  for (const { row, eq } of rows) {
    let value = 0;
    let scale = 1 + Math.abs(row.rhs);
    for (const [c, v] of row.coefficients) {
      value += v * x[c]!;
      scale += Math.abs(v * x[c]!);
    }
    worst = Math.max(worst, (eq ? Math.abs(value - row.rhs) : value - row.rhs) / scale);
  }
  return worst;
}

describe("simplex walks that once never ended", () => {
  it("a basic column with rounding residue in its column does not re-enter in place", () => {
    // Recipe ratios six orders apart, all rows homogeneous: the column scaling
    // gives some costs near 1e7, and residue left in a basic column read as a
    // positive reduced cost, so the walk pivoted that column on itself forever.
    const lp: LinearProgram = {
      maximize: [0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1],
      equalities: [
        {
          coefficients: new Map([
            [6, 1.6339869281045753e-5],
            [7, 1.6339869281045753e-5],
            [4, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [7, 4.340277777777778e-7],
            [5, -1],
          ]),
          rhs: 0,
        },
        { coefficients: new Map([[5, -1]]), rhs: 0 },
        {
          coefficients: new Map([
            [8, 2.170138888888889e-7],
            [2, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [8, 1.633986928104575e-6],
            [4, -0.9999999999999999],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [9, 9.191176470588236e-7],
            [1, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [9, 1.6339869281045753e-5],
            [4, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [10, 0.00390625],
            [2, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [10, 1],
            [0, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [11, 0.002777777777777778],
            [0, -1],
          ]),
          rhs: 0,
        },
        {
          coefficients: new Map([
            [11, 9.864267676767677e-8],
            [3, -1],
          ]),
          rhs: 0,
        },
      ],
      upperBounds: [],
    };
    const result = solveLp(lp);
    expect(result.status).toBe("optimal");
    expect(result.objective).toBe(0);
    expect(worstViolation(lp, result.x)).toBeLessThanOrEqual(1e-9);
  });

  it("a row whose value and pivot are both residue does not derail the walk", () => {
    // Cut down from a trades-stage model of a corpus chart. A pivot of 1e-9 on
    // a row holding 1e-15 inflated the tableau and the walk wandered to the
    // iteration cap; with that fixed, the walk's drift still left basic values
    // near -1e-8 until they are read again from the starting rows.
    const raw = tinyPivotLp as unknown as {
      maximize: number[];
      equalities: Array<{ c: [number, number][]; rhs: number }>;
      upperBounds: Array<{ c: [number, number][]; rhs: number }>;
    };
    const lp: LinearProgram = {
      maximize: raw.maximize,
      equalities: raw.equalities.map((row) => ({ coefficients: new Map(row.c), rhs: row.rhs })),
      upperBounds: raw.upperBounds.map((row) => ({ coefficients: new Map(row.c), rhs: row.rhs })),
    };
    const result = solveLp(lp);
    expect(result.status).toBe("optimal");
    expect(result.objective).toBeCloseTo(0, 9);
    expect(worstViolation(lp, result.x)).toBeLessThanOrEqual(1e-9);
  });
});
