package com.rs.lib.item;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import com.rs.lib.Ph;

/**
 * How registered items run their action lists and check their requirements. RsLib binds its
 * action engine here for each plugin ({@link ItemRegistry#bindActions}); plugins never
 * implement it.
 *
 * <p>Until an engine is bound, action lists are skipped with one warning each and any
 * requirement that is written fails: an item whose requirements cannot be checked is never
 * usable by mistake.</p>
 */
public interface ItemActions {

    /**
     * Runs {@code actions} for {@code player}.
     *
     * @param click   the click that used the item, or null (pickup, drop, break)
     * @param onBlock true when a block was clicked, false for air, null when not a click
     * @param phs     local placeholders for the lines
     */
    void run(Player player, List<String> actions, ClickType click, Boolean onBlock, Ph... phs);

    /** Parses {@code lines} (joined by AND) once; problems go to {@code warn}. */
    Check requirement(List<String> lines, Consumer<String> warn);

    /** A parsed requirement list. */
    @FunctionalInterface
    interface Check {

        /** Every line holds for {@code player}, placeholders resolved through {@code resolver} (may be null). */
        boolean test(Player player, Function<String, String> resolver);
    }

    /** No engine: actions are skipped and written requirements fail, each reported once. */
    static ItemActions unbound(Logger logger) {
        Set<String> reported = ConcurrentHashMap.newKeySet();
        return new ItemActions() {
            @Override
            public void run(Player player, List<String> actions, ClickType click, Boolean onBlock, Ph... phs) {
                if (actions != null && !actions.isEmpty() && reported.add("run:" + actions)) {
                    logger.warning("Item actions " + actions + " were not run: no action engine is bound.");
                }
            }

            @Override
            public Check requirement(List<String> lines, Consumer<String> warn) {
                if (lines == null || lines.isEmpty()) {
                    return (player, resolver) -> true;
                }
                if (reported.add("req:" + lines)) {
                    warn.accept("Requirements " + lines + " cannot be checked (no action engine is bound); they fail.");
                }
                return (player, resolver) -> false;
            }
        };
    }
}
