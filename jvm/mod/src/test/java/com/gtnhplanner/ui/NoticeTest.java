package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.harness.LangText;

/**
 * The board's notice rows: a ring's alarm chip and the row keeping its words whole however narrow the board. What the
 * notices say and their order are the core's ({@code Notices}, conformance/view/notices/cases.json).
 */
class NoticeTest {

    /** The game's font runs about six pixels a letter. */
    private static final ToIntFunction<String> SIX = s -> 6 * s.length();
    private static final List<Uuid> CARDS = List.of(new Uuid(0, 1), new Uuid(0, 2));

    private LangText lang;

    @BeforeEach
    void words() {
        lang = LangText.english();
    }

    @AfterEach
    void putBack() {
        lang.close();
    }

    @Test
    void aRingsNoticeIsAnAlarmWithItsChip() {
        final Notice alarm = Notice.alarm(UpToLoop.Kind.CLOG_LOCK, "x", CARDS);
        assertEquals(Severity.ERROR, alarm.severity());
        assertEquals("CLOG LOCK", alarm.tag());
        assertEquals(CARDS, alarm.focus());
        assertEquals("Show me", alarm.keyLabel());
        assertNull(new Notice(Severity.ERROR, "failed", List.of()).tag());
    }

    @Test
    void anAlarmsSentenceSitsBesideItsChipWhereItFitsAndUnderItWhereItDoesNot() {
        final String sentence = "These machines starve each other in a downward spiral.";
        final Notice alarm = Notice.alarm(UpToLoop.Kind.DEAD_LOOP, sentence, CARDS);
        final int chip = SIX.applyAsInt("DEAD LOOP") + 2 * NoticeRow.CHIP_PAD;
        // A wide board: one line, the sentence after the chip.
        final NoticeRow wide = NoticeRow.of(alarm, 800, SIX);
        assertFalse(wide.under());
        assertEquals(chip, wide.chipWidth());
        assertEquals(NoticeRow.ROW, wide.height());
        assertEquals(NoticeRow.LEFT + chip + NoticeRow.CHIP_GAP, wide.textX());
        assertEquals(
            1,
            wide.text()
                .lines()
                .size());
        // A narrow one: the chip and Show me on the first line, the sentence under them in whole words.
        final NoticeRow narrow = NoticeRow.of(alarm, 220, SIX);
        assertTrue(narrow.under());
        assertEquals(NoticeRow.LEFT, narrow.textX());
        assertEquals(220, narrow.width());
        final int lines = narrow.text()
            .lines()
            .size();
        assertTrue(lines >= 2, "wraps");
        assertEquals(NoticeRow.ROW + lines * NoticeRow.LINE, narrow.height());
        for (int l = 0; l < lines; l++) {
            final String line = narrow.text()
                .shown(l)
                .strip();
            assertTrue(SIX.applyAsInt(line) <= narrow.width() - NoticeRow.LEFT - 8, line);
            for (final String word : line.split(" "))
                assertTrue(sentence.contains(word + " ") || sentence.endsWith(word), word);
        }
        // Any other notice keeps its words beside its stripe.
        final NoticeRow plain = NoticeRow.of(new Notice(Severity.WARN, sentence, CARDS), 220, SIX);
        assertFalse(plain.under());
        assertEquals(0, plain.chipWidth());
    }
}
