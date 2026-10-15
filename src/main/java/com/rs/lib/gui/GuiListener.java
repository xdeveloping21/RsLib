package com.rs.lib.gui;

import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Clicks, drags and closes of every RsLib menu on the server, registered once by RsLib. A menu
 * is recognised by its holder ({@link RsGuiHolder}), never by its title. Only methods of the
 * events themselves are used (no {@code InventoryView}), so the same jar runs on every
 * supported server version.
 */
final class GuiListener implements Listener {

    /** The session of {@code inventory}, or null when it is not an RsLib menu. */
    static GuiSession sessionOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof RsGuiHolder menu ? menu.session() : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        GuiSession session = sessionOf(top);
        if (session == null) {
            return;
        }
        HumanEntity who = event.getWhoClicked();
        if (session.closed() || session.inventory() != top || !(who instanceof Player player)
                || !player.getUniqueId().equals(session.viewerId())) {
            event.setCancelled(true);
            return;
        }
        int raw = event.getRawSlot();
        OfferRouting.Zone zone = OfferRouting.zone(raw, top.getSize());
        ClickType click = event.getClick();
        boolean input = zone == OfferRouting.Zone.TOP && session.isInputSlot(raw);
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        OfferRouting.Outcome outcome = OfferRouting.click(event.getAction(), zone, click, input, empty(cursor),
                empty(current), session.playerInventory());
        if (outcome == OfferRouting.Outcome.PASS) {
            return;
        }
        event.setCancelled(true);
        try {
            switch (outcome) {
                case BUTTON -> session.handleClick(raw, click);
                case OFFER_CURSOR -> session.handleOffer(new ItemOffer(player, ItemOffer.Kind.CURSOR, raw, -1,
                        cursor.clone(), click));
                case OFFER_SHIFT -> session.handleOffer(new ItemOffer(player, ItemOffer.Kind.SHIFT_CLICK, -1,
                        event.getSlot(), current.clone(), click));
                default -> {
                    // Cancelled, nothing runs.
                }
            }
        } catch (RuntimeException | LinkageError e) {
            session.holder().owner().getLogger().log(Level.WARNING,
                    "A click in menu " + session.guiId() + " failed", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getInventory();
        GuiSession session = sessionOf(top);
        if (session == null) {
            return;
        }
        HumanEntity who = event.getWhoClicked();
        if (session.closed() || session.inventory() != top || !(who instanceof Player player)
                || !player.getUniqueId().equals(session.viewerId())) {
            event.setCancelled(true);
            return;
        }
        int topSize = top.getSize();
        int cells = 0;
        int cell = -1;
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < topSize) {
                cells++;
                cell = raw;
            }
        }
        ItemStack dragged = event.getOldCursor();
        boolean singleInput = cells == 1 && session.isInputSlot(cell);
        OfferRouting.DragOutcome outcome = OfferRouting.drag(cells, singleInput, empty(dragged),
                session.playerInventory());
        if (outcome == OfferRouting.DragOutcome.PASS) {
            return;
        }
        event.setCancelled(true);
        if (outcome == OfferRouting.DragOutcome.OFFER) {
            ClickType click = event.getType() == DragType.SINGLE ? ClickType.RIGHT : ClickType.LEFT;
            try {
                session.handleOffer(new ItemOffer(player, ItemOffer.Kind.DRAG, cell, -1, dragged.clone(), click));
            } catch (RuntimeException | LinkageError e) {
                session.holder().owner().getLogger().log(Level.WARNING,
                        "A drag in menu " + session.guiId() + " failed", e);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClose(InventoryCloseEvent event) {
        closed(event.getInventory());
    }

    /**
     * {@code top} was closed: a natural close of its session, unless it is an inventory the
     * session already replaced (a title change) or the session was closed by the plugin.
     */
    static void closed(Inventory top) {
        GuiSession session = sessionOf(top);
        if (session == null || session.inventory() != top) {
            return;
        }
        try {
            session.closedByEvent();
        } catch (RuntimeException | LinkageError e) {
            session.holder().owner().getLogger().log(Level.WARNING,
                    "Closing menu " + session.guiId() + " failed", e);
        }
    }

    /**
     * No stack, or air. Compares the three air materials instead of {@code Material.isAir()},
     * which goes through the block registry: this runs on every click of the server.
     */
    static boolean empty(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return true;
        }
        Material type = stack.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }
}
