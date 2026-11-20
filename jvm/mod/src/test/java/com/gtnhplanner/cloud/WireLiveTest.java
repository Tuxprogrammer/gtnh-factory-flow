package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.net.LiveSocket;
import com.gtnhplanner.cloud.wire.Wire;
import com.gtnhplanner.cloud.wire.WireShape;

/**
 * The mod's side of the wire contract's live socket ({@code conformance/wire/live.json}): what the mod does with each
 * answer to its upgrade and each close code, and the conversations frame by frame. The service's side of the same file
 * is src/live/wire-live.test.ts.
 */
class WireLiveTest {

    private static final JsonObject SHAPES = Wire.read("shapes.json");
    private static final JsonObject LIVE = Wire.read("live.json");
    private static final Map<String, JsonElement> VARS = Wire.vars();
    private static final LiveSocket.Options FAST = LiveSocketTest.FAST;

    private static JsonElement fill(final JsonElement v) {
        return Wire.fill(v, VARS);
    }

    private static List<JsonObject> list(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        if (o.has(key)) for (final JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsJsonObject());
        return out;
    }

    private static String str(final JsonObject o, final String key) {
        return o.get(key)
            .getAsString();
    }

    private static boolean has(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key)
            .isJsonNull();
    }

    /** What a socket handed out, in order. */
    private static final class Log implements LiveSocket.Sink {

        final List<String> pairs = new ArrayList<>();
        final List<String> frames = new ArrayList<>();

        @Override
        public void state(final LiveSocket.State state, final String reason) {
            pairs.add(state + "/" + (reason == null ? "" : reason));
        }

        @Override
        public void frame(final String text) {
            frames.add(text);
        }
    }

    private static void await(final String what, final Runnable poll, final BooleanSupplier done) throws Exception {
        final long end = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < end) {
            poll.run();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        poll.run();
        throw new AssertionError("timed out waiting for " + what);
    }

    // region Upgrade answers and close codes

    /** Runs one row of the connect or close tables: the server's doing, then what the mod ends up in and does next. */
    private static void expectMod(final JsonObject mod, final LiveSocketAndServer rig) throws Exception {
        final String state = str(mod, "state");
        final String reason = has(mod, "reason") ? str(mod, "reason") : "";
        final String pair = state + "/" + reason;
        await(
            "state " + pair + " in " + rig.log.pairs,
            () -> rig.socket.poll(rig.log),
            () -> rig.log.pairs.contains(pair) || (reason.isEmpty() && rig.log.pairs.stream()
                .anyMatch(p -> p.startsWith(state + "/"))));
        if (!has(mod, "retries")) return;
        final String retries = str(mod, "retries");
        final int tries = rig.server.cookies.size();
        if (retries.equals("never")) {
            Thread.sleep(700);
            rig.socket.poll(rig.log);
            assertEquals(tries, rig.server.cookies.size(), "no new try after " + pair);
        } else if (retries.startsWith("after Retry-After")) {
            Thread.sleep(1000);
            assertEquals(tries, rig.server.cookies.size(), "waits for the Retry-After");
            await("a try after the wait", () -> rig.socket.poll(rig.log), () -> rig.server.cookies.size() > tries);
        } else {
            await(
                "a new try after " + pair,
                () -> rig.socket.poll(rig.log),
                () -> rig.server.cookies.size() > tries || rig.server.connections.get() > 1);
        }
    }

    private static final class LiveSocketAndServer implements AutoCloseable {

        final LiveTestServer server;
        final LiveSocket socket;
        final Log log = new Log();

        LiveSocketAndServer() throws Exception {
            server = new LiveTestServer(null);
            socket = new LiveSocket(URI.create("ws://127.0.0.1:" + server.port() + "/live"), () -> "tok", null, FAST);
        }

        @Override
        public void close() {
            socket.stop();
            server.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> everyAnswerToTheUpgrade() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonObject row : list(LIVE, "connect")) {
            final String id = str(row, "id");
            tests.add(DynamicTest.dynamicTest("connect " + id, () -> {
                try (LiveSocketAndServer rig = new LiveSocketAndServer()) {
                    final int status = row.get("status")
                        .getAsInt();
                    if (status != 101) {
                        rig.server.upgradeStatus = status;
                        if (row.has("headers") && row.getAsJsonObject("headers")
                            .has("Retry-After"))
                            rig.server.retryAfter = row.getAsJsonObject("headers")
                                .get("Retry-After")
                                .getAsString();
                    } else {
                        final List<String> frames = new ArrayList<>();
                        for (final JsonObject f : list(row, "frames")) {
                            assertEquals(List.of(), WireShape.problems(SHAPES, f.get("shape"), f.get("example")), id);
                            frames.add(fill(f.get("example")).toString());
                        }
                        rig.server.greet = frames;
                        rig.server.greetClose = has(row, "close") ? row.get("close")
                            .getAsInt() : 0;
                    }
                    rig.socket.start();
                    expectMod(row.getAsJsonObject("mod"), rig);
                    // What the mod hears before the close is the example frame, whole.
                    for (final JsonObject f : list(row, "frames")) {
                        assertTrue(
                            rig.log.frames.stream()
                                .anyMatch(
                                    t -> WireShape
                                        .expectProblems(
                                            new JsonParser().parse(t),
                                            fill(f.get("expect")).getAsJsonObject())
                                        .isEmpty()),
                            id + ": the mod hears the error frame");
                    }
                }
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyCloseCode() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonObject row : list(LIVE, "close")) {
            final int code = row.get("code")
                .getAsInt();
            tests.add(DynamicTest.dynamicTest("close " + code, () -> {
                try (LiveSocketAndServer rig = new LiveSocketAndServer()) {
                    rig.socket.start();
                    await(
                        "open",
                        () -> rig.socket.poll(rig.log),
                        () -> rig.socket.state() == LiveSocket.State.OPEN && rig.server.clients.size() == 1);
                    rig.server.closeAll(code);
                    expectMod(row.getAsJsonObject("mod"), rig);
                }
            }));
        }
        return tests.stream();
    }

    // endregion

    // region Conversations

    /** What the mod's client has been told, one object per frame type holding the last frame of it. */
    private static final class Heard implements LiveClient.Listener {

        final JsonObject last = new JsonObject();
        final Map<String, Integer> count = new HashMap<>();

        private void put(final String type, final JsonObject v) {
            last.add(type, v);
            count.merge(type, 1, Integer::sum);
        }

        int count(final String type) {
            return count.getOrDefault(type, 0);
        }

        private static JsonObject person(final String id, final String username) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("username", username);
            return o;
        }

        private static JsonArray members(final List<LiveClient.Member> list) {
            final JsonArray a = new JsonArray();
            for (final LiveClient.Member m : list) a.add(person(m.id(), m.username()));
            return a;
        }

        private static JsonArray present(final List<LiveClient.Person> list) {
            final JsonArray a = new JsonArray();
            for (final LiveClient.Person p : list) {
                final JsonObject o = person(p.id(), p.username());
                o.addProperty("color", p.color());
                o.addProperty("client", p.client());
                o.addProperty("conn", p.conn());
                a.add(o);
            }
            return a;
        }

        private static JsonObject obj(final Object... kv) {
            final JsonObject o = new JsonObject();
            for (int i = 0; i < kv.length; i += 2) {
                final String k = (String) kv[i];
                final Object v = kv[i + 1];
                if (v == null) o.add(k, JsonNull.INSTANCE);
                else if (v instanceof JsonElement e) o.add(k, e);
                else if (v instanceof Number n) o.addProperty(k, n);
                else o.addProperty(k, v.toString());
            }
            return o;
        }

        @Override
        public void onWelcome(final LiveClient.Person p) {
            put("welcome", obj("you", obj("id", p.id(), "username", p.username(), "color", p.color())));
        }

        @Override
        public void onSnapshot(final LiveClient.Snapshot s) {
            put(
                "snapshot",
                obj(
                    "doc",
                    s.doc(),
                    "seq",
                    s.seq(),
                    "role",
                    s.role(),
                    "owner",
                    s.owner() == null ? null
                        : person(
                            s.owner()
                                .id(),
                            s.owner()
                                .username()),
                    "members",
                    members(s.members()),
                    "present",
                    present(s.present())));
        }

        @Override
        public void onOps(final LiveClient.OpsBatch b) {
            put("ops", obj("doc", b.doc(), "seq", b.seq(), "by", b.by(), "cid", b.cid(), "ops", b.ops()));
        }

        @Override
        public void onPresence(final LiveClient.Presence p) {
            put(
                "presence",
                obj(
                    "doc",
                    p.doc(),
                    "who",
                    p.who(),
                    "conn",
                    p.conn(),
                    "cursor",
                    p.cursor(),
                    "sel",
                    p.sel(),
                    "view",
                    p.view()));
        }

        @Override
        public void onPresent(final String doc, final List<LiveClient.Person> list) {
            put("present", obj("doc", doc, "present", present(list)));
        }

        @Override
        public void onMembers(final String doc, final List<LiveClient.Member> list) {
            put("members", obj("doc", doc, "members", members(list)));
        }

        @Override
        public void onKicked(final String doc, final String reason) {
            put("kicked", obj("doc", doc, "reason", reason));
        }

        @Override
        public void onError(final String doc, final String code, final String message) {
            put("error", obj("doc", doc, "code", code, "msg", message));
        }

        @Override
        public void onShared(final String doc, final LiveClient.Member owner, final String name) {
            put("shared", obj("doc", doc, "owner", person(owner.id(), owner.username()), "name", name));
        }

        @Override
        public void onUnshared(final String doc) {
            put("unshared", obj("doc", doc));
        }

        @Override
        public void onRefused(final String doc, final String cid, final String code, final String message) {
            put("refused", obj("doc", doc, "cid", cid, "code", code));
        }
    }

    private static JsonObject script(final String id) {
        for (final JsonObject s : list(LIVE, "scripts")) if (id.equals(str(s, "id"))) return s;
        throw new IllegalArgumentException(id);
    }

    /** A client step and the server frames the service sends in answer, up to the next client step. */
    private record Turn(JsonObject client, List<JsonObject> answers) {}

    private static List<Turn> turns(final JsonObject script) {
        final List<Turn> out = new ArrayList<>();
        JsonObject client = null;
        List<JsonObject> answers = new ArrayList<>();
        for (final JsonObject step : list(script, "steps")) {
            if ("client".equals(str(step, "dir"))) {
                if (client != null) out.add(new Turn(client, answers));
                client = step;
                answers = new ArrayList<>();
            } else {
                answers.add(step);
            }
        }
        if (client != null) out.add(new Turn(client, answers));
        return out;
    }

    private static final class Rig implements AutoCloseable {

        final LiveTestServer server;
        final LiveClient client;
        final Heard heard = new Heard();

        Rig() throws Exception {
            server = new LiveTestServer(null);
            client = new LiveClient(
                URI.create("ws://127.0.0.1:" + server.port() + "/live"),
                () -> "tok",
                new LiveClient.Hello("0.2.0-test", "srv1", "Alex"),
                null,
                FAST);
            client.addListener(heard);
        }

        List<String> sent() {
            final List<String> out = new ArrayList<>();
            for (final String s : server.received) if (!s.contains("\"t\":\"ping\"")) out.add(s);
            return out;
        }

        void until(final String what, final BooleanSupplier done) throws Exception {
            await(what, client::poll, done);
        }

        @Override
        public void close() {
            client.stop();
            server.close();
        }
    }

    private static void checkHeard(final Rig rig, final JsonObject heard, final String where) {
        if (!heard.has("heard")) return;
        for (final Map.Entry<String, JsonElement> e : heard.getAsJsonObject("heard")
            .entrySet()) {
            final JsonElement got = WireShape.valueAt(rig.heard.last, e.getKey());
            assertEquals(fill(e.getValue()), got, where + ": the mod hears " + e.getKey() + " in " + rig.heard.last);
        }
    }

    /** Does what the step's {@code via} says on the mod's side. */
    private static void act(final Rig rig, final JsonObject step) {
        final String via = has(step, "via") ? str(step, "via") : "";
        final String doc = via.contains(":")
            ? fill(new JsonPrimitive(via.substring(via.indexOf(':') + 1))).getAsString()
            : "";
        switch (via.contains(":") ? via.substring(0, via.indexOf(':')) : via) {
            case "open" -> rig.client.start();
            case "join" -> rig.client.join(doc);
            case "leave" -> rig.client.leave(doc);
            case "presence" -> {
                final JsonObject view = new JsonObject();
                view.addProperty("x", 0);
                view.addProperty("y", 0);
                view.addProperty("zoom", 1);
                rig.client.sendPresence(doc, LiveClient.at(4, 8), List.of("n1"), view);
            }
            case "ops" -> {
                final JsonObject expect = step.has("expect") ? step.getAsJsonObject("expect") : new JsonObject();
                final JsonObject op = new JsonObject();
                if (step.has("frame")) {
                    final JsonObject f = fill(step.get("frame")).getAsJsonObject();
                    assertTrue(
                        rig.client.sendOps(
                            doc,
                            f.get("base")
                                .getAsLong(),
                            str(f, "cid"),
                            f.getAsJsonArray("ops")));
                    return;
                }
                op.addProperty("o", "patch");
                op.addProperty("c", "tasks");
                op.addProperty("id", "t1");
                final JsonObject fields = new JsonObject();
                fields.addProperty("title", "Second");
                op.add("f", fields);
                final JsonArray ops = new JsonArray();
                ops.add(op);
                assertTrue(
                    rig.client.sendOps(
                        doc,
                        expect.get("base")
                            .getAsLong(),
                        str(expect, "cid"),
                        ops));
            }
            default -> throw new IllegalStateException("the contract names a step the test cannot do: " + via);
        }
    }

    private static void checkSent(final JsonObject step, final String text, final String where) {
        final JsonElement frame = new JsonParser().parse(text);
        assertEquals(List.of(), WireShape.problems(SHAPES, step.get("shape"), frame), where + " shape in " + text);
        if (step.has("expect")) {
            assertEquals(
                List.of(),
                WireShape.expectProblems(frame, fill(step.get("expect")).getAsJsonObject()),
                where + " expect in " + text);
        }
    }

    /** One scripted conversation from the mod's side: the mod acts, the stand-in answers from the contract. */
    private static void converse(final String id) throws Exception {
        final List<Turn> all = turns(script(id));
        // A ping comes on the socket's own timer, not in step: it is checked on its own, and the stock pong stands.
        final List<Turn> turns = new ArrayList<>();
        boolean pinged = false;
        for (final Turn t : all) {
            if ("ping".equals(str(t.client(), "via"))) pinged = true;
            else turns.add(t);
        }
        try (Rig rig = new Rig()) {
            int sent = 0;
            if (!"open".equals(
                str(
                    turns.get(0)
                        .client(),
                    "via"))) {
                // The script starts with the client already in the room: hello and join, answered the stock way.
                rig.client.start();
                rig.client.join(fill(new JsonPrimitive("{doc}")).getAsString());
                rig.until("the room", () -> rig.heard.count("snapshot") == 1);
                sent = 2;
            }
            final int[] next = { 0 };
            rig.server.script = frame -> {
                final int at = next[0]++;
                final List<String> out = new ArrayList<>();
                if (at < turns.size()) for (final JsonObject a : turns.get(at)
                    .answers()) out.add(fill(a.get("example")).toString());
                return out;
            };
            for (int i = 0; i < turns.size(); i++) {
                final Turn t = turns.get(i);
                final String where = id + " step " + i + " (" + str(t.client(), "via") + ")";
                final Map<String, Integer> before = new HashMap<>(rig.heard.count);
                act(rig, t.client());
                final int want = ++sent;
                rig.until(
                    where + " sent",
                    () -> rig.sent()
                        .size() >= want);
                checkSent(
                    t.client(),
                    rig.sent()
                        .get(want - 1),
                    where);
                for (final JsonObject a : t.answers()) {
                    final String type = str(
                        a.get("example")
                            .getAsJsonObject(),
                        "t");
                    if (type.equals("pong")) continue;
                    final int was = before.getOrDefault(type, 0);
                    rig.until(where + " heard " + type, () -> rig.heard.count(type) > was);
                    checkHeard(rig, a, where);
                }
            }
            if (pinged) {
                rig.until("a ping", () -> rig.server.pings.get() >= 1);
                final JsonObject step = list(script(id), "steps").stream()
                    .filter(s -> has(s, "via") && str(s, "via").equals("ping"))
                    .findFirst()
                    .get();
                checkSent(
                    step,
                    rig.server.framesOf("ping")
                        .get(0)
                        .toString(),
                    id + " ping");
            }
            // Every frame the mod sent is one of the contract's client shapes.
            for (final String text : rig.server.received) {
                final JsonObject f = new JsonParser().parse(text)
                    .getAsJsonObject();
                final String type = str(f, "t");
                final String name = "$Client" + Character.toUpperCase(type.charAt(0)) + type.substring(1);
                assertEquals(List.of(), WireShape.problems(SHAPES, new JsonPrimitive(name), f), text);
            }
        }
    }

    @Test
    void theGameClientEditsAPlan() throws Exception {
        converse("game-edit");
    }

    @Test
    void theServiceRefusesWhatItWillNotDo() throws Exception {
        converse("refusals");
    }

    @Test
    void theMouthOfTheRoomIsHeard() throws Exception {
        try (Rig rig = new Rig()) {
            final String doc = fill(new JsonPrimitive("{doc}")).getAsString();
            rig.client.start();
            rig.client.join(doc);
            rig.until("the room", () -> rig.heard.count("snapshot") == 1);
            int i = 0;
            for (final JsonObject step : list(script("room-events"), "steps")) {
                final String type = str(
                    step.get("example")
                        .getAsJsonObject(),
                    "t");
                final int was = rig.heard.count(type);
                rig.server.send(fill(step.get("example")).toString());
                final String where = "room-events step " + i++ + " (" + type + ")";
                rig.until(where, () -> rig.heard.count(type) > was);
                checkHeard(rig, step, where);
            }
        }
    }

    @Test
    void everyServerExampleMatchesItsShape() {
        for (final JsonObject s : list(LIVE, "scripts")) {
            for (final JsonObject step : list(s, "steps")) {
                if (!"server".equals(str(step, "dir"))) continue;
                final JsonElement example = fill(step.get("example"));
                assertEquals(
                    List.of(),
                    WireShape.problems(SHAPES, step.get("shape"), example),
                    str(s, "id") + " " + example);
                if (step.has("expect")) {
                    assertEquals(
                        List.of(),
                        WireShape.expectProblems(example, fill(step.get("expect")).getAsJsonObject()),
                        str(s, "id") + " " + example);
                }
            }
        }
    }

    // endregion
}
