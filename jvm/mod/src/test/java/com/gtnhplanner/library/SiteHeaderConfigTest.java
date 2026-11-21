package com.gtnhplanner.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.core.base.Log;

/**
 * The site header's config (two keys under {@code network}), which names it may take, and which addresses it goes to.
 * The value stands for a secret: nothing the config does with it may reach a log line or a message.
 */
class SiteHeaderConfigTest {

    private static final String VALUE = "s3cret-placeholder-value";

    private final String site = CommunityApi.site();
    private final String live = SiteConfig.live();
    private final Log log = Log.get();

    @AfterEach
    void restore() {
        SiteConfig.useHeader("", "");
        CommunityApi.useSite(site);
        SiteConfig.useLive(live);
        CloudApi.useLive(null);
        Log.install(log);
        System.clearProperty(SiteConfig.HEADER_NAME_PROPERTY);
        System.clearProperty(SiteConfig.HEADER_VALUE_PROPERTY);
    }

    /** A config file as a map; the keys it lacks are empty, as in a fresh file. */
    private static SiteConfig.Values file(final Map<String, String> text) {
        return new SiteConfig.Values() {

            @Override
            public String text(final String key, final String comment) {
                return text.getOrDefault(key, "");
            }

            @Override
            public String[] list(final String key, final String comment) {
                return new String[0];
            }
        };
    }

    private static Map<String, String> header(final String name, final String value) {
        final Map<String, String> m = new HashMap<>();
        m.put(SiteConfig.HEADER_NAME_KEY, name);
        m.put(SiteConfig.HEADER_VALUE_KEY, value);
        return m;
    }

    /** What a request to {@code url} would carry: the header's name to value, or null. */
    private static Map.Entry<String, String> sent(final String url) {
        final Map<String, String> got = new HashMap<>();
        SiteHeader.addTo(url, got::put);
        return got.isEmpty() ? null
            : got.entrySet()
                .iterator()
                .next();
    }

    @Test
    void theKeysAreEmptyByDefaultAndSendNothing() {
        SiteConfig.read(file(Map.of()));
        assertFalse(SiteHeader.configured());
        CommunityApi.useSite("http://127.0.0.1:1");
        assertNull(sent("http://127.0.0.1:1/api/library"));
    }

    @Test
    void bothKeysTogetherTurnItOn() {
        SiteConfig.read(file(header("X-Access-Key", VALUE)));
        // Reading the file sets the site from it too (empty here), so the site is pointed after.
        CommunityApi.useSite("http://127.0.0.1:1");
        assertTrue(SiteHeader.configured());
        assertEquals(Map.entry("X-Access-Key", VALUE), sent("http://127.0.0.1:1/api/library"));
    }

    @Test
    void oneKeyAloneSendsNothingAndTheWarningHasNoValue() {
        final List<String> lines = capture();
        SiteConfig.read(file(header("X-Access-Key", "")));
        assertFalse(SiteHeader.configured());
        SiteConfig.read(file(header("", VALUE)));
        assertFalse(SiteHeader.configured());
        assertFalse(lines.isEmpty());
        for (final String l : lines) assertFalse(l.contains(VALUE), l);
    }

    @Test
    void namesThatWouldOverrideWhatTheClientSetsAreRefused() {
        final List<String> lines = capture();
        for (final String n : List.of(
            "Cookie",
            "cookie",
            "COOKIE",
            "Host",
            "Content-Length",
            "content-type",
            "Transfer-Encoding",
            "Connection",
            "Upgrade",
            "User-Agent",
            "Accept",
            "Origin",
            "Sec-WebSocket-Key",
            "sec-websocket-protocol",
            "Proxy-Authorization",
            "Set-Cookie")) {
            SiteConfig.read(file(header(n, VALUE)));
            assertFalse(SiteHeader.configured(), n);
        }
        assertFalse(lines.isEmpty());
        for (final String l : lines) assertFalse(l.contains(VALUE), l);
    }

    @Test
    void namesThatAreNotATokenAreRefused() {
        capture();
        for (final String n : List.of("X Access", "X-Access:", "X\r\nCookie", ":authority", "X-\u00c4", "a,b"))
            assertFalse(SiteHeader.usableName(n), n);
        for (final String n : List.of("X-Access-Key", "CF-Access-Client-Secret", "X_Key", "Authorization"))
            assertTrue(SiteHeader.usableName(n), n);
    }

    @Test
    void aValueWithALineBreakOrAControlIsRefusedWithoutSayingIt() {
        final List<String> lines = capture();
        for (final String v : List.of("a\r\nCookie: x", "a\nb", "a\u0000b", "café")) {
            SiteConfig.read(file(header("X-Access-Key", v)));
            assertFalse(SiteHeader.configured(), v);
        }
        for (final String l : lines) assertFalse(l.contains("Cookie: x") || l.contains("caf"), l);
    }

    @Test
    void aSystemPropertyWinsOverTheFileLikeTheOtherKeys() {
        System.setProperty(SiteConfig.HEADER_NAME_PROPERTY, "X-From-Property");
        System.setProperty(SiteConfig.HEADER_VALUE_PROPERTY, "from-property");
        SiteConfig.read(file(header("X-Access-Key", VALUE)));
        CommunityApi.useSite("http://127.0.0.1:1");
        assertEquals(Map.entry("X-From-Property", "from-property"), sent("http://127.0.0.1:1/x"));
    }

    @Test
    void itGoesToTheSiteAndTheLiveServiceAndNowhereElse() {
        CommunityApi.useSite("https://planner.test");
        SiteConfig.useLive("");
        SiteConfig.useHeader("X-Access-Key", VALUE);
        assertEquals("X-Access-Key", sent("https://planner.test/api/library").getKey());
        assertEquals("X-Access-Key", sent("https://PLANNER.test:443/api/library").getKey());
        // The live address derived from the site is the site's host.
        assertEquals("X-Access-Key", sent("wss://planner.test/live").getKey());
        assertEquals("X-Access-Key", sent("https://planner.test/live/health").getKey());

        assertNull(sent("https://other.test/api/library"));
        assertNull(sent("https://planner/api/library"));
        assertNull(sent("https://planner.test.evil.test/api/library"));
        assertNull(sent("https://planner.test:8443/api/library"));
        assertNull(sent("http://planner.test/api/library"));
        assertNull(sent("https://user@other.test/"));
        assertNull(sent("not a url"));
        assertNull(sent(""));
    }

    @Test
    void aConfiguredLiveAddressOnAnotherHostCountsAsTheSites() {
        CommunityApi.useSite("https://planner.test");
        SiteConfig.useLive("wss://live.test/live");
        SiteConfig.useHeader("X-Access-Key", VALUE);
        assertEquals("X-Access-Key", sent("wss://live.test/live").getKey());
        assertEquals("X-Access-Key", sent("https://live.test/live/nearby").getKey());
        CloudApi.useLive("wss://elsewhere.test/live");
        assertEquals("X-Access-Key", sent("wss://elsewhere.test/live").getKey());
        assertNull(sent("https://third.test/live"));
    }

    @Test
    void aConnectionThatQuotesTheHeaderDoesNotPassItOn() {
        CommunityApi.useSite("http://127.0.0.1:1");
        SiteConfig.useHeader("X-Access-Key", VALUE);
        final IllegalStateException e = assertThrows(
            IllegalStateException.class,
            () -> SiteHeader.addTo("http://127.0.0.1:1/x", (n, v) -> { throw new IllegalArgumentException(v); }));
        assertFalse(
            String.valueOf(e.getMessage())
                .contains(VALUE));
        assertNull(e.getCause());
    }

    @Test
    void whatItPrintsSaysOnlyWhetherOneIsSet() {
        SiteConfig.useHeader("X-Access-Key", VALUE);
        assertFalse(
            SiteHeader.summary()
                .contains(VALUE));
        assertFalse(
            SiteHeader.summary()
                .contains("X-Access-Key"));
    }

    @Test
    void theRedirectsItNamesAreTheOnesASignInUses() {
        for (final int s : new int[] { 301, 302, 303, 307, 308 }) assertTrue(SiteHeader.redirect(s), "" + s);
        for (final int s : new int[] { 200, 204, 304, 400, 401, 403 }) assertFalse(SiteHeader.redirect(s), "" + s);
        assertFalse(SiteHeader.SIGN_IN.contains("—"));
    }

    /** Takes over the log: every line it is given, as the text a player's log file would hold. */
    private static List<String> capture() {
        final List<String> lines = new ArrayList<>();
        Log.install(new Log() {

            @Override
            public void debug(final String message, final Object... args) {
                lines.add(Log.fill(message, args));
            }

            @Override
            public void info(final String message, final Object... args) {
                lines.add(Log.fill(message, args));
            }

            @Override
            public void warn(final String message, final Object... args) {
                lines.add(Log.fill(message, args));
            }

            @Override
            public void error(final String message, final Object... args) {
                lines.add(Log.fill(message, args));
            }
        });
        return lines;
    }
}
