package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.wire.Wire;
import com.gtnhplanner.cloud.wire.WireShape;
import com.gtnhplanner.library.CommunityApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The mod's side of the wire contract's REST part ({@code conformance/wire/rest.json}): each step runs one client call
 * against a stand-in that answers with the step's example, then checks what the client sent (method, path with query,
 * the session cookie only where the step sends one, headers, body) and what it made of the answer, errors included. The
 * website's side of the same steps is src/lib/server/wire-rest.test.ts.
 */
class WireRestTest {

    private static final JsonObject SHAPES = Wire.read("shapes.json");
    private static final JsonObject REST = Wire.read("rest.json");

    /** One request as the stand-in saw it. */
    private record Seen(String method, String uri, String cookie, String accept, String agent, String type,
        String length, String body) {}

    /** What the stand-in sends back. */
    private record Answer(int status, String body, Map<String, String> headers) {}

    private static HttpServer server;
    private static final List<Seen> seen = new ArrayList<>();
    private static final Deque<Answer> answers = new ArrayDeque<>();
    private static String site, live;
    private static final Map<String, JsonElement> vars = new HashMap<>();

    @BeforeAll
    static void start() throws IOException {
        site = CommunityApi.site();
        live = CloudApi.live();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", WireRestTest::answer);
        server.start();
        final int port = server.getAddress()
            .getPort();
        CommunityApi.useSite("http://127.0.0.1:" + port);
        CloudApi.useLive("ws://127.0.0.1:" + port + "/live");
        vars.putAll(Wire.vars());
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        CommunityApi.useSite(site);
        CloudApi.useLive(live.isEmpty() ? null : live);
    }

    private static void answer(final HttpExchange ex) throws IOException {
        final byte[] in = ex.getRequestBody()
            .readAllBytes();
        final Answer a;
        synchronized (answers) {
            seen.add(
                new Seen(
                    ex.getRequestMethod(),
                    ex.getRequestURI()
                        .toString(),
                    ex.getRequestHeaders()
                        .getFirst("Cookie"),
                    ex.getRequestHeaders()
                        .getFirst("Accept"),
                    ex.getRequestHeaders()
                        .getFirst("User-Agent"),
                    ex.getRequestHeaders()
                        .getFirst("Content-Type"),
                    ex.getRequestHeaders()
                        .getFirst("Content-Length"),
                    new String(in, StandardCharsets.UTF_8)));
            a = answers.poll();
        }
        if (a == null) {
            ex.sendResponseHeaders(500, -1);
            ex.close();
            return;
        }
        final byte[] out = a.body()
            .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders()
            .set("Content-Type", "application/json");
        for (final Map.Entry<String, String> h : a.headers()
            .entrySet())
            ex.getResponseHeaders()
                .add(h.getKey(), h.getValue());
        ex.sendResponseHeaders(a.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
        ex.close();
    }

    private static JsonObject obj(final Object... kv) {
        final JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            final Object v = kv[i + 1];
            final String k = (String) kv[i];
            if (v == null) o.add(k, JsonNull.INSTANCE);
            else if (v instanceof JsonElement e) o.add(k, e);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else if (v instanceof Number n) o.addProperty(k, n);
            else o.addProperty(k, v.toString());
        }
        return o;
    }

    private static JsonObject members(final List<LiveClient.Member> list) {
        return obj("members", memberArray(list));
    }

    private static JsonArray memberArray(final List<LiveClient.Member> list) {
        final JsonArray a = new JsonArray();
        for (final LiveClient.Member m : list) a.add(obj("id", m.id(), "username", m.username()));
        return a;
    }

    private static JsonElement designView(@Nullable final CloudApi.Design d) {
        return d == null ? JsonNull.INSTANCE
            : obj(
                "id",
                d.id(),
                "name",
                d.name(),
                "ownerId",
                d.ownerId(),
                "shared",
                d.shared(),
                "deletedAt",
                d.deletedAt());
    }

    // region The client calls, one adapter per contract client name

    private static String s(final JsonArray a, final int i) {
        return a.get(i)
            .isJsonNull() ? null
                : a.get(i)
                    .getAsString();
    }

    private static JsonObject o(final JsonArray a, final int i) {
        return a.get(i)
            .isJsonNull() ? null
                : a.get(i)
                    .getAsJsonObject();
    }

    private static JsonElement nullable(final JsonObject o, final String key) {
        return o.has(key) ? o.get(key) : JsonNull.INSTANCE;
    }

    private static CloudApi.Upsert upsert(final JsonObject u) {
        return new CloudApi.Upsert(
            u.get("name")
                .getAsString(),
            u.get("icon")
                .isJsonNull() ? null
                    : u.get("icon")
                        .getAsJsonObject(),
            u.get("folderId")
                .isJsonNull() ? null
                    : u.get("folderId")
                        .getAsString(),
            u.get("closed")
                .getAsBoolean(),
            u.get("favorite")
                .getAsBoolean(),
            u.get("order")
                .isJsonNull() ? null
                    : u.get("order")
                        .getAsInt(),
            u.get("createdAt")
                .getAsString(),
            u.get("updatedAt")
                .getAsString(),
            u.get("planUpdatedAt")
                .getAsString(),
            !u.has("plan") || u.get("plan")
                .isJsonNull() ? null
                    : u.get("plan")
                        .getAsJsonObject());
    }

    private static JsonElement call(final String client, final JsonArray a) throws IOException {
        switch (client) {
            case "CommunityApi.register": {
                final CommunityApi.SignedIn in = CommunityApi.register(s(a, 0), s(a, 1));
                return obj("username", in.username(), "token", in.token());
            }
            case "CommunityApi.signIn": {
                final CommunityApi.SignedIn in = CommunityApi.signIn(s(a, 0), s(a, 1));
                return obj("username", in.username(), "token", in.token());
            }
            case "CommunityApi.whoIs": {
                final String who = CommunityApi.whoIs(s(a, 0));
                return who == null ? JsonNull.INSTANCE : obj("username", who);
            }
            case "CloudApi.me": {
                final CloudApi.Account me = CloudApi.me(s(a, 0));
                return me == null ? JsonNull.INSTANCE : obj("id", me.id(), "username", me.username());
            }
            case "CloudApi.library":
                return CloudApi.library(s(a, 0), s(a, 1))
                    .toJson();
            case "CloudApi.put": {
                final CloudApi.Put p = CloudApi.put(s(a, 0), s(a, 1), s(a, 2), upsert(o(a, 3)));
                return obj("behind", p.behind(), "live", p.live(), "design", designView(p.design()));
            }
            case "CloudApi.plan": {
                final CloudApi.Fetched f = CloudApi.plan(s(a, 0), s(a, 1), s(a, 2));
                return obj("role", f.role(), "seq", f.seq(), "design", designView(f.design()), "plan", f.plan());
            }
            case "CloudApi.delete":
                CloudApi.delete(s(a, 0), s(a, 1));
                return obj();
            case "CloudApi.members":
                return members(CloudApi.members(s(a, 0), s(a, 1), s(a, 2)));
            case "CloudApi.sharing": {
                final CloudApi.Sharing sh = CloudApi.sharing(s(a, 0), s(a, 1), s(a, 2));
                return obj(
                    "ownerId",
                    sh.ownerId(),
                    "ownerName",
                    sh.ownerName(),
                    "role",
                    sh.role(),
                    "members",
                    memberArray(sh.members()));
            }
            case "CloudApi.addMember":
                CloudApi.addMember(s(a, 0), s(a, 1), s(a, 2), s(a, 3));
                return obj();
            case "CloudApi.removeMember":
                CloudApi.removeMember(s(a, 0), s(a, 1), s(a, 2), s(a, 3));
                return obj();
            case "CloudApi.search": {
                final JsonArray out = new JsonArray();
                for (final CloudApi.Account x : CloudApi.search(s(a, 0), s(a, 1)))
                    out.add(obj("id", x.id(), "username", x.username()));
                return out;
            }
            case "CloudApi.nearby": {
                final List<String> names = new ArrayList<>();
                for (final JsonElement n : a.get(1)
                    .getAsJsonArray()) names.add(n.getAsString());
                final JsonArray out = new JsonArray();
                for (final CloudApi.Player x : CloudApi.nearby(s(a, 0), names))
                    out.add(obj("id", x.id(), "username", x.username(), "mcName", x.mcName()));
                return out;
            }
            case "CloudApi.hasLive":
                return obj("live", CloudApi.hasLive(s(a, 0)));
            case "CommunityApi.post": {
                final JsonObject p = o(a, 1);
                return obj(
                    "id",
                    CommunityApi.post(
                        s(a, 0),
                        new CommunityApi.Post(
                            p.get("name")
                                .getAsString(),
                            p.get("description")
                                .getAsString(),
                            p.get("icon")
                                .isJsonNull() ? null
                                    : p.get("icon")
                                        .getAsJsonObject(),
                            p.get("gameVersion")
                                .getAsString(),
                            p.get("deviceId")
                                .getAsString(),
                            p.get("plan")
                                .getAsJsonObject())));
            }
            case "CommunityApi.list": {
                final JsonObject q = o(a, 0);
                final List<String> makes = new ArrayList<>();
                for (final JsonElement m : q.getAsJsonArray("makes")) makes.add(m.getAsString());
                final CommunityApi.Page page = CommunityApi.list(
                    new CommunityApi.Query(
                        q.get("search")
                            .getAsString(),
                        q.get("sort")
                            .getAsString(),
                        q.get("maxTierIndex")
                            .getAsInt(),
                        q.get("gameVersion")
                            .getAsString(),
                        makes,
                        q.get("mine")
                            .getAsBoolean()),
                    a.get(1)
                        .getAsInt(),
                    a.get(2)
                        .getAsInt(),
                    s(a, 3));
                final JsonArray setups = new JsonArray();
                for (final CommunityApi.Setup x : page.setups())
                    setups.add(obj("id", x.id(), "name", x.name(), "authorName", x.author(), "mine", x.mine()));
                final JsonArray versions = new JsonArray();
                for (final String v : page.gameVersions()) versions.add(new com.google.gson.JsonPrimitive(v));
                return obj("total", page.total(), "page", page.page(), "setups", setups, "gameVersions", versions);
            }
            case "CommunityApi.download": {
                final CommunityApi.Download d = CommunityApi.download(s(a, 0));
                return obj(
                    "name",
                    d.name(),
                    "plan",
                    new JsonParser().parse(d.planJson()));
            }
            case "CommunityApi.update":
                CommunityApi.update(s(a, 0), s(a, 1), o(a, 2));
                return obj();
            case "CommunityApi.delete":
                CommunityApi.delete(s(a, 0), s(a, 1));
                return obj();
            case "CommunityApi.report": {
                final CommunityApi.Sent sent = CommunityApi.report(o(a, 0));
                return obj("number", sent.number(), "url", sent.url());
            }
            case "CommunityApi.icon":
                return CommunityApi.icon(s(a, 0), s(a, 1), s(a, 2), s(a, 3));
            default:
                throw new IllegalStateException("the contract names a client call this test does not know: " + client);
        }
    }

    /** Where the session token sits in a client call's arguments, or -1 when the call sends none. */
    private static int tokenAt(final String client) {
        switch (client) {
            case "CommunityApi.register":
            case "CommunityApi.signIn":
            case "CommunityApi.download":
            case "CommunityApi.report":
            case "CommunityApi.icon":
                return -1;
            case "CommunityApi.list":
                return 3;
            default:
                return 0;
        }
    }

    // endregion

    private static List<JsonObject> steps() {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonElement e : REST.getAsJsonArray("steps")) out.add(e.getAsJsonObject());
        return out;
    }

    private static JsonObject step(final String id) {
        for (final JsonObject s : steps()) if (id.equals(
            s.get("id")
                .getAsString()))
            return s;
        throw new IllegalArgumentException(id);
    }

    private static boolean has(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key)
            .isJsonNull();
    }

    private static Answer answerFor(final JsonObject step) {
        final Map<String, String> headers = new HashMap<>();
        if (step.has("headers")) for (final Map.Entry<String, JsonElement> h : step.getAsJsonObject("headers")
            .entrySet()) {
            headers.put(
                h.getKey(),
                h.getValue()
                    .getAsJsonObject()
                    .get("example")
                    .getAsString());
        }
        final JsonElement example = step.has("example") ? Wire.fill(step.get("example"), vars) : null;
        return new Answer(
            step.get("status")
                .getAsInt(),
            example == null ? "" : example.toString(),
            headers);
    }

    /** Checks one request the client made against the step that names it. */
    private static void checkRequest(final JsonObject step, final Seen got, final String token) {
        final String id = step.get("id")
            .getAsString();
        final JsonObject want = step.getAsJsonObject("request");
        assertEquals(
            want.get("method")
                .getAsString(),
            got.method(),
            id + " method");
        assertEquals(
            Wire.fill(want.get("path"), vars)
                .getAsString(),
            got.uri(),
            id + " path");
        if ("session".equals(
            want.get("auth")
                .getAsString())) {
            assertEquals("gtnh_session=" + token, got.cookie(), id + " cookie");
        } else {
            assertEquals(null, got.cookie(), id + " sends no session where the contract says none");
        }
        assertEquals("application/json", got.accept(), id + " Accept");
        assertTrue(
            got.agent() != null && got.agent()
                .startsWith("GTNH Planner/"),
            id + " User-Agent " + got.agent());
        if (has(want, "body")) {
            assertEquals("application/json", got.type(), id + " Content-Type");
            final JsonElement body = new JsonParser().parse(got.body());
            assertEquals(Wire.fill(want.get("body"), vars), body, id + " body");
            if (want.has("bodyShape")) {
                assertEquals(List.of(), WireShape.problems(SHAPES, want.get("bodyShape"), body), id + " body shape");
            }
            if (want.has("bodyExpect")) {
                assertEquals(
                    List.of(),
                    WireShape.expectProblems(body, Wire.fill(want.get("bodyExpect"), vars)
                        .getAsJsonObject()),
                    id + " body expect");
            }
        } else {
            assertEquals("", got.body(), id + " sends no body");
            // A bodyless POST carries the JDK's own form type; the site reads no body there.
            assertTrue(
                got.type() == null || got.type()
                    .equals("application/x-www-form-urlencoded"),
                id + " sends no JSON Content-Type without a body: " + got.type());
        }
    }

    private static void run(final JsonObject head) throws Exception {
        final String id = head.get("id")
            .getAsString();
        // A client call that makes two requests is two steps, the second one naming the first as what it joins.
        final List<JsonObject> group = new ArrayList<>();
        group.add(head);
        for (final JsonObject s : steps()) if (has(s, "joins") && id.equals(
            s.get("joins")
                .getAsString()))
            group.add(s);
        synchronized (answers) {
            seen.clear();
            answers.clear();
            for (final JsonObject s : group) answers.add(answerFor(s));
        }
        final JsonArray args = Wire.fill(head.get("args"), vars)
            .getAsJsonArray();
        final String client = head.get("client")
            .getAsString();
        JsonElement view = null;
        Exception failed = null;
        try {
            view = call(client, args);
        } catch (final IOException e) {
            failed = e;
        }
        final List<Seen> made;
        synchronized (answers) {
            made = new ArrayList<>(seen);
        }
        assertEquals(group.size(), made.size(), id + ": requests made");
        final int at = tokenAt(client);
        final String token = at < 0 || args.get(at)
            .isJsonNull() ? null
                : args.get(at)
                    .getAsString();
        for (int i = 0; i < group.size(); i++) {
            checkRequest(group.get(i), made.get(i), token);
        }

        final JsonObject result = head.getAsJsonObject("result");
        final int status = head.get("status")
            .getAsInt();
        if (result.has("ok")) {
            assertEquals(null, failed, id + ": the call went through");
            assertEquals(
                List.of(),
                WireShape.expectProblems(view, Wire.fill(result.get("ok"), vars)
                    .getAsJsonObject()),
                id + " result");
        } else if (result.has("null")) {
            assertEquals(null, failed, id + ": the call went through");
            assertEquals(JsonNull.INSTANCE, view, id + " result is null");
        } else if (result.has("refused")) {
            assertNotNull(failed, id + ": the call is refused");
            final String reason = result.get("refused")
                .getAsString();
            final int got;
            if (failed instanceof CloudApi.Refused r) got = r.status;
            else if (failed instanceof CommunityApi.Refused r) got = r.status;
            else throw new AssertionError(id + ": expected a refusal, got " + failed, failed);
            assertEquals(status, got, id + " refused status");
            assertEquals(reason, failed.getMessage(), id + " refused reason");
        } else if (result.has("ioerror")) {
            assertNotNull(failed, id + ": the call fails");
            assertFalse(failed instanceof CloudApi.Refused || failed instanceof CommunityApi.Refused, id + ": " + failed);
        } else {
            throw new AssertionError(id + ": a result the test does not know: " + result);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyStepOfTheContract() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonObject s : steps()) {
            final String id = s.get("id")
                .getAsString();
            // The second half of a two-request call runs with its first.
            if (has(s, "joins")) continue;
            tests.add(DynamicTest.dynamicTest(id, () -> run(s)));
        }
        return tests.stream();
    }

    @Test
    void everyExampleAndBodyMatchesItsShape() {
        for (final JsonObject s : steps()) {
            final String id = s.get("id")
                .getAsString();
            final JsonElement example = Wire.fill(s.get("example"), vars);
            assertEquals(List.of(), WireShape.problems(SHAPES, s.get("shape"), example), id + " example");
            if (s.has("expect")) {
                assertEquals(
                    List.of(),
                    WireShape.expectProblems(example, Wire.fill(s.get("expect"), vars)
                        .getAsJsonObject()),
                    id + " example holds what the step expects");
            }
            final JsonObject req = s.getAsJsonObject("request");
            if (req.has("bodyShape") && has(req, "body")) {
                assertEquals(
                    List.of(),
                    WireShape.problems(SHAPES, req.get("bodyShape"), Wire.fill(req.get("body"), vars)),
                    id + " body");
            }
        }
    }

    @Test
    void aStepOnlyDependsOnWhatAnEarlierStepGave() {
        // A step's {post.id}-style variable is one the run knows; a hole fails the fill.
        for (final JsonObject s : steps()) Wire.fill(s, vars);
        assertInstanceOf(JsonObject.class, step("login-ok"));
    }
}
