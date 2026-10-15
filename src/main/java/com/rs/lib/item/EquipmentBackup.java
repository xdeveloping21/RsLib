package com.rs.lib.item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The real equipment a player wore before {@link ItemRegistry#apply} put a registered item in
 * its place, kept in {@code plugins/<Plugin>/data/equipment-backup.yml}:
 *
 * <pre>
 * &lt;uuid&gt;:
 *   HEAD:
 *     id: crown        # the registered item put on
 *     item: "..."      # the real item, ItemSerializer Base64; absent when the slot was empty
 * </pre>
 *
 * Every change is written at once, on the calling thread (a crash must not lose a real item).
 * The item codec is the caller's: this class only stores texts.
 */
final class EquipmentBackup {

    /** One backed-up slot: the id put on and the real item ({@code null} when the slot was empty). */
    record Saved(String id, String item) {
    }

    /** Where a real item goes back. */
    enum Target { SLOT, INVENTORY }

    private final Path file;
    private final Logger logger;
    private final YamlConfiguration data = new YamlConfiguration();

    EquipmentBackup(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        if (Files.exists(file)) {
            try {
                data.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                logger.warning("Could not read " + file + "; equipment put on by apply() before this start"
                        + " will not be given back: " + e);
            }
        }
    }

    /**
     * A real item goes back into its slot only when the slot is empty or holds the applied item
     * (or another locked item of the same plugin); otherwise it goes to the inventory, so
     * nothing the player put there since is lost.
     */
    static Target target(boolean slotEmpty, boolean slotHoldsOwnItem) {
        return slotEmpty || slotHoldsOwnItem ? Target.SLOT : Target.INVENTORY;
    }

    synchronized Saved get(UUID player, EquipmentSlot slot) {
        ConfigurationSection section = data.getConfigurationSection(player + "." + slot.name());
        return section == null ? null : new Saved(section.getString("id", ""), section.getString("item"));
    }

    synchronized Map<EquipmentSlot, Saved> all(UUID player) {
        Map<EquipmentSlot, Saved> out = new EnumMap<>(EquipmentSlot.class);
        ConfigurationSection section = data.getConfigurationSection(player.toString());
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            EquipmentSlot slot;
            try {
                slot = EquipmentSlot.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry != null) {
                out.put(slot, new Saved(entry.getString("id", ""), entry.getString("item")));
            }
        }
        return out;
    }

    synchronized Set<UUID> players() {
        Set<UUID> out = new LinkedHashSet<>();
        for (String key : data.getKeys(false)) {
            try {
                out.add(UUID.fromString(key));
            } catch (IllegalArgumentException notUuid) {
                // Not ours: left alone.
            }
        }
        return out;
    }

    synchronized void put(UUID player, EquipmentSlot slot, String id, String item) {
        String path = player + "." + slot.name();
        data.set(path, null);
        data.set(path + ".id", id);
        if (item != null) {
            data.set(path + ".item", item);
        }
        write();
    }

    synchronized void remove(UUID player, EquipmentSlot slot) {
        data.set(player + "." + slot.name(), null);
        ConfigurationSection rest = data.getConfigurationSection(player.toString());
        if (rest != null && rest.getKeys(false).isEmpty()) {
            data.set(player.toString(), null);
        }
        write();
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            if (data.getKeys(false).isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, data.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            logger.severe("Could not write " + file + ": " + e);
        }
    }
}
