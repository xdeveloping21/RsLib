package com.rs.lib.modifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

/**
 * The built-in provider: {@code <prefix>.modifier.<type>.<source>.<factor>} and
 * {@code <prefix>.modifier.<type>.<factor>} (every source of that type). The factor is a whole
 * or decimal number ({@code 2}, {@code 1.5}, {@code 0.75}); among the player's nodes that
 * match, the highest wins, and 1 when none does. A source cannot be only digits, so
 * {@code money.1.5} is "x1.5 on every money source".
 *
 * <p>A player's nodes are read once and kept until they quit, until LuckPerms reports their
 * permissions (or a group's) changed, or for {@link #WATCHED_TTL_MILLIS} at most; without
 * LuckPerms, for {@link #TTL_MILLIS}.</p>
 */
final class PermissionModifiers implements ModifierProvider {

    /** How long a player's nodes are kept when nothing reports permission changes. */
    static final long TTL_MILLIS = 5_000L;
    /** How long they are kept while LuckPerms reports changes (other plugins may still add permissions). */
    static final long WATCHED_TTL_MILLIS = 60_000L;

    /** One parsed node; {@code source} is null for "every source". */
    record Node(String type, String source, double factor) {
    }

    private record Entry(List<Node> nodes, long expiresAt) {
    }

    private final ModifierHost host;
    private final String prefix;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    /** {@code prefix} without the trailing dot, such as {@code rsgens}. */
    PermissionModifiers(ModifierHost host, String prefix) {
        this.host = host;
        this.prefix = prefix.toLowerCase(Locale.ROOT) + ".modifier.";
    }

    @Override
    public double factor(Player player, String type, String source) {
        double best = Double.NaN;
        for (Node node : nodes(player)) {
            if (node.type.equals(type) && (node.source == null || node.source.equals(source))) {
                best = Double.isNaN(best) ? node.factor : Math.max(best, node.factor);
            }
        }
        return Double.isNaN(best) ? 1.0 : best;
    }

    void invalidate(UUID player) {
        cache.remove(player);
    }

    void invalidateAll() {
        cache.clear();
    }

    private List<Node> nodes(Player player) {
        UUID id = player.getUniqueId();
        long now = host.now();
        Entry entry = cache.get(id);
        if (entry != null && now < entry.expiresAt) {
            return entry.nodes;
        }
        boolean watched = host.watchPermissions(this::invalidate, this::invalidateAll);
        List<Node> nodes = parseAll(host.grantedPermissions(player));
        cache.put(id, new Entry(nodes, now + (watched ? WATCHED_TTL_MILLIS : TTL_MILLIS)));
        return nodes;
    }

    private List<Node> parseAll(Collection<String> permissions) {
        List<Node> nodes = new ArrayList<>();
        for (String permission : permissions) {
            if (permission == null) {
                continue;
            }
            String lower = permission.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix)) {
                Node node = parse(lower.substring(prefix.length()));
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        return List.copyOf(nodes);
    }

    /** {@code money.gens.1.5}, {@code money.gens.2}, {@code money.1.5} or {@code money.2}; null when it is none of them. */
    static Node parse(String rest) {
        String[] parts = rest.split("\\.", -1);
        int end = parts.length;
        int factorStart;
        String factorText;
        if (end >= 3 && digits(parts[end - 2]) && digits(parts[end - 1])) {
            factorStart = end - 2;
            factorText = parts[end - 2] + "." + parts[end - 1];
        } else if (end >= 2 && digits(parts[end - 1])) {
            factorStart = end - 1;
            factorText = parts[end - 1];
        } else {
            return null;
        }
        String type;
        String source;
        if (factorStart == 1) {
            type = parts[0];
            source = null;
        } else if (factorStart == 2) {
            type = parts[0];
            source = parts[1];
            if (!name(source) || digits(source)) {
                return null;
            }
        } else {
            return null;
        }
        if (!name(type)) {
            return null;
        }
        double factor = Double.parseDouble(factorText);
        return Double.isFinite(factor) ? new Node(type, source, factor) : null;
    }

    private static boolean digits(String text) {
        if (text.isEmpty() || text.length() > 9) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** A type or source: letters, digits, {@code -} and {@code _}. */
    static boolean name(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }
}
