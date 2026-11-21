package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.cloud.net.LiveSocket;
import com.gtnhplanner.core.base.Log;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.Posting;
import com.gtnhplanner.library.SiteConfig;
import com.gtnhplanner.library.SiteHeader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The site header (one configured name and value, sent to the site and the live service and to no other host) on every
 * kind of request and on the WebSocket upgrade, against stand-ins that can require it and answer 302 to a sign-in page
 * without it, as a guarding proxy does. The value is a placeholder standing for a secret: it must never turn up in a
 * log
 * line, an exception or a refusal, and a redirect must never be followed.
 */
class SiteHeaderTest {

    private static final String NAME = "X-Access-Key", VALUE = "s3cret-placeholder-value";

    private record Seen(String method, String path, String header, String cookie) {}

    private HttpServer site, elsewhere;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> reachedElsewhere = new CopyOnWriteArrayList<>();
    /** The value the site's front door wants in {@link #NAME}; empty lets everything in. */
    private volatile String requires = "";
    private volatile int status = 200;
    private LiveTestServer live, other;
    private LiveSocket socket;

    private final String siteWas = CommunityApi.site();
    private final String liveWas = SiteConfig.live();
    private final Log logWas = Log.get();
    private final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/", ex -> {
            reachedElsewhere.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        elsewhere.start();
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", this::answer);
        site.start();
        CommunityApi.useSite(
            "http://127.0.0.1:" + site.getAddress()
                .getPort());
        CloudApi.useLive(
            "ws://127.0.0.1:" + site.getAddress()
                .getPort() + "/live");
        Log.install(new Log() {

            @Override
            public void debug(final String message, final Object... args) {
                logged.add(Log.fill(message, args));
            }

            @Override
            public void info(final String message, final Object... args) {
                logged.add(Log.fill(message, args));
            }

            @Override
            public void warn(final String message, final Object... args) {
                logged.add(Log.fill(message, args));
            }

            @Override
            public void error(final String message, final Object... args) {
                logged.add(Log.fill(message, args));
            }
        });
    }

    @AfterEach
    void stop() {
        if (socket != null) socket.stop();
        if (live != null) live.close();
        if (other != null) other.close();
        site.stop(0);
        elsewhere.stop(0);
        SiteConfig.useHeader("", "");
        CommunityApi.useSite(siteWas);
        SiteConfig.useLive(liveWas);
        CloudApi.useLive(null);
        Log.install(logWas);
    }

    private void answer(final HttpExchange ex) throws IOException {
        ex.getRequestBody()
            .readAllBytes();
        final String h = ex.getRequestHeaders()
            .getFirst(NAME);
        seen.add(
            new Seen(
                ex.getRequestMethod(),
                ex.getRequestURI()
                    .toString(),
                h,
                ex.getRequestHeaders()
                    .getFirst("Cookie")));
        if (!requires.isEmpty() && !requires.equals(h)) {
            // A guarding proxy: the way to its sign-in page, here pointing at another service to see it is not
            // followed.
            ex.getResponseHeaders()
                .add(
                    "Location",
                    "http://127.0.0.1:" + elsewhere.getAddress()
                        .getPort() + "/sign-in");
            ex.sendResponseHeaders(302, -1);
            ex.close();
            return;
        }
        final byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders()
            .add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }

    private interface Call {

        void run() throws Exception;
    }

    /** One of every request the mod makes of the site, by name. */
    private static List<Object[]> everyKind() {
        final JsonObject plan = new JsonObject();
        final CloudApi.Upsert body = new CloudApi.Upsert(
            "Smelter",
            null,
            null,
            false,
            false,
            null,
            "2026-01-01T00:00:00.000Z",
            "2026-01-02T00:00:00.000Z",
            "2026-01-02T00:00:00.000Z",
            plan);
        final List<Object[]> calls = new ArrayList<>();
        add(calls, "community list", () -> CommunityApi.list(CommunityApi.Query.start(), 1, 10, null));
        add(calls, "community download", () -> CommunityApi.download("p1"));
        add(calls, "sign in", () -> CommunityApi.signIn("ada", "pw"));
        add(calls, "register", () -> CommunityApi.register("ada", "pw"));
        add(calls, "who is", () -> CommunityApi.whoIs("tok"));
        add(calls, "post", () -> CommunityApi.post("tok", new CommunityApi.Post("n", "d", null, "2.9", "dev", plan)));
        add(calls, "edit post", () -> CommunityApi.update("tok", "p1", new JsonObject()));
        add(calls, "delete post", () -> CommunityApi.delete("tok", "p1"));
        add(calls, "report", () -> CommunityApi.report(new JsonObject()));
        add(calls, "cloud me", () -> CloudApi.me("tok"));
        add(calls, "cloud library", () -> CloudApi.library("tok", "u1"));
        add(calls, "cloud plan", () -> CloudApi.plan("tok", "u1", "d1"));
        add(calls, "cloud put", () -> CloudApi.put("tok", "d1", body));
        add(calls, "cloud put shared", () -> CloudApi.put("tok", "d1", "u2", body));
        add(calls, "cloud delete", () -> CloudApi.delete("tok", "d1"));
        add(calls, "members", () -> CloudApi.members("tok", "d1", null));
        add(calls, "sharing", () -> CloudApi.sharing("tok", "d1", "u2"));
        add(calls, "add member", () -> CloudApi.addMember("tok", "d1", "u3", null));
        add(calls, "remove member", () -> CloudApi.removeMember("tok", "d1", "u3", null));
        add(calls, "search accounts", () -> CloudApi.search("tok", "ad"));
        add(calls, "nearby (live service)", () -> CloudApi.nearby("tok", List.of("Ada")));
        add(calls, "live health (live service)", () -> CloudApi.hasLive("tok"));
        return calls;
    }

    private static void add(final List<Object[]> calls, final String name, final Call call) {
        calls.add(new Object[] { name, call });
    }

    @Test
    void everyKindOfRequestCarriesTheHeader() {
        SiteConfig.useHeader(NAME, VALUE);
        for (final Object[] c : everyKind()) {
            final int before = seen.size();
            try {
                ((Call) c[1]).run();
            } catch (final Exception e) {
                // The stand-in answers {} to everything: some calls cannot read that, and only their request counts.
            }
            assertTrue(seen.size() > before, c[0] + " made no request");
            for (final Seen s : seen.subList(before, seen.size())) assertEquals(VALUE, s.header(), c[0] + " " + s);
        }
    }

    @Test
    void withNoHeaderConfiguredNoRequestCarriesOne() {
        for (final Object[] c : everyKind()) {
            final int before = seen.size();
            try {
                ((Call) c[1]).run();
            } catch (final Exception e) {
                // See above.
            }
            assertTrue(seen.size() > before, c[0] + " made no request");
            for (final Seen s : seen.subList(before, seen.size())) assertNull(s.header(), c[0] + " " + s);
        }
    }

    @Test
    void theSessionCookieAndTheHeaderGoTogether() throws Exception {
        SiteConfig.useHeader(NAME, VALUE);
        CloudApi.me("tok");
        final Seen s = seen.get(seen.size() - 1);
        assertEquals("gtnh_session=tok", s.cookie());
        assertEquals(VALUE, s.header());
    }

    @Test
    void aSiteThatWantsTheHeaderServesAClientThatSendsIt() throws Exception {
        requires = VALUE;
        SiteConfig.useHeader(NAME, VALUE);
        assertNotNull(CommunityApi.list(CommunityApi.Query.start(), 1, 10, null));
        assertTrue(reachedElsewhere.isEmpty());
    }

    @Test
    void aRedirectToASignInIsReportedPlainlyAndNotFollowed() {
        requires = VALUE;
        // No header configured, and a wrong one: the site answers 302 either way.
        for (final String sent : new String[] { "", "a-wrong-value" }) {
            SiteConfig.useHeader(sent.isEmpty() ? "" : NAME, sent);
            for (final Object[] c : everyKind()) {
                try {
                    ((Call) c[1]).run();
                    fail(c[0] + " went through a redirect");
                } catch (final IOException e) {
                    assertEquals(SiteHeader.SIGN_IN, e.getMessage(), c[0] + "");
                    final int status = e instanceof final CommunityApi.Refused r ? r.status
                        : ((CloudApi.Refused) e).status;
                    assertEquals(302, status);
                } catch (final Exception e) {
                    fail(c[0] + ": " + e);
                }
            }
        }
        assertTrue(reachedElsewhere.isEmpty(), "the redirect was followed: " + reachedElsewhere);
    }

    @Test
    void makingAnAccountThroughARedirectSaysAccountsAreMadeOnTheWebsite() {
        requires = VALUE;
        SiteConfig.useHeader("", "");
        final Exception e = assertThrows(java.io.IOException.class, () -> CommunityApi.register("ada", "pw"));
        assertEquals("Create your account on the website, then sign in here.", Posting.creating(e));
        assertEquals("Create your account on the website, then sign in here.", SiteHeader.CREATE_ACCOUNT);
        // Signing in, and any other refusal of an account, keep their own words.
        final Exception in = assertThrows(java.io.IOException.class, () -> CommunityApi.signIn("ada", "pw"));
        assertEquals(SiteHeader.SIGN_IN, in.getMessage());
        assertEquals("busy", Posting.creating(new java.io.IOException("busy")));
        assertTrue(SiteHeader.CREATE_ACCOUNT.length() < 140 && !SiteHeader.CREATE_ACCOUNT.contains("—"));
    }

    @Test
    void theRedirectMessageIsPlainAndShort() {
        final String m = SiteHeader.SIGN_IN;
        assertTrue(m.length() < 140, m);
        assertFalse(m.contains("—") || m.contains("302") || m.contains("Location"), m);
        assertTrue(m.startsWith("The site asked for a sign-in the mod cannot do"), m);
    }

    @Test
    void theValueIsNeverLoggedNorInAnExceptionOrARefusal() {
        requires = "something-else";
        SiteConfig.useHeader(NAME, VALUE);
        final List<String> said = new ArrayList<>();
        // Redirects, refusals with the site's own words, an error with no body, and a connection that goes nowhere.
        for (final int code : new int[] { 302, 401, 403, 404, 409, 429, 500 }) {
            requires = code == 302 ? "something-else" : "";
            status = code == 302 ? 200 : code;
            for (final Object[] c : everyKind()) {
                try {
                    ((Call) c[1]).run();
                } catch (final Exception e) {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        said.add(String.valueOf(t.getMessage()));
                        said.add(t.toString());
                    }
                }
            }
        }
        site.stop(0);
        for (final Object[] c : everyKind()) {
            try {
                ((Call) c[1]).run();
            } catch (final Exception e) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                    said.add(String.valueOf(t.getMessage()));
                    said.add(t.toString());
                }
            }
        }
        assertFalse(said.isEmpty());
        for (final String s : said) assertFalse(s.contains(VALUE), s);
        for (final String s : logged) assertFalse(s.contains(VALUE), s);
    }

    // region The WebSocket upgrade

    private static final LiveSocket.Options FAST = new LiveSocket.Options(150, 40, 160, 8, 1 << 25, 2_000);

    private static final class Events implements LiveSocket.Sink {

        final List<LiveSocket.State> states = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();

        @Override
        public void state(final LiveSocket.State state, final String reason) {
            states.add(state);
            reasons.add(reason);
        }

        @Override
        public void frame(final String text) {}
    }

    private static void await(final Events ev, final LiveSocket s, final BooleanSupplier done) throws Exception {
        final long end = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < end) {
            s.poll(ev);
            if (done.getAsBoolean()) {
                s.poll(ev);
                return;
            }
            Thread.sleep(10);
        }
        s.poll(ev);
        throw new AssertionError("timed out; states " + ev.states + " " + ev.reasons);
    }

    private LiveSocket connectTo(final LiveTestServer server, final String host) {
        return new LiveSocket(URI.create("ws://" + host + ":" + server.port() + "/live"), () -> "tok", null, FAST);
    }

    @Test
    void theUpgradeCarriesTheHeaderToTheLiveService() throws Exception {
        live = new LiveTestServer(null);
        live.watch = NAME;
        live.requireHeader = NAME + "=" + VALUE;
        CloudApi.useLive("ws://localhost:" + live.port() + "/live");
        SiteConfig.useHeader(NAME, VALUE);
        socket = connectTo(live, "localhost");
        final Events ev = new Events();
        socket.start();
        await(ev, socket, () -> socket.state() == LiveSocket.State.OPEN);
        assertEquals(List.of(VALUE), live.watched);
        assertEquals(List.of("gtnh_session=tok"), live.cookies);
        for (final String r : ev.reasons) assertFalse(r.contains(VALUE), r);
    }

    @Test
    void anUpgradeTheSiteSendsToASignInStopsPlainlyAndFollowsNowhere() throws Exception {
        live = new LiveTestServer(null);
        other = new LiveTestServer(null);
        live.requireHeader = NAME + "=" + VALUE;
        live.redirectTo = "ws://127.0.0.1:" + other.port() + "/live";
        CloudApi.useLive("ws://127.0.0.1:" + live.port() + "/live");
        // No header configured: the redirect is the answer.
        socket = connectTo(live, "127.0.0.1");
        final Events ev = new Events();
        socket.start();
        await(ev, socket, () -> socket.state() == LiveSocket.State.CLOSED && !ev.reasons.isEmpty());
        assertEquals(LiveSocket.SIGN_IN, ev.reasons.get(ev.reasons.size() - 1));
        Thread.sleep(400);
        socket.poll(ev);
        assertEquals(1, live.upgrades.get(), "no retrying a redirect");
        assertEquals(0, other.connections.get() + other.cookies.size(), "the redirect was followed");
    }

    @Test
    void theUpgradeToAnyOtherHostCarriesNoHeader() throws Exception {
        live = new LiveTestServer(null);
        live.watch = NAME;
        // The site and its live service are the stand-in site; this service is neither.
        SiteConfig.useHeader(NAME, VALUE);
        socket = connectTo(live, "localhost");
        final Events ev = new Events();
        socket.start();
        await(ev, socket, () -> socket.state() == LiveSocket.State.OPEN);
        assertEquals(List.of(""), live.watched);
    }

    // endregion
}
