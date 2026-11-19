public final class PlantedRemainder {

    double ofDoubles(final double x, final double y) {
        return x % y; // expect: remainder
    }

    float ofFloats(final float x, final float y) {
        return x % y; // expect: remainder
    }

    int ofInts(final int x, final int y) {
        return x % y; // exact on every target: no finding
    }

    long ofLongs(final long x, final long y) {
        return x % y; // no finding
    }
}
