package com.gtnhplanner.core.view.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.corpus.RecordReader;
import com.gtnhplanner.core.view.corpus.ViewCorpus;
import com.gtnhplanner.data.flowchart.PinRule;

/**
 * The reflection-free card reader the web build uses reads every shared case as the tests' reflective reader does
 * (the records compare by value), so a card that crosses as text is the card the corpus tests ran.
 */
class CardInputJsonTest {

    @Test
    void readsEverySharedCaseAsTheRecordReaderDoes() throws IOException {
        final List<ViewCorpus.Case> cases = ViewCorpus.open("card", "cases")
            .cases();
        assertTrue(cases.size() >= 99);
        for (final ViewCorpus.Case c : cases) {
            final CardInput.Case want = RecordReader.read(CardInput.Case.class, c.input());
            assertEquals(want, CardInputJson.readCase(c.input()
                .toString()), c.name());
        }
    }

    @Test
    void aMissingKeyIsTheEmptyValue() {
        final CardInput.Case c = CardInputJson.readCase(
            "{\"dials\":{\"rate\":\"SECOND\",\"power\":\"eu\"},\"card\":{\"count\":{\"rule\":\"ANY\"}}}");
        assertEquals("", c.card()
            .nodeId());
        assertNull(c.card()
            .power());
        assertTrue(c.card()
            .inputs()
            .isEmpty());
        assertEquals(PinRule.ANY, c.card()
            .count()
            .rule());
        assertNull(c.card()
            .count()
            .pin());
    }

    @Test
    void anUnknownEnumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CardInputJson.readCase(
            "{\"dials\":{\"rate\":\"FORTNIGHT\",\"power\":\"eu\"},\"card\":{\"count\":{\"rule\":\"ANY\"}}}"));
    }
}
