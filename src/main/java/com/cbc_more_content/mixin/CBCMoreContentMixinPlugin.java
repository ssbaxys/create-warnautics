package com.cbc_more_content.mixin;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Gates optional compat mixins (Sable / Offroad) so the mod loads without them. */
public class CBCMoreContentMixinPlugin implements IMixinConfigPlugin {
    private static final boolean SABLE_LOADED = LoadingModList.get().getModFileById("sable") != null;
    private static final boolean OFFROAD_LOADED = LoadingModList.get().getModFileById("offroad") != null;
    private static final boolean SODIUM_LOADED = LoadingModList.get().getModFileById("sodium") != null;

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".compat.sable.")) {
            return SABLE_LOADED;
        }
        if (mixinClassName.contains(".compat.offroad.")) {
            return OFFROAD_LOADED;
        }
        if (mixinClassName.contains(".compat.sodium.")) {
            return SODIUM_LOADED;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
