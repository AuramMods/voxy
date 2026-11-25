package me.cortex.voxy.client.mixin;

import me.cortex.voxy.client.compat.seedgen.SeedGenBootstrap;
import me.cortex.voxy.client.compat.seedgen.SeedGenCalls;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private boolean seedGenCompatible;
    private boolean valkyrienSkiesInstalled;
    private boolean nvidiumInstalled;
    private boolean irisInstalled;
    private boolean createInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        // Use loader metadata here. Initializing Voxy's config serializers during
        // mixin selection can load Minecraft classes before their mixins are prepared.
        var loadingMods = LoadingModList.get();
        if (loadingMods == null) return;
        if (FMLLoader.getDist().isClient()) {
            var seedGenFile = loadingMods.getModFileById(SeedGenBootstrap.MOD_ID);
            seedGenCompatible = seedGenFile != null && seedGenFile.getMods().stream().anyMatch(mod ->
                    SeedGenBootstrap.MOD_ID.equals(mod.getModId()) && SeedGenBootstrap.supports(mod.getVersion().toString()));
        }
        valkyrienSkiesInstalled = loadingMods.getModFileById("valkyrienskies") != null;
        nvidiumInstalled = loadingMods.getModFileById("nvidium") != null;
        irisInstalled = loadingMods.getModFileById("iris") != null || loadingMods.getModFileById("oculus") != null;
        createInstalled = loadingMods.getModFileById("create") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.contains(".iris.") || irisInstalled;
    }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (seedGenCompatible) mixins.add(SeedGenBootstrap.MIXIN);
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }
        if (createInstalled) {
            mixins.add("create.MixinVisualizationManagerImpl");
            mixins.add("create.AccessorAbstractEntityVisual");
            mixins.add("create.MixinSafeBlockEntityRenderer");
            mixins.add("create.MixinSmartBlockEntity");
            mixins.add("create.MixinContraptionEntityRenderer");
            mixins.add("create.MixinContraptionVisual");
            mixins.add("create.MixinContraptionShapeRevision");
            mixins.add("create.MixinContraptionBlockChange");
            mixins.add("create.MixinCarriageShapeRevision");
            mixins.add("create.MixinCarriageBlockChange");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.startsWith("me.cortex.voxy.client.mixin.seedgen.")) {
            SeedGenCalls.apply(targetClass, false);
        }
    }
}
