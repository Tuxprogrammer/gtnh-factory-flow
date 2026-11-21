import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the core may do: none of this is a finding. Text in comments and strings is ignored: lombok, UUID. */
public final class PlantedClean {

    record Point(int x, int y) {}

    static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    static String all(final int n, final long l, final String s, final Object o) {
        final List<String> out = new ArrayList<>();
        out.add("n=" + n + " l=" + l + " s=" + s + " o=" + o + " lombok java.util.UUID");
        out.add(s.toLowerCase(Locale.ROOT));
        out.add(String.valueOf(n));
        out.add(new StringBuilder().append(n).append(l).append(s).toString());
        out.add(Long.toString(l) + Integer.toString(n));
        out.sort(String::compareTo);
        out.forEach(x -> CACHE.merge(x, 1, Integer::sum));
        return String.join(",", out) + new Point(n, n) + (int) Math.floor(n + 1.5);
    }

    static synchronized long parse(final String s) {
        return Long.parseLong(s) + Integer.parseInt(s);
    }
}
