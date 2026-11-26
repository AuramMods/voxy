package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRefinementTest {
    @Test
    void zoomOutReleasesDetailRequestsAndAllowsTheSameParentToRefineAgain() {
        var watcher = new SectionUpdateRouter();
        watcher.setCallbacks(pos -> {}, pos -> {}, section -> {});
        var nodes = new NodeManager(32, new GeometryStub(), watcher);
        int level = WorldEngine.MAX_LOD_LAYER;
        long parent = WorldEngine.getWorldSectionId(level, 1, 0, 0);
        long child = WorldEngine.getWorldSectionId(level - 1, 2, 0, 0);
        var frustum = new FrustumIntersection(new Matrix4f().scaling(0.0001f));
        var zoomed = new RefinementView(0, 0, 0, 4, frustum);
        var wide = new RefinementView(0, 0, 0, 0.25, frustum);

        nodes.insertTopLevelNode(parent);
        nodes.processGeometryResult(new BuiltSection(parent, (byte) 1, 0, new MemoryBuffer(8), null, null));
        nodes.processRequest(parent, zoomed);
        assertTrue(watcher.get(child) != 0);
        assertTrue(nodes.pruneCameraRefinements(wide));
        assertEquals(0, watcher.get(child));
        assertTrue(watcher.get(parent) != 0);

        nodes.processRequest(parent, wide);
        assertEquals(0, watcher.get(child));
        nodes.processRequest(parent, zoomed);
        assertTrue(watcher.get(child) != 0);
        assertFalse(nodes.pruneCameraRefinements(zoomed));
        nodes.removeTopLevelNode(parent);
    }

    private static final class GeometryStub implements IGeometryManager {
        @Override
        public int uploadSection(BuiltSection section) {
            section.free();
            return 0;
        }

        @Override
        public int uploadReplaceSection(int oldId, BuiltSection section) {
            throw new AssertionError("Unexpected mesh replacement");
        }

        @Override
        public void removeSection(int id) {}

        @Override
        public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
            throw new AssertionError("Unexpected mesh download");
        }
    }
}
