package com.rs.lib.region;

import java.util.Objects;

import org.bukkit.Location;

/**
 * A named {@link Cuboid} registered by one plugin with {@code rs().regions()}. Immutable; two
 * regions are equal when the same plugin registered them under the same id, so a region
 * registered again with a new box is still "the same region" for enter and leave.
 */
public final class RsRegion {

    private final String owner;
    private final String id;
    private final Cuboid area;
    /** Registration order; a region registered again keeps its place. */
    final long order;
    /** Set when the region was removed without leave events (its plugin shut down). */
    volatile boolean released;

    RsRegion(String owner, String id, Cuboid area, long order) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = Objects.requireNonNull(id, "id");
        this.area = Objects.requireNonNull(area, "area");
        this.order = order;
    }

    /** The name of the plugin that registered it. */
    public String owner() {
        return owner;
    }

    /** Its id, unique within its plugin. */
    public String id() {
        return id;
    }

    /** Its box. */
    public Cuboid area() {
        return area;
    }

    /** The name of the world it is in. */
    public String worldName() {
        return area.worldName();
    }

    /** True when the block of {@code location} is inside. */
    public boolean contains(Location location) {
        return area.contains(location);
    }

    /** {@code <plugin>:<id>}: the key that identifies it server-wide. */
    public String key() {
        return owner + ':' + id;
    }

    boolean sameKey(String otherOwner, String otherId) {
        return owner.equals(otherOwner) && id.equals(otherId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RsRegion r && r.owner.equals(owner) && r.id.equals(id);
    }

    @Override
    public int hashCode() {
        return 31 * owner.hashCode() + id.hashCode();
    }

    /** {@code RsRegion[plugin:id world;minX;...]}. */
    @Override
    public String toString() {
        return "RsRegion[" + key() + ' ' + area.serialize() + ']';
    }
}
