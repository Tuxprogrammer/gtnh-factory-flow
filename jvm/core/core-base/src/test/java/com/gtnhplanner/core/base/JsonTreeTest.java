package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The JSON tree reader and writer the core's tables and exchange text go through. */
class JsonTreeTest {

    @Test
    void readsEveryValueKind() {
        final Map<String, Object> o = JsonTree
            .object(JsonTree.parse(" {\"a\": 1.5, \"b\": [1, null, \"x\"], \"c\": true, \"d\": null, \"e\": {} } "));
        assertEquals(1.5, JsonTree.num(o, "a"));
        assertTrue(JsonTree.object(o.get("e")).isEmpty());
        assertTrue(JsonTree.array(o.get("a")).isEmpty());
        final Double[] b = JsonTree.ladder(o, "b");
        assertEquals(3, b.length);
        assertEquals(1.0, b[0]);
        assertNull(b[1]);
        assertNull(b[2]);
        assertEquals(Boolean.TRUE, JsonTree.bool(o, "c"));
        assertNull(JsonTree.bool(o, "a"));
        assertNull(JsonTree.boxed(o, "d"));
        assertEquals(0, JsonTree.num(o, "missing"));
        assertNull(JsonTree.str(o, "a"));
        assertNull(JsonTree.ladder(o, "missing"));
    }

    @Test
    void keepsKeyOrder() {
        final Map<String, Object> o = JsonTree.object(JsonTree.parse("{\"z\":1,\"a\":2,\"m\":3}"));
        assertEquals("[z, a, m]", o.keySet()
            .toString());
    }

    @Test
    void numbersReadAsJavaScriptReadsThem() {
        final List<Object> list = JsonTree.array(JsonTree.parse("[1e3, -0.5, 1E-2, 12345678901234567890, 0]"));
        assertEquals(1000.0, list.get(0));
        assertEquals(-0.5, list.get(1));
        assertEquals(0.01, list.get(2));
        assertEquals(1.2345678901234567E19, list.get(3));
        assertEquals(0.0, list.get(4));
    }

    @Test
    void stringEscapes() {
        assertEquals("a\"b\\c\nd\teé/", JsonTree.parse("\"a\\\"b\\\\c\\nd\\te\\u00e9\\/\""));
    }

    @Test
    void badTextThrows() {
        for (final String bad : new String[] { "", "{", "[", "[1,", "{\"a\"", "{\"a\":", "{\"a\":1", "[1 2]", "{1:2}",
            "\"abc", "tru", "[1] x", "\"\\u12", "{\"a\":1,}" }) {
            assertThrows(IllegalStateException.class, () -> JsonTree.parse(bad), bad);
        }
    }

    @Test
    void writesCompactJsonInMapOrder() {
        final Map<String, Object> o = new LinkedHashMap<>();
        o.put("b", 2.0);
        o.put("a", List.of(1.0, "x", true));
        o.put("n", null);
        o.put("i", 3);
        assertEquals("{\"b\":2,\"a\":[1,\"x\",true],\"n\":null,\"i\":3}", JsonTree.write(o));
    }

    @Test
    void writesNumbersAsJavaScriptSpellsThem() {
        assertEquals("[0.1,1e+21,1.5e-7,100,null,null]", JsonTree.write(List.of(0.1, 1e21, 1.5e-7, 100.0,
            Double.NaN, Double.POSITIVE_INFINITY)));
    }

    @Test
    void writesEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", JsonTree.write("a\"b\\c\nd\u0001"));
    }

    @Test
    void roundTrips() {
        final String text = "{\"s\":\"q\\\"\\n\\u0007\",\"l\":[1,2.5,{\"k\":[]},{}],\"b\":false,\"z\":null,\"e\":1e-7}";
        final Object tree = JsonTree.parse(text);
        assertEquals(JsonTree.write(tree), JsonTree.write(JsonTree.parse(JsonTree.write(tree))));
        assertTrue(JsonTree.write(tree).contains("\"e\":1e-7"));
    }

    @Test
    void writingAnythingElseThrows() {
        assertThrows(IllegalArgumentException.class, () -> JsonTree.write(new Object()));
    }
}
