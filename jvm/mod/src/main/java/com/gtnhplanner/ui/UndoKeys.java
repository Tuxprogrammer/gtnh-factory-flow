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
