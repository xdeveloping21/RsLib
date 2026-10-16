package com.rs.lib.hologram;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.bukkit.entity.Display;

/**
 * {@code Display#setTeleportDuration}, which exists from Paper 1.20.2: found once by reflection,
 * so RsLib links and runs on 1.20.1 too, where a move simply jumps.
 */
final class DisplayMoves {

    private static final MethodHandle SET_TELEPORT_DURATION = find();

    private DisplayMoves() {
    }

    private static MethodHandle find() {
        try {
            return MethodHandles.publicLookup().findVirtual(Display.class, "setTeleportDuration",
                    MethodType.methodType(void.class, int.class));
        } catch (NoSuchMethodException | IllegalAccessException | RuntimeException absent) {
            return null;
        }
    }

    /** Sets the teleport duration of {@code display}; false when the server cannot. */
    static boolean setTeleportDuration(Display display, int ticks) {
        MethodHandle handle = SET_TELEPORT_DURATION;
        if (handle == null) {
            return false;
        }
        try {
            handle.invokeExact(display, ticks);
            return true;
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            return false;
        }
    }
}
