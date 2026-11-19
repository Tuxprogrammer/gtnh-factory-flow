package com.gtnhplanner.web.exports;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

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
