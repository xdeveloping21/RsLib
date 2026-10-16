package com.rs.lib.audit.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.RsLib;
import com.rs.lib.audit.AuditEntry;
import com.rs.lib.audit.AuditItems;
import com.rs.lib.audit.AuditLog;
import com.rs.lib.audit.AuditPage;
import com.rs.lib.audit.AuditQuery;
import com.rs.lib.internal.LibMessages;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.util.PlayerNames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /rslib logs <player|*> [plugin|*] [page]}: the audit log of every plugin, newest first,
 * {@value #PAGE_SIZE} entries a page, in chat or in the console. {@code *} as the player reads
 * everybody's entries; as the plugin, every plugin's. Everything an entry holds is shown as
 * plain text. The texts around it are in RsLib's language ({@link LibMessages}).
 */
public final class AuditCommand {

    public static final String PERMISSION = "rslib.admin.logs";
    public static final String USAGE = "<player|*> [plugin|*] [page]";
    static final int PAGE_SIZE = 10;
    private static final String EVERYBODY = "*";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private AuditCommand() {
    }

    /** Runs the subcommand; {@code args} are the words after {@code logs}. */
    public static void run(JavaPlugin rslib, CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 3) {
            LibMessages.send(sender, "rslib.admin.logs.usage", Ph.literal("label", label));
            return;
        }
        String who = args[0];
        UUID player = null;
        if (!who.equals(EVERYBODY)) {
            Player online = Bukkit.getPlayerExact(who);
            Optional<UUID> id = online != null ? Optional.of(online.getUniqueId()) : PlayerNames.uuid(who);
            if (id.isEmpty()) {
                LibMessages.send(sender, "rslib.admin.logs.player-not-found", Ph.literal("player", who));
                return;
            }
            player = id.get();
            if (online != null) {
                who = online.getName();
            }
        }
        String plugin = null;
        int page = 1;
        if (args.length >= 2) {
            String second = args[1];
            Integer number = args.length == 2 && pluginNamed(second) == null ? pageNumber(second) : null;
            if (number != null) {
                page = number;
            } else if (!second.equals(EVERYBODY) && !second.equalsIgnoreCase("all")) {
                plugin = pluginNamed(second);
                if (plugin == null) {
                    LibMessages.send(sender, "rslib.admin.logs.plugin-not-found", Ph.literal("plugin", second));
                    return;
                }
            }
        }
        if (args.length == 3) {
            Integer number = pageNumber(args[2]);
            if (number == null) {
                LibMessages.send(sender, "rslib.admin.logs.bad-page", Ph.literal("page", args[2]));
                return;
            }
            page = number;
        }
        AuditQuery query = AuditQuery.builder().player(player).plugin(plugin).page(page).pageSize(PAGE_SIZE).build();
        String subject = player == null ? null : who;
        String next = "/" + label + " logs " + (player == null ? EVERYBODY : who) + " "
                + (plugin == null ? EVERYBODY : plugin) + " " + (page + 1);
        String finalPlugin = plugin;
        RsScheduler scheduler = new RsScheduler(rslib);
        AuditLog.search(query).whenComplete((result, error) -> {
            List<Component> lines;
            if (error != null) {
                rslib.getLogger().log(Level.WARNING, "Could not read the audit logs", error);
                lines = List.of(LibMessages.render("rslib.admin.logs.read-failed"));
            } else {
                lines = lines(result, subject, finalPlugin, next, ZoneId.systemDefault());
            }
            try {
                scheduler.sync(() -> lines.forEach(sender::sendMessage));
            } catch (RuntimeException disabling) {
                // RsLib is disabling: nobody is left to read it.
            }
        });
    }

    /** Completions for the words after {@code logs}. */
    public static List<String> complete(String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add(EVERYBODY);
            for (Player online : Bukkit.getOnlinePlayers()) {
                options.add(online.getName());
            }
        } else if (args.length == 2) {
            options.add(EVERYBODY);
            options.addAll(pluginsWithLog());
        } else {
            return List.of();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    /**
     * The chat lines of {@code page}: a header, one line per entry and the next-page hint.
     * {@code subject} is the player's name, null for everybody; {@code plugin} null is every plugin.
     */
    static List<Component> lines(AuditPage page, String subject, String plugin, String nextCommand, ZoneId zone) {
        List<Component> lines = new ArrayList<>();
        Ph who = Ph.literal("player", subject == null ? LibMessages.text("rslib.admin.logs.everybody") : subject);
        Ph where = Ph.literal("plugin", plugin == null ? LibMessages.text("rslib.admin.logs.every-plugin") : plugin);
        if (page.total() == 0) {
            lines.add(LibMessages.render("rslib.admin.logs.none", who, where));
            return lines;
        }
        if (page.entries().isEmpty()) {
            lines.add(LibMessages.render("rslib.admin.logs.empty-page", Ph.of("page", page.page()),
                    Ph.of("pages", page.pages())));
            return lines;
        }
        lines.add(LibMessages.render("rslib.admin.logs.header", who, where, Ph.of("total", page.total()),
                Ph.of("page", page.page()), Ph.of("pages", page.pages())));
        for (AuditEntry entry : page.entries()) {
            lines.add(line(entry, zone));
        }
        if (page.page() < page.pages()) {
            lines.add(LibMessages.render("rslib.admin.logs.next-page", Ph.literal("command", nextCommand))
                    .clickEvent(ClickEvent.runCommand(nextCommand))
                    .hoverEvent(HoverEvent.showText(LibMessages.render("rslib.admin.logs.click-to-open"))));
        }
        return lines;
    }

    /** {@code 2026-10-16 14:03 RsMarket market-buy: Steve -> Alex | 4,500 | 16x Diamond | detail | world 10, 64, -3}. */
    static Component line(AuditEntry entry, ZoneId zone) {
        TextComponent.Builder out = Component.text();
        out.append(Component.text(WHEN.format(Instant.ofEpochMilli(entry.createdAt()).atZone(zone)) + " ", NamedTextColor.DARK_GRAY));
        out.append(Component.text(entry.plugin() + " ", NamedTextColor.GOLD));
        out.append(Component.text(entry.action() + ":", NamedTextColor.YELLOW));
        out.append(Component.text(" " + (entry.actorName() == null ? "-" : entry.actorName()), NamedTextColor.WHITE));
        if (entry.targetName() != null || entry.targetId() != null) {
            out.append(Component.text(" -> ", NamedTextColor.GRAY));
            out.append(Component.text(entry.targetName() != null ? entry.targetName() : entry.targetId().toString(),
                    NamedTextColor.WHITE));
        }
        if (entry.money() != null) {
            separator(out);
            out.append(Component.text(AuditItems.money(entry.money()), NamedTextColor.GREEN));
        }
        if (entry.items() != null && !entry.items().isEmpty()) {
            separator(out);
            out.append(Component.text(entry.items(), NamedTextColor.AQUA));
        }
        if (entry.details() != null && !entry.details().isEmpty()) {
            separator(out);
            out.append(Component.text(entry.details(), NamedTextColor.GRAY));
        }
        if (entry.hasLocation()) {
            separator(out);
            out.append(Component.text(entry.world() + " " + (long) Math.floor(entry.x()) + ", "
                    + (long) Math.floor(entry.y()) + ", " + (long) Math.floor(entry.z()), NamedTextColor.DARK_AQUA));
        }
        return out.build();
    }

    private static void separator(TextComponent.Builder out) {
        out.append(Component.text(" | ", NamedTextColor.DARK_GRAY));
    }

    private static Integer pageNumber(String text) {
        try {
            int number = Integer.parseInt(text);
            return number >= 1 ? number : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The name of the enabled plugin with an audit log called {@code name} (any case), or null. */
    private static String pluginNamed(String name) {
        for (String plugin : pluginsWithLog()) {
            if (plugin.equalsIgnoreCase(name)) {
                return plugin;
            }
        }
        return null;
    }

    private static List<String> pluginsWithLog() {
        List<String> names = new ArrayList<>();
        for (Rs rs : RsLib.contexts()) {
            if (rs.auditOrNull() != null) {
                names.add(rs.plugin().getName());
            }
        }
        return names;
    }
}
