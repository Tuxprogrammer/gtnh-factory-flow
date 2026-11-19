import java.nio.charset.StandardCharsets;

public final class PlantedNet {

    static String encode(final String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); // expect: net
    }
}
