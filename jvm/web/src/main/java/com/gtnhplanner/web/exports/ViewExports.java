package com.gtnhplanner.web.exports;

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

/**
 * core-view for JavaScript: a recipe card's whole view from its facts, one call per card or per board, JSON in and JSON
 * out on the record convention (conformance/view/README.md): in {@code CardInput.Case} ({@code {dials, card}}), out
 * {@code {"view": CardView, "geometry": CardGeometry}} with the website's geometry ({@code CardGeometry.web}).
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

    private static TextMeasure measure(final CardMeasure m) {
        final String[] names = new String[STYLES.length];
        for (int i = 0; i < names.length; i++) names[i] = STYLES[i].name();
        return (style, text) -> width(m, names[style.ordinal()], text);
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
