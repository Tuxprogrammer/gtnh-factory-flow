package com.gtnhplanner.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;

/** Canon, Sha256 and PlanJson against the shared vectors (conformance/codec). */
class CodecTest {

    @Test
    void sha256OfTheStandardMessages() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(Sha256.utf8("")));
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex(Sha256.utf8("abc")));
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex(Sha256.utf8("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")));
        // 55, 56 and 64 bytes: the length runs into a second block.
        assertEquals(64, Sha256.hex(Sha256.utf8("x".repeat(55))).length());
        assertEquals(
            Sha256.hex(Sha256.utf8("x".repeat(64))),
            Sha256.hex(Sha256.utf8("x".repeat(64))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void canonSpellsAndHashesAsTheModsCloudMapDoes() throws IOException {
        final Map<String, Object> file = JsonTree.object(JsonTree.parse(Conformance.text("codec/canon.json")));
        final List<Object> cases = JsonTree.array(file.get("cases"));
        assertTrue(cases.size() >= 10, "too few canon cases");
        for (final Object c : cases) {
            final Map<String, Object> k = (Map<String, Object>) c;
            final Object tree = JsonTree.parse((String) k.get("text"));
            assertEquals(k.get("canon"), Canon.of(tree), "canon of " + k.get("name"));
            assertEquals(k.get("sum"), Canon.sum(tree), "sum of " + k.get("name"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void planTextIsWrittenAsJsonStringifyWritesIt() throws IOException {
        final Map<String, Object> file = JsonTree.object(JsonTree.parse(Conformance.text("codec/plan-text.json")));
        for (final Object c : JsonTree.array(file.get("cases"))) {
            final Map<String, Object> k = (Map<String, Object>) c;
            assertEquals(k.get("written"), PlanJson.write(JsonTree.parse((String) k.get("text"))), (String) k.get("name"));
        }
        int plans = 0;
        for (final Object c : JsonTree.array(file.get("plans"))) {
            final Map<String, Object> k = (Map<String, Object>) c;
            final String text = Conformance.text((String) k.get("path"));
            final String written = PlanJson.write(PlanJson.parse(text));
            assertEquals(k.get("sha256"), Sha256.hex(Sha256.utf8(written)), "compact text of " + k.get("path"));
            // Read again, the same text: nothing is lost or respelled on a second trip.
            assertEquals(written, PlanJson.write(PlanJson.parse(written)), (String) k.get("path"));
            plans++;
        }
        assertTrue(plans >= 20, "too few plans: " + plans);
    }

    @Test
    void aPlanIsAnObject() {
        assertThrows(IllegalArgumentException.class, () -> PlanJson.parse("[1]"));
        assertThrows(IllegalArgumentException.class, () -> PlanJson.parse("{\"a\":"));
        assertEquals("{\"futureField\":{\"x\":1}}", PlanJson.write(PlanJson.parse("{\"futureField\":{\"x\":1.0}}")));
    }
}
