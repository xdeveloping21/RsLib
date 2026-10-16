package com.rs.lib.settings;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.db.RsDb;
import com.rs.lib.yml.YmlTestSupport.Captured;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** A settings host without a server: a real database (or none), a list of online players and a recording console. */
final class FakeSettingsHost implements SettingsHost {

    final Captured log = new Captured();
    final List<Player> online = new CopyOnWriteArrayList<>();
    /** Every message sent: {@code <receiver>: <text or key>}. */
    final List<String> sent = new CopyOnWriteArrayList<>();
    final String owner;
    final RsDb db;
    boolean shuttingDown;
    private final CommandSender console;

    FakeSettingsHost(String owner, RsDb db) {
        this.owner = owner;
        this.db = db;
        this.console = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "CONSOLE";
                    case "sendMessage" -> record("CONSOLE", args[0]);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "CONSOLE";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Object record(String who, Object message) {
        String text = message instanceof Component component
                ? PlainTextComponentSerializer.plainText().serialize(component) : String.valueOf(message);
        sent.add(who + ": " + text);
        return null;
    }

    /** A player with an id, a name and the permissions in {@code perms}; their messages are recorded. */
    Player player(String name, String... perms) {
        UUID id = UUID.randomUUID();
        Set<String> granted = Set.of(perms);
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "hasPermission" -> args[0] instanceof String perm && granted.contains(perm);
                    case "isOnline" -> online.contains(proxy);
                    case "sendMessage" -> record(name, args[0]);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public Logger logger() {
        return log.logger;
    }

    @Override
    public RsDb db() {
        if (db == null) {
            throw new UnsupportedOperationException(owner + " called rs().db() without declaring it");
        }
        return db;
    }

    @Override
    public boolean shuttingDown() {
        return shuttingDown;
    }

    @Override
    public Collection<? extends Player> online() {
        return new ArrayList<>(online);
    }

    @Override
    public CommandSender console() {
        return console;
    }

    @Override
    public void send(CommandSender target, String key, Ph... phs) {
        StringBuilder line = new StringBuilder(key);
        for (Ph ph : phs) {
            line.append(' ').append(ph.key()).append('=').append(ph.value());
        }
        sent.add(target.getName() + ": " + line);
    }

    @Override
    public String text(String key) {
        return Defaults.get(key);
    }
}
