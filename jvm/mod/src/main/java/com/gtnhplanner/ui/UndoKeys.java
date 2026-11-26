package com.gtnhplanner.ui;

import java.util.function.BiPredicate;

import com.gtnhplanner.nei.NEIPlanConfig;

import codechicken.nei.NEIClientUtils;

/**
 * Undo and redo from the keyboard: Ctrl+Z undoes, Ctrl+Shift+Z and Ctrl+Y redo. NEI keeps each binding as a plain
 * key (Controls can move it) and drops any modifiers it was registered with, so the modifiers are asked for here: a
 * binding read with none would answer only to the bare key, and Ctrl+Z would do nothing.
 */
final class UndoKeys {

    private UndoKeys() {}

    enum Act {
        NONE,
        UNDO,
        REDO
    }

    /** Set once an NEI without {@code KeyManager.isHashDown(name, modifiers)} turned out to be the one loaded. */
    private static boolean olderNei;

    /**
     * Which act the key just pressed ({@code key}, with the modifiers held) asks for, from NEI's bindings. The NEI of
     * 2.9.0 beta-1 has no
     * {@code KeyManager.isHashDown(name, modifiers)}: there the binds are read with NEI's own whole-bind check, as the
     * board always read them.
     */
    static Act now(final int key) {
        if (!olderNei) {
            try {
                final boolean ctrl = NEIClientUtils.controlKey(), shift = NEIClientUtils.shiftKey(),
                    alt = NEIClientUtils.altKey();
                return read(
                    (name, mods) -> pressed(key, codechicken.nei.KeyManager.getKeyCode(name), mods, ctrl, shift, alt));
            } catch (final LinkageError e) {
                olderNei = true;
            }
        }
        if (codechicken.nei.NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigUndoKey.KEY)) return Act.UNDO;
        if (codechicken.nei.NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoKey.KEY)
            || codechicken.nei.NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoAltKey.KEY)) return Act.REDO;
        return Act.NONE;
    }

    /**
     * Whether the key just pressed, {@code key}, is a binding's ({@code bound}, any modifiers NEI hashed into it left
     * out) with exactly the modifiers {@code mods} held. The key comes from the press itself, not from whether it is
     * down now: the game reads key states once a frame, so a tap shorter than a frame reads as never down, and a
     * modifier pressed in the same frame as the key would read the key as down and act twice.
     */
    static boolean pressed(final int key, final int bound, final int mods, final boolean ctrl, final boolean shift,
        final boolean alt) {
        final int plain = bound & ~(NEIClientUtils.CTRL_HASH | NEIClientUtils.SHIFT_HASH | NEIClientUtils.ALT_HASH);
        return key != 0 && key == plain
            && ctrl == ((mods & NEIClientUtils.CTRL_HASH) != 0)
            && shift == ((mods & NEIClientUtils.SHIFT_HASH) != 0)
            && alt == ((mods & NEIClientUtils.ALT_HASH) != 0);
    }

    /**
     * Which act the keys held now ask for. {@code down} answers whether a binding's key is down with exactly these
     * modifiers held (NEI's {@code KeyManager.isHashDown(name, modifiers)}).
     */
    static Act read(final BiPredicate<String, Integer> down) {
        if (down.test(NEIPlanConfig.ConfigUndoKey.KEY, NEIClientUtils.CTRL_HASH)) return Act.UNDO;
        if (down.test(NEIPlanConfig.ConfigRedoKey.KEY, NEIClientUtils.CTRL_HASH | NEIClientUtils.SHIFT_HASH)
            || down.test(NEIPlanConfig.ConfigRedoAltKey.KEY, NEIClientUtils.CTRL_HASH)) return Act.REDO;
        return Act.NONE;
    }
}
