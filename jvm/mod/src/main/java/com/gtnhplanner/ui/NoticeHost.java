package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.gtnhplanner.core.view.rail.Notices;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;

/**
 * The mod's end of the notices bar: the core works out which notices stand and in what order ({@link Notices}), and
 * this gives each its sentence from the language file, with the rates in the board's own number format, and its cards
 * and drawers as the ids "Show me" frames. Pure.
 */
public final class NoticeHost {

    private NoticeHost() {}

    /** The core's list as the board's notices. */
    public static List<Notice> of(final List<Notices.Notice> list, final Notices.RateFormat rates) {
        final List<Notice> out = new ArrayList<>(list.size());
        for (final Notices.Notice n : list) {
            final List<UUID> focus = new ArrayList<>(n.focus().size());
            for (final String id : n.focus()) focus.add(UUID.fromString(id));
            final String text = Notices.text(n, rates);
            if (n.tag() != null) out.add(
                Notice.alarm("dead".equals(n.loop()) ? UpToLoop.Kind.DEAD_LOOP : UpToLoop.Kind.CLOG_LOCK, text, focus));
            else out.add(new Notice(severity(n.severity()), text, focus));
        }
        return out;
    }

    private static Severity severity(final String word) {
        return word.equals("error") ? Severity.ERROR : word.equals("warn") ? Severity.WARN : Severity.INFO;
    }
}
