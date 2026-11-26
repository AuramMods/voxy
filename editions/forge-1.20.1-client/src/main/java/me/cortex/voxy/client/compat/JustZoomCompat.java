package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.lang.reflect.Method;

public final class JustZoomCompat {
    private static Method isZooming = findHook();

    private JustZoomCompat() {}

    public static boolean isZooming() {
        var hook = isZooming;
        if (hook == null) return false;
        try {
            return (boolean) hook.invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            isZooming = null;
            Logger.warn("Could not read Just Zoom state", e);
            return false;
        }
    }

    private static Method findHook() {
        if (!VoxyCommon.getPlatformUtil().isModLoaded("justzoom")) return null;
        try {
            return Class.forName("de.keksuccino.justzoom.ZoomHandler", false, JustZoomCompat.class.getClassLoader())
                    .getMethod("isZooming");
        } catch (ReflectiveOperationException | LinkageError e) {
            Logger.warn("Just Zoom state is unavailable", e);
            return null;
        }
    }
}
