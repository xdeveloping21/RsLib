package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** What server owners are told about text: color cancels format, hints on local placeholders only, prefix tags. */
class AdminTextPageTest {

    private static TextDecoration.State boldOf(Component c, String text) {
        TextDecoration.State[] found = {null};
        walk(c, TextDecoration.State.NOT_SET, text, found);
        return found[0];
    }

    private static void walk(Component c, TextDecoration.State inherited, String text, TextDecoration.State[] found) {
        TextDecoration.State own = c.decoration(TextDecoration.BOLD);
        TextDecoration.State state = own == TextDecoration.State.NOT_SET ? inherited : own;
        if (c instanceof net.kyori.adventure.text.TextComponent t && t.content().contains(text)) {
            found[0] = state;
        }
        for (Component child : c.children()) {
            walk(child, state, text, found);
        }
    }

    @Test
    void colorCancelsEarlierFormats() {
        assertNotEquals(TextDecoration.State.TRUE, boldOf(RsText.color("&l&cHello"), "Hello"));
        assertEquals(TextDecoration.State.TRUE, boldOf(RsText.color("&c&lHello"), "Hello"));
        assertNotEquals(TextDecoration.State.TRUE, boldOf(RsText.color("&c&lA&rB"), "B"));
    }

    @Test
    void hintsWorkOnLocalPlaceholdersInBothForms() {
        Ph balance = Ph.of("balance", 1_500_000);
        assertEquals("1.5M 1,500,000 1500000", RsText.applyLocals("{balance:short} {balance:grouped} {balance:raw}", balance));
        assertEquals("1.5M", RsText.applyLocals("%balance:short%", balance));
        assertEquals("Steve", RsText.applyLocals("{player:short}", Ph.of("player", "Steve")));
        assertEquals("<t:1700000000:R>", RsText.applyLocals("<t:1700000000:R>", balance));
        assertEquals("%vault_eco_balance:short%", RsText.applyLocals("%vault_eco_balance:short%", balance));
    }

    @Test
    void prefixLandsAfterTheLeadingTags() {
        assertEquals("[rgb]P x", RsText.insertPrefix("[rgb]x", "P "));
        assertEquals("[center][rgb][small]P x", RsText.insertPrefix("[center][rgb][small]x", "P "));
        assertEquals("[rgb][noprefix]x", RsText.insertPrefix("[rgb][noprefix]x", "P "));
        assertEquals("P x [noprefix]", RsText.insertPrefix("x [noprefix]", "P "));
        assertEquals("x", PlainTextComponentSerializer.plainText().serialize(RsText.color("[center][noprefix]x")).trim());
    }
}
