package com.mochi_753.steadychunks.light;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.bootstrap.ModuleStates;

/**
 * 光照兼容探测，对应开发计划 §8.1。
 * <p>
 * 启动时检测：
 * <ul>
 *   <li>Bye-Pregen YALight 光照模块是否启用</li>
 *   <li>其他已知光照引擎是否安装</li>
 *   <li>是否存在已知冲突注入</li>
 * </ul>
 * <p>
 * 如果已有兼容光照算法：
 * <ul>
 *   <li>SteadyChunks 仅管理任务预算、优先级和完成整形</li>
 *   <li>不替换算法内部</li>
 * </ul>
 * <p>
 * 探测结果通过 {@link LightCompatDecision} 描述 SteadyChunks 应采取的策略。
 */
public final class LightCompatProbe {

    private LightCompatProbe() {
    }

    /**
     * 执行光照兼容探测。
     * <p>
     * 基于已采集的 {@link ModuleStates}（由 {@code CompatibilityProbe} 填充），
     * 避免重复反射加载。
     *
     * @param states 启动时探测的模块状态快照
     * @return 光照兼容决策
     */
    public static LightCompatDecision probe(ModuleStates states) {
        // 1. Bye-Pregen YALight 光照引擎已启用
        if (states.yalightEnabled()) {
            SteadyChunks.LOGGER.info("SteadyChunks 光照兼容：检测到 Bye-Pregen YALight，算法模块让路，仅管理预算与完成整形");
            return new LightCompatDecision(
                    LightEngine.YALIGHT,
                    false, // 不替换算法
                    true,  // 管理预算
                    true,  // 管理完成整形
                    "Bye-Pregen YALight 已启用，SteadyChunks 仅做调度与整形"
            );
        }

        // 2. 检测其他已知光照引擎（通过类存在性）
        KnownLightEngine other = detectOtherLightEngines();
        if (other != null) {
            SteadyChunks.LOGGER.info("SteadyChunks 光照兼容：检测到 {}，算法模块让路", other.displayName);
            return new LightCompatDecision(
                    other.engine,
                    false,
                    true,
                    true,
                    other.displayName + " 已启用，SteadyChunks 仅做调度与整形"
            );
        }

        // 3. 检测已知冲突注入（未来扩展点）
        if (detectKnownConflicts()) {
            SteadyChunks.LOGGER.warn("SteadyChunks 光照兼容：检测到已知冲突注入，关闭光照调度避免风险");
            return new LightCompatDecision(
                    LightEngine.UNKNOWN,
                    false,
                    false,
                    false,
                    "检测到已知冲突注入，关闭光照调度"
            );
        }

        // 4. 无第三方光照引擎：使用原版光照，SteadyChunks 全权管理预算与整形
        SteadyChunks.LOGGER.info("SteadyChunks 光照兼容：未检测到第三方光照引擎，使用原版光照并全权管理调度");
        return new LightCompatDecision(
                LightEngine.VANILLA,
                false, // 不替换算法（1.0 不自研光照算法）
                true,  // 管理预算
                true,  // 管理完成整形
                "原版光照，SteadyChunks 管理预算与完成整形"
        );
    }

    /**
     * 检测其他已知光照引擎。
     * <p>
     * 当前无已知第三方光照引擎（除 YALight），返回 null。
     * 后续发现新的光照模组时在此扩展。
     */
    private static KnownLightEngine detectOtherLightEngines() {
        // 扩展点：未来检测 Starlight 等光照模组
        // NeoForge 1.21.1 已内置 Starlight，无需检测
        return null;
    }

    /**
     * 检测已知冲突注入。
     * <p>
     * 当前无已知冲突，返回 false。后续发现冲突时在此扩展。
     */
    private static boolean detectKnownConflicts() {
        return false;
    }

    /**
     * 已知光照引擎描述（内部使用）。
     */
    private record KnownLightEngine(LightEngine engine, String displayName) {
    }

    /** 光照引擎类型 */
    public enum LightEngine {
        /** 原版光照（NeoForge 1.21.1 内置 Starlight） */
        VANILLA,
        /** Bye-Pregen YALight */
        YALIGHT,
        /** 其他已知光照引擎 */
        OTHER,
        /** 未知（冲突或检测失败） */
        UNKNOWN
    }

    /**
     * 光照兼容决策。
     *
     * @param engine              检测到的光照引擎
     * @param replaceAlgorithm    是否替换光照算法（1.0 始终 false）
     * @param manageBudget        是否管理光照任务预算
     * @param manageCommitShaping 是否管理完成整形
     * @param description         人类可读的策略描述
     */
    public record LightCompatDecision(
            LightEngine engine,
            boolean replaceAlgorithm,
            boolean manageBudget,
            boolean manageCommitShaping,
            String description
    ) {
    }
}
