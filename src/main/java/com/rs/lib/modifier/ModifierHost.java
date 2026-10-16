package com.rs.lib.modifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import com.rs.lib.Rs;
import com.rs.lib.hook.SoftDependency;

/** What the modifiers need from the server; lets their logic run in tests. */
interface ModifierHost {

    Logger logger();

    /** Milliseconds, for the permission cache. */
    long now();

    /** The permissions {@code player} has set to true, as Bukkit lists them. */
    Collection<String> grantedPermissions(Player player);

    /**
     * Starts watching permission changes, the first time it is called, so that {@code onPlayer}
     * runs for a player whose permissions changed and {@code onEveryone} when it is not known
     * whose did. True while such changes are reported (LuckPerms is there).
     */
    boolean watchPermissions(Consumer<UUID> onPlayer, Runnable onEveryone);

    /** Stops watching permission changes. */
    void stopWatching();

    RandomGenerator random();

    static ModifierHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server: Bukkit permissions, and LuckPerms' events when LuckPerms is there. */
    final class Server implements ModifierHost {

        private final Rs rs;
        private SoftDependency<LuckPermsWatch> luckPerms;

        Server(Rs rs) {
            this.rs = rs;
        }

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public Collection<String> grantedPermissions(Player player) {
            List<String> granted = new ArrayList<>();
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                if (info.getValue()) {
                    granted.add(info.getPermission());
                }
            }
            return granted;
        }

        @Override
        public boolean watchPermissions(Consumer<UUID> onPlayer, Runnable onEveryone) {
            SoftDependency<LuckPermsWatch> dep;
            synchronized (this) {
                if (luckPerms == null) {
                    luckPerms = SoftDependency.of(rs.plugin(), "LuckPerms",
                            () -> LuckPermsWatch.start(rs.plugin(), onPlayer, onEveryone))
                            .requiresClass(LuckPermsWatch.PROVIDER);
                }
                dep = luckPerms;
            }
            return dep.isAvailable();
        }

        @Override
        public void stopWatching() {
            SoftDependency<LuckPermsWatch> dep;
            synchronized (this) {
                dep = luckPerms;
                luckPerms = null;
            }
            if (dep != null) {
                dep.get().ifPresent(LuckPermsWatch::close);
                dep.forceDisable();
            }
        }

        @Override
        public RandomGenerator random() {
            return ThreadLocalRandom.current();
        }
    }
}
