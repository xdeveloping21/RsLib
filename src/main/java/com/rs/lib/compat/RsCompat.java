package com.rs.lib.compat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Small helpers to use newer server API when it exists and degrade cleanly when it does not.
 */
public final class RsCompat {

    private static final Logger LOG = Logger.getLogger("RsLib");
    private static final Map<String, Optional<Method>> PROBES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private RsCompat() {
    }

    /**
     * A public method of a server or JDK class, or null when this server does not have it.
     * Both answers are cached; a missing method is reported once. Classes loaded by another
     * plugin are refused (caching their methods would keep that plugin loaded after a reload).
     */
    public static Method probe(Class<?> owner, String name, Class<?>... params) {
        ClassLoader loader = owner.getClassLoader();
        if (loader != null && loader != ClassLoader.getPlatformClassLoader()
                && loader != org.bukkit.Bukkit.class.getClassLoader()) {
            if (WARNED.add("plugin:" + owner.getName())) {
                LOG.warning("[RsLib] probe() only works with server classes, not " + owner.getName());
            }
            return null;
        }
        String key = owner.getName() + '#' + name + Arrays.toString(Arrays.stream(params).map(Class::getName).toArray());
        return PROBES.computeIfAbsent(key, k -> {
            try {
                return Optional.of(owner.getMethod(name, params));
            } catch (NoSuchMethodException | SecurityException absent) {
                LOG.warning("[RsLib] " + owner.getSimpleName() + "#" + name
                        + " needs a newer Minecraft version; that feature is disabled.");
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * The modern value on {@code 1.<minor>} or newer, the fallback otherwise (reported once
     * per calling class and line).
     */
    public static <T> T since(int minor, Supplier<T> modern, Supplier<T> fallback) {
        if (RsVersion.supports(minor)) {
            return modern.get();
        }
        StackWalker.StackFrame caller = StackWalker.getInstance()
                .walk(frames -> frames.skip(1).findFirst().orElse(null));
        String site = caller == null ? "?" : caller.getClassName() + ":" + caller.getLineNumber();
        if (WARNED.add("since:" + site)) {
            LOG.warning("[RsLib] " + site + " uses a feature from 1." + minor
                    + "; this server runs " + RsVersion.describe() + ", using the older behavior.");
        }
        return fallback.get();
    }
}
