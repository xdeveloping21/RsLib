package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.papi.RsPapi;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Trampa 20: a template over a plugin's stack only adds its name and lore, and touches nothing otherwise. */
class OverlayTest {

    /** A stack whose meta is a name and lore lines, recording every time its meta is read or written. */
    static final class MetaStack extends ItemStack {
        Component name;
        List<Component> lore;
        int metaReads;
        int metaWrites;

        @SuppressWarnings("deprecation")
        MetaStack(Component name, List<Component> lore) {
            super();
            this.name = name;
            this.lore = lore;
        }

        @Override
        public MetaStack clone() {
            return new MetaStack(name, lore == null ? null : new ArrayList<>(lore));
        }

        @Override
        public Material getType() {
            return Material.DIAMOND_SWORD;
        }

        @Override
        public boolean hasItemMeta() {
            return true;
        }

        @Override
        public ItemMeta getItemMeta() {
            metaReads++;
            Component[] shownName = {name};
            List<List<Component>> shownLore = new ArrayList<>();
            shownLore.add(lore);
            return (ItemMeta) Proxy.newProxyInstance(OverlayTest.class.getClassLoader(), new Class<?>[] {ItemMeta.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "displayName" -> {
                            if (args == null) {
                                yield shownName[0];
                            }
                            shownName[0] = (Component) args[0];
                            yield null;
                        }
                        case "hasLore" -> shownLore.get(0) != null;
                        case "lore" -> {
                            if (args == null) {
                                yield shownLore.get(0);
                            }
                            @SuppressWarnings("unchecked")
                            List<Component> lines = (List<Component>) args[0];
                            shownLore.set(0, lines);
                            yield null;
                        }
                        case "metaName" -> shownName[0];
                        case "metaLore" -> shownLore.get(0);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Override
        public boolean setItemMeta(ItemMeta meta) {
            metaWrites++;
            name = meta.displayName();
            lore = meta.lore();
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return "MetaStack";
        }
    }

    private static ItemAppearance look(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return ItemAppearance.parse(config, "guis/test.yml -> templates.t", ItemAppearance.TextResolver.of((RsPapi) null),
                message -> { });
    }

    private static String plain(Component component) {
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void withoutNameOrLoreTheCopyIsUntouched() {
        MetaStack base = new MetaStack(Component.text("Excalibur"), List.of(Component.text("old")));
        ItemStack out = look("material: STONE\n").overlay(base, null, ItemAppearance.locals());
        assertNotSame(base, out);
        MetaStack copy = (MetaStack) out;
        assertEquals(0, copy.metaReads, "its meta is never read nor rewritten");
        assertEquals(0, copy.metaWrites);
        out = look("display-name: ''\n").overlay(base, null, ItemAppearance.locals());
        assertEquals(0, ((MetaStack) out).metaWrites, "an empty name keeps the stack's own");
    }

    @Test
    void nameReplacesAndLoreIsAppended() {
        MetaStack base = new MetaStack(Component.text("Excalibur"), List.of(Component.text("old")));
        MetaStack out = (MetaStack) look("display-name: 'Sell {price}'\nlore: ['Price: {price}']\n")
                .overlay(base, null, ItemAppearance.locals(Ph.of("price", "5")));
        assertEquals("Sell 5", plain(out.name));
        assertEquals(List.of("old", "Price: 5"), out.lore.stream().map(OverlayTest::plain).toList());
        assertEquals("Excalibur", plain(base.name), "the plugin's stack itself is never changed");
        assertEquals(1, base.lore.size());
    }

    @Test
    void loreOnlyKeepsTheName() {
        MetaStack base = new MetaStack(Component.text("Excalibur"), null);
        MetaStack out = (MetaStack) look("lore: ['extra']\n").overlay(base, null, ItemAppearance.locals());
        assertEquals("Excalibur", plain(out.name));
        assertEquals(List.of("extra"), out.lore.stream().map(OverlayTest::plain).toList());
        assertTrue(out.metaWrites > 0);
        assertNull(base.lore);
    }
}
