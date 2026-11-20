// Powers the way the game computes them, so the dataset does not depend on the Node version that normalizes it.
// ECMAScript leaves Math.pow and ** implementation-approximated: V8 changed its pow between Node 22 and 24, and
// 0.98^4 came out as 0.92236816 under one and 0.9223681599999999 under the other. A product of doubles is exact in
// every engine (IEEE 754 rounds each multiplication), so a power built from multiplications is the same everywhere.

/**
 * GregTech's `GTUtility.powInt(double, int)`: exponentiation by squaring. (The game builds powers of 2 and 4 from the
 * exponent bits; squaring gives the same doubles, since products of powers of two are exact until they overflow.)
 * The Industrial Coke Oven's coil discount is `powInt(0.98, coilTier)` (MTEIndustrialCokeOven.getEuModifier).
 */
export function gtPowInt(base, exponent) {
  if (!Number.isInteger(exponent)) {
    throw new Error(`gtPowInt needs an integer exponent, got ${exponent}`);
  }
  if (exponent < 0) return 1 / gtPowBySquaring(base, -exponent);
  if (exponent === 0) return 1;
  return gtPowBySquaring(base, exponent);
}

function gtPowBySquaring(base, exponent) {
  let result = 1;
  let square = base;
  let rest = exponent;
  while (rest > 0) {
    if (rest % 2 === 1) result *= square;
    square *= square;
    rest = Math.floor(rest / 2);
  }
  return result;
}
