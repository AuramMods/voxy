package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(value = ShaderPackSourceNames.class, remap = false)
public class MixinShaderPackSourceNames {
    @Unique
    private static String[] voxy$programFiles() {
        // Oculus calls this while initializing ShaderPackSourceNames.
        return new String[] {"voxy.json", "voxy_opaque.glsl", "voxy_translucent.glsl", "voxy_taa.glsl", "voxy_shadow.glsl"};
    }

    @WrapOperation(method = "findPotentialStarts", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableList;builder()Lcom/google/common/collect/ImmutableList$Builder;"))
    private static ImmutableList.Builder<String> voxy$injectVoxyShaderPatch(Operation<ImmutableList.Builder<String>> original){
        var builder = original.call();
        builder.add(voxy$programFiles());
        return builder;
    }

    @Inject(method = "findPresentSources", at = @At("RETURN"), cancellable = true)
    private static void voxy$findProgramDirectorySources(ImmutableList.Builder<AbsolutePackPath> starts,
                                                          Path packRoot,
                                                          AbsolutePackPath directory,
                                                          ImmutableList<String> candidates,
                                                          CallbackInfoReturnable<Boolean> cir) throws IOException {
        AbsolutePackPath programDirectory = directory.resolve("program");
        Path programPath = programDirectory.resolved(packRoot);
        if (!Files.isDirectory(programPath)) {
            return;
        }

        boolean found = false;
        for (String file : voxy$programFiles()) {
            if (Files.isRegularFile(programPath.resolve(file))) {
                starts.add(programDirectory.resolve(file));
                found = true;
            }
        }
        if (found && !cir.getReturnValue()) {
            cir.setReturnValue(true);
        }
    }
}
