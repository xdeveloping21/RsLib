package com.rs.lib.velocity;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.slf4j.Logger;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;

/** In-memory stand-ins for the parts of the Velocity API that RsLib touches, recording each call. */
final class VelocityFakes {

    /** A task given to the fake scheduler. */
    static final class Task {
        final Object plugin;
        final Runnable body;
        long delayMillis = -1;
        long repeatMillis = -1;
        boolean scheduled;

        Task(Object plugin, Runnable body) {
            this.plugin = plugin;
            this.body = body;
        }
    }

    /** A command registered through the fake command manager. */
    static final class Registration {
        final String name;
        final Set<String> aliases;
        final Object plugin;
        final Command command;
        final CommandMeta meta;

        Registration(String name, Set<String> aliases, Object plugin, Command command, CommandMeta meta) {
            this.name = name;
            this.aliases = aliases;
            this.plugin = plugin;
            this.command = command;
            this.meta = meta;
        }
    }

    final List<Task> tasks = new ArrayList<>();
    final List<Registration> commands = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    String version = "1.2.3";

    final ProxyServer proxy = fake(ProxyServer.class, (name, args) -> switch (name) {
        case "getScheduler" -> scheduler();
        case "getCommandManager" -> commandManager();
        case "getPluginManager" -> pluginManager();
        default -> throw new UnsupportedOperationException(name);
    });

    /** Records each call as {@code level: message} with the {@code {}} filled in. */
    final Logger logger = fake(Logger.class, (name, args) -> {
        switch (name) {
            case "trace", "debug", "info", "warn", "error" -> logs.add(name + ": " + format(args));
            case "getName" -> {
                return "test";
            }
            default -> {
                if (name.startsWith("is")) {
                    return true;
                }
            }
        }
        return null;
    });

    /** Messages logged at {@code level}. */
    List<String> logged(String level) {
        List<String> out = new ArrayList<>();
        for (String line : logs) {
            if (line.startsWith(level + ": ")) {
                out.add(line.substring(level.length() + 2));
            }
        }
        return out;
    }

    private Scheduler scheduler() {
        return fake(Scheduler.class, (name, args) -> {
            if (!name.equals("buildTask")) {
                throw new UnsupportedOperationException(name);
            }
            Task task = new Task(args[0], (Runnable) args[1]);
            tasks.add(task);
            return taskBuilder(task);
        });
    }

    private Scheduler.TaskBuilder taskBuilder(Task task) {
        Scheduler.TaskBuilder[] self = new Scheduler.TaskBuilder[1];
        self[0] = fake(Scheduler.TaskBuilder.class, (name, args) -> {
            switch (name) {
                case "delay" -> task.delayMillis = ((TimeUnit) args[1]).toMillis((Long) args[0]);
                case "repeat" -> task.repeatMillis = ((TimeUnit) args[1]).toMillis((Long) args[0]);
                case "schedule" -> {
                    task.scheduled = true;
                    return fake(ScheduledTask.class, (n, a) -> null);
                }
                default -> throw new UnsupportedOperationException(name);
            }
            return self[0];
        });
        return self[0];
    }

    private CommandManager commandManager() {
        return fake(CommandManager.class, (name, args) -> switch (name) {
            case "metaBuilder" -> metaBuilder((String) args[0]);
            case "register" -> {
                CommandMeta meta = (CommandMeta) args[0];
                commands.add(new Registration(name(meta), Set.copyOf(meta.getAliases()), meta.getPlugin(), (Command) args[1], meta));
                yield null;
            }
            default -> throw new UnsupportedOperationException(name);
        });
    }

    private static String name(CommandMeta meta) {
        return meta.toString();
    }

    private static CommandMeta.Builder metaBuilder(String name) {
        Set<String> aliases = new HashSet<>();
        aliases.add(name);
        Object[] plugin = new Object[1];
        CommandMeta.Builder[] self = new CommandMeta.Builder[1];
        self[0] = fake(CommandMeta.Builder.class, (method, args) -> {
            switch (method) {
                case "aliases" -> Collections.addAll(aliases, (String[]) args[0]);
                case "plugin" -> plugin[0] = args[0];
                case "build" -> {
                    Set<String> frozen = Set.copyOf(aliases);
                    Object owner = plugin[0];
                    return fake(CommandMeta.class, (n, a) -> switch (n) {
                        case "getAliases" -> frozen;
                        case "getPlugin" -> owner;
                        case "toString" -> name;
                        default -> null;
                    });
                }
                default -> throw new UnsupportedOperationException(method);
            }
            return self[0];
        });
        return self[0];
    }

    private PluginManager pluginManager() {
        PluginDescription description = fake(PluginDescription.class, (name, args) -> switch (name) {
            case "getVersion" -> Optional.ofNullable(version);
            default -> throw new UnsupportedOperationException(name);
        });
        PluginContainer container = fake(PluginContainer.class, (name, args) -> switch (name) {
            case "getDescription" -> description;
            default -> throw new UnsupportedOperationException(name);
        });
        return fake(PluginManager.class, (name, args) -> switch (name) {
            case "fromInstance" -> Optional.of(container);
            default -> throw new UnsupportedOperationException(name);
        });
    }

    /** An implementation of {@code type} answering every call through {@code handler}. */
    static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> handler) {
        Object instance = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> {
                        Object answer = handler.apply("toString", new Object[0]);
                        yield answer == null ? type.getSimpleName() + " fake" : answer;
                    }
                    default -> handler.apply(method.getName(), args == null ? new Object[0] : args);
                });
        return type.cast(instance);
    }

    private static String format(Object[] args) {
        if (args.length == 0 || !(args[0] instanceof String pattern)) {
            return "";
        }
        List<Object> values = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof Object[] array) {
                Collections.addAll(values, array);
            } else {
                values.add(args[i]);
            }
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int next = 0;
        int at;
        while ((at = pattern.indexOf("{}", from)) >= 0 && next < values.size()) {
            out.append(pattern, from, at).append(values.get(next++));
            from = at + 2;
        }
        out.append(pattern.substring(from));
        if (next < values.size() && values.get(values.size() - 1) instanceof Throwable failure) {
            out.append(" [").append(failure.getClass().getSimpleName()).append(']');
        }
        return out.toString();
    }
}
