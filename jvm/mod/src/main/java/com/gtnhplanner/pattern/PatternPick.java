package com.gtnhplanner.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.popup.BoxFit;
import com.gtnhplanner.ui.popup.FramedPopup;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.ScrollBody;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * "Pattern this" on a recipe with more than one product: one row per product (its picture, name and amount) with an
 * include switch, then Make pattern and Cancel. It opens with the card's remembered choice, else the first product
 * alone. Make pattern is off while a recipe has no product included. A click off the box or Esc cancels; Enter makes
 * the pattern. On a screen too short for every row the rows scroll between the title and the keys ({@link PickLayout}).
 */
public final class PatternPick {

    /** One product row. */
    record Row(String key, @Nullable ItemStack item, @Nullable FluidStack fluid, String name, String amount) {}

    /** One recipe's rows (a shared machine has one per section) and what is included now. */
    static final class Recipe {

        /** "Recipe 2" over a shared machine's recipe; null over a card's one recipe. */
        @Nullable
        final String head;
        final List<Row> rows;
        final List<String> products = new ArrayList<>();
        List<String> included;

        Recipe(@Nullable final String head, final List<Row> rows, final List<String> included) {
            this.head = head;
            this.rows = rows;
            for (final Row r : rows) products.add(r.key());
            this.included = included;
        }
    }

    private static final int W = PickLayout.W, PAD = PickLayout.PAD, ROW = PickLayout.ROW,
        SWITCH_W = PickLayout.SWITCH_W, SWITCH_H = PickLayout.SWITCH_H;
    // Darker than the box behind them, so an off switch and an off key still read as controls.
    private static final int SWITCH_ON = 0xFF3E8F72, TRACK = 0xFF26282D, TRACK_HOT = 0xFF4A4D55;

    /** The box opened last, for the dev harness's clicks. */
    @Nullable
    private static PatternPick last;

    private final List<Recipe> recipes;
    private final boolean many;
    private final Consumer<List<Recipe>> make;
    private final PickLayout layout;
    private final Rows rows = new Rows();
    private final FramedPopup popup;

    private PatternPick(final List<Recipe> recipes, final boolean many, final Consumer<List<Recipe>> make) {
        this.recipes = recipes;
        this.many = many;
        this.make = make;
        final boolean[] heads = new boolean[recipes.size()];
        final int[] counts = new int[recipes.size()];
        for (int i = 0; i < counts.length; i++) {
            heads[i] = recipes.get(i).head != null;
            counts[i] = recipes.get(i).rows.size();
        }
        layout = new PickLayout(heads, counts);
        popup = new FramedPopup(
            "gtnhplanner_pattern_pick",
            W,
            new Head(),
            PickLayout.HEADER,
            rows,
            new Foot(),
            PickLayout.FOOT_H);
        popup.onEnter(this::makePressed);
    }

    /**
     * Opens the box over {@code board}; {@code make} runs with the choices when Make pattern is pressed ({@code many}:
     * more than one pattern will be made).
     */
    static void open(final ModularPanel board, final List<Recipe> recipes, final boolean many,
        final Consumer<List<Recipe>> make) {
        final PatternPick pick = new PatternPick(recipes, many, make);
        last = pick;
        Popup.openCentred(board, pick.popup, 1 / 3f);
    }

    static String t(final String key, final Object... args) {
        final String k = "gtnhplanner.pattern.pick." + key;
        return args.length == 0 ? StatCollector.translateToLocal(k) : StatCollector.translateToLocalFormatted(k, args);
    }

    private boolean ready() {
        for (final Recipe r : recipes) if (!PatternOutputs.canMake(r.included)) return false;
        return true;
    }

    private String makeLabel() {
        return t(many ? "make_many" : "make");
    }

    /** Make pattern's key, in the footer. */
    private int[] makeRect() {
        return PickLayout.makeRect(Hyb.width(makeLabel()));
    }

    /** Cancel's key, in the footer. */
    private int[] cancelRect() {
        return PickLayout.cancelRect(Hyb.width(t("cancel")), Hyb.width(makeLabel()));
    }

    private void makePressed() {
        if (!ready()) {
            Sfx.DENY.play();
            return;
        }
        Hyb.click();
        popup.closeIfOpen();
        make.accept(recipes);
    }

    private static void pill(final int x, final int y, final int w, final int h, final int colour) {
        Hyb.rect(x + 1, y, w - 2, h, colour);
        Hyb.rect(x, y + 1, 1, h - 2, colour);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, colour);
    }

    /** The title line. With no product included it says what Make pattern needs. */
    private final class Head extends Widget<Head> implements Interactable {

        /** A click on the title stays in the box. */
        @Override
        public Result onMousePressed(final int mouseButton) {
            return Result.SUCCESS;
        }

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            final boolean ready = ready();
            Hyb.text(Hyb.fit(t(ready ? "title" : "none"), W - 2 * PAD), PAD, PAD, ready ? Hyb.MUTED : Hyb.RED_INK);
        }
    }

    /** The recipes' heads and rows: the part that scrolls. */
    private final class Rows extends ScrollBody {

        Rows() {
            super("pattern");
        }

        @Override
        public int contentHeight() {
            return layout.contentHeight();
        }

        @Override
        protected void drawContent(final ModularGuiContext context, final int mx, final int my) {
            final float z = context.getCurrentDrawingZ();
            final int[] hot = layout.rowAt(mx, my);
            for (int i = 0; i < recipes.size(); i++) {
                final Recipe r = recipes.get(i);
                if (r.head != null) Hyb.text(r.head, PAD, layout.headTop(i) + 3, Hyb.MUTED);
                for (int j = 0; j < r.rows.size(); j++) {
                    final int y = layout.rowTop(i, j);
                    final Row row = r.rows.get(j);
                    final boolean on = r.included.contains(row.key());
                    final boolean rowHot = hot != null && hot[0] == i && hot[1] == j;
                    if (rowHot) Hyb.rect(PAD - 2, y, W - 2 * PAD + 4, ROW, 0x14FFFFFF);
                    Hyb.icon(row.item(), row.fluid(), PAD, y + 2, 16, z);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    final int sx = W - PAD - SWITCH_W, amountRight = sx - 8;
                    final int amountW = Hyb.width(row.amount());
                    Hyb.text(
                        Hyb.fit(row.name(), amountRight - amountW - 8 - (PAD + 22)),
                        PAD + 22,
                        y + 6,
                        on ? Hyb.INK : Hyb.MUTED);
                    Hyb.textRight(row.amount(), amountRight, y + 6, Hyb.MUTED);
                    final int sy = y + (ROW - SWITCH_H) / 2;
                    pill(sx, sy, SWITCH_W, SWITCH_H, on ? SWITCH_ON : rowHot ? TRACK_HOT : TRACK);
                    final int k = SWITCH_H - 4;
                    pill(on ? sx + SWITCH_W - 2 - k : sx + 2, sy + 2, k, k, on ? Hyb.INK : 0xFF9A9CA4);
                }
            }
        }

        @Override
        protected Result pressContent(final int mouseButton, final int mx, final int my) {
            if (mouseButton != 0) return Result.ACCEPT;
            final int[] at = layout.rowAt(mx, my);
            if (at == null) return Result.SUCCESS;
            final Recipe r = recipes.get(at[0]);
            final String key = r.rows.get(at[1])
                .key();
            final boolean was = r.included.contains(key);
            r.included = PatternOutputs.toggle(r.products, r.included, key);
            (was ? Sfx.TOGGLE_OFF : Sfx.TOGGLE_ON).play();
            return Result.SUCCESS;
        }
    }

    /** Cancel and Make pattern, always in view under the rows. */
    private final class Foot extends Widget<Foot> implements Interactable {

        private int mx() {
            return isHovering() ? getContext().getAbsMouseX() - getArea().x : -1;
        }

        private int my() {
            return isHovering() ? getContext().getAbsMouseY() - getArea().y : -1;
        }

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            final int mx = mx(), my = my();
            final int[] cancel = cancelRect(), make = makeRect();
            Hyb.bevel(
                cancel[0],
                cancel[1],
                cancel[2],
                cancel[3],
                PickLayout.in(cancel, mx, my) ? Hyb.KEY_HOVER : Hyb.KEY,
                Hyb.KEY_HI,
                Hyb.KEY_LO,
                0,
                1);
            Hyb.textCentered(t("cancel"), cancel[0] + cancel[2] / 2f, cancel[1] + 5, Hyb.INK);
            if (ready()) {
                Hyb.rect(make[0], make[1], make[2], make[3], PickLayout.in(make, mx, my) ? 0xFF38E1F5 : 0xFF22D3EE);
                Hyb.rect(make[0], make[1], make[2], 1, 0x66FFFFFF);
                com.cleanroommc.modularui.drawable.GuiDraw.drawText(
                    makeLabel(),
                    make[0] + (make[2] - Hyb.width(makeLabel())) / 2f,
                    make[1] + 5,
                    1f,
                    0xFF0B1A1E,
                    false);
            } else {
                Hyb.rect(make[0], make[1], make[2], make[3], TRACK);
                Hyb.textCentered(makeLabel(), make[0] + make[2] / 2f, make[1] + 5, 0xFF7A7D86);
            }
        }

        @Override
        public Result onMousePressed(final int mouseButton) {
            if (mouseButton != 0) return Result.ACCEPT;
            final int mx = mx(), my = my();
            if (PickLayout.in(cancelRect(), mx, my)) {
                popup.closeIfOpen();
                Sfx.CLOSE.play();
            } else if (PickLayout.in(makeRect(), mx, my)) makePressed();
            return Result.SUCCESS;
        }

        @Override
        public boolean canHover() {
            return true;
        }
    }

    /**
     * Scrolls the open box so row {@code index} (counting every recipe's rows in order) shows whole; for the dev
     * harness, whose clicks reach only rows in view.
     */
    public static void reveal(final int index) {
        final PatternPick p = last;
        if (p == null) return;
        int n = index;
        for (int i = 0; i < p.recipes.size(); i++) {
            final int count = p.recipes.get(i).rows.size();
            if (n < count) {
                p.rows.reveal(n == 0 ? p.layout.headTop(i) : p.layout.rowTop(i, n), ROW);
                return;
            }
            n -= count;
        }
    }

    /**
     * Where the dev harness clicks, in board px: each row's switch in order (null for a row not wholly in view: scroll
     * it there with {@link #reveal}), then Cancel, then Make pattern; with whether the box is open, how far its rows
     * are scrolled and can scroll, and each recipe's products included now. Null before any box opened.
     */
    @Nullable
    public static java.util.Map<String, Object> targets() {
        final PatternPick p = last;
        if (p == null) return null;
        final int ax = p.popup.getArea().x, ay = p.popup.getArea().y;
        final BoxFit.Frame f = p.popup.frame();
        final int scroll = p.rows.scroll();
        final List<int[]> switches = new ArrayList<>();
        final List<List<String>> included = new ArrayList<>();
        for (int i = 0; i < p.recipes.size(); i++) {
            final Recipe r = p.recipes.get(i);
            for (int j = 0; j < r.rows.size(); j++) {
                final int top = p.layout.rowTop(i, j);
                switches.add(
                    f.shows(top, ROW, scroll)
                        ? new int[] { ax + W - PAD - SWITCH_W / 2, ay + f.bodyTop() + top - scroll + ROW / 2 }
                        : null);
            }
            included.add(new ArrayList<>(r.included));
        }
        final int[] c = PickLayout.inBox(f, p.cancelRect()), m = PickLayout.inBox(f, p.makeRect());
        final java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("open", p.popup.isOpen());
        out.put("switches", switches);
        out.put("scroll", scroll);
        out.put("maxScroll", f.maxScroll());
        out.put("cancel", new int[] { ax + c[0] + c[2] / 2, ay + c[1] + c[3] / 2 });
        out.put("make", new int[] { ax + m[0] + m[2] / 2, ay + m[1] + m[3] / 2 });
        out.put("makeOn", p.ready());
        out.put("included", included);
        return out;
    }
}
