package com.gtnhplanner.importer.game;

import java.util.Map;

import net.minecraft.item.Item;

import com.gtnhplanner.read.postea.PosteaRenames;

/**
 * The running game's item renames (Postea's simple replacements) as resource ids, read once: mods register them while
 * the game loads, before any plan is read.
 */
public final class GameRenames {

    private static volatile Map<String, String> table;

    private GameRenames() {}

    public static Map<String, String> table() {
        Map<String, String> t = table;
        if (t == null) {
            t = PosteaRenames.read(item -> {
                final Object name = item instanceof Item ? Item.itemRegistry.getNameForObject(item) : null;
                return name == null ? null : String.valueOf(name);
            })
                .table();
            table = t;
        }
        return t;
    }
}
