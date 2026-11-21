package com.gtnhplanner.web.exports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.TextStyle;
import com.gtnhplanner.core.view.card.CardGeometry;
import com.gtnhplanner.core.view.card.CardInput;
import com.gtnhplanner.core.view.card.CardInputJson;
import com.gtnhplanner.core.view.card.CardView;
import com.gtnhplanner.core.view.card.CardViews;
import com.gtnhplanner.core.view.rails.NodeRails;
import com.gtnhplanner.core.view.special.SpecialInput;
import com.gtnhplanner.core.view.special.SpecialViews;
import com.gtnhplanner.web.wire.ViewJs;

/**
 * core-view for JavaScript: a recipe card's whole view from its facts. The website's path is one call per card with
 * the records as JavaScript objects ({@link #viewCardJs}: jvm/web/README.md, "Records across the boundary"): in a
 * {@code CardInput.Case} ({@code {dials, card}}), out {@code {view: CardView, geometry: CardGeometry}} with the
 * website's geometry ({@code CardGeometry.web}). The same answers as JSON text, one call per card or per board, stay
 * for the conformance run and the tests (record convention: conformance/view/README.md).
 *
 * <p>
 * The words a card shows are the core's {@link Text} keys ({@code gtnhplanner.card.*}); the host hands its word table
 * once ({@link #viewCardWords}), else a card reads its keys. Widths come from the host: a measure object whose
 * {@code width(style, text)} answers in board px for a {@link TextStyle} name ({@code *Measured}, {@link #viewCards}),
 * or the shared cases' six px a character, nine for the row figure ({@link #viewCard}, {@link #viewCardsSix}).
 */
public final class ViewExports {

    private ViewExports() {}

    /** The host's text widths: {@code width(style, text)}, style a {@link TextStyle} name. */
    public interface CardMeasure extends JSObject {}

    @JSBody(params = { "m", "style", "text" }, script = "return m.width(style, text);")
    private static native double width(CardMeasure m, String style, String text);

    /** The shared view cases' measure ({@code six-px}): six px a character, nine for the row figure. */
    private static final TextMeasure SIX = (style, text) -> (style == TextStyle.ROW_FIGURE ? 9.0 : 6.0) * text.length();

    private static final TextStyle[] STYLES = TextStyle.values();
    private static final String[] STYLE_NAMES = new String[STYLES.length];
    static {
        for (int i = 0; i < STYLES.length; i++) STYLE_NAMES[i] = STYLES[i].name();
    }

    private static TextMeasure measure(final CardMeasure m) {
        return (style, text) -> width(m, STYLE_NAMES[style.ordinal()], text);
    }

    /**
     * Widths the host measured, by style and text, kept while its font state stays the one they were measured in
     * ({@link #viewCardJsKept}): a width asked again crosses no boundary. Dropped whole when the state changes or past
     * {@link #KEPT_WIDTHS_MAX} entries (a board's names and figures are a few thousand).
     */
    private static final List<Map<String, Double>> KEPT_WIDTHS = new ArrayList<>();
    static {
        for (int i = 0; i < STYLES.length; i++) KEPT_WIDTHS.add(new HashMap<>());
    }
    private static final int KEPT_WIDTHS_MAX = 50_000;
    private static int keptFontState;
    private static int keptWidths;

    private static TextMeasure keptMeasure(final CardMeasure m, final int fontState) {
        if (fontState != keptFontState || keptWidths > KEPT_WIDTHS_MAX) {
            for (final Map<String, Double> widths : KEPT_WIDTHS) widths.clear();
            keptFontState = fontState;
            keptWidths = 0;
        }
        return (style, text) -> {
            final Map<String, Double> widths = KEPT_WIDTHS.get(style.ordinal());
            final Double kept = widths.get(text);
            if (kept != null) return kept;
            final double w = width(m, STYLE_NAMES[style.ordinal()], text);
            widths.put(text, w);
            keptWidths++;
            return w;
        };
    }

    /**
     * Installs the host's words as the core's {@link Text}: a JSON object of key to pattern (a language file's
     * {@code %s}, {@code %n$s} and {@code %%}); a key it lacks reads as itself. Answers how many words it holds.
     */
    @JSExport
    public static int viewCardWords(final String wordsJson) {
        final Map<String, String> words = new HashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(JsonTree.parse(wordsJson))
            .entrySet()) if (e.getValue() instanceof String s) words.put(e.getKey(), s);
        Text.install(new Words(words));
        return words.size();
    }

    /** One card measured six px a character (the shared cases): a {@code CardInput.Case} in, {@code {"view", "geometry"}} out. */
    @JSExport
    public static String viewCard(final String caseJson) {
        final CardInput.Case c = CardInputJson.readCase(caseJson);
        return card(c.card(), c.dials(), SIX);
    }

    /** One card measured by the host: a {@code CardInput.Case} in, {@code {"view", "geometry"}} out. */
    @JSExport
    public static String viewCardMeasured(final String caseJson, final CardMeasure measure) {
        final CardInput.Case c = CardInputJson.readCase(caseJson);
        return card(c.card(), c.dials(), measure(measure));
    }

    /**
     * A board's cards in one call, measured by the host: the dials once, a JSON array of {@code CardInput}s, and a JSON
     * array of {@code {"view", "geometry"}} back in the same order.
     */
    @JSExport
    public static String viewCards(final String dialsJson, final String cardsJson, final CardMeasure measure) {
        return cards(dialsJson, cardsJson, measure(measure));
    }

    /** {@link #viewCards} measured six px a character: what the cards cost without a call back for each width. */
    @JSExport
    public static String viewCardsSix(final String dialsJson, final String cardsJson) {
        return cards(dialsJson, cardsJson, SIX);
    }

    /**
     * One card, the website's path: its {@code CardInputCase} object in, {@code {view, geometry}} objects out (the
     * objects {@code JSON.parse} gives of {@link #viewCardMeasured}'s answer, key order included), measured by the
     * host. Nothing crosses as text.
     */
    @JSExport
    public static JSObject viewCardJs(final JSObject cardCase, final CardMeasure measure) {
        return cardJs(cardCase, measure(measure));
    }

    /**
     * {@link #viewCardJs} with the host's widths kept in the core: the host's measure must be a pure function of
     * (style, text) while {@code fontState} stays the same, and the host passes a new state whenever a width could
     * change (the website's font epoch: a face that loads late, another face picked). Widths kept under another state
     * are dropped before the card is measured.
     */
    @JSExport
    public static JSObject viewCardJsKept(final JSObject cardCase, final CardMeasure measure, final int fontState) {
        final CardInput.Case c = ViewJs.readCardInputCase(cardCase);
        final CardView view = CardViews.card(c.card(), c.dials(), keptMeasure(measure, fontState), CardViews.Host.WEB);
        hold(c, view, fontState);
        return viewAndGeometry(ViewJs.js(view), ViewJs.js(CardGeometry.web(view)));
    }

    /**
     * The cards {@link #viewCardJsKept} made last, each with its input and the font state it was measured in, held
     * under a ticket so a special card's parts start from its card's view without making it again
     * ({@link #viewSpecialJsHeld}). A ring: the oldest goes when a new one comes, so a ticket can be gone.
     */
    private record Held(int ticket, CardInput.Case input, CardView view, int fontState) {}

    private static final int HELD_MAX = 512;
    private static final Held[] HELD = new Held[HELD_MAX];
    private static int lastTicket;

    private static void hold(final CardInput.Case c, final CardView view, final int fontState) {
        lastTicket = lastTicket == Integer.MAX_VALUE ? 1 : lastTicket + 1;
        HELD[lastTicket % HELD_MAX] = new Held(lastTicket, c, view, fontState);
    }

    @Nullable
    private static Held held(final int ticket, final int fontState) {
        if (ticket <= 0) return null;
        final Held h = HELD[ticket % HELD_MAX];
        return h != null && h.ticket == ticket && h.fontState == fontState ? h : null;
    }

    /** The ticket of the card {@link #viewCardJsKept} made last (0 before the first). */
    @JSExport
    public static int viewCardTicket() {
        return lastTicket;
    }

    /** Whether the card under {@code ticket} is still held, measured in {@code fontState}. */
    @JSExport
    public static boolean viewCardHeld(final int ticket, final int fontState) {
        return held(ticket, fontState) != null;
    }

    /**
     * A special card's own parts from its card's view as {@link #viewCardJsKept} made it ({@code ticket}, still held:
     * ask {@link #viewCardHeld} first), its kind's facts ({@code SpecialInput} object) in, its {@code SpecialView}
     * object out: the card is neither read nor made again. Widths kept as {@link #viewCardJsKept} keeps them.
     */
    @JSExport
    public static JSObject viewSpecialJsHeld(final int ticket, final JSObject special, final CardMeasure measure,
        final int fontState) {
        final Held h = held(ticket, fontState);
        if (h == null) throw new IllegalStateException("card " + ticket + " is no longer held");
        return ViewJs.js(
            SpecialViews.special(
                ViewJs.readSpecialInput(special),
                h.input.card(),
                h.view,
                h.input.dials(),
                keptMeasure(measure, fontState),
                CardViews.Host.WEB));
    }

    /** {@link #viewCardJs} measured six px a character (the shared cases). */
    @JSExport
    public static JSObject viewCardJsSix(final JSObject cardCase) {
        return cardJs(cardCase, SIX);
    }

    private static JSObject cardJs(final JSObject cardCase, final TextMeasure measure) {
        final CardInput.Case c = ViewJs.readCardInputCase(cardCase);
        final CardView view = CardViews.card(c.card(), c.dials(), measure, CardViews.Host.WEB);
        return viewAndGeometry(ViewJs.js(view), ViewJs.js(CardGeometry.web(view)));
    }

    /**
     * A node's rail rows and verdict ({@link NodeRails#build}), records as JavaScript objects: a {@code NodeRailsInput}
     * in, a {@code NodeRailsView} out, the same object {@code JSON.parse} gives of the JSON the conformance cases keep.
     */
    @JSExport
    public static JSObject viewRailsJs(final JSObject railsInput) {
        return ViewJs.js(NodeRails.build(ViewJs.readNodeRailsInput(railsInput)));
    }

    /**
     * A special card's own parts, the website's path: its {@code SpecialInputCase} object in ({@code {dials, card,
     * special}}), its {@code SpecialView} object out, measured by the host and the widths kept as
     * {@link #viewCardJsKept} keeps them. The card's own view is made on the way (the special parts start from it) and
     * not handed back: the host has it from {@link #viewCardJsKept}.
     */
    @JSExport
    public static JSObject viewSpecialJsKept(final JSObject specialCase, final CardMeasure measure,
        final int fontState) {
        return specialJs(specialCase, keptMeasure(measure, fontState));
    }

    /** {@link #viewSpecialJsKept} measured six px a character (the shared cases). */
    @JSExport
    public static JSObject viewSpecialJsSix(final JSObject specialCase) {
        return specialJs(specialCase, SIX);
    }

    private static JSObject specialJs(final JSObject specialCase, final TextMeasure measure) {
        final SpecialInput.Case c = ViewJs.readSpecialInputCase(specialCase);
        final CardView base = CardViews.card(c.card(), c.dials(), measure, CardViews.Host.WEB);
        return ViewJs.js(SpecialViews.special(c.special(), c.card(), base, c.dials(), measure, CardViews.Host.WEB));
    }

    @JSBody(params = { "view", "geometry" }, script = "return { view: view, geometry: geometry };")
    private static native JSObject viewAndGeometry(JSObject view, JSObject geometry);

    private static String cards(final String dialsJson, final String cardsJson, final TextMeasure m) {
        final CardInput.Dials dials = CardInputJson.dials(JsonTree.parse(dialsJson));
        final List<Object> cards = JsonTree.array(JsonTree.parse(cardsJson));
        final StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) out.append(',');
            out.append(card(CardInputJson.card(cards.get(i)), dials, m));
        }
        return out.append(']')
            .toString();
    }

    private static String card(final CardInput in, final CardInput.Dials dials, final TextMeasure measure) {
        final CardView view = CardViews.card(in, dials, measure, CardViews.Host.WEB);
        return "{\"view\":" + view.toJson() + ",\"geometry\":" + CardGeometry.web(view)
            .toJson() + "}";
    }

    /** A language file's patterns: {@code %s} in turn, {@code %n$s} by place, {@code %%}; no host formatter. */
    private static final class Words implements Text {

        private final Map<String, String> words;

        Words(final Map<String, String> words) {
            this.words = words;
        }

        @Override
        public String translate(final String key) {
            final String w = words.get(key);
            return w == null ? key : w;
        }

        @Override
        public String format(final String key, final Object... args) {
            final String pattern = words.get(key);
            if (pattern == null) return key;
            final StringBuilder out = new StringBuilder();
            int next = 0;
            for (int i = 0; i < pattern.length(); i++) {
                final char c = pattern.charAt(i);
                if (c != '%' || i + 1 >= pattern.length()) {
                    out.append(c);
                    continue;
                }
                final char d = pattern.charAt(i + 1);
                if (d == '%') {
                    out.append('%');
                    i++;
                } else if (d == 's') {
                    out.append(text(next < args.length ? args[next] : null));
                    next++;
                    i++;
                } else if (d >= '1' && d <= '9' && i + 3 < pattern.length() && pattern.charAt(i + 2) == '$'
                    && pattern.charAt(i + 3) == 's') {
                        final int at = d - '1';
                        out.append(text(at < args.length ? args[at] : null));
                        i += 3;
                    } else out.append(c);
            }
            return out.toString();
        }

        private static String text(@Nullable final Object arg) {
            if (arg instanceof Double d) return NumberText.of(d);
            return String.valueOf(arg);
        }
    }
}
