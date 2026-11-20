public final class PlantedMathDrift {

    double exact(final double x, final double y) {
        return Math.cbrt(x) + StrictMath.cbrt(x) // expect: math-exact
            + Math.rint(x) // expect: math-exact
            + Math.hypot(x, y) // expect: math-exact
            + Math.tanh(x) // expect: math-exact
            + Math.log1p(x) // expect: math-exact
            + Math.expm1(x) // expect: math-exact
            + Math.sinh(x) // expect: math-exact
            + Math.cosh(x) // expect: math-exact
            + Math.log10(x) // expect: math-exact
            + Math.toRadians(x) // expect: math-exact
            + Math.toDegrees(x) // expect: math-exact
            + Math.ulp(x) // expect: math-exact
            + Math.pow(x, 2) // expect: math-exact
            + Math.exp(x) // expect: math-exact
            + Math.log(x) // expect: math-exact
            + Math.sin(x) // expect: math-exact
            + Math.cos(x) // expect: math-exact
            + Math.tan(x) // expect: math-exact
            + Math.asin(x) // expect: math-exact
            + Math.acos(x) // expect: math-exact
            + Math.atan(x) // expect: math-exact
            + Math.atan2(x, y) // expect: math-exact
            + Math.round(x) // expect: math-exact
            + StrictMath.sqrt(x); // expect: math-exact
    }

    double drift(final double x, final float f) {
        return Math.IEEEremainder(x, 2) // expect: math-drift
            + Math.scalb(x, 2) // expect: math-drift
            + Math.round(f) // expect: math-drift
            + Math.ulp(f); // expect: math-drift
    }

    double fine(final double x) {
        return Math.sqrt(x) + Math.floor(x) + Math.ceil(x) + Math.abs(x) + Math.max(x, 1) + Math.min(x, 1)
            + Math.signum(x) + Math.copySign(x, -1.0);
    }
}
