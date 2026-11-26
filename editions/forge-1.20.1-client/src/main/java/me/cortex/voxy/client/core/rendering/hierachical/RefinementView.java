package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.world.WorldEngine;
import org.joml.FrustumIntersection;

record RefinementView(double x, double y, double z, double lodDistanceScaleSquared, FrustumIntersection frustum) {
    static RefinementView capture(Viewport<?> viewport, float subdivisionSize) {
        return new RefinementView(viewport.cameraX, viewport.cameraY, viewport.cameraZ,
                viewport.getLodDistanceScaleSquared(subdivisionSize), new FrustumIntersection(viewport.MVP));
    }

    boolean needsRefinement(long pos) {
        int size = 32 << WorldEngine.getLevel(pos);
        double minX = (double) WorldEngine.getX(pos) * size - this.x;
        double minY = (double) WorldEngine.getY(pos) * size - this.y;
        double minZ = (double) WorldEngine.getZ(pos) * size - this.z;
        double dx = distanceToAxis(minX, size);
        double dy = distanceToAxis(minY, size);
        double dz = distanceToAxis(minZ, size);

        // Match traversal's projected-size test with headroom for camera motion and delayed GPU readbacks.
        if (Math.max(1, dx * dx + dy * dy + dz * dz) >= this.lodDistanceScaleSquared * size * size * 1.21) {
            return false;
        }
        float padding = 8;
        return this.frustum.testAab((float) minX - padding, (float) minY - padding, (float) minZ - padding,
                (float) (minX + size) + padding, (float) (minY + size) + padding, (float) (minZ + size) + padding);
    }

    private static double distanceToAxis(double min, int size) {
        return Math.max(0, Math.max(min, -min - size));
    }
}
