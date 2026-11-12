package com.mochi_753.steadychunks.mixin;

import com.mochi_753.steadychunks.bootstrap.MixinGate;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 加载决策插件，对接 {@link MixinGate}。
 * <p>
 * Mixin 类在 mixins.json 中声明，但是否实际应用由 {@link MixinGate#shouldApplyMixin} 决定。
 * 这样可以根据兼容性探测结果（FastNoise/Bye-Pregen 是否安装）让路。
 */
public final class SteadyChunksMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return MixinGate.shouldApplyMixin(mixinClassName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
