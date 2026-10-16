package com.rs.lib.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.CommandSender;

import com.rs.lib.Ph;
import com.rs.lib.account.AltAccount;
import com.rs.lib.account.Accounts;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.util.PlayerNames;
import com.rs.lib.util.TimeUtil;

import net.kyori.adventure.text.Component;

/**
 * {@code /rslib alts <player>}: the accounts that joined from an address the player also
 * joined from. Online or offline; the answer comes back on the main thread, in RsLib's
 * language ({@link LibMessages}). Names are shown as they are.
 */
public final class AltsAdmin {

    private AltsAdmin() {
    }

    public static void run(CommandSender sender, String name, RsScheduler scheduler) {
        Accounts accounts = Accounts.get();
        if (!accounts.enabled()) {
            sender.sendMessage(off());
            return;
        }
        // The records first (they keep the name each account last joined with), then the name directory.
        CompletableFuture<Optional<UUID>> player = accounts.lookup(name).toCompletableFuture()
                .thenApply(found -> found.isPresent() ? found : PlayerNames.uuid(name));
        player.thenCompose(id -> id.isEmpty() ? CompletableFuture.completedFuture((List<AltAccount>) null)
                        : accounts.alts(id.get()).toCompletableFuture())
                .whenComplete((alts, error) -> {
                    List<Component> lines;
                    if (error != null) {
                        lines = List.of(LibMessages.render("rslib.admin.alts.read-failed", Ph.literal("error",
                                error.getCause() != null ? error.getCause().getMessage() : error.getMessage())));
                    } else {
                        lines = lines(name, alts, System.currentTimeMillis());
                    }
                    try {
                        scheduler.sync(() -> lines.forEach(sender::sendMessage));
                    } catch (RuntimeException disabled) {
                        // RsLib is shutting down.
                    }
                });
    }

    /** What the command prints; {@code alts} is null for a player with no record at all. */
    public static List<Component> lines(String name, List<AltAccount> alts, long now) {
        Ph player = Ph.literal("player", name);
        if (alts == null) {
            return List.of(LibMessages.render("rslib.admin.alts.no-record", player));
        }
        if (alts.isEmpty()) {
            return List.of(LibMessages.render("rslib.admin.alts.none", player));
        }
        List<Component> lines = new ArrayList<>(alts.size() + 1);
        lines.add(LibMessages.render("rslib.admin.alts.header", player, Ph.of("count", alts.size())));
        for (AltAccount alt : alts) {
            lines.add(LibMessages.render("rslib.admin.alts.entry", Ph.literal("name", alt.name()),
                    Ph.of("uuid", alt.uuid()), Ph.of("time", TimeUtil.humanizeShort(now - alt.lastSeen()))));
        }
        return lines;
    }

    /** The answer while the detection is off. */
    public static Component off() {
        return LibMessages.render("rslib.admin.alts.disabled");
    }
}
