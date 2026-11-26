package com.gtnhplanner.ui.popup;

import javax.annotation.Nullable;

import com.cleanroommc.modularui.widget.Widget;

/**
 * A box whose content can grow past the screen: a fixed header, a {@link ScrollBody} and a fixed footer stacked in that
 * order. As it opens it takes the height its room allows ({@link BoxFit#frame}): when everything fits it is exactly
 * header, content and footer; else the body scrolls and the header and footer, with the box's keys, stay in view. A
 * box whose content changes while it is open is fitted again the same way ({@link #reshape}), so it never grows past
 * the screen after it opened.
 */
public class FramedPopup extends Popup {

    @Nullable
    private final Widget<?> header, footer;
    private final ScrollBody body;
    private int headerH, footerH;
    private BoxFit.Frame frame;

    /**
     * A box {@code width} wide; {@code header} and {@code footer} (either may be null) are given the box's width and
     * their heights, the body the box's width and the window the room leaves.
     */
    public FramedPopup(final String name, final int width, @Nullable final Widget<?> header, final int headerH,
        final ScrollBody body, @Nullable final Widget<?> footer, final int footerH) {
        super(name, width, headerH + body.contentHeight() + footerH);
        this.header = header;
        this.footer = footer;
        this.body = body;
        this.headerH = header == null ? 0 : headerH;
        this.footerH = footer == null ? 0 : footerH;
        if (header != null) child(header);
        child(body);
        if (footer != null) child(footer);
        layOut(BoxFit.frame(Integer.MAX_VALUE / 2, this.headerH, body.contentHeight(), this.footerH));
    }

    /** The frame the box is laid out with now. */
    public BoxFit.Frame frame() {
        return frame;
    }

    @Override
    protected void fit(final int[] room, final int[] screen) {
        popupW = BoxFit.size(screen, popupW, 0)[0];
        layOut(BoxFit.frame(room[3], headerH, body.contentHeight(), footerH));
    }

    /**
     * Lays the box out again for a new width or new header and footer heights, or because the body's content changed
     * height: fitted to the room as it opened, its top left kept where the room and the screen allow.
     */
    public void reshape(final int width, final int headerH, final int footerH) {
        this.headerH = header == null ? 0 : headerH;
        this.footerH = footer == null ? 0 : footerH;
        popupW = width;
        if (!isOpen()) {
            layOut(BoxFit.frame(Integer.MAX_VALUE / 2, this.headerH, body.contentHeight(), this.footerH));
            return;
        }
        final int[] room = room(this), screen = screen();
        fit(room, screen);
        final int[] inRoom = BoxFit.place(room, getArea().x, getArea().y, popupW, popupH),
            spot = BoxFit.place(screen, inRoom[0], inRoom[1], popupW, popupH);
        pos(spot[0], spot[1]);
        scheduleResize();
    }

    /** The body's content is no longer the height the box was laid out for. */
    void contentChanged() {
        reshape(popupW, headerH, footerH);
    }

    private void layOut(final BoxFit.Frame f) {
        frame = f;
        popupH = f.height();
        size(popupW, popupH);
        if (header != null) header.pos(0, 0)
            .size(popupW, headerH);
        body.pos(0, f.bodyTop())
            .size(popupW, f.view());
        body.setFrame(f);
        if (footer != null) footer.pos(0, f.footerTop())
            .size(popupW, footerH);
    }
}
