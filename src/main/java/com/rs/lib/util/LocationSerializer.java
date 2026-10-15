package com.rs.lib.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Locations as one line of text: {@code world;x;y;z;yaw;pitch}.
 *
 * <p>Numbers are written with {@link Double#toString(double)} and {@link Float#toString(float)},
 * so the text never depends on the server's locale ({@code world;10.5;64.0;-3.25;90.0;0.0}).
 * Reading also accepts the short form {@code world;x;y;z} (yaw and pitch 0) and spaces around
 * each part. Anything else, a non-finite number or a world that is not loaded reads as null.</p>
 */
public final class LocationSerializer {

    private static final char SEPARATOR = ';';

    private LocationSerializer() {
    }

    /** {@code world;x;y;z;yaw;pitch}, or null when {@code location} or its world is null or unloaded. */
    public static String serialize(Location location) {
        if (location == null) {
            return null;
        }
        World world;
        try {
            world = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
        return world == null ? null : write(world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** The location in {@code text}, or null when it is malformed or its world is not loaded. */
    public static Location deserialize(String text) {
        Parts parts = parse(text);
        if (parts == null) {
            return null;
        }
        World world = Bukkit.getWorld(parts.world());
        return world == null ? null
                : new Location(world, parts.x(), parts.y(), parts.z(), parts.yaw(), parts.pitch());
    }

    static String write(String world, double x, double y, double z, float yaw, float pitch) {
        return world + SEPARATOR + x + SEPARATOR + y + SEPARATOR + z + SEPARATOR + yaw + SEPARATOR + pitch;
    }

    /** The parts of {@code text}, or null when it is not 4 or 6 well-formed parts. */
    static Parts parse(String text) {
        if (text == null) {
            return null;
        }
        String[] split = text.split(String.valueOf(SEPARATOR), -1);
        if (split.length != 4 && split.length != 6) {
            return null;
        }
        String world = split[0].trim();
        if (world.isEmpty()) {
            return null;
        }
        try {
            double x = Double.parseDouble(split[1].trim());
            double y = Double.parseDouble(split[2].trim());
            double z = Double.parseDouble(split[3].trim());
            float yaw = split.length == 6 ? Float.parseFloat(split[4].trim()) : 0f;
            float pitch = split.length == 6 ? Float.parseFloat(split[5].trim()) : 0f;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                return null;
            }
            return new Parts(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** A location before its world is looked up. */
    record Parts(String world, double x, double y, double z, float yaw, float pitch) {
    }
}
