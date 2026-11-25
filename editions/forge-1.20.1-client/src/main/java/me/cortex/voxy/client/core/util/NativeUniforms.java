package me.cortex.voxy.client.core.util;

import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector2ic;
import org.joml.Vector3fc;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.joml.Vector4ic;

import static org.lwjgl.system.MemoryUtil.memFloatBuffer;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

public final class NativeUniforms {
    private NativeUniforms() {}

    // JOML 1.10.5's address methods assume a JVM-specific field layout. Preserve
    // the packed GPU representation without reading from Java object offsets.
    public static void writeNative(long address, Matrix4fc matrix) {
        matrix.get(memFloatBuffer(address, 16));
    }

    public static void writeNative(long address, Vector2fc vector) {
        memPutFloat(address, vector.x());
        memPutFloat(address + 4, vector.y());
    }

    public static void writeNative(long address, Vector3fc vector) {
        writeNative2(address, vector.x(), vector.y());
        memPutFloat(address + 8, vector.z());
    }

    public static void writeNative(long address, Vector4fc vector) {
        writeNative2(address, vector.x(), vector.y());
        memPutFloat(address + 8, vector.z());
        memPutFloat(address + 12, vector.w());
    }

    private static void writeNative2(long address, float x, float y) {
        memPutFloat(address, x);
        memPutFloat(address + 4, y);
    }

    public static void writeNative(long address, Vector2ic vector) {
        memPutInt(address, vector.x());
        memPutInt(address + 4, vector.y());
    }

    public static void writeNative(long address, Vector3ic vector) {
        memPutInt(address, vector.x());
        memPutInt(address + 4, vector.y());
        memPutInt(address + 8, vector.z());
    }

    public static void writeNative(long address, Vector4ic vector) {
        memPutInt(address, vector.x());
        memPutInt(address + 4, vector.y());
        memPutInt(address + 8, vector.z());
        memPutInt(address + 12, vector.w());
    }
}
