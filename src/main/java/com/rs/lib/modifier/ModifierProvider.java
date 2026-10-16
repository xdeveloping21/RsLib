package com.rs.lib.modifier;

import org.bukkit.entity.Player;

/**
 * Something that boosts (or reduces) what players earn: a booster plugin, an event, a perk.
 * Registered with {@link Modifiers#register}; its factor is multiplied with every other
 * provider's.
 *
 * <p>Called synchronously, possibly for every block broken or mob killed and possibly off the
 * main thread: keep it to memory reads.</p>
 */
@FunctionalInterface
public interface ModifierProvider {

    /**
     * The multiplier for {@code player} on {@code type} ({@code money}, {@code xp},
     * {@code drops} or a plugin's own) earned from {@code source} ({@code gens},
     * {@code envoys}, ...; null when the caller named none). Both arrive in lower case. 1 means
     * no change; answer 1 for a type this provider does not handle.
     */
    double factor(Player player, String type, String source);
}
