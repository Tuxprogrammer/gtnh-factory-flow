/** Every way a float comes out unrounded on the web compile's JavaScript target. Not compiled into the core. */
public final class PlantedFloatNarrow {

    float field;

    float cast(final double d) {
        return (float) d; // expect: float-narrow
    }

    float fromInt(final int i) {
        return i; // expect: float-narrow
    }

    float fromLong(final long l) {
        return l; // expect: float-narrow
    }

    float sum(final float a, final float b) {
        return a + b; // expect: float-narrow
    }

    float difference(final float a, final float b) {
        return a - b; // expect: float-narrow
    }

    float product(final float a, final float b) {
        return a * b; // expect: float-narrow
    }

    float quotient(final float a, final float b) {
        return a / b; // expect: float-narrow
    }

    void compound(final double d) {
        field += d; // expect: float-narrow
    }

    float[] store(final double d) {
        final float[] out = new float[1];
        out[0] = (float) d; // expect: float-narrow
        return out;
    }

    float literal() {
        return 0.1f; // a literal is narrowed by the compiler: no finding
    }

    float viaBits(final float a) {
        return Float.intBitsToFloat(Float.floatToIntBits(a)); // no finding
    }
}
