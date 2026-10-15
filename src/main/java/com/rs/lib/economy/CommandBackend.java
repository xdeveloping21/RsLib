package com.rs.lib.economy;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.rs.lib.util.NumberFormatter;

/**
 * Money through console commands, for servers without Vault. The commands take
 * {@code %player%} and {@code %amount%}; the balance is read from a PlaceholderAPI placeholder
 * with colour codes and currency symbols removed ({@code §a$1.5K} reads 1500). A take reads the
 * balance first, refuses when it is short, runs the command and succeeds only when the
 * balance went down by the amount. Players must be online (placeholders need them).
 */
final class CommandBackend implements EconomyBridge.Backend {

    /** Rounding slack when comparing balances. */
    static final double TOLERANCE = 0.001;

    private static final CompletableFuture<Boolean> TRUE = CompletableFuture.completedFuture(true);
    private static final CompletableFuture<Boolean> FALSE = CompletableFuture.completedFuture(false);

    private final EconomyHost host;
    private final String giveCommand;
    private final String takeCommand;
    private final String balancePlaceholder;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    CommandBackend(EconomyHost host, String giveCommand, String takeCommand, String balancePlaceholder) {
        this.host = host;
        this.giveCommand = strip(giveCommand);
        this.takeCommand = strip(takeCommand);
        this.balancePlaceholder = balancePlaceholder == null ? "" : balancePlaceholder.trim();
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        double balance = read(player);
        return Double.isNaN(balance) ? 0 : balance;
    }

    @Override
    public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
        if (giveCommand.isEmpty()) {
            warnOnce("give", "The economy give command is empty; nothing can be given.");
            return FALSE;
        }
        return run(giveCommand, player, amount) ? TRUE : FALSE;
    }

    @Override
    public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
        if (takeCommand.isEmpty()) {
            warnOnce("take", "The economy take command is empty; nothing can be taken.");
            return FALSE;
        }
        double before = read(player);
        if (Double.isNaN(before) || before + TOLERANCE < amount) {
            return FALSE;
        }
        if (!run(takeCommand, player, amount)) {
            return FALSE;
        }
        double after = read(player);
        if (Double.isNaN(after)) {
            return FALSE;
        }
        boolean taken = before - after >= amount - TOLERANCE;
        if (!taken) {
            host.debug(() -> "economy take of " + amount + " from " + player.getName() + " ran but the balance went from "
                    + before + " to " + after);
        }
        return taken ? TRUE : FALSE;
    }

    /** Runs {@code template} for {@code player}; false when it did not run. */
    private boolean run(String template, OfflinePlayer player, double amount) {
        String name = player.getName();
        if (name == null) {
            return false;
        }
        String command = template.replace("%player%", name).replace("%amount%", plain(amount));
        try {
            boolean ran = host.dispatchConsole(command);
            if (!ran) {
                warnOnce("unknown:" + template, "The economy command '" + command + "' did not run (unknown command?).");
            }
            return ran;
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "The economy command '" + command + "' failed", e);
            return false;
        }
    }

    /** The balance, or NaN when it cannot be read. */
    private double read(OfflinePlayer offline) {
        Player player = offline.getPlayer();
        if (player == null || balancePlaceholder.isEmpty()) {
            return Double.NaN;
        }
        String resolved = host.placeholders(player, balancePlaceholder);
        if (resolved == null) {
            warnOnce("papi", "The economy balance needs PlaceholderAPI to read " + balancePlaceholder
                    + "; balances read 0 and takes fail.");
            return Double.NaN;
        }
        double value = parseBalance(resolved);
        if (Double.isNaN(value)) {
            warnOnce("parse", "The economy balance placeholder " + balancePlaceholder + " gave '" + resolved
                    + "', which is not a number; balances read 0 and takes fail.");
        }
        return value;
    }

    /** {@code §a$1,500.5} or {@code §e1.5K} as a number (colour codes and currency signs dropped); NaN when it is not one. */
    static double parseBalance(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (Character.getType(c) == Character.CURRENCY_SYMBOL) {
                continue;
            }
            clean.append(c);
        }
        try {
            double value = NumberFormatter.parseFormatted(clean.toString());
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    /** {@code 1500}, {@code 2.5}, {@code 0.0001}: no exponent, no trailing zeros. */
    static String plain(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    private static String strip(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            host.logger().warning(message);
        }
    }
}
