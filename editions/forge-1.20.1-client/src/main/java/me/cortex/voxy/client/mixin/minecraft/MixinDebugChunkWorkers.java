package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.server.ChunkWorkerUsage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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

@Mixin(value = DebugScreenOverlay.class, priority = 1100)
abstract class MixinDebugChunkWorkers {
    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void voxy$addChunkWorkerUsage(CallbackInfoReturnable<List<String>> callback) {
        var value = voxy$chunkWorkerUsage();
        if (value == null) return;
        var lines = new ArrayList<>(callback.getReturnValue());
        lines.add(0, Component.translatable("voxy.debug.chunk_workers").getString() + " " + value.getString());
        callback.setReturnValue(lines);
    }

    @Dynamic("Right-hand system panel added by BetterF3")
    @Inject(method = "betterF3$newRightText", at = @At("RETURN"), remap = false, require = 0)
    private void voxy$addBetterF3ChunkWorkerUsage(CallbackInfoReturnable<List<Component>> callback) {
        var value = voxy$chunkWorkerUsage();
        if (value == null) return;
        callback.getReturnValue().add(0, Component.translatable("voxy.debug.chunk_workers")
                .withStyle(ChatFormatting.GOLD).append(" ").append(value.withStyle(ChatFormatting.AQUA)));
    }

    @Unique
    private static MutableComponent voxy$chunkWorkerUsage() {
        if (Minecraft.getInstance().getSingleplayerServer() == null) return null;
        var sample = ChunkWorkerUsage.sample();
        return sample == null ? null : Component.translatable("voxy.debug.chunk_worker_usage",
                sample.percent(), sample.busy(), sample.total());
    }
}
