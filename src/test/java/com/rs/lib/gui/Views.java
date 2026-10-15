package com.rs.lib.gui;

import java.lang.reflect.Proxy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * An inventory view made of a proxy, to build real click and drag events in tests. (Only test
 * code touches {@code InventoryView}; RsLib itself never calls it.)
 */
final class Views {

    private Views() {
    }

    /** What a view shows: {@code top} over {@code bottom}, {@code player} looking, a cursor. */
    static final class View {
        final Inventory top;
        final Inventory bottom;
        final Player player;
        ItemStack cursor;
        final InventoryView view;

        View(Inventory top, Inventory bottom, Player player) {
            this.top = top;
            this.bottom = bottom;
            this.player = player;
            this.view = (InventoryView) Proxy.newProxyInstance(Views.class.getClassLoader(),
                    new Class<?>[] {InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getTopInventory" -> top;
                        case "getBottomInventory" -> bottom;
                        case "getPlayer" -> player;
                        case "getCursor" -> cursor;
                        case "setCursor" -> {
                            cursor = (ItemStack) args[0];
                            yield null;
                        }
                        case "getItem" -> {
                            int raw = (Integer) args[0];
                            yield raw < 0 ? null : raw < top.getSize() ? top.getItem(raw) : bottom.getItem(raw - top.getSize());
                        }
                        case "setItem" -> {
                            int raw = (Integer) args[0];
                            if (raw >= 0 && raw < top.getSize()) {
                                top.setItem(raw, (ItemStack) args[1]);
                            } else if (raw >= 0) {
                                bottom.setItem(raw - top.getSize(), (ItemStack) args[1]);
                            }
                            yield null;
                        }
                        case "convertSlot" -> {
                            int raw = (Integer) args[0];
                            yield raw < top.getSize() ? raw : raw - top.getSize();
                        }
                        case "getInventory" -> {
                            int raw = (Integer) args[0];
                            yield raw < 0 ? null : raw < top.getSize() ? top : bottom;
                        }
                        case "countSlots" -> top.getSize() + bottom.getSize();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "TestView";
                        default -> defaultFor(method.getReturnType());
                    });
        }
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
