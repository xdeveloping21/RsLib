package com.rs.lib.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.text.RsText;

/**
 * Runs action lists written by server owners ({@code rs().actions()}): menu clicks, item uses,
 * rewards. One engine per plugin.
 *
 * <p>Each line is {@code [guard]... [tag] argument}, for example
 * {@code "[right-click] [chance=25] [console] give %player_name% diamond 1"}; a line without a
 * tag is a {@code [message]}. Guards, which can be chained: {@code [right-click]} (RIGHT and
 * SHIFT_RIGHT), {@code [left-click]} (LEFT, SHIFT_LEFT, DOUBLE_CLICK, CREATIVE),
 * {@code [shift-right-click]}, {@code [shift-left-click]}, {@code [right-click-only]},
 * {@code [left-click-only]}, {@code [middle-click]}, {@code [double-click]},
 * {@code [drop-click]} (DROP only), {@code [number-key]}, {@code [swap-offhand]},
 * {@code [click=TYPE,TYPE]} (an unknown type means the line never runs), {@code [click-block]}
 * and {@code [click-air]} (physical items only, never in menus) and {@code [chance=N]} (a
 * malformed N lets the line run). A click guard skips its line when the context has no click
 * (close actions, rewards). The built-in tags are
 * {@code [player]}, {@code [player-as-op]}, {@code [console]}, {@code [message]},
 * {@code [broadcastmessage]}, {@code [actionbar]}, {@code [title]}, {@code [sound]},
 * {@code [close]}, {@code [open]}, {@code [connect]}, {@code [next-page]},
 * {@code [previous-page]}, {@code [set-page]}, {@code [refresh-page]}, {@code [refresh-menu]},
 * {@code [particle]}, {@code [potion]} and {@code [remove-item]}. Plugins add their own tags
 * with {@link #register}, which can also replace a built-in one.</p>
 *
 * <p>Lists are parsed once ({@link #compile}); {@link #run(Player, List, ActionContext)} keeps
 * the lists it was given parsed too. On every run only the argument is filled in: local
 * placeholders first, then PlaceholderAPI for the player. The text actions ({@code message},
 * {@code broadcastmessage}, {@code actionbar}, {@code title}) render the result with the full
 * text pipeline, where values made with {@link Ph#literal} stay plain text; every other action
 * receives values made with {@code Ph.literal} as plain characters.</p>
 *
 * <p>Lines run in order. A line that throws is reported once (with its text) and the next line
 * runs; an unknown tag is reported once and its line skipped. Runs happen on the thread that
 * owns the player: at once when the caller is already there (a click), otherwise on the next
 * tick of the player's scheduler. Nothing runs after {@link #shutdown()}, and {@code [open]}
 * opens nothing while the plugin is shutting down.</p>
 *
 * <p>Requirements: {@link #requirements(List, String, String)} parses a {@code *-requirements}
 * list once (see {@link RequirementEngine}; a line written wrong always fails and is reported
 * with the file and key), and {@link #test(Requirement, ActionContext)} checks it for a click
 * with the same placeholders the actions get.</p>
 */
public final class ActionEngine {

    private static final int MAX_CACHED = 1024;
    private static final int MAX_WARNED = 4096;
    private static final Ph[] NO_PHS = new Ph[0];

    private final ActionHost host;
    private final Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();
    /** Lists given to {@code run} as text, parsed; keyed by an unmodifiable copy. */
    private final Map<List<String>, ActionList> parsed = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Builtins builtins;
    private volatile MenuOpener menus;
    private volatile boolean shutDown;

    public ActionEngine(Rs rs) {
        this(ActionHost.of(Objects.requireNonNull(rs, "rs")));
    }

    ActionEngine(ActionHost host) {
        this.host = host;
        this.builtins = new Builtins(this, host);
        builtins.install(handlers);
    }

    /**
     * Opens the menu an {@code [open]} line names. Set by the menu module; without it
     * {@code [open]} reports once that there are no menus.
     */
    @FunctionalInterface
    public interface MenuOpener {

        /**
         * Opens {@code menuId} ({@code ns:id} allowed) for {@code player}.
         *
         * @return false when this plugin has no such menu
         */
        boolean open(Player player, String menuId, ActionContext context);
    }

    // ---------------------------------------------------------------- tags

    /**
     * Adds or replaces the action {@code [tag]} (any case; brackets are optional). The handler
     * receives the argument with placeholders resolved.
     */
    public void register(String tag, ActionHandler handler) {
        Objects.requireNonNull(handler, "handler");
        String key = normalize(tag);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Action tag is blank: '" + tag + "'");
        }
        handlers.put(key, handler);
    }

    /** Whether {@code [tag]} has a handler, built-in or registered. */
    public boolean hasHandler(String tag) {
        return tag != null && handlers.containsKey(normalize(tag));
    }

    /**
     * The action tag each line ends up with, without running anything: guards are skipped and a
     * line without a tag gives {@code message}. Blank lines give nothing. For checking a list
     * that came from somewhere untrusted against an allow list.
     */
    public List<String> effectiveTags(List<String> actions) {
        return compile(actions).tags();
    }

    /**
     * Sets the menu module's opener for {@code [open]} (null removes it). Called by RsLib's
     * menu module.
     */
    public void menuOpener(MenuOpener opener) {
        this.menus = opener;
    }

    // ---------------------------------------------------------------- compiling

    /** Parses {@code actions} once for repeated runs; see {@link #compile(List, String)}. */
    public ActionList compile(List<String> actions) {
        return compile(actions, null);
    }

    /**
     * Parses {@code actions} once for repeated runs. {@code source} says where the list was read
     * from, for warnings ({@code "guis/shop.yml, items.buy.click-actions"}); may be null.
     * Problems in a line (an unknown click type, a bad chance) are reported here, once.
     */
    public ActionList compile(List<String> actions, String source) {
        List<String> lines = actions == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(actions));
        List<Step> steps = new ArrayList<>(lines.size());
        Consumer<String> warn = message -> warnOnce("compile:" + source + ":" + message,
                () -> message + (source == null ? "" : " (in " + source + ")"));
        for (String line : lines) {
            Step step = Step.parse(line, source, warn);
            if (step != null) {
                steps.add(step);
            }
        }
        return new ActionList(this, steps.toArray(Step[]::new), lines);
    }

    // ---------------------------------------------------------------- running

    /** Runs {@code actions} for {@code player} with only local placeholders (no menu, no click). */
    public void run(Player player, List<String> actions, Ph... phs) {
        run(player, actions, ActionContext.of(player, host.rs(), phs));
    }

    /**
     * Runs {@code actions} for {@code player} in {@code context}. The parsed form of the list is
     * kept, so calling this on every click costs a lookup, not a parse; a precompiled
     * {@link ActionList} skips even that.
     */
    public void run(Player player, List<String> actions, ActionContext context) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        ActionList list = parsed.get(actions);
        if (list == null) {
            list = compile(actions);
            if (parsed.size() >= MAX_CACHED) {
                parsed.clear();
            }
            parsed.put(list.lines(), list);
        }
        dispatch(list, player, context == null ? ActionContext.of(player, host.rs()) : context);
    }

    /** Runs {@code list} on the player's thread: here when the caller is on it, else scheduled. */
    void dispatch(ActionList list, Player player, ActionContext context) {
        if (list.isEmpty()) {
            return;
        }
        if (shutDown) {
            host.debug(() -> "Actions " + list.lines() + " were not run: the plugin is shut down.");
            return;
        }
        if (host.ownsHere(player)) {
            execute(list, player, context);
            return;
        }
        try {
            host.runFor(player, () -> execute(list, player, context));
        } catch (RuntimeException notScheduled) {
            host.logger().warning("Actions " + list.lines() + (player == null ? "" : " for " + player.getName())
                    + " were dropped: the plugin is being disabled.");
        }
    }

    private void execute(ActionList list, Player player, ActionContext context) {
        if (shutDown || (player != null && !player.isOnline())) {
            return;
        }
        for (Step step : list.steps()) {
            try {
                runStep(step, player, context);
            } catch (RuntimeException | LinkageError failure) {
                warnOnce("failed:" + step.raw(), Level.WARNING, () -> "Action '" + step.raw() + "'" + step.where()
                        + " failed: " + failure, failure);
            }
        }
    }

    private void runStep(Step step, Player player, ActionContext context) {
        for (Guard guard : step.guards()) {
            if (!guard.allows(context, host::debug)) {
                return;
            }
        }
        ActionHandler handler = handlers.get(step.tag());
        if (handler == null) {
            warnOnce("tag:" + step.tag(), () -> "Unknown action [" + step.tag() + "] in '" + step.raw() + "'"
                    + step.where() + "; the line is skipped.");
            return;
        }
        boolean text = handler instanceof TextAction;
        String arg = step.constant() ? step.arg() : resolve(player, step.arg(), context.phs(), text);
        if (text) {
            ((TextAction) handler).send(player, arg, step.constant(), context);
        } else {
            handler.run(player, arg, context);
        }
    }

    /**
     * An argument for one run: local placeholders, then PlaceholderAPI; text actions keep
     * literal values marked for the renderer, the rest get them as plain characters.
     */
    String resolve(Player player, String raw, Ph[] phs, boolean text) {
        String s = phs == null || phs.length == 0 ? raw : RsText.applyLocals(raw, phs);
        if (hasPapiToken(s)) {
            s = host.papi(player, s);
            if (text) {
                s = RsText.normalizePapiOutput(s);
            }
        }
        return text ? s : RsText.unwrapLiterals(s);
    }

    // ---------------------------------------------------------------- requirements

    /**
     * Parses a {@code *-requirements} list once. A line written wrong always fails and is
     * reported as {@code [<file>] <key>: ...}.
     */
    public Requirement requirements(List<String> lines, String file, String key) {
        return RequirementEngine.parse(lines, located(file, key));
    }

    /**
     * Reads the requirement list {@code key} of {@code section} from file {@code file}. A key
     * that is not set is {@link Requirement#ALWAYS}. A value that is not a list of texts (a
     * single string, {@code - [x]} without quotes) cannot be read as the owner meant it: it
     * always fails and is reported, instead of being dropped and letting everything through.
     */
    public Requirement requirements(ConfigurationSection section, String key, String file) {
        Object value = section == null ? null : section.get(key);
        if (value == null) {
            return Requirement.ALWAYS;
        }
        String path = section.getCurrentPath() == null || section.getCurrentPath().isEmpty()
                ? key : section.getCurrentPath() + "." + key;
        Consumer<String> warn = located(file, path);
        if (!(value instanceof List<?> list)) {
            warn.accept("must be a list (one '- ' line per condition), got '" + value + "'; it always fails.");
            return Requirement.NEVER;
        }
        List<String> lines = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof String line) {
                lines.add(line);
            } else if (element instanceof Number || element instanceof Boolean) {
                lines.add(String.valueOf(element));
            } else {
                warn.accept("entry " + (i + 1) + " is not text (" + element
                        + "); write it in quotes. The requirement always fails.");
                return Requirement.NEVER;
            }
        }
        return RequirementEngine.parse(lines, warn);
    }

    /** Checks {@code requirement} for {@code context}: local placeholders, then PlaceholderAPI. */
    public boolean test(Requirement requirement, ActionContext context) {
        if (requirement == null || requirement == Requirement.ALWAYS) {
            return true;
        }
        Player player = context == null ? null : context.player();
        return requirement.test(player, resolver(player, context == null ? NO_PHS : context.phs()));
    }

    /**
     * The resolver requirement operands go through: local placeholders, then PlaceholderAPI for
     * {@code player} (main thread only), literal values as plain characters.
     */
    public Function<String, String> resolver(Player player, Ph... phs) {
        Ph[] locals = phs == null ? NO_PHS : phs;
        return operand -> resolve(player, operand, locals, false);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Stops running actions and releases the BungeeCord channel if {@code [connect]} opened it. */
    public void shutdown() {
        shutDown = true;
        menus = null;
        parsed.clear();
        builtins.shutdown();
    }

    boolean isShutDown() {
        return shutDown;
    }

    Rs context() {
        return host.rs();
    }

    MenuOpener menus() {
        return menus;
    }

    // ---------------------------------------------------------------- internals

    /** Reports {@code message} the first time {@code id} comes up. */
    void warnOnce(String id, Supplier<String> message) {
        warnOnce(id, Level.WARNING, message, null);
    }

    private void warnOnce(String id, Level level, Supplier<String> message, Throwable cause) {
        if (warned.size() >= MAX_WARNED) {
            warned.clear();
        }
        if (warned.add(id)) {
            host.logger().log(level, message.get(), cause);
        }
    }

    private Consumer<String> located(String file, String key) {
        String where = (file == null ? "" : "[" + file + "] ") + (key == null ? "" : key + ": ");
        return message -> host.logger().warning(where + message);
    }

    private static String normalize(String tag) {
        String key = tag == null ? "" : tag.trim();
        if (key.startsWith("[") && key.endsWith("]") && key.length() >= 2) {
            key = key.substring(1, key.length() - 1).trim();
        }
        return key.toLowerCase(Locale.ROOT);
    }

    /** Whether PlaceholderAPI could replace anything: at least two {@code %}. */
    private static boolean hasPapiToken(String s) {
        int first = s.indexOf('%');
        return first >= 0 && s.indexOf('%', first + 1) >= 0;
    }

    /**
     * A built-in action that shows text. It gets the argument with literal values still marked,
     * and whether the line had no placeholders, so a constant line can use the shared render cache.
     */
    abstract static class TextAction implements ActionHandler {

        @Override
        public final void run(Player player, String arg, ActionContext context) {
            send(player, arg, false, context);
        }

        abstract void send(Player player, String text, boolean constant, ActionContext context);
    }
}
