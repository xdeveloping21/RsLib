package com.rs.lib.audit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One entry of the audit log, as read back by {@link AuditLog#query}.
 *
 * @param id         row id; 0 while it waits to be written
 * @param plugin     the plugin that recorded it
 * @param action     the action key, lower case with dashes ({@code market-buy})
 * @param actorId    who did it, or null for the console or when nobody was given
 * @param actorName  their name, {@code CONSOLE}, or null when nobody was given
 * @param targetId   to whom, or null
 * @param targetName their name, or null
 * @param world      where it happened (the actor's world, or the place given), or null
 * @param x          block-precise coordinates; meaningless when {@code world} is null
 * @param y          see {@code x}
 * @param z          see {@code x}
 * @param money      an amount of money involved, or null
 * @param items      a description of the items involved ({@code 16x Diamond}), or null
 * @param details    free text, or null
 * @param createdAt  when it was recorded, epoch milliseconds
 */
public record AuditEntry(long id, String plugin, String action, UUID actorId, String actorName, UUID targetId,
                         String targetName, String world, double x, double y, double z, BigDecimal money,
                         String items, String details, long createdAt) {

    /** True when the entry has a place. */
    public boolean hasLocation() {
        return world != null;
    }

    /** True when {@code player} did it or it was done to them. */
    public boolean involves(UUID player) {
        return player != null && (player.equals(actorId) || player.equals(targetId));
    }

    AuditEntry withId(long newId) {
        return new AuditEntry(newId, plugin, action, actorId, actorName, targetId, targetName, world, x, y, z,
                money, items, details, createdAt);
    }
}
