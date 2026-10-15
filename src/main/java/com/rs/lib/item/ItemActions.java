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
import com.rs.lib.Rs;
import com.rs.lib.action.ActionContext;
import com.rs.lib.action.ActionEngine;
import com.rs.lib.action.ClickSurface;
import com.rs.lib.action.Requirement;
import com.rs.lib.action.RequirementEngine;

/**
 * How registered items run their action lists and check their requirements. RsLib binds the
 * plugin's action engine here ({@link ItemRegistry#bindActions}) when the context is built;
 * plugins never implement it.
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
    Requirement requirement(List<String> lines, Consumer<String> warn);

    /**
     * The resolver requirement operands of {@code player} go through (PlaceholderAPI), or null
     * to keep every operand as written.
     */
    default Function<String, String> resolver(Player player) {
        return null;
    }

    /**
     * The plugin's own action engine ({@code rs.actions()}): lines run with the click and what
     * was clicked ({@code [click-block]}, {@code [click-air]}), without a menu; requirements are
     * parsed by its requirement reader and resolve operands with PlaceholderAPI.
     */
    static ItemActions engine(Rs rs) {
        ActionEngine engine = rs.actions();
        return new ItemActions() {
            @Override
            public void run(Player player, List<String> actions, ClickType click, Boolean onBlock, Ph... phs) {
                ClickSurface surface = onBlock == null ? null : onBlock ? ClickSurface.BLOCK : ClickSurface.AIR;
                engine.run(player, actions, new ActionContext(player, rs, null, click, surface, phs));
            }

            @Override
            public Requirement requirement(List<String> lines, Consumer<String> warn) {
                return lines == null || lines.isEmpty() ? Requirement.ALWAYS : RequirementEngine.parse(lines, warn);
            }

            @Override
            public Function<String, String> resolver(Player player) {
                return engine.resolver(player);
            }
        };
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
            public Requirement requirement(List<String> lines, Consumer<String> warn) {
                if (lines == null || lines.isEmpty()) {
                    return Requirement.ALWAYS;
                }
                if (reported.add("req:" + lines)) {
                    warn.accept("Requirements " + lines + " cannot be checked (no action engine is bound); they fail.");
                }
                return Requirement.NEVER;
            }
        };
    }
}
