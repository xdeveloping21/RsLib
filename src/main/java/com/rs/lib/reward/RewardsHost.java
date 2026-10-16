package com.rs.lib.reward;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.ActionContext;
import com.rs.lib.action.Requirement;
import com.rs.lib.compat.RsVersion;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.item.ItemSerializer;
import com.rs.lib.lang.RsLang;
import com.rs.lib.text.RsText;

/** What {@link Rewards} needs from the server and the plugin; lets its logic run in tests. */
interface RewardsHost {

    Logger logger();

    /** The plugin, as the owner of main-thread hops. */
    Object owner();

    boolean shuttingDown();

    /** True when the calling thread owns {@code player} (the main thread on Paper). */
    boolean ownsHere(Player player);

    /**
     * Runs {@code task} on the thread that owns {@code player}; {@code retired} instead when it
     * can never run there (Folia, the player left). Throws when nothing can be scheduled.
     */
    void runFor(Player player, Runnable task, Runnable retired);

    /** The online player with this id, or null. */
    Player online(UUID id);

    /** Checks {@code requirement} for {@code player} with the caller's placeholders. */
    boolean test(Requirement requirement, Player player, Ph[] phs);

    /** Local placeholders, then PlaceholderAPI; {@code text} keeps literal values for the text pipeline. */
    String resolve(Player player, String raw, Ph[] phs, boolean text);

    /** The item {@code appearance} renders for {@code player}. */
    ItemStack render(ItemAppearance appearance, Player player, Ph[] phs);

    /** The item as Base64 ({@link ItemSerializer}). */
    String encode(ItemStack item);

    /** The item stored as {@code base64}; throws when it cannot be read. */
    ItemStack decode(String base64);

    /** Gives money; the future says whether it really happened. */
    CompletableFuture<Boolean> money(Player player, double amount);

    /** Runs a console command; false when the server says it failed. */
    boolean console(String command);

    /** Sends a resolved message (each line break starts a new message). */
    void message(Player player, String text);

    /** Gives a potion effect; false when there is no such effect. */
    boolean potion(Player player, String effect, int ticks, int amplifier);

    /** Sends the message {@code key}: the plugin's language files, or RsLib's English text without them. */
    void send(Player player, String key, Ph... phs);

    static RewardsHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements RewardsHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public Object owner() {
            return rs.plugin();
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
        public void runFor(Player player, Runnable task, Runnable retired) {
            rs.scheduler().entity(player, task, retired);
        }

        @Override
        public Player online(UUID id) {
            return Bukkit.getPlayer(id);
        }

        @Override
        public boolean test(Requirement requirement, Player player, Ph[] phs) {
            return rs.actions().test(requirement, ActionContext.of(player, rs, phs));
        }

        @Override
        public String resolve(Player player, String raw, Ph[] phs, boolean text) {
            String s = phs == null || phs.length == 0 ? raw : RsText.applyLocals(raw, phs);
            if (s.indexOf('%') >= 0) {
                s = rs.papi().apply(player, s);
                if (text) {
                    s = RsText.normalizePapiOutput(s);
                }
            }
            return text ? s : RsText.unwrapLiterals(s);
        }

        @Override
        public ItemStack render(ItemAppearance appearance, Player player, Ph[] phs) {
            return appearance.render(player, phs);
        }

        @Override
        public String encode(ItemStack item) {
            return ItemSerializer.serializeBase64(item);
        }

        @Override
        public ItemStack decode(String base64) {
            return ItemSerializer.deserializeBase64(base64);
        }

        @Override
        public CompletableFuture<Boolean> money(Player player, double amount) {
            return rs.economy().give(player, amount);
        }

        @Override
        public boolean console(String command) {
            if (RsVersion.isFolia()) {
                // Folia runs console commands on the global region only; the result is not known here.
                rs.scheduler().sync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                return true;
            }
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }

        @Override
        public void message(Player player, String text) {
            for (String line : text.split("\n", -1)) {
                player.sendMessage(RsText.color(line));
            }
        }

        @Override
        @SuppressWarnings({"deprecation", "removal"})
        public boolean potion(Player player, String effect, int ticks, int amplifier) {
            // getByKey and getByName are the lookups every version from 1.20 to 1.21.x has.
            NamespacedKey key = NamespacedKey.fromString(effect.toLowerCase(Locale.ROOT));
            PotionEffectType type = key == null ? null : PotionEffectType.getByKey(key);
            if (type == null) {
                type = PotionEffectType.getByName(effect.toUpperCase(Locale.ROOT));
            }
            if (type == null) {
                return false;
            }
            player.addPotionEffect(new PotionEffect(type, ticks, amplifier));
            return true;
        }

        @Override
        public void send(Player player, String key, Ph... phs) {
            RsLang lang = rs.langOrNull();
            if (lang != null) {
                lang.send(player, key, phs);
                return;
            }
            String text = Defaults.get(key);
            if (!text.isEmpty()) {
                player.sendMessage(RsText.colorUncached(RsText.applyLocals(text, phs)));
            }
        }
    }

    /** RsLib's English messages, for plugins without a language module. */
    final class Defaults {

        private static final YamlConfiguration MESSAGES = load();

        private Defaults() {
        }

        static String get(String key) {
            String value = MESSAGES.getString(key);
            return value == null ? "" : value;
        }

        private static YamlConfiguration load() {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream in = Rewards.class.getResourceAsStream("/rslib-messages_en.yml")) {
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
