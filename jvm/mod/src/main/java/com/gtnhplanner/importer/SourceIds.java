package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.gtnhplanner.core.base.Uuid;

/**
 * The ids an import gives what it makes, in the order of the plan's own ids. The solver breaks a tie between equal
 * answers (which of several equal places a leftover goes to) by id order, so ids made up at random gave the same plan
 * a different answer each import. Every id of one import shares a random first half, so two imports never meet, and
 * its second half is the rank of the plan's id among the plan's ids as the website hands them to the solver (a UUID as
 * itself, any other id after it in text order: src/lib/core/solve-adopt.ts idsFor), then the section; what the plan
 * has no id for follows in the order the import makes it.
 */
final class SourceIds {

    private static final Pattern UUID_ANY = Pattern
        .compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    /** Sections a card may have before the next card's ids. */
    private static final int SECTIONS = 1 << 16;

    private final long first;
    private final Map<String, Integer> rank = new HashMap<>();
    private long next;

    SourceIds(final Collection<String> planIds) {
        first = Uuid.randomUUID()
            .getMostSignificantBits();
        final List<String> ids = new ArrayList<>(new LinkedHashSet<>(planIds));
        final List<String> own = new ArrayList<>();
        for (final String id : ids) if (!UUID_ANY.matcher(id)
            .matches()) own.add(id);
        own.sort(null);
        final Map<String, Integer> ownAt = new HashMap<>();
        for (int i = 0; i < own.size(); i++) ownAt.put(own.get(i), i);
        final Map<String, Uuid> core = new HashMap<>();
        for (final String id : ids) {
            if (UUID_ANY.matcher(id)
                .matches()) {
                final String h = id.replace("-", "")
                    .toLowerCase(java.util.Locale.ROOT);
                core.put(
                    id,
                    Uuid.fromString(
                        h.substring(0, 8) + "-"
                            + h.substring(8, 12)
                            + "-"
                            + h.substring(12, 16)
                            + "-"
                            + h.substring(16, 20)
                            + "-"
                            + h.substring(20)));
            } else core.put(id, new Uuid(0x7fffffffffffffffL, ownAt.get(id)));
        }
        ids.sort(
            (a, b) -> core.get(a)
                .compareTo(core.get(b)));
        for (int i = 0; i < ids.size(); i++) rank.put(ids.get(i), i);
        next = (long) ids.size() * SECTIONS;
    }

    /** The id for section {@code section} (0 for the card itself) of what the plan calls {@code planId}. */
    Uuid of(final String planId, final int section) {
        final Integer at = rank.get(planId);
        if (at == null || section < 0 || section >= SECTIONS) return next();
        return at((long) at * SECTIONS + section);
    }

    /** The id for something the plan has no id for: after every one it has, in the order they are made. */
    Uuid next() {
        return at(next++);
    }

    private Uuid at(final long n) {
        // The variant bits set and the same for all, so the order is the order of n.
        return new Uuid(first, 0x8000000000000000L | n);
    }
}
