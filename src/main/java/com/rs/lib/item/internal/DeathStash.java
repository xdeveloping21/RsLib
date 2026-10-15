package com.rs.lib.item.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.item.ItemSerializer;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.util.InvUtil;

/**
 * Registered items a player keeps through death ({@code locked} and {@code keep-on-death}),
 * held from the death until the player is alive again.
 *
 * <p>Items taken out of the drops wait here in memory and go back on respawn, one tick later,
 * into the slot they were in (or anywhere in the inventory when that slot is taken, dropped at
 * the player's feet when it is full). When the server stops while a player is still dead, what
 * is waiting is written to {@code plugins/RsLib/data/death-stash/<kind>/<uuid>.txt}, one line
 * per item ({@code <slot>:<ItemSerializer Base64>}; a line without {@code <slot>:} is read as
 * an item without a slot), and given back on the next respawn or on the next join while alive.
 * A file is deleted only once its items are back with the player.</p>
 *
 * <p>Shared by every plugin; RsLib starts it on enable and writes it on disable. Main thread.</p>
 */
public final class DeathStash {

    /** Kind of kept items: {@code keep-on-death}. */
    public static final String KEEP_ON_DEATH = "keep-on-death";
    /** Kind of kept items: {@code locked}. */
    public static final String LOCKED = "locked";

    private static final Logger LOG = Logger.getLogger("RsLib");
    private static final String SUFFIX = ".txt";

    /** One kept item: the slot it was in (-1 when unknown), the item and its kind. */
    record Kept(int slot, ItemStack item, String kind) {
    }

    private static final Map<UUID, List<Kept>> PENDING = new ConcurrentHashMap<>();
    private static volatile Path root;
    private static volatile RsScheduler scheduler;

    private DeathStash() {
    }

    /** Reads what an earlier run left in {@code plugins/RsLib/data/death-stash}; called by RsLib on enable. */
    public static void start(JavaPlugin rslib) {
        root = rslib.getDataFolder().toPath().resolve("data").resolve("death-stash");
        scheduler = new RsScheduler(rslib);
        Map<UUID, List<Kept>> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Map<UUID, List<String>>> kind : StashFiles.readAll(root, LOG).entrySet()) {
            for (Map.Entry<UUID, List<String>> player : kind.getValue().entrySet()) {
                for (String line : player.getValue()) {
                    StashFiles.Line parsed = StashFiles.parse(line);
                    if (parsed == null) {
                        continue;
                    }
                    try {
                        ItemStack item = ItemSerializer.deserializeBase64(parsed.encoded());
                        loaded.computeIfAbsent(player.getKey(), k -> new ArrayList<>())
                                .add(new Kept(parsed.slot(), item, kind.getKey()));
                    } catch (RuntimeException unreadable) {
                        LOG.warning("[RsLib] A kept item of " + player.getKey() + " could not be read; skipped: "
                                + unreadable);
                    }
                }
            }
        }
        loaded.forEach((player, items) -> PENDING.merge(player, items, (a, b) -> {
            List<Kept> all = new ArrayList<>(a);
            all.addAll(b);
            return all;
        }));
    }

    /** Writes every item still waiting (its owner is dead or offline); called by RsLib on disable. */
    public static void shutdown() {
        Path folder = root;
        if (folder != null) {
            for (Map.Entry<UUID, List<Kept>> entry : PENDING.entrySet()) {
                write(folder, entry.getKey(), entry.getValue());
            }
        }
        PENDING.clear();
        scheduler = null;
        root = null;
    }

    /** Holds {@code item}, taken from {@code player}'s drops, until the player is alive again. */
    public static void keep(UUID player, int slot, ItemStack item, String kind) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        PENDING.computeIfAbsent(player, k -> new ArrayList<>()).add(new Kept(slot, item.clone(), kind));
    }

    /** True when items are waiting for {@code player}. */
    public static boolean holds(UUID player) {
        List<Kept> kept = PENDING.get(player);
        return kept != null && !kept.isEmpty();
    }

    /** Gives back, one tick from now, what waits for {@code player} (respawn, or join while alive). */
    public static void giveBackLater(Player player) {
        if (!holds(player.getUniqueId())) {
            return;
        }
        RsScheduler hop = scheduler;
        if (hop == null) {
            giveBack(player);
            return;
        }
        try {
            hop.syncLater(1L, () -> {
                if (player.isOnline() && !player.isDead()) {
                    giveBack(player);
                }
            });
        } catch (RuntimeException disabled) {
            giveBack(player);
        }
    }

    /** Gives back now what waits for {@code player}: each item to its slot when free, the rest anywhere. */
    public static void giveBack(Player player) {
        List<Kept> kept = PENDING.remove(player.getUniqueId());
        if (kept == null || kept.isEmpty()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (Kept entry : kept) {
            int slot = entry.slot();
            if (slot >= 0 && slot < inventory.getSize() && empty(inventory.getItem(slot))) {
                inventory.setItem(slot, entry.item());
            } else {
                rest.add(entry.item());
            }
        }
        if (!rest.isEmpty()) {
            InvUtil.giveItems(player, rest.toArray(new ItemStack[0]));
        }
        Path folder = root;
        if (folder != null) {
            StashFiles.delete(folder, player.getUniqueId(), LOG);
        }
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static void write(Path folder, UUID player, List<Kept> kept) {
        Map<String, List<String>> byKind = new LinkedHashMap<>();
        for (Kept entry : kept) {
            try {
                byKind.computeIfAbsent(entry.kind(), k -> new ArrayList<>())
                        .add(StashFiles.line(entry.slot(), ItemSerializer.serializeBase64(entry.item())));
            } catch (RuntimeException unwritable) {
                LOG.log(Level.WARNING, "[RsLib] A kept item of " + player + " could not be written", unwritable);
            }
        }
        for (Map.Entry<String, List<String>> kind : byKind.entrySet()) {
            StashFiles.write(folder, kind.getKey(), player, kind.getValue(), LOG);
        }
    }

    /** The file layout, on texts only. */
    static final class StashFiles {

        /** A parsed line: the slot (-1 when absent) and the encoded item. */
        record Line(int slot, String encoded) {
        }

        private StashFiles() {
        }

        static String line(int slot, String encoded) {
            return slot < 0 ? encoded : slot + ":" + encoded;
        }

        /** The slot and item of one line; null for a blank line. */
        static Line parse(String line) {
            String text = line == null ? "" : line.trim();
            if (text.isEmpty()) {
                return null;
            }
            int colon = text.indexOf(':');
            if (colon > 0 && colon <= 3) {
                try {
                    return new Line(Integer.parseInt(text.substring(0, colon)), text.substring(colon + 1).trim());
                } catch (NumberFormatException notSlot) {
                    // Base64 never holds ':' anyway; read the whole line as the item.
                }
            }
            return new Line(-1, text);
        }

        /** {@code <root>/<kind>/<uuid>.txt}, replaced as a whole. */
        static void write(Path root, String kind, UUID player, List<String> lines, Logger log) {
            try {
                Path folder = root.resolve(kind);
                Files.createDirectories(folder);
                Path file = folder.resolve(player + SUFFIX);
                Path temp = folder.resolve(player + SUFFIX + ".tmp");
                Files.write(temp, lines, StandardCharsets.UTF_8);
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                log.severe("[RsLib] Could not write the kept items of " + player + ": " + e);
            }
        }

        /** Every file under {@code root}: kind to player to lines. */
        static Map<String, Map<UUID, List<String>>> readAll(Path root, Logger log) {
            Map<String, Map<UUID, List<String>>> out = new LinkedHashMap<>();
            if (!Files.isDirectory(root)) {
                return out;
            }
            try (DirectoryStream<Path> kinds = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path kind : kinds) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(kind, "*" + SUFFIX)) {
                        for (Path file : files) {
                            String name = file.getFileName().toString();
                            UUID player;
                            try {
                                player = UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
                            } catch (IllegalArgumentException notOurs) {
                                continue;
                            }
                            out.computeIfAbsent(kind.getFileName().toString(), k -> new LinkedHashMap<>())
                                    .put(player, Files.readAllLines(file, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warning("[RsLib] Could not read the kept items in " + root + ": " + e);
            }
            return out;
        }

        /** Deletes {@code player}'s file in every kind folder. */
        static void delete(Path root, UUID player, Logger log) {
            if (!Files.isDirectory(root)) {
                return;
            }
            try (DirectoryStream<Path> kinds = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path kind : kinds) {
                    Files.deleteIfExists(kind.resolve(player + SUFFIX));
                }
            } catch (IOException | RuntimeException e) {
                log.warning("[RsLib] Could not delete the kept items file of " + player + ": " + e);
            }
        }
    }
}
