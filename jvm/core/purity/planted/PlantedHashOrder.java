import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class PlantedHashOrder {

    static int host(final List<String> names) {
        final Map<String, Integer> byName = new HashMap<>(); // expect: hash-order
        final Set<String> seen = new HashSet<>(); // expect: hash-order
        final Map<Object, Integer> byIdentity = new IdentityHashMap<>(); // expect: hash-order
        final Set<String> fixed = Set.of("a", "b"); // expect: hash-order
        final Map<String, Integer> table = Map.of("a", 1, "b", 2); // expect: hash-order
        final Set<String> copy = Set.copyOf(seen); // expect: hash-order
        final Map<String, Integer> frozen = Map.copyOf(byName); // expect: hash-order
        final Set<String> distinct = names.stream().collect(Collectors.toSet()); // expect: hash-order
        final Map<String, String> grouped = names.stream().collect(Collectors.toMap(n -> n, n -> n)); // expect: hash-order
        return byName.size() + seen.size() + byIdentity.size() + fixed.size() + table.size() + copy.size()
            + frozen.size() + distinct.size() + grouped.size();
    }

    /** What a defined order allows: none of this is a finding. */
    static int defined(final List<String> names) {
        final Map<String, Integer> byName = new LinkedHashMap<>();
        final Set<String> seen = new LinkedHashSet<>();
        final Map<String, Integer> sorted = new TreeMap<>();
        for (final String n : names) {
            byName.put(n, 1);
            seen.add(n);
            sorted.put(n, 1);
        }
        return byName.size() + seen.size() + sorted.size();
    }
}
