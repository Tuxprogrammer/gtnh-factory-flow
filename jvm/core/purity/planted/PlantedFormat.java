public final class PlantedFormat {

    static String count(final int n) {
        return String.format("%d", n); // expect: string-format
    }

    static String decimal(final double d) {
        return new java.text.DecimalFormat("0.0").format(d); // expect: java-text
    }
}
