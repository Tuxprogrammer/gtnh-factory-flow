package com.gtnhplanner.core.layout.arrange;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouteJudge;
import com.gtnhplanner.core.layout.RouterConformanceAccess;

/**
 * The two Arranges on the reference boards (conformance/layout/arrange.json.gz): the mod's old port
 * (com.gtnhplanner.layout.arrange, judged by its RouterJudge) and the website's (core.layout.arrange, equal to the
 * website's own to the bit), each final layout judged afresh by the same router judge on the board's own requests.
 * Writes build/bench/arrange-versus.md. {@code -Pbench --tests '*ArrangeVersus*'}.
 */
@Tag("bench")
class ArrangeVersus {

    @Test
    void versus() throws IOException {
        final StringBuilder table = new StringBuilder(
            "| board | cards | wires | spacing | old crossings | old length | old points | old ms | web crossings | web length | web points | web ms |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        final double[] totals = new double[8];
        for (final JsonElement e : ArrangeConformanceTest.read()
            .getAsJsonArray("boards")) {
            final JsonObject b = e.getAsJsonObject();
            final JsonObject judgeInput = b.getAsJsonObject("judgeInput");
            final List<ArrangeCard> cards = ArrangeConformanceTest.cards(b.getAsJsonArray("cards"));
            final List<ArrangeWire> wires = ArrangeConformanceTest.wires(b.getAsJsonArray("wires"));
            final String spacing = ArrangeConformanceTest.spacing(b.get("taste"));
            final RouteJudge judge = new RouteJudge(
                RouterConformanceAccess.obstacles(judgeInput.get("obstacles")),
                RouterConformanceAccess.requests(judgeInput.get("requests")),
                RouterConformanceAccess.tuning(judgeInput.get("tuning")),
                List.of());

            final List<com.gtnhplanner.layout.arrange.ArrangeCard> oldCards = new ArrayList<>();
            for (final ArrangeCard c : cards) oldCards.add(
                new com.gtnhplanner.layout.arrange.ArrangeCard(c.id(), c.x(), c.y(), c.width(), c.height(), c.storage()));
            final List<com.gtnhplanner.layout.arrange.ArrangeWire> oldWires = new ArrayList<>();
            for (final ArrangeWire w : wires) oldWires.add(
                new com.gtnhplanner.layout.arrange.ArrangeWire(
                    w.id(),
                    w.source(),
                    w.target(),
                    w.sourcePortY(),
                    w.targetPortY(),
                    w.weight(),
                    w.width()));
            long t0 = System.nanoTime();
            final com.gtnhplanner.layout.arrange.Arrange.Result old = com.gtnhplanner.layout.arrange.Arrange.arrange(
                new com.gtnhplanner.layout.arrange.Arrange.Input(
                    oldCards,
                    oldWires,
                    null,
                    spacing,
                    new com.gtnhplanner.layout.arrange.RouterJudge(oldCards, oldWires),
                    null,
                    null,
                    null,
                    null));
            final double oldMs = (System.nanoTime() - t0) / 1e6;
            final Map<String, GridPoint> oldAt = new LinkedHashMap<>();
            for (final Map.Entry<String, com.gtnhplanner.layout.arrange.Point> p : old.positions()
                .entrySet())
                oldAt.put(
                    p.getKey(),
                    new GridPoint(
                        p.getValue()
                            .x(),
                        p.getValue()
                            .y()));

            t0 = System.nanoTime();
            final ArrangeResult web = Arrange
                .arrangeBoard(ArrangeConformanceTest.input(b, ArrangeConformanceTest.judge(judgeInput), null));
            final double webMs = (System.nanoTime() - t0) / 1e6;
            final Map<String, GridPoint> webAt = new LinkedHashMap<>();
            for (final ArrangeResult.Move m : web.moves()) webAt.put(m.id(), m.position());

            final RouteJudge.Verdict o = judge.judge(oldAt, false, null);
            final RouteJudge.Verdict w = judge.judge(webAt, false, null);
            table.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %d | %d | %s | %d | %.0f | %.0f | %.0f | %d | %.0f | %.0f | %.0f |%n",
                    b.get("name")
                        .getAsString(),
                    cards.size(),
                    wires.size(),
                    spacing,
                    o.crossings(),
                    o.length(),
                    o.points(),
                    oldMs,
                    w.crossings(),
                    w.length(),
                    w.points(),
                    webMs));
            final double[] v = { o.crossings(), o.length(), o.points(), oldMs, w.crossings(), w.length(), w.points(),
                webMs };
            for (int i = 0; i < v.length; i++) totals[i] += v[i];
        }
        table.append(
            String.format(
                Locale.ROOT,
                "| **total** | | | | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f |%n",
                totals[0],
                totals[1],
                totals[2],
                totals[3],
                totals[4],
                totals[5],
                totals[6],
                totals[7]));
        final File out = new File("build/bench");
        out.mkdirs();
        Files.writeString(new File(out, "arrange-versus.md").toPath(), table.toString(), StandardCharsets.UTF_8);
        System.out.println(table);
    }
}
