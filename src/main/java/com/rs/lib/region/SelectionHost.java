package com.rs.lib.region;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.event.RsSelectionCompleteEvent;
import com.rs.lib.item.RsItem;
import com.rs.lib.lang.RsLang;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;

/** What {@link SelectionManager} needs from the server and the plugin; lets its logic run in tests. */
interface SelectionHost {

    /** The look of a wand whose spec sets none. */
    String DEFAULT_WAND_NAME = "&6&lRegion Wand";

    Plugin plugin();

    /** The plugin's name in lower case: the namespace of its wand mark. */
    String namespace();

    Logger logger();

    boolean shuttingDown();

    long nowMillis();

    /** Sends the message {@code key}: the plugin's language files, or RsLib's English text without them. */
    void send(Player player, String key, Ph... phs);

    /** Fires {@code event}; true when nobody cancelled it. */
    boolean fire(RsSelectionCompleteEvent event);

    /** Repeats {@code task} on the thread that owns {@code player}; throws when it cannot be scheduled. */
    TaskHandle timer(Player player, long periodTicks, Runnable task);

    /** True when the calling thread may touch {@code player} (always on Paper's main thread). */
    boolean ownsHere(Player player);

    /** The players in {@code world}. */
    Collection<? extends Player> playersIn(World world);

    /** The online player with this id, or null. */
    Player online(UUID id);

    /** One particle for {@code viewer} only. */
    void particle(Player viewer, Particle particle, double x, double y, double z, Object data);

    /** A wand before its mark: the spec's item, or a BLAZE_ROD named {@value #DEFAULT_WAND_NAME}. */
    ItemStack wandBase(SelectionSpec spec);

    static SelectionHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements SelectionHost {

        @Override
        public Plugin plugin() {
            return rs.plugin();
        }

        @Override
        public String namespace() {
            return rs.plugin().getName().toLowerCase(Locale.ROOT);
        }

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void send(Player player, String key, Ph... phs) {
            RsLang lang = rs.langOrNull();
            if (lang != null) {
                lang.send(player, key, phs);
                return;
            }
            player.sendMessage(RsText.colorUncached(RsText.applyLocals(Defaults.get(key), phs)));
        }

        @Override
        public boolean fire(RsSelectionCompleteEvent event) {
            return event.call();
        }

        @Override
        public TaskHandle timer(Player player, long periodTicks, Runnable task) {
            return rs.scheduler().entityTimer(player, periodTicks, periodTicks, task);
        }

        @Override
        public boolean ownsHere(Player player) {
            return rs.scheduler().ownsHere(player);
        }

        @Override
        public Collection<? extends Player> playersIn(World world) {
            return world.getPlayers();
        }

        @Override
        public Player online(UUID id) {
            return Bukkit.getPlayer(id);
        }

        @Override
        public void particle(Player viewer, Particle particle, double x, double y, double z, Object data) {
            viewer.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, data);
        }

        @Override
        public ItemStack wandBase(SelectionSpec spec) {
            ItemStack item = spec.wandItem();
            if (item != null && !item.getType().isAir()) {
                return item;
            }
            return RsItem.builder(Material.BLAZE_ROD).name(DEFAULT_WAND_NAME).build();
        }
    }

    /** RsLib's English messages, for plugins without a language module. */
    final class Defaults {

        private static final YamlConfiguration MESSAGES = load();

        private Defaults() {
        }

        static String get(String key) {
            String value = MESSAGES.getString(key);
            return value == null ? key : value;
        }

        private static YamlConfiguration load() {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream in = SelectionManager.class.getResourceAsStream("/rslib-messages_en.yml")) {
                if (in != null) {
                    yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception unreadable) {
                Logger.getLogger("RsLib").log(Level.WARNING, "[RsLib] Could not read rslib-messages_en.yml", unreadable);
            }
            return yaml;
        }
    }
}
