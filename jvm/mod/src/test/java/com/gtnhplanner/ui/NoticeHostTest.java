package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.view.rail.Notices;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.harness.LangText;

/**
 * The mod's side of the notices: the core's records become the bar's rows, with the mod's words from its lang file.
 * What each notice says is pinned by the conformance cases; this pins the mapping (severity, alarm, focus).
 */
class NoticeHostTest {

    private static final Notices.RateFormat RATES = (perSecond, resource) -> perSecond + "/s";

    @Test
    void aRingBecomesAnAlarmWithItsChipAndCards() {
        final Uuid a = new Uuid(0, 1), b = new Uuid(0, 2);
        final Notices.Input input = new Notices.Input(
            null,
            true,
            List.of(new Notices.Loop("DEAD_LOOP", List.of(a.toString(), b.toString()))),
            List.of());
        try (LangText lang = LangText.english()) {
            final List<Notice> rows = NoticeHost.of(Notices.build(input), RATES);
            assertEquals(1, rows.size());
            final Notice row = rows.get(0);
            assertEquals(Severity.ERROR, row.severity());
            assertEquals(UpToLoop.Kind.DEAD_LOOP, row.loop());
            assertEquals("DEAD LOOP", row.tag());
            assertEquals(List.of(a, b), row.focus());
            assertEquals("These machines starve each other in a downward spiral.", row.text());
        }
    }

    @Test
    void anEmptyBoardHasNoNotices() {
        assertEquals(
            List.of(),
            NoticeHost.of(Notices.build(new Notices.Input(null, false, List.of(), List.of())), RATES));
    }
}
