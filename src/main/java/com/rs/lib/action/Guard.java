package com.rs.lib.action;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.event.inventory.ClickType;

/**
 * A leading {@code [word]} that decides whether the rest of an action line runs.
 *
 * <ul>
 *   <li>Inclusive clicks: {@code [right-click]} (RIGHT, SHIFT_RIGHT), {@code [left-click]}
 *   (LEFT, SHIFT_LEFT, DOUBLE_CLICK, CREATIVE).</li>
 *   <li>Exact clicks: {@code [shift-right-click]}, {@code [shift-left-click]},
 *   {@code [right-click-only]}, {@code [left-click-only]}, {@code [middle-click]},
 *   {@code [double-click]}, {@code [drop-click]} (DROP only, not CONTROL_DROP),
 *   {@code [number-key]}, {@code [swap-offhand]}.</li>
 *   <li>{@code [click=TYPE,TYPE]}: Bukkit click type names, any case, {@code -} for
 *   {@code _}. A name that does not exist FAILS CLOSED: reported once, the line never runs.</li>
 *   <li>{@code [click-block]}, {@code [click-air]}: physical items only; never in menus. They
 *   need a click type too, like the click guards.</li>
 *   <li>{@code [chance=N]}: N percent (decimals allowed). A malformed N FAILS OPEN: reported
 *   once, the line always runs.</li>
 * </ul>
 *
 * <p>A click guard in a context without a click type (close actions, rewards) skips its line
 * with a debug note.</p>
 */
interface Guard {

    /** Whether the line may run in {@code context}; {@code debug} takes notes for the debug output. */
    boolean allows(ActionContext context, Consumer<Supplier<String>> debug);

    /** The named click guards. */
    Map<String, Set<ClickType>> NAMED = Map.ofEntries(
            Map.entry("right-click", EnumSet.of(ClickType.RIGHT, ClickType.SHIFT_RIGHT)),
            Map.entry("left-click", EnumSet.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.DOUBLE_CLICK,
                    ClickType.CREATIVE)),
            Map.entry("shift-right-click", EnumSet.of(ClickType.SHIFT_RIGHT)),
            Map.entry("shift-left-click", EnumSet.of(ClickType.SHIFT_LEFT)),
            Map.entry("right-click-only", EnumSet.of(ClickType.RIGHT)),
            Map.entry("left-click-only", EnumSet.of(ClickType.LEFT)),
            Map.entry("middle-click", EnumSet.of(ClickType.MIDDLE)),
            Map.entry("double-click", EnumSet.of(ClickType.DOUBLE_CLICK)),
            Map.entry("drop-click", EnumSet.of(ClickType.DROP)),
            Map.entry("number-key", EnumSet.of(ClickType.NUMBER_KEY)),
            Map.entry("swap-offhand", EnumSet.of(ClickType.SWAP_OFFHAND)));

    /** A line that never runs: a {@code [click=...]} with a name that does not exist. */
    Guard NEVER = (context, debug) -> false;

    /** A guard that lets everything through: a malformed {@code [chance=...]}. */
    Guard ALWAYS = (context, debug) -> true;

    /**
     * The guard {@code word} (lower case, without brackets) stands for, or null when it is not a
     * guard and so is the line's tag. Problems go to {@code warn}.
     */
    static Guard parse(String word, Consumer<String> warn) {
        Set<ClickType> named = NAMED.get(word);
        if (named != null) {
            return new Clicks(word, named);
        }
        switch (word) {
            case "click-block" -> {
                return new Surface(ClickSurface.BLOCK);
            }
            case "click-air" -> {
                return new Surface(ClickSurface.AIR);
            }
            default -> {
                // Below.
            }
        }
        if (word.startsWith("click=")) {
            return clicks(word, warn);
        }
        if (word.startsWith("chance=")) {
            return chance(word, warn);
        }
        return null;
    }

    private static Guard clicks(String word, Consumer<String> warn) {
        Set<ClickType> types = EnumSet.noneOf(ClickType.class);
        String list = word.substring("click=".length());
        int from = 0;
        while (from <= list.length()) {
            int comma = list.indexOf(',', from);
            int to = comma < 0 ? list.length() : comma;
            String name = list.substring(from, to).trim().replace('-', '_').toUpperCase(Locale.ROOT);
            try {
                types.add(ClickType.valueOf(name));
            } catch (IllegalArgumentException unknown) {
                warn.accept("'" + (name.isEmpty() ? "(empty)" : name) + "' in [" + word
                        + "] is not a click type; the line never runs.");
                return NEVER;
            }
            if (comma < 0) {
                break;
            }
            from = comma + 1;
        }
        return new Clicks(word, types);
    }

    private static Guard chance(String word, Consumer<String> warn) {
        String value = word.substring("chance=".length()).trim();
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        double percent = RequirementEngine.number(value);
        if (Double.isNaN(percent) || percent < 0) {
            warn.accept("[" + word + "] is not a chance from 0 to 100; the line always runs.");
            return ALWAYS;
        }
        return new Chance(percent);
    }

    /** One of the listed click types. */
    record Clicks(String name, Set<ClickType> types) implements Guard {
        @Override
        public boolean allows(ActionContext context, Consumer<Supplier<String>> debug) {
            ClickType click = context.clickType();
            if (click == null) {
                debug.accept(() -> "[" + name + "] skipped its line: there is no click here.");
                return false;
            }
            return types.contains(click);
        }
    }

    /** Used on a block or in the air; never in menus. Like every click guard, it needs a click. */
    record Surface(ClickSurface surface) implements Guard {
        @Override
        public boolean allows(ActionContext context, Consumer<Supplier<String>> debug) {
            if (context.clickType() == null) {
                debug.accept(() -> "[" + name() + "] skipped its line: there is no click here.");
                return false;
            }
            return context.clickSurface() == surface;
        }

        /** The guard as written: {@code click-block} or {@code click-air}. */
        String name() {
            return surface == ClickSurface.BLOCK ? "click-block" : "click-air";
        }
    }

    /** Runs {@code percent} percent of the time. */
    record Chance(double percent) implements Guard {
        @Override
        public boolean allows(ActionContext context, Consumer<Supplier<String>> debug) {
            return percent >= 100 || ThreadLocalRandom.current().nextDouble(100) < percent;
        }
    }
}
