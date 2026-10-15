package com.rs.lib.action;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.text.RsText;
import com.rs.lib.util.SoundUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/** The actions every engine starts with; a plugin can replace any of them. */
final class Builtins {

    static final String BUNGEE = "BungeeCord";
    static final int TITLE_FADE_IN = 10;
    static final int TITLE_STAY = 70;
    static final int TITLE_FADE_OUT = 20;

    private final ActionEngine engine;
    private final ActionHost host;
    private final AtomicBoolean bungeeOpen = new AtomicBoolean();

    Builtins(ActionEngine engine, ActionHost host) {
        this.engine = engine;
        this.host = host;
    }

    void install(Map<String, ActionHandler> into) {
        into.put("player", this::player);
        into.put("player-as-op", this::playerAsOp);
        into.put("console", this::console);
        into.put("message", text(Player::sendMessage));
        into.put("broadcastmessage", new ActionEngine.TextAction() {
            @Override
            void send(Player player, String text, boolean constant, ActionContext context) {
                Bukkit.getServer().sendMessage(render(text, constant));
            }
        });
        into.put("actionbar", text(Player::sendActionBar));
        into.put("title", new ActionEngine.TextAction() {
            @Override
            void send(Player player, String text, boolean constant, ActionContext context) {
                if (player != null) {
                    player.showTitle(title(text, constant, engine::warnOnce));
                }
            }
        });
        into.put("sound", (player, arg, context) -> SoundUtil.play(player, arg));
        into.put("close", (player, arg, context) -> {
            if (player != null) {
                player.closeInventory();
            }
        });
        into.put("open", this::open);
        into.put("connect", this::connect);
        into.put("next-page", page("next-page", PageTarget::nextPage));
        into.put("previous-page", page("previous-page", PageTarget::previousPage));
        into.put("refresh-page", page("refresh-page", PageTarget::refreshPage));
        into.put("refresh-menu", page("refresh-menu", PageTarget::refreshMenu));
        into.put("set-page", this::setPage);
        into.put("particle", new ParticleAction(engine::warnOnce));
        into.put("potion", new PotionAction(engine::warnOnce));
        into.put("remove-item", new RemoveItemAction(host, engine::warnOnce));
    }

    /** Releases the BungeeCord channel if {@code [connect]} opened it. */
    void shutdown() {
        JavaPlugin plugin = host.plugin();
        if (bungeeOpen.getAndSet(false) && plugin != null) {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE);
        }
    }

    // ---------------------------------------------------------------- commands

    private void player(Player player, String arg, ActionContext context) {
        String command = command("player", arg);
        if (player != null && command != null) {
            player.performCommand(command);
        }
    }

    /**
     * The command runs with operator rights the player did not have; they are taken back in a
     * {@code finally}, so a command that throws cannot leave the player an operator.
     */
    private void playerAsOp(Player player, String arg, ActionContext context) {
        String command = command("player-as-op", arg);
        if (player == null || command == null) {
            return;
        }
        boolean wasOp = player.isOp();
        try {
            if (!wasOp) {
                player.setOp(true);
            }
            player.performCommand(command);
        } finally {
            if (!wasOp) {
                player.setOp(false);
            }
        }
    }

    private void console(Player player, String arg, ActionContext context) {
        String command = command("console", arg);
        if (command != null) {
            host.console(command);
        }
    }

    /** The command without its leading slash, or null (reported once) when there is none. */
    private String command(String tag, String arg) {
        String command = arg.startsWith("/") ? arg.substring(1).trim() : arg;
        if (command.isEmpty()) {
            engine.warnOnce("empty:" + tag, () -> "An action [" + tag + "] has no command; nothing ran.");
            return null;
        }
        return command;
    }

    // ---------------------------------------------------------------- text

    private static ActionEngine.TextAction text(BiConsumer<Player, Component> sink) {
        return new ActionEngine.TextAction() {
            @Override
            void send(Player player, String text, boolean constant, ActionContext context) {
                if (player != null) {
                    sink.accept(player, render(text, constant));
                }
            }
        };
    }

    /** A constant line uses the shared render cache; a filled-in one does not push config lines out of it. */
    static Component render(String text, boolean constant) {
        return constant ? RsText.color(text) : RsText.colorUncached(text);
    }

    /**
     * {@code title;subtitle;fadeIn;stay;fadeOut}, times in ticks (10, 70 and 20 when missing or
     * empty; a time that is not a whole number is reported and uses its default).
     */
    static Title title(String text, boolean constant, BiConsumer<String, Supplier<String>> warn) {
        List<String> parts = split(text, ';');
        Component title = render(parts.get(0), constant);
        Component subtitle = parts.size() > 1 ? render(parts.get(1), constant) : Component.empty();
        return Title.title(title, subtitle, Title.Times.times(
                ticks(parts, 2, TITLE_FADE_IN, text, warn),
                ticks(parts, 3, TITLE_STAY, text, warn),
                ticks(parts, 4, TITLE_FADE_OUT, text, warn)));
    }

    private static Duration ticks(List<String> parts, int index, int fallback, String text,
                                  BiConsumer<String, Supplier<String>> warn) {
        String value = index < parts.size() ? parts.get(index).trim() : "";
        int ticks = fallback;
        if (!value.isEmpty()) {
            try {
                ticks = Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException notANumber) {
                warn.accept("title:" + text, () -> "Time '" + value + "' in [title] " + text
                        + " is not a whole number of ticks; using " + fallback + ".");
            }
        }
        return Duration.ofMillis(ticks * 50L);
    }

    // ---------------------------------------------------------------- menus

    private void open(Player player, String arg, ActionContext context) {
        if (player == null) {
            return;
        }
        if (host.shuttingDown()) {
            host.debug(() -> "[open] " + arg + " did nothing: the plugin is shutting down.");
            return;
        }
        ActionEngine.MenuOpener menus = engine.menus();
        if (menus == null) {
            engine.warnOnce("open:none", () -> "[open] " + arg + ": this plugin has no menus"
                    + " (RsSpec.builder().guis()); nothing opened.");
            return;
        }
        if (arg.isEmpty()) {
            engine.warnOnce("open:empty", () -> "An action [open] has no menu id; nothing opened.");
            return;
        }
        if (!menus.open(player, arg, context)) {
            engine.warnOnce("open:" + arg, () -> "[open] " + arg + ": there is no menu with that id.");
        }
    }

    private ActionHandler page(String tag, Consumer<PageTarget> action) {
        return (player, arg, context) -> {
            PageTarget target = context.pageTarget();
            if (target == null || !target.paginationEnabled()) {
                host.debug(() -> "[" + tag + "] did nothing: no paginated menu here (pagination: true).");
                return;
            }
            action.accept(target);
        };
    }

    private void setPage(Player player, String arg, ActionContext context) {
        PageTarget target = context.pageTarget();
        if (target == null || !target.paginationEnabled()) {
            host.debug(() -> "[set-page] did nothing: no paginated menu here (pagination: true).");
            return;
        }
        target.setPage(pageNumber(arg, engine::warnOnce));
    }

    /** The page {@code arg} names; anything that is not a whole number from 1 is reported once and reads as 1. */
    static int pageNumber(String arg, BiConsumer<String, Supplier<String>> warn) {
        try {
            int page = Integer.parseInt(arg.trim());
            if (page >= 1) {
                return page;
            }
        } catch (NumberFormatException notANumber) {
            // Reported below.
        }
        warn.accept("set-page:" + arg, () -> "[set-page] " + arg + " is not a page number; going to page 1.");
        return 1;
    }

    // ---------------------------------------------------------------- servers

    private void connect(Player player, String arg, ActionContext context) {
        JavaPlugin plugin = host.plugin();
        if (player == null || plugin == null) {
            return;
        }
        if (arg.isEmpty()) {
            engine.warnOnce("connect:empty", () -> "An action [connect] has no server name; nobody moved.");
            return;
        }
        if (bungeeOpen.compareAndSet(false, true)) {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE);
        }
        player.sendPluginMessage(plugin, BUNGEE, connectMessage(arg));
    }

    /** The BungeeCord plugin message that sends a player to {@code server}. */
    static byte[] connectMessage(String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    // ---------------------------------------------------------------- shared

    /** {@code s} cut at every {@code separator}; empty pieces are kept. */
    static List<String> split(String s, char separator) {
        List<String> parts = new ArrayList<>(5);
        int from = 0;
        while (true) {
            int at = s.indexOf(separator, from);
            if (at < 0) {
                parts.add(s.substring(from));
                return parts;
            }
            parts.add(s.substring(from, at));
            from = at + 1;
        }
    }

    /** The words of {@code s}, split on any run of whitespace. */
    static List<String> words(String s) {
        List<String> words = new ArrayList<>(6);
        int n = s.length();
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                words.add(s.substring(start, i));
            }
        }
        return words;
    }
}
