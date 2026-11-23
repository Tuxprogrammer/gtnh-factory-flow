/**
 * The basic values of a final simplex basis, solved afresh from the rows the
 * walk started on: B x = b by Gaussian elimination with partial pivoting.
 *
 * A tableau walk carries its right-hand side through every pivot, so rounding
 * and the Harris ratio test's small steps below zero add up over a long walk
 * into basic values that read negative although the basis is feasible. One
 * solve from the original rows drops that accumulated drift.
 *
 * `entry(r, c)` is row r's coefficient on tableau column c. Returns null when
 * the basis matrix is singular to working precision; the caller then keeps the
 * tableau's own values.
 */
export function solveBasis(
  m: number,
  basis: readonly number[],
  entry: (row: number, column: number) => number,
  rhs: readonly number[],
): number[] | null {
  const a: Float64Array[] = [];
  for (let r = 0; r < m; r += 1) {
    const line = new Float64Array(m + 1);
    for (let k = 0; k < m; k += 1) {
      line[k] = entry(r, basis[k]!);
    }
    line[m] = rhs[r]!;
    a.push(line);
  }
  for (let k = 0; k < m; k += 1) {
    let p = k;
    for (let r = k + 1; r < m; r += 1) {
      if (Math.abs(a[r]![k]!) > Math.abs(a[p]![k]!)) {
        p = r;
      }
    }
    if (!(Math.abs(a[p]![k]!) > 1e-12)) {
      return null;
    }
    [a[k], a[p]] = [a[p]!, a[k]!];
    const pivotLine = a[k]!;
    for (let r = k + 1; r < m; r += 1) {
      const line = a[r]!;
      const factor = line[k]! / pivotLine[k]!;
      if (factor === 0) {
        continue;
      }
      for (let c = k; c <= m; c += 1) {
        line[c] = line[c]! - factor * pivotLine[c]!;
      }
    }
  }
  const x = new Array<number>(m).fill(0);
  for (let k = m - 1; k >= 0; k -= 1) {
    const line = a[k]!;
    let value = line[m]!;
    for (let c = k + 1; c < m; c += 1) {
      value -= line[c]! * x[c]!;
    }
    x[k] = value / line[k]!;
  }
  return x.every(Number.isFinite) ? x : null;
}
