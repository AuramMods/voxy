package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.server.ServerLodService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class MixinServerLevel {
    @Inject(method = "onBlockStateChange", at = @At("TAIL"))
    private void voxy$recordEdit(BlockPos position, BlockState previous, BlockState current, CallbackInfo callback) {
        if (previous != current) ServerLodService.blockChanged((ServerLevel) (Object) this, position);
    }
}
