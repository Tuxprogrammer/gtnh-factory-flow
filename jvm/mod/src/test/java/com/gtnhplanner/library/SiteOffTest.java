package com.gtnhplanner.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.cloud.net.CloudApi;
import com.sun.net.httpserver.HttpServer;

/**
 * With nothing configured the mod names no host: every call that would reach one stops first with a plain message, and
 * what the config file sets is read back exactly (keys, empty defaults, bad values ignored). A local server counts
 * what arrives, so "nothing was sent" is checked, not assumed.
 */
class SiteOffTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final String site = CommunityApi.site();

    /** A config file in memory: what was asked for, and what the "file" holds. */
    private static final class Fake implements SiteConfig.Values {

        final Map<String, String> text = new HashMap<>();
        final Map<String, String[]> lists = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public String text(final String key, final String comment) {
            asked.add(key);
            return text.getOrDefault(key, "");
        }

        @Override
        public String[] list(final String key, final String comment) {
            asked.add(key);
            return lists.getOrDefault(key, new String[0]);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        CommunityApi.useSite("");
        CloudApi.useLive(null);
        SiteConfig.useLive(null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        CommunityApi.useSite(site);
        CloudApi.useLive(null);
        SiteConfig.useLive(null);
        SiteConfig.useUpdate(null);
        SiteConfig.useSupport(null);
        SiteConfig.useOrigins(List.of());
    }

    @Test
    void theDefaultsNameNothing() {
        // A fresh config file: every key empty.
        SiteConfig.read(new Fake());
        // (A system property set for this run would win; the test runs name none.)
        assertEquals("", CommunityApi.site());
        assertFalse(CommunityApi.configured());
        assertEquals("", SiteConfig.live());
        assertEquals("", SiteConfig.update());
        assertEquals("", SiteConfig.support());
        assertFalse(SiteConfig.websiteOpens());
        assertEquals("", CloudApi.live());
        assertFalse(CloudApi.liveConfigured());
    }

    @Test
    void theConfigFileSetsEachAddress() {
        final Fake c = new Fake();
        c.text.put(SiteConfig.SITE_KEY, "https://planner.test/");
        c.text.put(SiteConfig.LIVE_KEY, "wss://live.test/live/");
        c.text.put(SiteConfig.UPDATE_KEY, "https://updates.test/releases");
        c.text.put(SiteConfig.SUPPORT_KEY, "https://help.test/room");
        c.lists.put(SiteConfig.ORIGINS_KEY, new String[] { "https://www.planner.test/ignored/path", "not an address" });
        SiteConfig.read(c);
        assertEquals("https://planner.test", CommunityApi.site());
        assertEquals("wss://live.test/live", CloudApi.live());
        assertEquals("https://updates.test/releases", SiteConfig.update());
        assertEquals("https://help.test/room", SiteConfig.support());
        assertTrue(SiteConfig.listedOrigin("https://www.planner.test"));
        assertFalse(SiteConfig.listedOrigin("not an address"));
        assertTrue(SiteConfig.websiteOpens());
    }

    @Test
    void liveFollowsTheSiteWhenItIsNotSetOnItsOwn() {
        CommunityApi.useSite("https://planner.test");
        assertEquals("wss://planner.test/live", CloudApi.live());
        assertTrue(CloudApi.liveConfigured());
    }

    @Test
    void addressesThatAreNotWebAddressesAreIgnored() {
        for (final String bad : new String[] { "", "  ", "planner.test", "ftp://planner.test", "file:///etc/passwd",
            "javascript:alert(1)", "https://", "http:///x", "https://a b.test", "wss://live.test" }) {
            assertEquals("", SiteConfig.address(bad, false), bad);
        }
        assertEquals("", SiteConfig.address("https://planner.test", true));
        assertEquals("wss://live.test/live", SiteConfig.address("wss://live.test/live//", true));
        assertEquals("http://127.0.0.1:3000", SiteConfig.address(" http://127.0.0.1:3000/ ", false));
        CommunityApi.useSite("file:///etc");
        assertFalse(CommunityApi.configured());
    }

    @Test
    void everyCallStopsBeforeTheNetworkAndSaysSo() {
        final List<org.junit.jupiter.api.function.Executable> calls = List.of(
            () -> CommunityApi.list(CommunityApi.Query.start(), 1, 10, null),
            () -> CommunityApi.download("p"),
            () -> CommunityApi.signIn("a", "b"),
            () -> CommunityApi.register("a", "b"),
            () -> CommunityApi.whoIs("t"),
            () -> CommunityApi.report(new com.google.gson.JsonObject()),
            () -> CloudApi.library("t", "u"),
            () -> CloudApi.hasLive("t"),
            () -> CloudApi.nearby("t", List.of("steve")));
        for (final org.junit.jupiter.api.function.Executable call : calls) {
            final SiteOff off = assertThrows(SiteOff.class, call);
            assertEquals(SiteOff.MESSAGE, off.getMessage());
        }
        assertEquals(0, hits.get());
    }

    @Test
    void noSessionIsSentWhenThereIsNoSite() throws IOException {
        // The loopback stand-in answers nothing useful, but the site-off path must not even reach it.
        CommunityApi.useSite("");
        assertThrows(SiteOff.class, () -> CommunityApi.whoIs("a-session-from-another-site"));
        assertEquals(0, hits.get());
        // And with the site set, the same call does reach it (so the count above means something).
        CommunityApi.useSite(
            "http://127.0.0.1:" + server.getAddress()
                .getPort());
        assertThrows(IOException.class, () -> CommunityApi.whoIs("t"));
        assertEquals(1, hits.get());
    }

    @Test
    void theConfigFileAsksForTheKeysAPackFillsIn() {
        final Fake c = new Fake();
        SiteConfig.read(c);
        assertEquals(
            List.of("siteUrl", "liveUrl", "updateUrl", "supportUrl", "websiteOrigins", "headerName", "headerValue"),
            c.asked,
            "these key names are what packs set; changing one breaks them");
        assertEquals("network", SiteConfig.CATEGORY);
    }
}
