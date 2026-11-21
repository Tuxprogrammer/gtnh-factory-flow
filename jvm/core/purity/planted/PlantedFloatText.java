import java.math.BigDecimal;

public final class PlantedFloatText {

    static String a(final double d) {
        return Double.toString(d); // expect: float-text
    }

    static String b(final double d) {
        return "rate " + d; // expect: float-text
    }

    static String c(final float f) {
        return String.valueOf(f); // expect: float-text
    }

    static String d(final StringBuilder sb, final double d) {
        return sb.append(d).toString(); // expect: float-text
    }

    static BigDecimal e(final double d) {
        return BigDecimal.valueOf(d); // expect: float-text
    }

    static String f(final Double boxed) {
        return "boxed " + boxed; // expect: float-text
    }
}
