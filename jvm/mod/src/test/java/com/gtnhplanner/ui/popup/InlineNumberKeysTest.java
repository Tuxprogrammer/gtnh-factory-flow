package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

class InlineNumberKeysTest {

    private static InlineNumber open() {
        final InlineNumber n = new InlineNumber();
        n.start("k", 0, v -> {});
        return n;
    }

    private static void key(final InlineNumber n, final char c, final int code) {
        n.keyPressed(c, code, false, false);
    }

    @Test
    void padKeysWithNoCharacterTypeANumber() {
        final InlineNumber n = open();
        key(n, (char) 0, Keyboard.KEY_NUMPAD1);
        key(n, (char) 0, Keyboard.KEY_NUMPAD2);
        key(n, (char) 0, Keyboard.KEY_DECIMAL);
        key(n, (char) 0, Keyboard.KEY_NUMPAD5);
        assertEquals("12.5", n.text());
    }

    @Test
    void padKeysThatCarryTheirCharacterTypeOnce() {
        final InlineNumber n = open();
        key(n, '1', Keyboard.KEY_NUMPAD1);
        key(n, '2', Keyboard.KEY_NUMPAD2);
        key(n, '.', Keyboard.KEY_DECIMAL);
        key(n, '5', Keyboard.KEY_NUMPAD5);
        assertEquals("12.5", n.text());
    }

    @Test
    void padOperatorsAndTheTopRowMix() {
        final InlineNumber n = open();
        key(n, '6', Keyboard.KEY_6);
        key(n, (char) 0, Keyboard.KEY_DIVIDE);
        key(n, (char) 0, Keyboard.KEY_NUMPAD3);
        key(n, (char) 0, Keyboard.KEY_MULTIPLY);
        key(n, (char) 0, Keyboard.KEY_ADD);
        key(n, (char) 0, Keyboard.KEY_SUBTRACT);
        assertEquals("6/3*+-", n.text());
    }

    @Test
    void controlAndPadKeysNeverTypeAnything() {
        final InlineNumber n = open();
        n.keyPressed((char) 0, Keyboard.KEY_NUMPAD1, true, false);
        key(n, (char) 0, Keyboard.KEY_NUMPADEQUALS);
        assertEquals("", n.text());
    }
}
