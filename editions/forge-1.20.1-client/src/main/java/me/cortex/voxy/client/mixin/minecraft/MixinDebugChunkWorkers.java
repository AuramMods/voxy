package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientDebugStats;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Mixin(value = DebugScreenOverlay.class, priority = 1100)
abstract class MixinDebugChunkWorkers {
    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void voxy$addSystemMetrics(CallbackInfoReturnable<List<String>> callback) {
        var metrics = voxy$metrics(false);
        if (metrics.isEmpty()) return;
        var lines = new ArrayList<String>(metrics.size() + callback.getReturnValue().size());
        for (var metric : metrics) lines.add(metric.getString());
        lines.addAll(callback.getReturnValue());
        callback.setReturnValue(lines);
    }

    @Dynamic("Right-hand system panel added by BetterF3")
    @Inject(method = "betterF3$newRightText", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void voxy$addBetterF3SystemMetrics(CallbackInfoReturnable<List<Component>> callback) {
        var metrics = voxy$metrics(true);
        if (metrics.isEmpty()) return;
        metrics.addAll(callback.getReturnValue());
        callback.setReturnValue(metrics);
    }

    @Unique
    private static List<Component> voxy$metrics(boolean colored) {
        var sample = ClientDebugStats.latest();
        if (sample == null) return List.of();
        var lines = new ArrayList<Component>(2);
        if (sample.totalWorkers() > 0) {
            var usage = Component.translatable("voxy.debug.chunk_worker_usage",
                    Math.round(100.0 * sample.busyWorkers() / sample.totalWorkers()),
                    voxy$oneDecimal(sample.busyWorkers()), sample.totalWorkers());
            lines.add(voxy$metric("voxy.debug.chunk_workers", usage, colored));
        }
        if (sample.queuedMeshJobs() >= 0) {
            var lods = Component.translatable("voxy.debug.lod_work", sample.queuedMeshJobs(),
                    voxy$oneDecimal(sample.meshesPerSecond()));
            if (sample.localTerrain()) {
                lods.append(Component.translatable("voxy.debug.lod_local"));
            } else if (sample.remoteStream()) {
                lods.append(Component.translatable("voxy.debug.lod_received",
                        voxy$oneDecimal(sample.receivedSectionsPerSecond())));
            }
            lines.add(voxy$metric("voxy.debug.lods", lods, colored));
        }
        return lines;
    }

    @Unique
    private static Component voxy$metric(String label, MutableComponent value, boolean colored) {
        var title = Component.translatable(label);
        if (colored) {
            title.withStyle(ChatFormatting.GOLD);
            value.withStyle(ChatFormatting.AQUA);
        }
        return title.append(" ").append(value);
    }

    @Unique
    private static String voxy$oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
