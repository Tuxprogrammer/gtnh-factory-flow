package com.gtnhplanner.web.exports;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.Float32;
import com.gtnhplanner.core.base.JsMath;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.base.Uuid;

/**
 * core-base for JavaScript: number spelling and ids. The conformance run checks these against Node's own numbers and
 * against java.util.UUID. The host reaches them as {@code BaseExports.numberTextOf(x)}: TeaVM exports this class by its
 * simple name, so the class is the module's namespace and every exports class is named {@code <Module>Exports}. Ids
 * cross as hex text (a 64-bit long does not cross the boundary the same way on both targets), a double crosses as a
 * number.
 */
public final class BaseExports {

    private BaseExports() {}

    @JSExport
    public static String numberTextOf(final double n) {
        return NumberText.of(n);
    }

    @JSExport
    public static String numberTextJson(final double n) {
        return NumberText.json(n);
    }

    /** {@code digits} outside 0 to 100 throws (JavaScript's RangeError); the host sees an Error with the message. */
    @JSExport
    public static String numberTextFixed(final double n, final int digits) {
        return NumberText.fixed(n, digits);
    }

    @JSExport
    public static double numberTextParse(final String text) {
        return NumberText.parse(text);
    }

    @JSExport
    public static double jsMathRound(final double n) {
        return JsMath.round(n);
    }

    @JSExport
    public static double float32Round(final double n) {
        return Float32.round(n);
    }

    /** Up to 16 hex digits as the long they spell (TeaVM's Long has no parseUnsignedLong). */
    private static long hex(final String digits) {
        long v = 0;
        for (int i = 0; i < digits.length(); i++) v = v << 4 | Character.digit(digits.charAt(i), 16);
        return v;
    }

    private static Uuid id(final String msbHex, final String lsbHex) {
        return new Uuid(hex(msbHex), hex(lsbHex));
    }

    @JSExport
    public static String uuidToString(final String msbHex, final String lsbHex) {
        return id(msbHex, lsbHex).toString();
    }

    @JSExport
    public static int uuidHash(final String msbHex, final String lsbHex) {
        return id(msbHex, lsbHex).hashCode();
    }

    @JSExport
    public static int uuidCompare(final String aMsbHex, final String aLsbHex, final String bMsbHex,
        final String bLsbHex) {
        return Integer.signum(id(aMsbHex, aLsbHex).compareTo(id(bMsbHex, bLsbHex)));
    }

    /**
     * The indices of {@code ids} (text of the form {@code msb:lsb,msb:lsb,...}, hex) in {@code Uuid.compareTo} order,
     * comma-separated; equal ids keep their input order.
     */
    @JSExport
    public static String uuidOrder(final String ids) {
        final String[] parts = ids.split(",");
        final List<Uuid> list = new ArrayList<>();
        for (final String p : parts) {
            final int colon = p.indexOf(':');
            list.add(id(p.substring(0, colon), p.substring(colon + 1)));
        }
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) order.add(i);
        order.sort((x, y) -> list.get(x)
            .compareTo(list.get(y)));
        final StringBuilder out = new StringBuilder();
        for (final int i : order) {
            if (out.length() > 0) out.append(',');
            out.append(i);
        }
        return out.toString();
    }

    /** {@code Uuid.nameUUIDFromBytes} of the text's UTF-8 bytes. */
    @JSExport
    public static String uuidName(final String text) {
        return Uuid.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8))
            .toString();
    }

    /** {@code Uuid.fromString(text).toString()}, or the exception's simple class name when the text is not an id. */
    @JSExport
    public static String uuidParse(final String text) {
        try {
            return Uuid.fromString(text)
                .toString();
        } catch (final NumberFormatException e) {
            return "NumberFormatException";
        } catch (final IllegalArgumentException e) {
            return "IllegalArgumentException";
        }
    }

    /**
     * core-base's ExactMath by name ({@code y} is read by pow, atan2 and hypot only); round's long answer comes from
     * {@link #exactMathRoundHex}. The conformance run holds both to the JVM's StrictMath.
     */
    @JSExport
    public static double exactMath(final String fn, final double x, final double y) {
        switch (fn) {
            case "sin": return ExactMath.sin(x);
            case "cos": return ExactMath.cos(x);
            case "tan": return ExactMath.tan(x);
            case "asin": return ExactMath.asin(x);
            case "acos": return ExactMath.acos(x);
            case "atan": return ExactMath.atan(x);
            case "exp": return ExactMath.exp(x);
            case "log": return ExactMath.log(x);
            case "log10": return ExactMath.log10(x);
            case "cbrt": return ExactMath.cbrt(x);
            case "sinh": return ExactMath.sinh(x);
            case "cosh": return ExactMath.cosh(x);
            case "tanh": return ExactMath.tanh(x);
            case "expm1": return ExactMath.expm1(x);
            case "log1p": return ExactMath.log1p(x);
            case "rint": return ExactMath.rint(x);
            case "toRadians": return ExactMath.toRadians(x);
            case "toDegrees": return ExactMath.toDegrees(x);
            case "ulp": return ExactMath.ulp(x);
            case "pow": return ExactMath.pow(x, y);
            case "atan2": return ExactMath.atan2(x, y);
            case "hypot": return ExactMath.hypot(x, y);
            default: throw new IllegalArgumentException("no ExactMath." + fn);
        }
    }

    @JSExport
    public static String exactMathRoundHex(final double x) {
        return Long.toHexString(ExactMath.round(x));
    }

    /**
     * The same functions called on java.lang.Math ({@code strict} false) or java.lang.StrictMath: what ojAlgo and the
     * class library get. The build's ExactMathPlugin sends both to ExactMath, so these answer as the JVM's StrictMath.
     */
    @JSExport
    public static double javaMath(final boolean strict, final String fn, final double x, final double y) {
        switch (fn) {
            case "sin": return strict ? StrictMath.sin(x) : Math.sin(x);
            case "cos": return strict ? StrictMath.cos(x) : Math.cos(x);
            case "tan": return strict ? StrictMath.tan(x) : Math.tan(x);
            case "asin": return strict ? StrictMath.asin(x) : Math.asin(x);
            case "acos": return strict ? StrictMath.acos(x) : Math.acos(x);
            case "atan": return strict ? StrictMath.atan(x) : Math.atan(x);
            case "exp": return strict ? StrictMath.exp(x) : Math.exp(x);
            case "log": return strict ? StrictMath.log(x) : Math.log(x);
            case "log10": return strict ? StrictMath.log10(x) : Math.log10(x);
            case "cbrt": return strict ? StrictMath.cbrt(x) : Math.cbrt(x);
            case "sinh": return strict ? StrictMath.sinh(x) : Math.sinh(x);
            case "cosh": return strict ? StrictMath.cosh(x) : Math.cosh(x);
            case "tanh": return strict ? StrictMath.tanh(x) : Math.tanh(x);
            case "expm1": return strict ? StrictMath.expm1(x) : Math.expm1(x);
            case "log1p": return strict ? StrictMath.log1p(x) : Math.log1p(x);
            case "rint": return strict ? StrictMath.rint(x) : Math.rint(x);
            case "toRadians": return strict ? StrictMath.toRadians(x) : Math.toRadians(x);
            case "toDegrees": return strict ? StrictMath.toDegrees(x) : Math.toDegrees(x);
            case "ulp": return strict ? StrictMath.ulp(x) : Math.ulp(x);
            case "pow": return strict ? StrictMath.pow(x, y) : Math.pow(x, y);
            case "atan2": return strict ? StrictMath.atan2(x, y) : Math.atan2(x, y);
            case "hypot": return strict ? StrictMath.hypot(x, y) : Math.hypot(x, y);
            default: throw new IllegalArgumentException("no Math." + fn);
        }
    }

    @JSExport
    public static String javaMathRoundHex(final boolean strict, final double x) {
        return Long.toHexString(strict ? StrictMath.round(x) : Math.round(x));
    }

    /** Java's {@code -x} (the build's plugin keeps the sign of a zero on the WebAssembly target). */
    @JSExport
    public static double javaNegate(final double x) {
        return -x;
    }

    /** Java's {@code (int) x}: NaN is 0, out of range is the nearest end (the plugin, on the JavaScript target). */
    @JSExport
    public static int javaToInt(final double x) {
        return (int) x;
    }

    /** Java's {@code (long) x} as hex, same rule. */
    @JSExport
    public static String javaToLongHex(final double x) {
        return Long.toHexString((long) x);
    }

    /**
     * Java's order of double operations, where TeaVM's JavaScript printer dropped the brackets (the build plugin puts
     * them back): form 0 {@code a + (b + c)}, 1 {@code a + (b - c)}, 2 {@code a * (b * c)}, 3 {@code a * (b / c)},
     * 4 {@code (a - b) + (b * c + a)}, 5 {@code a += b + c + a} (the shape of a running total, as core-layout's
     * Optimize.total has it).
     */
    @JSExport
    public static double javaOrder(final int form, final double a, final double b, final double c) {
        switch (form) {
            case 0: return a + (b + c);
            case 1: return a + (b - c);
            case 2: return a * (b * c);
            case 3: return a * (b / c);
            case 4: return (a - b) + (b * c + a);
            case 5: {
                double sum = a;
                sum += b + c + a;
                return sum;
            }
            default: throw new IllegalArgumentException("no form " + form);
        }
    }

    /** {@code Double.toString(x)} as JDK 19+ spells it, from core-base (the same text on every target). */
    @JSExport
    public static String numberTextJavaString(final double x) {
        return NumberText.javaString(x);
    }

    /** {@code BigDecimal.valueOf(x).toString()}: what ojAlgo makes of a coefficient (the build plugin's redirect). */
    @JSExport
    public static String javaBigDecimalOf(final double x) {
        return java.math.BigDecimal.valueOf(x).toString();
    }

    /**
     * java.math.BigDecimal arithmetic as ojAlgo's model uses it, for the conformance run (the web build replaces
     * TeaVM's BigDecimal, whose digit counts were estimates): op 0 multiply, 1 add, 2 divide to a MathContext, 3 round,
     * 4 setScale(digits, mode), 5 multiply to a MathContext, 6 new BigDecimal(a, MathContext); a MathContext is
     * (digits, RoundingMode.values()[mode]). The answer is toString(), or the exception's simple class name.
     */
    @JSExport
    public static String javaBigDecimalOp(final int op, final String a, final String b, final int digits,
        final int mode) {
        try {
            final java.math.RoundingMode rounding = java.math.RoundingMode.values()[mode];
            final java.math.MathContext mc = new java.math.MathContext(Math.max(0, digits), rounding);
            final java.math.BigDecimal x = new java.math.BigDecimal(a), y = new java.math.BigDecimal(b);
            switch (op) {
                case 0: return x.multiply(y).toString();
                case 1: return x.add(y).toString();
                case 2: return x.divide(y, mc).toString();
                case 3: return x.round(mc).toString();
                case 4: return x.setScale(digits, rounding).toString();
                case 5: return x.multiply(y, mc).toString();
                default: return new java.math.BigDecimal(a, mc).toString();
            }
        } catch (final ArithmeticException e) {
            return "ArithmeticException";
        }
    }

    /** A structured result: what the host gets for an id, as one plain JavaScript object. */
    public interface UuidInfo extends JSObject {}

    @JSBody(params = { "text", "hash", "version", "variant" },
        script = "return { text: text, hash: hash, version: version, variant: variant };")
    private static native UuidInfo uuidInfo(String text, int hash, int version, int variant);

    /** The example of the convention's structured-result rule: a JSO object built by a {@code @JSBody}. */
    @JSExport
    public static UuidInfo uuidInfo(final String msbHex, final String lsbHex) {
        final Uuid u = id(msbHex, lsbHex);
        return uuidInfo(u.toString(), u.hashCode(), u.version(), u.variant());
    }
}
