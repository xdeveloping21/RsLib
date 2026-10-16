package com.rs.lib.reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;

import com.rs.lib.action.Requirement;
import com.rs.lib.action.RequirementEngine;
import com.rs.lib.item.ItemAppearance;

/**
 * An immutable list of {@link Reward}s, in the order they were written. Read from YAML, one
 * section per reward (its key is the reward's id):
 *
 * <pre>
 * rewards:
 *   diamonds:
 *     type: ITEM               # ITEM, COMMAND, MONEY, EXPERIENCE, POTION or MESSAGE
 *     material: DIAMOND        # every item appearance key (see ItemAppearance)
 *     amount: 4
 *     chance: 25               # percent, 0 to 100; 100 when left out
 *     weight: 10               # only for picks by weight; 1 when left out
 *     permission: crates.vip
 *     requirements:
 *       - "%player_level% &gt;= 10"
 *   stored:
 *     type: ITEM
 *     item: "AAAAAR+LCAAA..."  # an item stored by ItemSerializer (Base64); wins over the keys above
 *   coins:
 *     type: MONEY
 *     amount: 500
 *   rank:
 *     type: COMMAND
 *     command: "lp user {player} parent add vip"
 *   xp:
 *     type: EXPERIENCE
 *     amount: 250              # points
 *   speed:
 *     type: POTION
 *     potion: "speed 30 1"     # effect, seconds (10), amplifier (0)
 *   note:
 *     type: MESSAGE
 *     message: "&amp;aYou won!"    # or a list of lines
 * </pre>
 *
 * <p>An entry that cannot be read is reported and skipped; the rest still load. A requirement
 * list written wrong fails closed (the reward is never given), like everywhere else in RsLib.</p>
 */
public final class RewardList implements Iterable<Reward> {

    /** The list with no rewards. */
    public static final RewardList EMPTY = new RewardList(List.of());

    private static final double DEFAULT_POTION_SECONDS = 10;

    private final List<Reward> rewards;

    private RewardList(List<Reward> rewards) {
        this.rewards = rewards;
    }

    /** A list of {@code rewards}, in this order. Ids must be unique. */
    public static RewardList of(Reward... rewards) {
        return of(rewards == null ? List.of() : List.of(rewards));
    }

    /** A list of {@code rewards}, in this order. Ids must be unique. */
    public static RewardList of(List<Reward> rewards) {
        List<Reward> copy = List.copyOf(rewards);
        List<String> ids = new ArrayList<>(copy.size());
        for (Reward reward : copy) {
            if (ids.contains(reward.id())) {
                throw new IllegalArgumentException("Two rewards are called " + reward.id());
            }
            ids.add(reward.id());
        }
        return copy.isEmpty() ? EMPTY : new RewardList(copy);
    }

    /**
     * Reads every child section of {@code section} as a reward (a null section is the empty
     * list).
     *
     * @param source   what to call the list in warnings, such as {@code crates/basic.yml -> rewards}
     * @param resolver how item texts resolve their placeholders (see {@link ItemAppearance#parse})
     * @param warn     where problems go; null logs them through RsLib's logger
     */
    public static RewardList read(ConfigurationSection section, String source, ItemAppearance.TextResolver resolver,
                                  Consumer<String> warn) {
        if (section == null) {
            return EMPTY;
        }
        String where = source == null ? "rewards" : source;
        Consumer<String> out = warn != null ? warn
                : message -> java.util.logging.Logger.getLogger("RsLib").warning("[RsLib] " + message);
        List<Reward> read = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            String at = where + " -> " + key;
            if (entry == null) {
                out.accept(at + ": a reward must be a section (type, chance, ...); skipped.");
                continue;
            }
            Consumer<String> local = message -> out.accept(at + ": " + message);
            Reward reward;
            try {
                reward = entry(key, entry, at, resolver, local);
            } catch (IllegalArgumentException invalid) {
                local.accept(invalid.getMessage() + "; skipped.");
                reward = null;
            }
            if (reward != null) {
                read.add(reward);
            }
        }
        return read.isEmpty() ? EMPTY : new RewardList(Collections.unmodifiableList(read));
    }

    private static Reward entry(String id, ConfigurationSection s, String at, ItemAppearance.TextResolver resolver,
                                Consumer<String> warn) {
        String typeName = s.getString("type");
        RewardType type = RewardType.parse(typeName);
        if (type == null) {
            warn.accept((typeName == null ? "'type' is missing" : "'" + typeName + "' is not a reward type")
                    + " (ITEM, COMMAND, MONEY, EXPERIENCE, POTION or MESSAGE); skipped.");
            return null;
        }
        Reward.Builder builder = switch (type) {
            case ITEM -> item(id, s, at, resolver, warn);
            case COMMAND -> {
                String command = text(s, "command", warn);
                yield command == null ? null : Reward.command(id, command);
            }
            case MONEY -> {
                double amount = number(s, "amount", Double.NaN, warn);
                if (!(amount > 0) || Double.isInfinite(amount)) {
                    warn.accept("'amount' must be money above 0; skipped.");
                    yield null;
                }
                yield Reward.money(id, amount);
            }
            case EXPERIENCE -> {
                double amount = number(s, "amount", Double.NaN, warn);
                if (!(amount >= 1) || amount > Integer.MAX_VALUE || amount != Math.rint(amount)) {
                    warn.accept("'amount' must be a whole number of experience points above 0; skipped.");
                    yield null;
                }
                yield Reward.experience(id, (int) amount);
            }
            case POTION -> potion(id, s, warn);
            case MESSAGE -> {
                String message = message(s, warn);
                yield message == null ? null : Reward.message(id, message);
            }
        };
        if (builder == null) {
            return null;
        }
        double chance = number(s, "chance", Reward.ALWAYS, warn);
        if (Double.isNaN(chance)) {
            chance = Reward.ALWAYS;
        } else if (chance < 0 || chance > Reward.ALWAYS) {
            double clamped = Math.max(0, Math.min(Reward.ALWAYS, chance));
            warn.accept("'chance' is a percent from 0 to 100, got " + chance + "; using " + clamped + ".");
            chance = clamped;
        }
        double weight = number(s, "weight", 1, warn);
        if (Double.isNaN(weight) || weight < 0) {
            warn.accept("'weight' must be 0 or more; using " + (Double.isNaN(weight) ? 1 : 0) + ".");
            weight = Double.isNaN(weight) ? 1 : 0;
        }
        return builder.chance(chance)
                .weight(weight)
                .permission(s.getString("permission"))
                .requirement(requirements(s, warn))
                .build();
    }

    private static Reward.Builder item(String id, ConfigurationSection s, String at, ItemAppearance.TextResolver resolver,
                                       Consumer<String> warn) {
        String stored = s.getString("item");
        if (stored != null && !stored.isBlank()) {
            return Reward.serializedItem(id, stored);
        }
        if (!s.contains("material", true)) {
            warn.accept("an item reward needs 'material' (or 'item' with a stored item); skipped.");
            return null;
        }
        return Reward.item(id, ItemAppearance.parse(s, at, resolver, warn));
    }

    private static Reward.Builder potion(String id, ConfigurationSection s, Consumer<String> warn) {
        String line = text(s, "potion", warn);
        if (line == null) {
            return null;
        }
        String[] words = line.trim().split("\\s+");
        double seconds = DEFAULT_POTION_SECONDS;
        if (words.length > 1) {
            double value = parse(words[1]);
            if (!(value > 0) || Double.isInfinite(value)) {
                warn.accept("'" + words[1] + "' is not a number of seconds; using 10.");
            } else {
                seconds = value;
            }
        }
        int amplifier = 0;
        if (words.length > 2) {
            double value = parse(words[2]);
            if (Double.isNaN(value) || value != Math.rint(value)) {
                warn.accept("'" + words[2] + "' is not an amplifier; using 0.");
            } else {
                amplifier = (int) Math.max(0, Math.min(255, value));
            }
        }
        int ticks = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(seconds * 20)));
        return Reward.potion(id, words[0], ticks, amplifier);
    }

    private static String text(ConfigurationSection s, String key, Consumer<String> warn) {
        Object value = s.get(key);
        if (value == null || value instanceof ConfigurationSection || value instanceof List<?>
                || String.valueOf(value).isBlank()) {
            warn.accept("'" + key + "' must be a line of text; skipped.");
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static String message(ConfigurationSection s, Consumer<String> warn) {
        Object value = s.get("message");
        if (value instanceof List<?> lines && !lines.isEmpty()) {
            List<String> parts = new ArrayList<>(lines.size());
            for (Object line : lines) {
                parts.add(line == null ? "" : String.valueOf(line));
            }
            return String.join("\n", parts);
        }
        if (value != null && !(value instanceof ConfigurationSection) && !String.valueOf(value).isBlank()) {
            return String.valueOf(value);
        }
        warn.accept("'message' must be text or a list of lines; skipped.");
        return null;
    }

    /** The number at {@code key}; {@code missing} when not set, NaN (reported) when it is not a number. */
    private static double number(ConfigurationSection s, String key, double missing, Consumer<String> warn) {
        Object value = s.get(key);
        if (value == null) {
            return missing;
        }
        double parsed = value instanceof Number n ? n.doubleValue() : parse(String.valueOf(value));
        if (Double.isNaN(parsed)) {
            warn.accept("'" + key + "' is not a number ('" + value + "').");
        }
        return parsed;
    }

    private static double parse(String text) {
        try {
            String clean = text.trim();
            if (clean.isEmpty() || !clean.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) {
                return Double.NaN;
            }
            return Double.parseDouble(clean);
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    /**
     * The {@code requirements} list. Not a list of texts: it cannot be read as the owner meant
     * it, so it always fails (reported) rather than letting everyone through.
     */
    private static Requirement requirements(ConfigurationSection s, Consumer<String> warn) {
        Object value = s.get("requirements");
        if (value == null) {
            return Requirement.ALWAYS;
        }
        if (!(value instanceof List<?> list)) {
            warn.accept("'requirements' must be a list (one '- ' line per condition), got '" + value
                    + "'; the reward is never given.");
            return Requirement.NEVER;
        }
        List<String> lines = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof String || element instanceof Number || element instanceof Boolean) {
                lines.add(String.valueOf(element));
            } else {
                warn.accept("'requirements' entry " + (i + 1) + " is not text (" + element
                        + "); write it in quotes. The reward is never given.");
                return Requirement.NEVER;
            }
        }
        return RequirementEngine.parse(lines, warn);
    }

    // ------------------------------------------------------------------ reading

    public List<Reward> rewards() {
        return rewards;
    }

    /** The reward called {@code id} (any case), or null. */
    public Reward get(String id) {
        for (Reward reward : rewards) {
            if (reward.id().equalsIgnoreCase(id)) {
                return reward;
            }
        }
        return null;
    }

    public int size() {
        return rewards.size();
    }

    public boolean isEmpty() {
        return rewards.isEmpty();
    }

    @Override
    public Iterator<Reward> iterator() {
        return rewards.iterator();
    }

    @Override
    public String toString() {
        return "RewardList" + rewards.stream().map(Reward::id).toList();
    }
}
