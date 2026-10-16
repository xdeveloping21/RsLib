package com.rs.lib.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.RsLib;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.RsFuture;
import com.rs.lib.db.TransferRefused;
import com.rs.lib.db.TransferReport;
import com.rs.lib.scheduler.RsScheduler;

import net.kyori.adventure.text.Component;

/**
 * {@code /rslib db export <plugin>} and {@code /rslib db import <plugin> <file> [--force]}:
 * a plugin's database written to a file in its folder, or read back into the database it is
 * configured with now (from SQLite to MySQL, for instance). The work runs on the plugin's
 * database threads; progress and the result come back to the sender on the main thread, in
 * RsLib's language ({@link LibMessages}). What the sender typed is shown as it is.
 */
public final class DbAdmin {

    public static final String USAGE = "db <export <plugin>|import <plugin> <file> [--force]>";
    private static final String FORCE = "--force";

    private final RsScheduler scheduler;
    /** RsLib's own context, for its own database (alt-account records). */
    private final Supplier<Rs> own;
    /** Plugins with a transfer running; a second one waits for the first. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public DbAdmin(RsScheduler scheduler, Supplier<Rs> own) {
        this.scheduler = scheduler;
        this.own = own;
    }

    /** Runs {@code /<label> db ...}; {@code args[0]} is "db". */
    public void run(CommandSender sender, String label, String[] args) {
        Ph labelPh = Ph.literal("label", label);
        if (args.length < 3) {
            LibMessages.send(sender, "rslib.admin.db.usage", labelPh);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        Rs rs = context(args[2]);
        RsDb db = rs == null ? null : rs.dbOrNull();
        if (db == null) {
            LibMessages.send(sender, "rslib.admin.db.no-database", Ph.literal("plugin", args[2]));
            return;
        }
        String plugin = rs.plugin().getName();
        Ph pluginPh = Ph.literal("plugin", plugin);
        Ph database = Ph.literal("database", db.config());
        Path folder = rs.plugin().getDataFolder().toPath().toAbsolutePath().normalize();
        switch (action) {
            case "export" -> {
                if (args.length != 3) {
                    LibMessages.send(sender, "rslib.admin.db.usage-export", labelPh);
                    return;
                }
                if (!claim(sender, plugin)) {
                    return;
                }
                LibMessages.send(sender, "rslib.admin.db.exporting", pluginPh, database);
                finish(sender, plugin, "export", db.exportTo(folder, progress(sender)),
                        report -> LibMessages.render("rslib.admin.db.exported", summary(report, pluginPh,
                                Ph.literal("file", report.file().getFileName()))));
            }
            case "import" -> {
                boolean force = args.length == 5 && args[4].equalsIgnoreCase(FORCE);
                if (args.length < 4 || args.length > 5 || args.length == 5 && !force) {
                    LibMessages.send(sender, "rslib.admin.db.usage-import", labelPh);
                    return;
                }
                Path file = folder.resolve(args[3]).normalize();
                if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
                    LibMessages.send(sender, "rslib.admin.db.no-file", Ph.literal("file", args[3]), pluginPh);
                    return;
                }
                if (!claim(sender, plugin)) {
                    return;
                }
                LibMessages.send(sender, force ? "rslib.admin.db.importing-force" : "rslib.admin.db.importing",
                        Ph.literal("file", file.getFileName()), pluginPh, database);
                finish(sender, plugin, "import", db.importFrom(file, force, progress(sender)),
                        report -> LibMessages.render("rslib.admin.db.imported", summary(report, pluginPh)));
            }
            default -> LibMessages.send(sender, "rslib.admin.db.usage", labelPh);
        }
    }

    /** Completions after {@code db}: the action, a plugin with a database, its dump files, the flag. */
    public List<String> complete(String[] args) {
        if (args.length == 2) {
            return List.of("export", "import");
        }
        if (args.length == 3) {
            List<String> names = new ArrayList<>();
            Rs self = own.get();
            if (self != null && self.dbOrNull() != null) {
                names.add(self.plugin().getName());
            }
            for (Rs rs : RsLib.contexts()) {
                if (rs.dbOrNull() != null) {
                    names.add(rs.plugin().getName());
                }
            }
            return names;
        }
        boolean importing = args[1].equalsIgnoreCase("import");
        if (importing && args.length == 4) {
            Rs rs = context(args[2]);
            if (rs == null) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(rs.plugin().getDataFolder().toPath())) {
                return files.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(TransferReport.EXTENSION)).sorted().toList();
            } catch (IOException e) {
                return List.of();
            }
        }
        if (importing && args.length == 5) {
            return List.of(FORCE);
        }
        return List.of();
    }

    private Rs context(String name) {
        Rs self = own.get();
        if (self != null && self.plugin().getName().equalsIgnoreCase(name)) {
            return self;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin == null ? null : RsLib.context(plugin);
    }

    private boolean claim(CommandSender sender, String plugin) {
        if (running.add(plugin)) {
            return true;
        }
        LibMessages.send(sender, "rslib.admin.db.running", Ph.literal("plugin", plugin));
        return false;
    }

    private TransferReport.Progress progress(CommandSender sender) {
        return (table, rows) -> tell(sender, LibMessages.render("rslib.admin.db.progress", Ph.literal("table", table),
                Ph.of("rows", rows)));
    }

    /** {@code what} is {@code export} or {@code import}: the {@code -refused} and {@code -failed} messages. */
    private void finish(CommandSender sender, String plugin, String what, RsFuture<TransferReport> future,
                        Function<TransferReport, Component> done) {
        future.toCompletableFuture().whenComplete((report, error) -> {
            running.remove(plugin);
            if (error == null) {
                tell(sender, done.apply(report));
                return;
            }
            Throwable cause = error;
            while (cause.getCause() != null && !(cause instanceof TransferRefused)) {
                cause = cause.getCause();
            }
            String reason = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            tell(sender, LibMessages.render("rslib.admin.db." + what + (cause instanceof TransferRefused ? "-refused"
                    : "-failed"), Ph.literal("plugin", plugin), Ph.literal("reason", reason)));
        });
    }

    /** The counts of {@code report} ({@code {tables}}, {@code {rows}}, {@code {millis}}) after {@code more}. */
    static Ph[] summary(TransferReport report, Ph... more) {
        Ph[] out = Arrays.copyOf(more, more.length + 3);
        out[more.length] = Ph.of("tables", report.tables().size());
        out[more.length + 1] = Ph.of("rows", report.rows());
        out[more.length + 2] = Ph.of("millis", report.millis());
        return out;
    }

    private void tell(CommandSender sender, Component message) {
        try {
            scheduler.sync(() -> sender.sendMessage(message));
        } catch (RuntimeException disabled) {
            // RsLib is shutting down; nobody is listening.
        }
    }
}
