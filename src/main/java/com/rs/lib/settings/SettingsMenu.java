package com.rs.lib.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;
import com.rs.lib.item.RsItem;
import com.rs.lib.text.RsText;

/**
 * The one settings menu: a double chest built in code (RsLib's {@code guis/} menus are read
 * from a plugin's files, and this one belongs to no plugin). It lists every option of every
 * plugin the viewer may see, 45 per page, with the previous page, close and next page buttons
 * on the bottom row. A left click flips a toggle or moves a choice or number forward, a right
 * click moves it back. Every click and drag in it is cancelled, so nothing can be taken out.
 *
 * <p>Texts are the {@code rslib.settings.*} messages of whoever opened it.</p>
 */
final class SettingsMenu implements InventoryHolder {

    static final int SIZE = 54;
    static final int PER_PAGE = 45;
    static final int PREVIOUS = 45;
    static final int CLOSE = 49;
    static final int NEXT = 53;

    private static final Set<SettingsMenu> OPEN = ConcurrentHashMap.newKeySet();

    /** One option of one plugin, as listed. */
    record Entry(SettingsManager manager, PlayerOption option) {
    }

    private final Player viewer;
    private final Function<String, String> texts;
    private final List<Entry> entries;
    private int page;
    private Inventory inventory;

    private SettingsMenu(Player viewer, Function<String, String> texts, List<Entry> entries) {
        this.viewer = viewer;
        this.texts = texts;
        this.entries = entries;
    }

    /** Opens the menu for {@code player} (main thread), or tells them there is nothing to set. */
    static void open(Player player, Function<String, String> texts) {
        List<Entry> entries = SettingsManager.entriesFor(player);
        if (entries.isEmpty()) {
            player.sendMessage(RsText.colorUncached(texts.apply("rslib.settings.none")));
            return;
        }
        SettingsMenu menu = new SettingsMenu(player, texts, entries);
        menu.inventory = Bukkit.createInventory(menu, SIZE, RsText.color(texts.apply("rslib.settings.menu-title")));
        menu.render();
        OPEN.add(menu);
        player.openInventory(menu.inventory);
    }

    /** Closes every open settings menu. */
    static void closeAll() {
        for (SettingsMenu menu : new ArrayList<>(OPEN)) {
            OPEN.remove(menu);
            if (menu.viewer.isOnline() && viewing(menu.viewer, menu.inventory)) {
                menu.viewer.closeInventory();
            }
        }
    }

    static void handleClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder(false) instanceof SettingsMenu menu)) {
            return;
        }
        event.setCancelled(true);
        HumanEntity who = event.getWhoClicked();
        if (event.getClickedInventory() != top || !who.getUniqueId().equals(menu.viewer.getUniqueId())) {
            return;
        }
        menu.click(event.getRawSlot(), event.getClick());
    }

    static void handleDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof SettingsMenu) {
            event.setCancelled(true);
        }
    }

    static void handleClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof SettingsMenu menu) {
            OPEN.remove(menu);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ internals

    /** Which page index {@code slot} of page {@code page} shows, or -1 for none. */
    static int indexAt(int page, int slot, int size) {
        if (slot < 0 || slot >= PER_PAGE) {
            return -1;
        }
        int index = page * PER_PAGE + slot;
        return index < size ? index : -1;
    }

    static int pages(int size) {
        return Math.max(1, (size + PER_PAGE - 1) / PER_PAGE);
    }

    private void click(int slot, ClickType click) {
        int index = indexAt(page, slot, entries.size());
        if (index >= 0) {
            Entry entry = entries.get(index);
            if (!entry.manager().closed()) {
                entry.manager().click(viewer, entry.option().key(), !click.isRightClick());
                inventory.setItem(slot, item(entry));
            }
            return;
        }
        if (slot == PREVIOUS && page > 0) {
            page--;
            render();
        } else if (slot == NEXT && page + 1 < pages(entries.size())) {
            page++;
            render();
        } else if (slot == CLOSE) {
            OPEN.remove(this);
            viewer.closeInventory();
        }
    }

    private void render() {
        inventory.clear();
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int index = indexAt(page, slot, entries.size());
            if (index < 0) {
                break;
            }
            inventory.setItem(slot, item(entries.get(index)));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, button(Material.ARROW, "rslib.settings.previous-page"));
        }
        inventory.setItem(CLOSE, button(Material.BARRIER, "rslib.settings.close"));
        if (page + 1 < pages(entries.size())) {
            inventory.setItem(NEXT, button(Material.ARROW, "rslib.settings.next-page"));
        }
    }

    private ItemStack item(Entry entry) {
        PlayerOption option = entry.option();
        String value = entry.manager().value(viewer, option.key());
        String shown = option.kind() == PlayerOption.Kind.TOGGLE
                ? text(Boolean.parseBoolean(value) ? "rslib.settings.state-on" : "rslib.settings.state-off")
                : value;
        String hint = switch (option.kind()) {
            case TOGGLE -> text("rslib.settings.click-toggle");
            case CHOICE -> text("rslib.settings.click-cycle");
            case NUMBER -> text("rslib.settings.click-number", Ph.of("step", option.step()));
        };
        return RsItem.builder(option.icon())
                .name(option.name())
                .lore(text("rslib.settings.plugin", Ph.literal("plugin", entry.manager().owner())),
                        text("rslib.settings.value", Ph.of("value", shown)), hint)
                .hideAllTooltipFlags()
                .build();
    }

    private ItemStack button(Material material, String key) {
        return RsItem.builder(material).name(text(key)).hideAllTooltipFlags().build();
    }

    private String text(String key, Ph... phs) {
        return RsText.applyLocals(texts.apply(key), phs);
    }

    private static boolean viewing(Player player, Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (HumanEntity viewer : inventory.getViewers()) {
            if (viewer.getUniqueId().equals(player.getUniqueId())) {
                return true;
            }
        }
        return false;
    }
}
