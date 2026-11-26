package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.lang.reflect.Method;

public final class DynamicFpsCompat {
    private static Hooks hooks = findHooks();

    private record Hooks(Method powerState, Method targetFrameRate, Method isDisabled) {}

    private DynamicFpsCompat() {}

    public static boolean isIdleLimited(int activeFrameLimit) {
        var current = hooks;
        if (current == null) return false;
        try {
            if ((boolean) current.isDisabled().invoke(null)) return false;
            var state = (Enum<?>) current.powerState().invoke(null);
            if (state == null) return false;
            return switch (state.name()) {
                case "ABANDONED", "UNFOCUSED", "HOVERED", "INVISIBLE" -> {
                    int target = (int) current.targetFrameRate().invoke(null);
                    yield target >= 0 && target < activeFrameLimit;
                }
                default -> false;
            };
        } catch (ReflectiveOperationException | LinkageError e) {
            hooks = null;
            Logger.warn("Could not read Dynamic FPS idle state", e);
            return false;
        }
    }

    private static Hooks findHooks() {
        if (!VoxyCommon.getPlatformUtil().isModLoaded("dynamic_fps")) return null;
        try {
            var mod = Class.forName("dynamic_fps.impl.DynamicFPSMod", false, DynamicFpsCompat.class.getClassLoader());
            return new Hooks(mod.getMethod("powerState"), mod.getMethod("targetFrameRate"), mod.getMethod("isDisabled"));
        } catch (ReflectiveOperationException | LinkageError e) {
            Logger.warn("Dynamic FPS idle state is unavailable", e);
            return null;
        }
    }
}
