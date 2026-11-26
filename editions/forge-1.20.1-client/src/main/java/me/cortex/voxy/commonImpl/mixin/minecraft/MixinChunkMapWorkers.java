package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.server.ChunkWorkerUsage;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.Executor;

@Mixin(ChunkMap.class)
abstract class MixinChunkMapWorkers {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static Executor voxy$trackChunkMailboxWorkers(Executor executor) {
        return ChunkWorkerUsage.track(executor);
    }

    @ModifyArg(method = "readChunk", at = @At(value = "INVOKE", target =
            "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            index = 1, require = 0)
    private Executor voxy$trackChunkReadWorker(Executor executor) {
        return ChunkWorkerUsage.track(executor);
    }
}
