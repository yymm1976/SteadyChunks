package com.mochi_753.steadychunks.bootstrap;

import org.jetbrains.annotations.Nullable;

/**
 * Mixin 应用决策中心。
 * <p>
 * 由 MixinPlugin 实现类调用 {@link #shouldApplyMixin(String)} 决定是否应用某个 Mixin。
 * 决策依据是 {@link ModuleStates} 快照，由 {@link ModuleBootstrap} 在启动时填充。
 * <p>
 * 当前为占位实现，所有 Mixin 默认应用；Phase 2+ 实现具体 MixinPlugin 时按 mixin 类名前缀
 * 查找对应模块，根据 ModuleStates 决定是否让路（参见 compatibility-ownership.md §3 Mixin 冲突矩阵）。
 */
public final class MixinGate {
    @Nullable
    private static ModuleStates states;

    private MixinGate() {
    }

    /**
     * 启动时填充状态快照；仅 {@link ModuleBootstrap} 调用一次。
     */
    public static void initialize(ModuleStates detected) {
        states = detected;
    }

    /**
     * MixinPlugin 调用：是否应用指定 Mixin。
     * <p>
     * 当前实现：bootstrap 完成前默认应用，完成后也默认应用（暂无需要让路的 Mixin）。
     * Phase 2+ 添加实际 Mixin 时，按类名前缀匹配模块并查 ModuleStates 决定让路。
     *
     * @param mixinClassName Mixin 类的全限定名
     * @return true 表示应用，false 表示跳过
     */
    public static boolean shouldApplyMixin(String mixinClassName) {
        if (states == null) {
            return true;
        }
        // TODO Phase 2+：按 mixinClassName 前缀查找模块，根据 states 决定让路
        // 示例：
        // if (mixinClassName.startsWith("com.mochi_753.steadychunks.mixin.io.") && states.byepregenPresent) {
        //     return false; // Bye-Pregen 已重写 IO，SteadyChunks 让路
        // }
        return true;
    }
}
