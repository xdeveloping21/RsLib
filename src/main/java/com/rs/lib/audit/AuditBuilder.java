package com.rs.lib.audit;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One audit entry being written, from {@link AuditLog#entry(String)}; every part is optional.
 * {@link #record()} queues it. Not thread-safe: build and record it on one thread.
 */
public final class AuditBuilder {

    private final AuditLog log;
    private final String action;
    private UUID actorId;
    private String actorName;
    private UUID targetId;
    private String targetName;
    private String world;
    private double x;
    private double y;
    private double z;
    private BigDecimal money;
    private String items;
    private String details;
    private boolean recorded;

    AuditBuilder(AuditLog log, String action) {
        this.log = log;
        this.action = action;
    }

    /**
     * Who did it. A player is stored with their id and name, and their location when this
     * thread owns them (and no place was given with {@link #at}); anything else is stored as
     * {@value AuditLog#CONSOLE}.
     */
    public AuditBuilder actor(CommandSender sender) {
        if (sender instanceof Player player) {
            actorId = player.getUniqueId();
            actorName = AuditLog.cut(player.getName(), AuditLog.MAX_NAME);
            if (world == null && log.ownsHere(player)) {
                place(player.getLocation());
            }
        } else if (sender != null) {
            actorId = null;
            actorName = AuditLog.CONSOLE;
        }
        return this;
    }

    /** Who did it, by id and name (either may be null). */
    public AuditBuilder actor(UUID id, String name) {
        actorId = id;
        actorName = AuditLog.cut(name, AuditLog.MAX_NAME);
        return this;
    }

    /** To whom, who may be offline. */
    public AuditBuilder target(OfflinePlayer player) {
        if (player != null) {
            targetId = player.getUniqueId();
            targetName = AuditLog.cut(player.getName(), AuditLog.MAX_NAME);
        }
        return this;
    }

    /** To whom, by id and name (either may be null). */
    public AuditBuilder target(UUID id, String name) {
        targetId = id;
        targetName = AuditLog.cut(name, AuditLog.MAX_NAME);
        return this;
    }

    /** Where it happened; replaces the actor's location. A location without a world is ignored. */
    public AuditBuilder at(Location location) {
        place(location);
        return this;
    }

    /** An amount of money involved. */
    public AuditBuilder money(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("Money must be a finite number: " + amount);
        }
        money = BigDecimal.valueOf(amount);
        return this;
    }

    /** An amount of money involved; null clears it. */
    public AuditBuilder money(BigDecimal amount) {
        money = amount;
        return this;
    }

    /** The items involved, already described ({@link AuditItems#describe}, {@link AuditItems.Diff#describe()}). */
    public AuditBuilder items(String description) {
        items = AuditLog.cut(description, AuditLog.MAX_TEXT);
        return this;
    }

    /** The items involved, described with {@link AuditItems#describe(Collection)}. */
    public AuditBuilder items(Collection<ItemStack> stacks) {
        return items(AuditItems.describe(stacks));
    }

    /** Free text about what happened. */
    public AuditBuilder details(String text) {
        details = AuditLog.cut(text, AuditLog.MAX_TEXT);
        return this;
    }

    /**
     * Queues the entry with the time of this call. Recording the same builder twice throws.
     *
     * @throws IllegalStateException when this builder was already recorded
     */
    public void record() {
        if (recorded) {
            throw new IllegalStateException("This audit entry was already recorded");
        }
        recorded = true;
        log.add(new AuditEntry(0L, log.plugin(), action, actorId, actorName, targetId, targetName, world, x, y, z,
                money, items, details, log.now()));
    }

    private void place(Location location) {
        if (location == null) {
            return;
        }
        World in;
        try {
            in = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return;
        }
        if (in == null) {
            return;
        }
        world = in.getName();
        x = location.getX();
        y = location.getY();
        z = location.getZ();
    }
}
