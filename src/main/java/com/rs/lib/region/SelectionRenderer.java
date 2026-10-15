package com.rs.lib.region;

/**
 * Where a selection's particles go, as pure geometry: no server, no allocation per point.
 *
 * <p>A box is drawn on its outer faces, from {@code min} to {@code max + 1} on each axis, so a
 * one-block box outlines that block. The eight corners are drawn once and each of the twelve
 * edges gets particles every {@code step} blocks between them. When that would pass the
 * budget, the spacing grows until it fits: the edges stay complete, only sparser. A box too big
 * to draw is marked by a small cross on each corner.</p>
 */
final class SelectionRenderer {

    /** Offsets of the points on each arm of a corner cross. */
    private static final double[] CROSS = {-0.5, -0.25, 0.25, 0.5};
    /** Points of the eight corner crosses: the corner and four per axis. */
    static final int CROSS_POINTS = 8 * (1 + 3 * CROSS.length);

    private SelectionRenderer() {
    }

    /** Receives one particle position. */
    @FunctionalInterface
    interface PointSink {

        void at(double x, double y, double z);
    }

    /** The outline of {@code box}; returns the number of points sent. */
    static int outline(Cuboid box, double step, int budget, PointSink sink) {
        return outline(box.minX(), box.minY(), box.minZ(), box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0,
                step, budget, sink);
    }

    static int outline(double x0, double y0, double z0, double x1, double y1, double z1,
                       double step, int budget, PointSink sink) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int between = Math.max(0, budget - 8);
        double spacing = step;
        if (inner(dx, dy, dz, spacing) > between) {
            // Each edge holds at most length / spacing points between its ends.
            spacing = between == 0 ? Double.POSITIVE_INFINITY : Math.max(step, 4 * (dx + dy + dz) / between);
        }
        int sent = 0;
        for (int i = 0; i < 8; i++) {
            sink.at((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
            sent++;
        }
        for (int i = 0; i < 4; i++) {
            double ya = (i & 1) == 0 ? y0 : y1;
            double za = (i & 2) == 0 ? z0 : z1;
            sent += edgeX(x0, dx, ya, za, spacing, sink);
            double xb = (i & 1) == 0 ? x0 : x1;
            double zb = (i & 2) == 0 ? z0 : z1;
            sent += edgeY(xb, y0, dy, zb, spacing, sink);
            double xc = (i & 1) == 0 ? x0 : x1;
            double yc = (i & 2) == 0 ? y0 : y1;
            sent += edgeZ(xc, yc, z0, dz, spacing, sink);
        }
        return sent;
    }

    /** A cross on each corner of {@code box}; only the corners when the budget is smaller than the crosses. */
    static int corners(Cuboid box, int budget, PointSink sink) {
        double x0 = box.minX();
        double y0 = box.minY();
        double z0 = box.minZ();
        double x1 = box.maxX() + 1.0;
        double y1 = box.maxY() + 1.0;
        double z1 = box.maxZ() + 1.0;
        boolean crosses = budget >= CROSS_POINTS;
        int sent = 0;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? x0 : x1;
            double y = (i & 2) == 0 ? y0 : y1;
            double z = (i & 4) == 0 ? z0 : z1;
            sink.at(x, y, z);
            sent++;
            if (crosses) {
                for (double d : CROSS) {
                    sink.at(x + d, y, z);
                    sink.at(x, y + d, z);
                    sink.at(x, y, z + d);
                    sent += 3;
                }
            }
        }
        return sent;
    }

    /** Points between the ends of all twelve edges at {@code spacing}. */
    static long inner(double dx, double dy, double dz, double spacing) {
        return 4 * (between(dx, spacing) + between(dy, spacing) + between(dz, spacing));
    }

    /** Points strictly between the ends of an edge of {@code length}: at spacing, 2 spacing, ... */
    private static long between(double length, double spacing) {
        if (!(spacing > 0) || Double.isInfinite(spacing) || length <= spacing) {
            return 0;
        }
        // The small margin keeps a last point that lands on the end (1.5 / 0.5) from doubling the corner.
        long whole = (long) Math.ceil(length / spacing - 1e-9) - 1;
        return Math.max(0, whole);
    }

    private static int edgeX(double x0, double length, double y, double z, double spacing, PointSink sink) {
        long n = between(length, spacing);
        for (long k = 1; k <= n; k++) {
            sink.at(x0 + k * spacing, y, z);
        }
        return (int) n;
    }

    private static int edgeY(double x, double y0, double length, double z, double spacing, PointSink sink) {
        long n = between(length, spacing);
        for (long k = 1; k <= n; k++) {
            sink.at(x, y0 + k * spacing, z);
        }
        return (int) n;
    }

    private static int edgeZ(double x, double y, double z0, double length, double spacing, PointSink sink) {
        long n = between(length, spacing);
        for (long k = 1; k <= n; k++) {
            sink.at(x, y, z0 + k * spacing);
        }
        return (int) n;
    }
}
