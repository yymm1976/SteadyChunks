package com.mochi_753.steadychunks.bootstrap;

import com.mochi_753.steadychunks.SteadyChunks;
import net.neoforged.fml.ModList;

/**
 * 启动时探测 FastNoise / Bye-Pregen / C2ME / 渲染优化模组的存在性与模块状态。
 * <p>
 * 探测策略遵循 ADR-002 与 compatibility-ownership.md §6：
 * <ul>
 *   <li>modid 检测用于"模组是否存在"</li>
 *   <li>类存在性检测用于"具体模块是否启用"，无法确认时保守假设启用</li>
 *   <li>独立 C2ME（modid `c2me`）触发 ADR-002 互斥；Bye-Pregen vendored C2ME 端口视为 Bye-Pregen 内部实现，不触发互斥</li>
 * </ul>
 * 详细探测表见 compatibility-ownership.md §6.2。
 */
public final class CompatibilityProbe {
    private CompatibilityProbe() {
    }

    /**
     * 执行一次兼容性探测，返回不可变的状态快照。
     */
    public static ModuleStates probe() {
        ModuleStates states = new ModuleStates();

        // FastNoise：Modrinth modid 为 zfastnoise
        states.fastNoisePresent = ModList.get().isLoaded("zfastnoise");

        // Bye-Pregen
        states.byepregenPresent = ModList.get().isLoaded("byepregen");
        if (states.byepregenPresent) {
            // YALight 光照引擎：通过核心类存在性判断
            states.yalightEnabled = isClassPresent("com.moepus.byepregen.yalight.LevelLightEngineYA");
            // Palette 重写：通过 PaletteedContainer Mixin 注入的辅助类判断
            states.paletteRewriteEnabled = isClassPresent("com.moepus.byepregen.PaletteContainer.PaletteedContainerMixin");
        }

        // 独立 C2ME（仅 modid 检测，避免与 Bye-Pregen vendored 端口误判）
        states.c2mePresent = ModList.get().isLoaded("c2me");

        // 渲染优化模组
        states.embeddiumPresent = ModList.get().isLoaded("embeddium");
        states.sodiumPresent = ModList.get().isLoaded("sodium");

        SteadyChunks.LOGGER.info("SteadyChunks 兼容性探测结果：{}", states);
        return states;
    }

    /**
     * 通过反射类加载判断类是否存在；无法加载返回 false（保守起见，对应模块视为未启用）。
     */
    private static boolean isClassPresent(String fqn) {
        try {
            Class.forName(fqn, false, CompatibilityProbe.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
