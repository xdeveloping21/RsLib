package com.rs.lib.util;

import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Who is behind a hit: the player responsible for a damage event, or for the entity that dealt
 * it.
 *
 * <pre>
 * Player killer = DamageBlame.responsiblePlayer(event);   // null: no player behind it
 * </pre>
 *
 * <p>Followed, up to {@value #MAX_HOPS} steps: a projectile (arrow, trident, snowball, potion,
 * firework, ...) to its shooter, primed TNT to whoever lit it, a lingering potion cloud to
 * whoever threw it, evoker fangs to their caster and a tamed animal (a wolf) to its owner when
 * that owner is online. A player is their own culprit. Anything else (a mob, a block, fall
 * damage, an owner who is offline) gives null. Pure; safe from any thread that may read the
 * entities.</p>
 */
public final class DamageBlame {

    /** The most steps followed from the damager to a player (TNT lit by an arrow is three). */
    public static final int MAX_HOPS = 4;

    private DamageBlame() {
    }

    /** The player responsible for {@code event}; null when no entity dealt it or no player is behind it. */
    public static Player responsiblePlayer(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            return responsiblePlayer(byEntity.getDamager());
        }
        return null;
    }

    /** The player behind {@code damager} (the damager itself when it is a player), or null. */
    public static Player responsiblePlayer(Entity damager) {
        Entity current = damager;
        for (int hop = 0; hop <= MAX_HOPS && current != null; hop++) {
            if (current instanceof Player player) {
                return player;
            }
            current = next(current);
        }
        return null;
    }

    /** One step back from {@code entity} to what caused it, or null. */
    private static Entity next(Entity entity) {
        if (entity instanceof Projectile projectile) {
            return asEntity(projectile.getShooter());
        }
        if (entity instanceof TNTPrimed tnt) {
            return tnt.getSource();
        }
        if (entity instanceof AreaEffectCloud cloud) {
            return asEntity(cloud.getSource());
        }
        if (entity instanceof EvokerFangs fangs) {
            return fangs.getOwner();
        }
        if (entity instanceof Tameable pet && pet.isTamed()) {
            AnimalTamer owner = pet.getOwner();
            return owner instanceof Player player ? player : null;
        }
        return null;
    }

    private static Entity asEntity(ProjectileSource source) {
        return source instanceof Entity entity ? entity : null;
    }
}
