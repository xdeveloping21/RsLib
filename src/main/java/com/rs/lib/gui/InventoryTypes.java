package com.rs.lib.gui;

import org.bukkit.event.inventory.InventoryType;

/** The inventory types the server knows, by name; lets menus be read in tests without one. */
@FunctionalInterface
interface InventoryTypes {

    /** The server's own types. */
    InventoryTypes SERVER = name -> {
        for (InventoryType type : InventoryType.values()) {
            if (type.name().equals(name)) {
                return type.getDefaultSize();
            }
        }
        return -1;
    };

    /** How many cells the type {@code name} (upper case, {@code _}) has, or -1 when there is no such type. */
    int size(String name);
}
