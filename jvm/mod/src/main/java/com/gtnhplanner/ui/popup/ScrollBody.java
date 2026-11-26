package com.gtnhplanner.ui.popup;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.theme.ScrollBar;

/**
 * The body of a {@link FramedPopup}: content that draws itself, shown through a window as tall as the room leaves. When
 * the content is taller, it is clipped to the window and scrolls with the wheel and a {@link ScrollBar} along the right
 * edge; when it fits, it draws exactly as it would with no window. Content is drawn and hit in content coordinates (y
 * from the content's top, the scroll added), so a point outside the window reaches no row.
 */
public abstract class ScrollBody extends Widget<ScrollBody> implements Interactable {

    private final ScrollBar bar;
    private BoxFit.Frame frame;
    private int scroll;

    /** A body whose bar the dev harness lists as {@code name}. */
    protected ScrollBody(final String name) {
        bar = new ScrollBar(name, this);
    }

    /** The content's whole height, read as the box opens; it stays the same while the box is open. */
    public abstract int contentHeight();

    /**
     * Draws the content with its top at y 0 ({@code mx, my} the mouse in content coordinates, -1 when it is not over
     * the window).
     */
    protected abstract void drawContent(ModularGuiContext context, int mx, int my);

    /** A press on the content at {@code mx, my} (content coordinates, always inside the window). */
    protected abstract Result pressContent(int mouseButton, int mx, int my);

    /** How far one notch of the wheel moves the content. */
    protected int wheelStep() {
        return 20;
    }

    /** The frame the box was laid out with; before it opens, a window that shows everything. */
    public BoxFit.Frame frame() {
        return frame != null ? frame : BoxFit.frame(Integer.MAX_VALUE / 2, 0, contentHeight(), 0);
    }

    void setFrame(final BoxFit.Frame frame) {
        this.frame = frame;
        scroll = frame.clamp(scroll);
    }

    public int scroll() {
        return frame().clamp(scroll);
    }

    /** Scrolls the least amount that shows content {@code y} to {@code y + h}. */
    public void reveal(final int y, final int h) {
        scroll = frame().reveal(y, h, scroll);
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    private boolean mouseInWindow() {
        if (!isHovering()) return false;
        final int x = localX(), y = localY();
        return x >= 0 && y >= 0 && x < getArea().width && y < getArea().height;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final BoxFit.Frame f = frame();
        scroll = f.clamp(scroll);
        final boolean over = mouseInWindow() && !bar.dragging() && !(f.scrolls() && bar.contains(localX(), localY()));
        final int mx = over ? localX() : -1, my = over ? localY() + scroll : -1;
        if (!f.scrolls()) {
            drawContent(context, mx, my);
            return;
        }
        final int w = getArea().width;
        Stencil.apply(0, 0, w, f.view(), context);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, -scroll, 0);
        drawContent(context, mx, my);
        GL11.glPopMatrix();
        Stencil.remove();
        scroll = bar.draw(w - 1, 0, f.view(), f.content(), scroll, mouseInWindow() && bar.contains(localX(), localY()));
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (!mouseInWindow()) return Result.IGNORE;
        if (mouseButton == 0 && frame().scrolls() && bar.contains(localX(), localY())) {
            scroll = frame().clamp(bar.press(localY(), scroll));
            return Result.SUCCESS;
        }
        return pressContent(mouseButton, localX(), localY() + scroll());
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (bar.dragging()) scroll = frame().clamp(bar.drag(localY()));
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        return bar.release();
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final BoxFit.Frame f = frame();
        if (!f.scrolls()) return false;
        scroll = f.clamp(scroll + (direction == UpOrDown.UP ? -1 : 1) * wheelStep());
        return true;
    }

    @Override
    public boolean canHover() {
        return true;
    }
}
