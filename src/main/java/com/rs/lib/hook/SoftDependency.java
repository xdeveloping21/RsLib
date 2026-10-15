package com.rs.lib.hook;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A hook into another plugin that may be missing, disabled, too old, or come and go at runtime.
 *
 * <p>The factory is the only code that touches the other plugin's classes, and it runs only
 * after the target is found enabled, recent enough and (if asked) with the required class
 * present. Anything the factory throws, including a {@link LinkageError} from an adapter
 * compiled against a different API, is reported once and leaves the hook unavailable.
 * The answer is kept until the target is enabled or disabled again (RsLib listens for both)
 * or {@link #invalidate()} is called.</p>
 *
 * <p>When the owner is disabled (RsLib listens for that too, whether or not the owner is an
 * {@code RsPlugin}) its hooks are switched off and forgotten. A hook kept in a field comes
 * back by itself the next time {@link #get()} is called after the same owner is enabled
 * again.</p>
 */
public final class SoftDependency<T> {

    private static final CopyOnWriteArrayList<SoftDependency<?>> REGISTERED = new CopyOnWriteArrayList<>();

    private final JavaPlugin owner;
    private final String pluginName;
    private final Supplier<T> factory;
    private volatile String minVersion;
    private volatile String requiredClass;

    private final Object lock = new Object();
    private boolean resolved;
    private boolean disabledForGood;
    private T instance;

    private SoftDependency(JavaPlugin owner, String pluginName, Supplier<T> factory) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public static <T> SoftDependency<T> of(JavaPlugin owner, String pluginName, Supplier<T> factory) {
        SoftDependency<T> dep = new SoftDependency<>(owner, pluginName, factory);
        REGISTERED.add(dep);
        return dep;
    }

    /** The target must be at least this version (compared with {@link SemverComparator}). */
    public SoftDependency<T> minVersion(String version) {
        this.minVersion = version;
        invalidate();
        return this;
    }

    /** The target must provide this class (checked with its own class loader). */
    public SoftDependency<T> requiresClass(String className) {
        this.requiredClass = className;
        invalidate();
        return this;
    }

    public boolean isAvailable() {
        return get().isPresent();
    }

    public Optional<T> get() {
        synchronized (lock) {
            if (disabledForGood && owner.isEnabled()) {
                // The owner was disabled and enabled again: this hook is live once more.
                disabledForGood = false;
                resolved = false;
                REGISTERED.addIfAbsent(this);
            }
            if (!resolved) {
                instance = disabledForGood ? null : resolve();
                resolved = true;
            }
            return Optional.ofNullable(instance);
        }
    }

    /** Forgets the cached answer; the next {@link #get()} checks the target again. */
    public void invalidate() {
        synchronized (lock) {
            resolved = false;
            instance = null;
        }
    }

    /**
     * Turns the hook off and stops tracking it (used when the owner is disabled). It turns on
     * again by itself on the first {@link #get()} after the owner is enabled again.
     */
    public void forceDisable() {
        synchronized (lock) {
            disabledForGood = true;
            resolved = true;
            instance = null;
        }
        REGISTERED.remove(this);
    }

    public JavaPlugin owner() {
        return owner;
    }

    public String pluginName() {
        return pluginName;
    }

    /** Visits every live hook (RsLib uses it to react to plugins being enabled or disabled). */
    public static void forEachRegistered(Consumer<SoftDependency<?>> action) {
        REGISTERED.forEach(action);
    }

    /** Turns off every hook of an owner that is being disabled, and stops tracking them. */
    public static void disableAllOf(Plugin owner) {
        for (SoftDependency<?> dep : REGISTERED) {
            if (dep.owner == owner) {
                dep.forceDisable();
            }
        }
    }

    /** Called when the target was enabled: resolve again right away. */
    void targetEnabled() {
        invalidate();
        get();
    }

    /**
     * Called when the target is being disabled: park the hook as unavailable without
     * resolving again (the target still reports itself enabled during its own disable).
     */
    void targetDisabled() {
        synchronized (lock) {
            instance = null;
            resolved = true;
        }
    }

    boolean targets(String name) {
        return pluginName.equalsIgnoreCase(name);
    }

    private T resolve() {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null || !target.isEnabled()) {
            return null;
        }
        String wanted = minVersion;
        if (wanted != null) {
            String actual = target.getPluginMeta().getVersion();
            if (SemverComparator.compareVersions(actual, wanted) < 0) {
                owner.getLogger().warning(pluginName + " " + actual + " is older than " + wanted
                        + "; the " + pluginName + " hook stays off.");
                return null;
            }
        }
        String cls = requiredClass;
        if (cls != null) {
            try {
                Class.forName(cls, false, target.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError missing) {
                owner.getLogger().warning(pluginName + " does not provide " + cls
                        + "; the " + pluginName + " hook stays off.");
                return null;
            }
        }
        try {
            return factory.get();
        } catch (Throwable t) {
            owner.getLogger().log(Level.WARNING, "Could not hook into " + pluginName + " ("
                    + t.getClass().getSimpleName() + "); that integration stays off.", t);
            return null;
        }
    }

    @Override
    public String toString() {
        return "SoftDependency[" + owner.getName() + " -> " + pluginName.toLowerCase(Locale.ROOT) + "]";
    }
}
