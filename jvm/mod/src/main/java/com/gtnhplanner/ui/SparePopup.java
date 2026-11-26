package com.gtnhplanner.ui;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.popup.FramedPopup;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.ScrollBody;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * What a card's spare row opens (a click on it) when its leftover has somewhere else to go: the same slider with labels
 * as the overview's Spare section ({@link SpareBar}), the place clicked marked "here", and under it a small link for a
 * drawer to take it at that port instead. Nothing else: no title, no list. Hovering a place rings its card on the
 * board;
 * the slider is handled as in the overview. A leftover with more places than the screen shows scrolls its lines, the
 * drawer link kept under them ({@link FramedPopup}).
 */
public final class SparePopup extends ScrollBody {

    private static final int SLIDER_W = 190, PAD = 6, LINK_H = 9;
    private static final float SMALL = 0.75f;
    private static final String DRAWER = "or put a drawer here";

    private final BoardSession session;
    /** The leftover, by key: the solve gives it a new object each time, so it is looked up on every draw. */
    private final String key;
    private final String here;
    private final SpareBar bar = new SpareBar();
    private BoardSession.SpareLine line;

    private SparePopup(final BoardSession session, final BoardSession.SpareLine line, final String here) {
        super("spare");
        this.session = session;
        this.key = line.key();
        this.here = here;
        this.line = line;
    }

    /**
     * Opens the popup for {@code line} at a screen point, {@code here} being the key of the place whose row was
     * clicked, {@code drawer} what the drawer link does.
     */
    public static void open(final com.cleanroommc.modularui.screen.ModularPanel parent, final BoardSession session,
        final BoardSession.SpareLine line, final String here, final Runnable drawer, final int x, final int y) {
        final SparePopup lines = new SparePopup(session, line, here);
        final Popup popup = new FramedPopup(
            "gtnhplanner_spare",
            SLIDER_W + 2 * PAD,
            null,
            0,
            lines,
            new Link(drawer),
            LINK_H + PAD);
        Popup.open(parent, popup, x, y);
    }

    /** The lines: a gap, the slider with its labels, and the gap before the link. */
    @Override
    public int contentHeight() {
        return PAD + SpareBar.height(line) + 3;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        // The labels take more rows or fewer as the spare moves: the box is fitted again when they do.
        for (final BoardSession.SpareLine l : session.spares()) if (l.key()
            .equals(key)) line = l;
        super.draw(context, widgetTheme);
    }

    @Override
    protected void drawContent(final ModularGuiContext context, final int mx, final int my) {
        final SpareBar.Hit hover = mx < 0 ? null : bar.hitAt(mx, my);
        final int place = SpareBar.place(hover);
        session.setSpareMenuHover(
            place < 0 ? null : line,
            place < 0 ? null
                : line.bearers()
                    .get(place));
        Hyb.rect(1, PAD - 2, SLIDER_W + 2 * PAD - 2, SpareBar.height(line) + 4, SpareBar.tint(line));
        bar.devName("spare box", this, -scroll());
        bar.draw(session, line, PAD, PAD, SLIDER_W, context.getCurrentDrawingZ(), hover, here);
    }

    @Override
    protected Result pressContent(final int mouseButton, final int mx, final int my) {
        if (mouseButton != 0) return Result.ACCEPT;
        final SpareBar.Hit hit = bar.hitAt(mx, my);
        if (!bar.press(session, line, hit, mx, PAD, SLIDER_W) && bar.click(session, line, hit)) Hyb.click();
        return Result.SUCCESS;
    }

    @Override
    protected void dragContent(final int mouseButton, final int mx, final int my) {
        if (bar.dragging()) bar.drag(session, mx, PAD, SLIDER_W);
    }

    @Override
    protected boolean releaseContent(final int mouseButton) {
        if (!bar.dragging()) return false;
        bar.release(session);
        return true;
    }

    /** The drawer link under the lines, at the box's right. */
    private static final class Link extends Widget<Link> implements Interactable {

        private final Runnable drawer;

        Link(final Runnable drawer) {
            this.drawer = drawer;
        }

        @Override
        public boolean canHover() {
            return true;
        }

        private boolean over() {
            final float w = Hyb.width(DRAWER) * SMALL;
            final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
            return isHovering() && my >= 0 && my < LINK_H && mx >= PAD + SLIDER_W - w && mx < PAD + SLIDER_W;
        }

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            final float w = Hyb.width(DRAWER) * SMALL;
            Hyb.text(DRAWER, PAD + SLIDER_W - w, 1, SMALL, over() ? Hyb.GOLD : 0xFF9A9DA6);
        }

        @Override
        public Result onMousePressed(final int mouseButton) {
            if (mouseButton != 0 || !over()) return Result.ACCEPT;
            Hyb.click();
            getPanel().closeIfOpen();
            drawer.run();
            return Result.SUCCESS;
        }
    }
}
