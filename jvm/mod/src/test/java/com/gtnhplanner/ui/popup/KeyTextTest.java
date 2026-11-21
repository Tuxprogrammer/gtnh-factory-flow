package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

class KeyTextTest {

    @Test
    void padKeysWithNoCharacterTypeWhatTheyPrint() {
        final int[] digits = { Keyboard.KEY_NUMPAD0, Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3,
            Keyboard.KEY_NUMPAD4, Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7,
            Keyboard.KEY_NUMPAD8, Keyboard.KEY_NUMPAD9 };
        for (int d = 0; d < digits.length; d++) assertEquals((char) ('0' + d), KeyText.typed((char) 0, digits[d]));
        assertEquals('.', KeyText.typed((char) 0, Keyboard.KEY_DECIMAL));
        assertEquals('+', KeyText.typed((char) 0, Keyboard.KEY_ADD));
        assertEquals('-', KeyText.typed((char) 0, Keyboard.KEY_SUBTRACT));
        assertEquals('*', KeyText.typed((char) 0, Keyboard.KEY_MULTIPLY));
        assertEquals('/', KeyText.typed((char) 0, Keyboard.KEY_DIVIDE));
        assertEquals(',', KeyText.typed((char) 0, Keyboard.KEY_NUMPADCOMMA));
    }

    @Test
    void aControlCharacterFallsBackToo() {
        assertEquals('7', KeyText.typed((char) 7, Keyboard.KEY_NUMPAD7));
        assertEquals('7', KeyText.typed((char) 127, Keyboard.KEY_NUMPAD7));
    }

    @Test
    void aRealCharacterIsTheOneTyped() {
        assertEquals('7', KeyText.typed('7', Keyboard.KEY_NUMPAD7));
        // A layout whose pad prints something else keeps what the game says.
        assertEquals(',', KeyText.typed(',', Keyboard.KEY_DECIMAL));
        assertEquals('a', KeyText.typed('a', Keyboard.KEY_A));
    }

    @Test
    void otherKeysAndTheEqualsKeyTypeNothingWithoutACharacter() {
        assertEquals(0, KeyText.typed((char) 0, Keyboard.KEY_A));
        assertEquals(0, KeyText.typed((char) 0, Keyboard.KEY_NUMPADEQUALS));
        assertEquals(0, KeyText.typed((char) 0, Keyboard.KEY_NUMPADENTER));
        assertEquals(0, KeyText.typed((char) 0, 0));
    }

    @Test
    void aTextFieldTypesOnlyAPadKeyThatCameWithNoCharacter() {
        // ModularUI's field types nothing from a key event under lwjgl3ify, so the pad's no-character case is typed
        // here; a key that came with its character is the field's own, so it is typed once, not twice.
        assertEquals('4', KeyText.padOnly((char) 0, Keyboard.KEY_NUMPAD4));
        assertEquals('.', KeyText.padOnly((char) 0, Keyboard.KEY_DECIMAL));
        assertEquals('5', KeyText.padOnly((char) 0, Keyboard.KEY_NUMPAD5));
        assertEquals(0, KeyText.padOnly('4', Keyboard.KEY_NUMPAD4));
        assertEquals(0, KeyText.padOnly((char) 8, Keyboard.KEY_BACK));
        assertEquals(0, KeyText.padOnly((char) 1, Keyboard.KEY_A));
        assertEquals(0, KeyText.padOnly((char) 0, Keyboard.KEY_RETURN));
    }
}
