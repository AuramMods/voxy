package me.cortex.voxy.client.core.rendering;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

public final class ShadowReceiverFrustum {
    private ShadowReceiverFrustum() {}

    public static void write(Viewport<?> viewport, long address) {
        MemoryUtil.memSet(address, 0, 4 * 16);
        if (Boolean.getBoolean("voxy.debug.skipShadowReceiverFrustum")) return;

        var camera = CapturedRenderingState.INSTANCE;
        var projection = new Matrix4f(camera.getGbufferProjection());
        var view = new Matrix4f(camera.getGbufferModelView());
        var shadow = viewport.vanillaProjection.mul(viewport.modelView, new Matrix4f());
        if (!projection.isFinite() || !view.isFinite() || !shadow.isFinite() || !shadow.isAffine()
                || projection.m03() != 0.0f || projection.m13() != 0.0f
                || projection.m23() == 0.0f || projection.m33() != 0.0f) return;
        projection.invert();
        view.invert();
        if (!projection.isFinite() || !view.isFinite()) return;

        // Use the entire camera ray cone. Shadow receivers can include water and fog
        // between surfaces, so opaque camera depth alone does not define the cone.
        var shadowFromView = shadow.mul(view, new Matrix4f());
        var eye = shadowFromView.getTranslation(new Vector3f());
        var rays = new Vector3f[4];
        for (int corner = 0; corner < 4; ++corner) {
            float x = (corner & 1) == 0 ? -1.0625f : 1.0625f;
            float y = (corner & 2) == 0 ? -1.0625f : 1.0625f;
            rays[corner] = shadowFromView.transformDirection(projection.transformProject(new Vector3f(x, y, 0.0f)));
        }
        for (int i = 0; i < rays.length; ++i) {
            var direction = rays[i];
            float length = (float) Math.hypot(direction.x, direction.y);
            if (!(length > 1.0e-7f)) continue;
            float nx = -direction.y / length;
            float ny = direction.x / length;
            boolean positive = true;
            boolean negative = true;
            for (var ray : rays) {
                float side = nx * ray.x + ny * ray.y;
                positive &= side >= -1.0e-7f;
                negative &= side <= 1.0e-7f;
            }
            if (positive == negative) continue;
            if (negative) {
                nx = -nx;
                ny = -ny;
            }

            // Allow receiver position offsets and alternate near-object projections.
            float dx = nx * shadow.m00() + ny * shadow.m01();
            float dy = nx * shadow.m10() + ny * shadow.m11();
            float dz = nx * shadow.m20() + ny * shadow.m21();
            float padding = 12.0f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            long plane = address + i * 16L;
            MemoryUtil.memPutFloat(plane, nx);
            MemoryUtil.memPutFloat(plane + 4, ny);
            MemoryUtil.memPutFloat(plane + 8, nx * eye.x + ny * eye.y - padding);
        }
    }
}
