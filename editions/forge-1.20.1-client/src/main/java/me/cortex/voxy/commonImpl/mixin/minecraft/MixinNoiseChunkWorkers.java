package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.server.ChunkWorkerUsage;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class MixinNoiseChunkWorkers {
    // Vanilla dispatches the expensive noise and biome work outside the chunk mailboxes.
    @ModifyArg(method = {"createBiomes", "fillFromNoise"}, at = @At(value = "INVOKE", target =
            "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            index = 1, require = 0)
    private Executor voxy$trackNoiseWorker(Executor executor) {
        return ChunkWorkerUsage.track(executor);
    }
}
