package com.gtnhplanner.ui.popup;

import org.lwjgl.input.Keyboard;

/**
 * What a key event means for typing, for the planner's own text fields. A key on the number pad often arrives with no
 * character at all (lwjgl3ify gives a character only to keys that print one by themselves, and the pad's keys are not
 * among them), so its key code is read as the character it prints.
 */
public final class KeyText {

    private KeyText() {}

    /**
     * The character to hand a ModularUI text field for a key event: the pad key's own when the event carries none, else
     * the event's as it came, so Backspace, the arrows and shortcuts reach the field unchanged.
     */
    public static char forField(final char typed, final int code) {
        final char c = typed(typed, code);
        return c != 0 ? c : typed;
    }

    /**
     * The character to type for a key event, or 0 for none. A real typed character wins, so a game that delivers both
     * the key code and the character (the pad with NumLock on) types once; only an event with no character, or a
     * control character, falls back to the pad key's own.
     */
    public static char typed(final char typed, final int code) {
        if (typed >= ' ' && typed != 127) return typed;
        return switch (code) {
            case Keyboard.KEY_NUMPAD0 -> '0';
            case Keyboard.KEY_NUMPAD1 -> '1';
            case Keyboard.KEY_NUMPAD2 -> '2';
            case Keyboard.KEY_NUMPAD3 -> '3';
            case Keyboard.KEY_NUMPAD4 -> '4';
            case Keyboard.KEY_NUMPAD5 -> '5';
            case Keyboard.KEY_NUMPAD6 -> '6';
            case Keyboard.KEY_NUMPAD7 -> '7';
            case Keyboard.KEY_NUMPAD8 -> '8';
            case Keyboard.KEY_NUMPAD9 -> '9';
            case Keyboard.KEY_DECIMAL -> '.';
            case Keyboard.KEY_ADD -> '+';
            case Keyboard.KEY_SUBTRACT -> '-';
            case Keyboard.KEY_MULTIPLY -> '*';
            case Keyboard.KEY_DIVIDE -> '/';
            case Keyboard.KEY_NUMPADCOMMA -> ',';
            default -> 0;
        };
    }
}
