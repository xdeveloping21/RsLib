package com.rs.lib.item;

import java.util.List;
import java.util.function.Function;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The rendered lore of an appearance as plain text, without a server; for the tests of other packages (menus). */
public final class ItemTexts {

    private ItemTexts() {
    }

    /** The lore lines {@code look} renders for {@code viewer} with {@code locals}, as plain text. */
    public static List<String> lore(ItemAppearance look, Player viewer, Function<String, String> locals) {
        return look.toBuilder(viewer, locals).loreValue().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }
}
