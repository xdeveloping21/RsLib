package com.rs.lib.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The particle geometry: complete edges, a budget that spaces points out instead of cutting edges. */
class SelectionRendererTest {

    private final List<double[]> points = new ArrayList<>();

    private int outline(Cuboid box, double step, int budget) {
        points.clear();
        return SelectionRenderer.outline(box, step, budget, (x, y, z) -> points.add(new double[] {x, y, z}));
    }

    @Test
    void oneBlockIsOutlinedOnItsOuterFaces() {
        int sent = outline(Cuboid.of("w", 4, 64, -2, 4, 64, -2), 0.5, 2000);
        // Eight corners and one point in the middle of each of the twelve edges.
        assertEquals(20, sent);
        assertEquals(20, points.size());
        Set<String> corners = new HashSet<>();
        for (double[] p : points) {
            assertTrue(p[0] >= 4 && p[0] <= 5 && p[1] >= 64 && p[1] <= 65 && p[2] >= -2 && p[2] <= -1);
            if ((p[0] == 4 || p[0] == 5) && (p[1] == 64 || p[1] == 65) && (p[2] == -2 || p[2] == -1)) {
                corners.add(p[0] + "," + p[1] + "," + p[2]);
            }
        }
        assertEquals(8, corners.size(), "every corner, once");
    }

    @Test
    void pointsAreSpacedByTheStepWithoutDoublingTheCorners() {
        // A 3 x 1 x 1 box: edges of 3 along X (5 points between ends at 0.5), 1 along Y and Z (1 each).
        assertEquals(8 + 4 * 5 + 4 * 1 + 4 * 1, outline(Cuboid.of("w", 0, 0, 0, 2, 0, 0), 0.5, 2000));
        // 1.5 / 0.5 lands exactly on the end: that point is the corner, not drawn twice.
        assertEquals(2, SelectionRenderer.inner(1.5, 0, 0, 0.5) / 4);
    }

    @Test
    void overTheBudgetThePointsSpreadOutButEveryEdgeIsKept() {
        Cuboid box = Cuboid.of("w", 0, 0, 0, 99, 9, 49);
        int sent = outline(box, 0.5, 300);
        assertTrue(sent <= 300, "sent " + sent);
        assertTrue(sent > 200, "the budget is used, not wasted: " + sent);
        // Every one of the twelve edges still has points between its corners.
        int[] perAxis = new int[3];
        for (double[] p : points) {
            boolean xEdge = (p[1] == 0 || p[1] == 10) && (p[2] == 0 || p[2] == 50) && p[0] > 0 && p[0] < 100;
            boolean yEdge = (p[0] == 0 || p[0] == 100) && (p[2] == 0 || p[2] == 50) && p[1] > 0 && p[1] < 10;
            boolean zEdge = (p[0] == 0 || p[0] == 100) && (p[1] == 0 || p[1] == 10) && p[2] > 0 && p[2] < 50;
            perAxis[0] += xEdge ? 1 : 0;
            perAxis[1] += yEdge ? 1 : 0;
            perAxis[2] += zEdge ? 1 : 0;
        }
        assertTrue(perAxis[0] >= 4 && perAxis[1] >= 4 && perAxis[2] >= 4, java.util.Arrays.toString(perAxis));
        // A budget too small for anything but the corners.
        assertEquals(8, outline(box, 0.5, 8));
    }

    @Test
    void aBoxTooBigToDrawGetsACrossOnEachCorner() {
        List<double[]> marks = new ArrayList<>();
        Cuboid box = Cuboid.of("w", 0, 0, 0, 999, 255, 999);
        assertEquals(SelectionRenderer.CROSS_POINTS, SelectionRenderer.corners(box, 2000,
                (x, y, z) -> marks.add(new double[] {x, y, z})));
        assertEquals(104, marks.size());
        assertEquals(8, SelectionRenderer.corners(box, 50, (x, y, z) -> { }), "only the corners under a tiny budget");
    }
}
