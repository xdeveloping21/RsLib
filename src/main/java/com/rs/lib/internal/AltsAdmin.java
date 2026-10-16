package com.rs.lib.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.CommandSender;

import com.rs.lib.account.AltAccount;
import com.rs.lib.account.Accounts;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.util.PlayerNames;
import com.rs.lib.util.TimeUtil;

/**
 * {@code /rslib alts <player>}: the accounts that joined from an address the player also
 * joined from. Online or offline; the answer comes back on the main thread.
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
                    List<String> lines;
                    if (error != null) {
                        lines = List.of("Could not read the alt-account records: " + (error.getCause() != null
                                ? error.getCause().getMessage() : error.getMessage()));
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
    public static List<String> lines(String name, List<AltAccount> alts, long now) {
        if (alts == null) {
            return List.of("No join of " + name + " is recorded.");
        }
        if (alts.isEmpty()) {
            return List.of(name + " shares no address with another account.");
        }
        List<String> lines = new ArrayList<>(alts.size() + 1);
        lines.add("Accounts that joined from an address of " + name + " (" + alts.size() + "):");
        for (AltAccount alt : alts) {
            lines.add("- " + alt.name() + " (" + alt.uuid() + "), last seen "
                    + TimeUtil.humanizeShort(now - alt.lastSeen()) + " ago");
        }
        return lines;
    }

    /** The answer while the detection is off. */
    public static String off() {
        return "Alt-account detection is off (accounts.enabled in RsLib's config.yml).";
    }
}
