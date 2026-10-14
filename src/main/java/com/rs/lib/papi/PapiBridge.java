package com.rs.lib.papi;

import org.bukkit.entity.Player;

import me.clip.placeholderapi.PlaceholderAPI;

/** The only class of the core that touches PlaceholderAPI; loaded on first use only. */
final class PapiBridge {

    private PapiBridge() {
    }

    static String set(Player viewer, String text) {
        return PlaceholderAPI.setPlaceholders(viewer, text);
    }
}
