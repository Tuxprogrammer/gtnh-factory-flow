package com.gtnhplanner.ui.dave;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.gtnhplanner.client.dave.DaveLink;
import com.gtnhplanner.client.dave.DaveTalk;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.popup.TextArea;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Ask Dave's side panel, along the board's right edge in the overview's look: the conversation, a box to write the
 * question in with its count against the bridge's limit, and Send (Stop while a question runs). The rows come from
 * {@link DaveTalk}; they are laid out again only when it changes or the panel's width does, never per frame.
 */
public final class AskDavePanel extends ParentWidget<AskDavePanel> implements Interactable {

    /** Its width, in board pixels. */
    public static final int W = 210;
    private static final int PAD = 6, TITLE = 20, LINE = 10, KEY_H = 14, INPUT_H = 42, GAP = 3;
    private static final int BG = 0xFF202226, EDGE = 0xFF0C0D10, QUESTION_BG = 0xFF2D2F35, WELL = 0xFF16171A;

    /** One row laid out: where it sits in the conversation, its lines, and its key's box when it has one. */
    private record Laid(DaveTalk.Row row, int y, int h, List<String> lines, int colour, @Nullable int[] key) {}

    private final BoardSession session;
    private final TextArea words;
    private List<Laid> laid = List.of();
    private int laidVersion = -1, laidWidth = -1, contentH;
    private int scroll;

    public AskDavePanel(final BoardSession session) {
        this.session = session;
        words = new TextArea(t("gtnhplanner.dave.hint")).maxLength(DaveLink.MAX_TEXT);
        child(
            words.left(PAD)
                .right(PAD)
                .bottom(PAD + KEY_H + GAP)
                .height(INPUT_H));
    }

    private static DaveTalk talk() {
        return AskDave.TALK;
    }

    // region Layout

    private int convTop() {
        return TITLE + 2;
    }

    private int convBottom() {
        return getArea().height - PAD - KEY_H - GAP - INPUT_H - GAP;
    }

    /** The rows' places, rebuilt only when the talk or the width changed. */
    private List<Laid> laid() {
        final int w = getArea().width;
        if (laidVersion == talk().version() && laidWidth == w) return laid;
        final boolean changed = laidVersion != talk().version();
        laidVersion = talk().version();
        laidWidth = w;
        final int room = w - 2 * PAD;
        final List<Laid> out = new ArrayList<>();
        int y = 4;
        for (final DaveTalk.Row r : talk().rows()) {
            switch (r.kind()) {
                case QUESTION -> {
                    if (!out.isEmpty()) y += 6;
                    final List<String> lines = wrap(r.text(), room - 8);
                    final int h = lines.size() * LINE + 6;
                    out.add(new Laid(r, y, h, lines, Hyb.INK, null));
                    y += h + GAP;
                }
                case ADD_PLAN -> {
                    final int kw = Math.min(room, Hyb.width(r.text()) + 12);
                    out.add(new Laid(r, y, KEY_H, List.of(r.text()), Hyb.INK, new int[] { PAD, y, kw, KEY_H }));
                    y += KEY_H + GAP;
                }
                case RECIPE -> {
                    final int kw = Math.max(
                        Hyb.width(t("gtnhplanner.dave.recipe.add")),
                        Math.max(
                            Hyb.width(t("gtnhplanner.dave.recipe.added")),
                            Hyb.width(t("gtnhplanner.dave.recipe.adding"))))
                        + 10;
                    final List<String> lines = wrap(r.text(), room - kw - 4);
                    final int h = Math.max(KEY_H, lines.size() * LINE);
                    out.add(new Laid(r, y, h, lines, Hyb.INK, new int[] { PAD + room - kw, y, kw, KEY_H }));
                    y += h + GAP;
                }
                default -> {
                    final List<String> lines = wrap(r.text(), room);
                    final int h = lines.size() * LINE;
                    out.add(new Laid(r, y, h, lines, colour(r), null));
                    y += h + GAP;
                }
            }
        }
        laid = out;
        contentH = y;
        // Something new: the newest line in view.
        if (changed) scroll = Math.max(0, contentH - (convBottom() - convTop()));
        return laid;
    }

    private static int colour(final DaveTalk.Row r) {
        return switch (r.kind()) {
            case ERROR -> Hyb.RED_INK;
            case OUTCOME -> r.problem() ? Hyb.RED_INK : Hyb.OK_INK;
            case NOTE, STATUS -> Hyb.MUTED;
            default -> Hyb.INK;
        };
    }

    private int[] closeRect() {
        return new int[] { getArea().width - PAD - 12, (TITLE - 12) / 2, 12, 12 };
    }

    private int[] sendRect() {
        final String label = t(talk().running() ? "gtnhplanner.dave.stop" : "gtnhplanner.dave.send");
        final int kw = Hyb.width(label) + 16;
        return new int[] { getArea().width - PAD - kw, getArea().height - PAD - KEY_H, kw, KEY_H };
    }

    // endregion

    // region Drawing

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, h = getArea().height;
        final boolean hovering = isHovering();
        final int mx = hovering ? context.getAbsMouseX() - getArea().x : -1,
            my = hovering ? context.getAbsMouseY() - getArea().y : -1;
        Hyb.rect(0, 0, w, h, BG);
        Hyb.rect(0, 0, 1, h, EDGE);
        Hyb.text(t("gtnhplanner.dave.title"), PAD, (TITLE - 8) / 2f, Hyb.INK);
        final int[] close = closeRect();
        if (in(close, mx, my)) Hyb.rect(close[0], close[1], close[2], close[3], Hyb.KEY_HOVER);
        Hyb.text("x", close[0] + 3, close[1] + 2, in(close, mx, my) ? Hyb.INK : Hyb.MUTED);
        Hyb.rect(1, TITLE, w - 2, 1, CardPaint.HAIR);

        final int top = convTop(), bottom = convBottom();
        final List<Laid> rows = laid();
        if (rows.isEmpty()) {
            int ey = top + 4;
            for (final String line : wrap(t("gtnhplanner.dave.empty"), w - 2 * PAD)) {
                Hyb.text(line, PAD, ey, Hyb.MUTED);
                ey += LINE;
            }
        }
        for (final Laid l : rows) {
            final int y = top + l.y() - scroll;
            if (y + l.h() < top || y > bottom) continue;
            final DaveTalk.Row r = l.row();
            if (r.kind() == DaveTalk.Kind.QUESTION && y >= top && y + l.h() <= bottom)
                Hyb.rect(PAD, y, w - 2 * PAD, l.h(), QUESTION_BG);
            if (l.key() != null) {
                final int[] k = { l.key()[0], top + l.key()[1] - scroll, l.key()[2], l.key()[3] };
                if (k[1] >= top && k[1] + k[3] <= bottom) {
                    final boolean on = r.enabled(), hot = on && in(k, mx, my);
                    Hyb.bevel(k[0], k[1], k[2], k[3], hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                    final String word = r.kind() == DaveTalk.Kind.RECIPE ? talk().addWord(r) : r.text();
                    Hyb.textCentered(word, k[0] + k[2] / 2f, k[1] + (k[3] - 8) / 2f, on ? Hyb.INK : Hyb.MUTED);
                }
                if (r.kind() == DaveTalk.Kind.ADD_PLAN) continue;
            }
            final int x = r.kind() == DaveTalk.Kind.QUESTION ? PAD + 4 : PAD;
            final int y0 = r.kind() == DaveTalk.Kind.QUESTION ? y + 3 : y;
            for (int i = 0; i < l.lines()
                .size(); i++) {
                final int ly = y0 + i * LINE;
                if (ly >= top && ly + 8 <= bottom) Hyb.text(
                    l.lines()
                        .get(i),
                    x,
                    ly,
                    l.colour());
            }
        }

        // The box to write in, sunk into the panel as the Feedback box's is.
        Hyb.rect(PAD, h - PAD - KEY_H - GAP - INPUT_H, w - 2 * PAD, INPUT_H, WELL);
        // Under the box: the count against the limit, and Send or Stop.
        final int n = words.text()
            .trim()
            .length();
        Hyb.text(
            Text.get()
                .format("gtnhplanner.dave.count", n, DaveLink.MAX_TEXT),
            PAD,
            h - PAD - KEY_H + 3,
            n > DaveLink.MAX_TEXT - 100 ? Hyb.AMBER_INK : Hyb.MUTED);
        final int[] send = sendRect();
        final boolean running = talk().running(), on = running || talk().canSend(words.text()),
            hot = on && in(send, mx, my);
        Hyb.bevel(send[0], send[1], send[2], send[3], hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.textCentered(
            t(running ? "gtnhplanner.dave.stop" : "gtnhplanner.dave.send"),
            send[0] + send[2] / 2f,
            send[1] + (send[3] - 8) / 2f,
            on ? Hyb.INK : Hyb.MUTED);
    }

    // endregion

    // region Input

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        if (in(closeRect(), mx, my)) {
            Hyb.click();
            AskDave.open = false;
            return Result.SUCCESS;
        }
        if (in(sendRect(), mx, my)) {
            if (talk().running()) {
                Hyb.click();
                talk().stop();
            } else if (talk().canSend(words.text())) {
                Hyb.click();
                talk().send(words.text(), () -> AskDave.ask(session));
                words.text("");
            }
            return Result.SUCCESS;
        }
        final int top = convTop(), bottom = convBottom();
        for (final Laid l : laid()) {
            if (l.key() == null || !l.row()
                .enabled()) continue;
            final int[] k = { l.key()[0], top + l.key()[1] - scroll, l.key()[2], l.key()[3] };
            if (k[1] < top || k[1] + k[3] > bottom || !in(k, mx, my)) continue;
            Hyb.click();
            final DaveTalk.Row r = l.row();
            if (r.kind() == DaveTalk.Kind.ADD_PLAN) talk().addToPlan(r.turn(), id -> AskDave.apply(r.turn(), id));
            else talk().addRecipe(r.turn(), r.index(), (recipe, done) -> AskDave.addRecipe(session, recipe, done));
            return Result.SUCCESS;
        }
        return Result.ACCEPT;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int view = convBottom() - convTop();
        laid();
        if (contentH <= view) return true;
        scroll = Math.max(0, Math.min(contentH - view, scroll + (direction == UpOrDown.UP ? -2 : 2) * LINE));
        return true;
    }

    // endregion

    /** Words onto as many lines as they take, a word longer than a line broken across lines. */
    static List<String> wrap(final String text, final int room) {
        final List<String> out = new ArrayList<>();
        final StringBuilder line = new StringBuilder();
        for (final String word : text.split(" ")) {
            String w = word;
            while (w.length() > 1 && Hyb.width(w) > room) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                int n = w.length() - 1;
                while (n > 1 && Hyb.width(w.substring(0, n)) > room) n--;
                out.add(w.substring(0, n));
                w = w.substring(n);
            }
            final String next = line.length() == 0 ? w : line + " " + w;
            if (line.length() > 0 && Hyb.width(next) > room) {
                out.add(line.toString());
                line.setLength(0);
                line.append(w);
            } else {
                line.setLength(0);
                line.append(next);
            }
        }
        if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
        return out;
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    private static String t(final String key) {
        return Text.get()
            .translate(key);
    }
}
