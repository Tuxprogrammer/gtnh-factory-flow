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
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * "Pattern this" on a recipe with more than one product: one row per product (its picture, name and amount) with an
 * include switch, then Make pattern and Cancel. It opens with the card's remembered choice, else the first product
 * alone. Make pattern is off while a recipe has no product included. A click off the box cancels.
 */
public final class PatternPick extends Widget<PatternPick> implements Interactable {

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

    private static final int W = 260, PAD = 8, TITLE_H = 18, HEAD_H = 14, ROW = 20, FOOT_H = 30, KEY_H = 18,
        SWITCH_W = 22, SWITCH_H = 12;
    // Darker than the box behind them, so an off switch and an off key still read as controls.
    private static final int SWITCH_ON = 0xFF3E8F72, TRACK = 0xFF26282D, TRACK_HOT = 0xFF4A4D55;

    /** The box opened last, for the dev harness's clicks. */
    @Nullable
    private static PatternPick last;

    private final List<Recipe> recipes;
    private final boolean many;
    private final Consumer<List<Recipe>> make;
    private final int h;

    private PatternPick(final List<Recipe> recipes, final boolean many, final Consumer<List<Recipe>> make) {
        this.recipes = recipes;
        this.many = many;
        this.make = make;
        this.h = height(recipes);
        size(W, h);
    }

    /**
     * Opens the box over {@code board}; {@code make} runs with the choices when Make pattern is pressed ({@code many}:
     * more than one pattern will be made).
     */
    static void open(final ModularPanel board, final List<Recipe> recipes, final boolean many,
        final Consumer<List<Recipe>> make) {
        final PatternPick pick = new PatternPick(recipes, many, make);
        last = pick;
        final Popup popup = new Popup("gtnhplanner_pattern_pick", W, pick.h);
        popup.child(pick.pos(0, 0));
        Popup.openCentred(board, popup, 1 / 3f);
    }

    private static int height(final List<Recipe> recipes) {
        int y = PAD + TITLE_H;
        for (final Recipe r : recipes) y += (r.head != null ? HEAD_H : 0) + r.rows.size() * ROW;
        return y + FOOT_H;
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

    private int[] makeRect() {
        final int w = Hyb.width(makeLabel()) + 24;
        return new int[] { W - PAD - w, h - PAD - KEY_H, w, KEY_H };
    }

    private int[] cancelRect() {
        final int w = Hyb.width(t("cancel")) + 16;
        return new int[] { makeRect()[0] - 6 - w, h - PAD - KEY_H, w, KEY_H };
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    /** The recipe and row under a point, {recipe, row}; null off every row. */
    @Nullable
    private int[] rowAt(final int mx, final int my) {
        int y = PAD + TITLE_H;
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).head != null) y += HEAD_H;
            final int n = recipes.get(i).rows.size();
            if (mx >= PAD && mx < W - PAD && my >= y && my < y + n * ROW) return new int[] { i, (my - y) / ROW };
            y += n * ROW;
        }
        return null;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final boolean hover = isHovering();
        final int mx = hover ? getContext().getAbsMouseX() - getArea().x : -1,
            my = hover ? getContext().getAbsMouseY() - getArea().y : -1;
        final float z = context.getCurrentDrawingZ();
        final int[] hot = rowAt(mx, my);
        final boolean ready = ready();
        // With no product included the title line says what Make pattern needs.
        Hyb.text(Hyb.fit(t(ready ? "title" : "none"), W - 2 * PAD), PAD, PAD, ready ? Hyb.MUTED : Hyb.RED_INK);
        int y = PAD + TITLE_H;
        for (int i = 0; i < recipes.size(); i++) {
            final Recipe r = recipes.get(i);
            if (r.head != null) {
                Hyb.text(r.head, PAD, y + 3, Hyb.MUTED);
                y += HEAD_H;
            }
            for (int j = 0; j < r.rows.size(); j++) {
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
                y += ROW;
            }
        }
        final int[] cancel = cancelRect(), make = makeRect();
        Hyb.bevel(
            cancel[0],
            cancel[1],
            cancel[2],
            cancel[3],
            in(cancel, mx, my) ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        Hyb.textCentered(t("cancel"), cancel[0] + cancel[2] / 2f, cancel[1] + 5, Hyb.INK);
        if (ready) {
            Hyb.rect(make[0], make[1], make[2], make[3], in(make, mx, my) ? 0xFF38E1F5 : 0xFF22D3EE);
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

    private static void pill(final int x, final int y, final int w, final int h, final int colour) {
        Hyb.rect(x + 1, y, w - 2, h, colour);
        Hyb.rect(x, y + 1, 1, h - 2, colour);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, colour);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        final int[] at = rowAt(mx, my);
        if (at != null) {
            final Recipe r = recipes.get(at[0]);
            final String key = r.rows.get(at[1])
                .key();
            final boolean was = r.included.contains(key);
            r.included = PatternOutputs.toggle(r.products, r.included, key);
            (was ? Sfx.TOGGLE_OFF : Sfx.TOGGLE_ON).play();
        } else if (in(cancelRect(), mx, my)) {
            if (getPanel() != null) getPanel().closeIfOpen();
            Sfx.CLOSE.play();
        } else if (in(makeRect(), mx, my)) {
            if (!ready()) {
                Sfx.DENY.play();
                return Result.SUCCESS;
            }
            Hyb.click();
            if (getPanel() != null) getPanel().closeIfOpen();
            make.accept(recipes);
        }
        return Result.SUCCESS;
    }

    /**
     * Where the dev harness clicks, in board px: each row's switch in order, then Cancel, then Make pattern; with
     * whether the box is open and each recipe's products included now. Null before any box opened.
     */
    @Nullable
    public static java.util.Map<String, Object> targets() {
        final PatternPick p = last;
        if (p == null) return null;
        final int ax = p.getArea().x, ay = p.getArea().y;
        final List<int[]> switches = new ArrayList<>();
        final List<List<String>> included = new ArrayList<>();
        int y = PAD + TITLE_H;
        for (final Recipe r : p.recipes) {
            if (r.head != null) y += HEAD_H;
            for (int j = 0; j < r.rows.size(); j++)
                switches.add(new int[] { ax + W - PAD - SWITCH_W / 2, ay + y + j * ROW + ROW / 2 });
            y += r.rows.size() * ROW;
            included.add(new ArrayList<>(r.included));
        }
        final int[] c = p.cancelRect(), m = p.makeRect();
        final java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put(
            "open",
            p.getPanel() != null && p.getPanel()
                .isOpen());
        out.put("switches", switches);
        out.put("cancel", new int[] { ax + c[0] + c[2] / 2, ay + c[1] + c[3] / 2 });
        out.put("make", new int[] { ax + m[0] + m[2] / 2, ay + m[1] + m[3] / 2 });
        out.put("makeOn", p.ready());
        out.put("included", included);
        return out;
    }

    @Override
    public boolean canHover() {
        return true;
    }
}
