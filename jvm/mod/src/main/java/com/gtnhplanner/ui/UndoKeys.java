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
     * Which act the keys held now ask for, from NEI. The NEI of 2.9.0 beta-1 has no
     * {@code KeyManager.isHashDown(name, modifiers)}: there the binds are read with NEI's own whole-bind check, as the
     * board always read them.
     */
    static Act now() {
        if (!olderNei) {
            try {
                return read(codechicken.nei.KeyManager::isHashDown);
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
