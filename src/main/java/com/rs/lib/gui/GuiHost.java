package com.rs.lib.gui;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.ActionEngine;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.util.SoundUtil;

import net.kyori.adventure.text.Component;

/**
 * What the menu module needs from the server and the plugin. Lets sessions, renders and
 * clicks run in tests without either.
 */
interface GuiHost {

    Logger logger();

    void debug(Supplier<String> message);

    /** The plugin, or null in tests. */
    JavaPlugin plugin();

    /** The context handed to action contexts, or null in tests. */
    Rs rs();

    ActionEngine actions();

    boolean shuttingDown();

    /** True when the calling thread may touch {@code player} now. */
    boolean ownsHere(Player player);

    /** Runs {@code task} on the thread that owns {@code player}; throws when it cannot be scheduled. */
    void runFor(Player player, Runnable task);

    TaskHandle later(Player player, long delayTicks, Runnable task);

    TaskHandle timer(Player player, long periodTicks, Runnable task);

    /** Runs {@code task} on the next tick, on the global thread; throws when it cannot be scheduled. */
    void nextTick(Runnable task);

    /** A menu inventory for {@code holder}: a chest of {@code def.rows()} or {@code def.inventoryType()}. */
    Inventory createInventory(RsGuiHolder holder, GuiDef def, Component title);

    void openWindow(Player player, Inventory inventory);

    /** True when {@code player} has {@code inventory} open. */
    boolean viewing(Player player, Inventory inventory);

    /** Closes whatever {@code player} has open. */
    void closeWindow(Player player);

    void sound(Player player, String spec);

    /** The title of {@code def} for {@code viewer}: placeholders, then the text pipeline. */
    Component title(GuiDef def, Player viewer, Ph[] phs);

    /**
     * Renders {@code look} for {@code viewer}; over a copy of {@code overlay} (only its name and
     * lore lines) when {@code overlay} is not null.
     */
    ItemStack build(ItemAppearance look, Player viewer, Ph[] phs, ItemStack overlay, Consumer<String> skinRefresh);

    /** A copy of {@code proto} carrying the menu stamp {@code "<guiId>:<slot>"}. */
    ItemStack stamp(ItemStack proto, String guiId, int slot);

    static GuiHost of(Rs rs) {
        return new Server(rs, new NamespacedKey(rs.plugin(), GuiManager.ITEM_TAG));
    }

    /** The real server. */
    record Server(Rs rs, NamespacedKey stampKey) implements GuiHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public void debug(Supplier<String> message) {
            rs.debug().log(message);
        }

        @Override
        public JavaPlugin plugin() {
            return rs.plugin();
        }

        @Override
        public ActionEngine actions() {
            return rs.actions();
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public boolean ownsHere(Player player) {
            return rs.scheduler().ownsHere(player);
        }

        @Override
        public void runFor(Player player, Runnable task) {
            rs.scheduler().entity(player, task);
        }

        @Override
        public TaskHandle later(Player player, long delayTicks, Runnable task) {
            return rs.scheduler().entityLater(player, delayTicks, task);
        }

        @Override
        public TaskHandle timer(Player player, long periodTicks, Runnable task) {
            return rs.scheduler().entityTimer(player, periodTicks, periodTicks, task);
        }

        @Override
        public void nextTick(Runnable task) {
            rs.scheduler().syncLater(1L, task);
        }

        @Override
        public Inventory createInventory(RsGuiHolder holder, GuiDef def, Component title) {
            if (def.inventoryType() != null) {
                try {
                    return Bukkit.createInventory(holder, def.inventoryType(), title);
                } catch (RuntimeException refused) {
                    GuiWarnings.once(logger(), "[" + def.file() + "] inventory-type " + def.inventoryType()
                            + " cannot be created on this server (" + refused.getMessage() + "); using a chest");
                }
            }
            return Bukkit.createInventory(holder, def.rows() * Layout.COLUMNS, title);
        }

        @Override
        public void openWindow(Player player, Inventory inventory) {
            player.openInventory(inventory);
        }

        @Override
        public boolean viewing(Player player, Inventory inventory) {
            for (HumanEntity viewer : inventory.getViewers()) {
                if (viewer.getUniqueId().equals(player.getUniqueId())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void closeWindow(Player player) {
            player.closeInventory();
        }

        @Override
        public void sound(Player player, String spec) {
            SoundUtil.play(player, spec);
        }

        @Override
        public Component title(GuiDef def, Player viewer, Ph[] phs) {
            String raw = def.title();
            if (raw.indexOf('%') < 0 && raw.indexOf('{') < 0) {
                return RsText.color(raw);
            }
            String text = def.resolver().resolve(raw, viewer, ItemAppearance.locals(phs));
            return RsText.colorUncached(text);
        }

        @Override
        public ItemStack build(ItemAppearance look, Player viewer, Ph[] phs, ItemStack overlay,
                               Consumer<String> skinRefresh) {
            if (overlay != null) {
                return look.overlay(overlay, viewer, ItemAppearance.locals(phs));
            }
            return look.render(viewer, ItemAppearance.locals(phs), skinRefresh);
        }

        @Override
        public ItemStack stamp(ItemStack proto, String guiId, int slot) {
            ItemStack out = proto.clone();
            if (out.getType().isAir()) {
                return out;
            }
            ItemMeta meta = out.getItemMeta();
            if (meta == null) {
                return out;
            }
            meta.getPersistentDataContainer().set(stampKey, PersistentDataType.STRING, guiId + ":" + slot);
            out.setItemMeta(meta);
            return out;
        }
    }
}
