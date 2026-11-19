public final class PlantedMathDrift {

    double cube(final double x) {
        return Math.cbrt(x) + StrictMath.cbrt(x); // expect: math-cbrt
    }

    boolean whole(final double x) {
        return x == Math.rint(x); // expect: math-rint
    }

    double length(final double x, final double y) {
        return Math.hypot(x, y) + StrictMath.hypot(x, y); // expect: math-hypot
    }

    double drift(final double x) {
        return Math.tanh(x) // expect: math-drift
            + Math.log1p(x) // expect: math-drift
            + Math.expm1(x) // expect: math-drift
            + Math.sinh(x) // expect: math-drift
            + Math.cosh(x) // expect: math-drift
            + Math.log10(x) // expect: math-drift
            + Math.toRadians(x) // expect: math-drift
            + Math.toDegrees(x) // expect: math-drift
            + Math.ulp(x) // expect: math-drift
            + Math.IEEEremainder(x, 2) // expect: math-drift
            + Math.scalb(x, 2) // expect: math-drift
            + StrictMath.log10(x); // expect: math-drift
    }

    double fine(final double x) {
        return Math.pow(x, 2) + Math.exp(x) + Math.log(x) + Math.sqrt(x) + Math.floor(x) + Math.ceil(x) + StrictMath.sin(x);
    }
}
