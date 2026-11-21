/**
 * GregTech's `GTUtility.powInt`: a power by repeated squaring. `Math.pow` (and `**`) round differently in the last digit
 * for some bases and exponents, and a total in the hundreds of millions of EU/t shows that digit as a whole EU/t. A heating
 * coil's discount is taken with this, as the game does. Bases 2 and 4 are exact either way.
 */
export function powInt(base: number, exp: number): number {
  if (exp < 0) return 1 / powInt(base, -exp);
  let result = 1;
  let square = base;
  for (let rest = exp; rest > 0; rest = Math.floor(rest / 2)) {
    if (rest % 2 === 1) result *= square;
    square *= square;
  }
  return result;
}
