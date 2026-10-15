package com.rs.lib.action;

import java.util.function.Function;

import org.bukkit.entity.Player;

/**
 * A parsed {@code *-requirements} list (see {@link RequirementEngine}).
 *
 * <p>{@code resolver} turns one operand as written ({@code %vault_eco_balance%},
 * {@code {price}}) into its value for this check; it is only called for operands that hold
 * {@code %} or <code>{</code>, and a null answer (or a null resolver) keeps the operand as
 * written. {@link ActionEngine#test(Requirement, ActionContext)} builds the usual resolver:
 * local placeholders, then PlaceholderAPI for the player.</p>
 */
@FunctionalInterface
public interface Requirement {

    /** The requirement of an empty or missing list. */
    Requirement ALWAYS = (player, resolver) -> true;

    /** The requirement of a list that could not be read: it never holds. */
    Requirement NEVER = (player, resolver) -> false;

    boolean test(Player player, Function<String, String> resolver);
}
