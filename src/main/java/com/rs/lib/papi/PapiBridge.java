package com.rs.lib.papi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * The only classes of the core that touch PlaceholderAPI; loaded on first use only, after
 * PlaceholderAPI was found enabled. Registration must run on the main thread (PlaceholderAPI
 * fires events for it).
 */
final class PapiBridge {

    private PapiBridge() {
    }

    static String set(Player viewer, String text) {
        return PlaceholderAPI.setPlaceholders(viewer, text);
    }

    /** Replaces any expansion with the same identifier; returns the new one, or null when refused. */
    static Object register(PapiHolder holder) {
        PlaceholderExpansion existing = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager()
                .getExpansion(holder.key());
        if (existing != null) {
            existing.unregister();
        }
        Expansion expansion = new Expansion(holder);
        return expansion.register() ? expansion : null;
    }

    static boolean isRegistered(Object handle) {
        return ((Expansion) handle).isRegistered();
    }

    /** PlaceholderAPI unregisters by identifier, so an expansion that replaced ours is left alone. */
    static void unregister(Object handle) {
        Expansion expansion = (Expansion) handle;
        if (expansion.isRegistered()) {
            expansion.unregister();
        }
    }

    /** What PlaceholderAPI sees; everything comes from the holder. */
    private static final class Expansion extends PlaceholderExpansion {

        private final PapiHolder holder;

        Expansion(PapiHolder holder) {
            this.holder = holder;
        }

        @Override
        public String getIdentifier() {
            return holder.identifier();
        }

        @Override
        public String getAuthor() {
            return holder.author();
        }

        @Override
        public String getVersion() {
            return holder.version();
        }

        @Override
        public boolean persist() {
            return holder.persist();
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            return holder.resolve(player, params);
        }
    }
}
