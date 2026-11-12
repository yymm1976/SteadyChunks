package com.mochi_753.steadychunks.bootstrap;

import org.jetbrains.annotations.Nullable;

/**
 * Mixin 应用决策中心，对应开发计划 P0-5 修复 + 审查两层门控修复。
 * <p>
 * 审查修复：拆分为加载阶段和运行阶段两层门控。
 * <ul>
 *   <li><b>加载阶段</b>（MixinPlugin 类转换时）：
 *       <ul>
 *         <li>遥测/诊断 Mixin：总是安全（只观测不修改）</li>
 *         <li>调度类 Mixin（.server. 包）：总是应用，行为由运行时门控控制。
 *             即使 Mixin 被应用，调度器未启用时也会透传原版路径。</li>
 *         <li>冲突类 Mixin（.noise. / .io.）：仅当 states 已填充且确认冲突时才拒绝。
 *             states == null 时保守拒绝（可能冲突）。</li>
 *       </ul>
 *   </li>
 *   <li><b>运行阶段</b>（Mixin 已应用，代码执行时）：
 *       调度 Mixin 内部通过 {@code ChunkScheduler.getInstance().isEnabled()} 判断是否启用。
 *       未启用时直接 return 走原版路径，不修改任何行为。</li>
 * </ul>
 * <p>
 * 核心原则：调度 Mixin 的"是否应用"与"是否生效"分离。
 * Mixin 总是被应用（加载阶段允许），但行为由运行时门控控制。
 * 这样避免了 states 未初始化时调度 Mixin 被永久拒绝的问题。
 */
public final class MixinGate {
    @Nullable
    private static volatile ModuleStates states;

    private MixinGate() {
    }

    /**
     * 启动时填充状态快照；仅 {@link ModuleBootstrap} 调用一次。
     * 使用 volatile 保证对 MixinPlugin 线程可见。
     */
    public static void initialize(ModuleStates detected) {
        states = detected;
    }

    /**
     * MixinPlugin 调用：是否应用指定 Mixin。
     * <p>
     * 决策逻辑：
     * <ol>
     *   <li>遥测/诊断 Mixin：总是允许（只观测不修改）</li>
     *   <li>调度类 Mixin（.server. 包）：总是允许（行为由运行时门控控制）</li>
     *   <li>冲突类 Mixin（.noise. / .io.）：
     *       <ul>
     *         <li>states 已填充：按冲突检测结果决定让路</li>
     *         <li>states == null：保守拒绝（可能冲突）</li>
     *       </ul>
     *   </li>
     *   <li>其他 Mixin：默认允许</li>
     * </ol>
     *
     * @param mixinClassName Mixin 类的全限定名
     * @return true 表示应用，false 表示跳过
     */
    public static boolean shouldApplyMixin(String mixinClassName) {
        // 遥测/诊断 Mixin：总是安全
        if (mixinClassName.contains(".telemetry.") || mixinClassName.contains(".diagnostics.")) {
            return true;
        }

        // 调度类 Mixin（.server. 包）：总是应用，行为由运行时门控控制
        // 审查修复：不再在 states == null 时拒绝调度 Mixin
        // 调度器未启用时 Mixin 内部透传原版路径，无副作用
        if (mixinClassName.contains(".server.")) {
            return true;
        }

        // 冲突类 Mixin：需要 states 才能判断
        if (states != null) {
            // Bye-Pregen 已重写 IO，SteadyChunks IO Mixin 让路
            if (mixinClassName.startsWith("com.mochi_753.steadychunks.mixin.io.") && states.byepregenPresent()) {
                return false;
            }
            // FastNoise 已重写噪声生成，SteadyChunks 噪声算法 Mixin 让路
            // 注意：这不影响 .server. 包下的调度 Mixin（只门控准入，不修改噪声算法）
            if (mixinClassName.startsWith("com.mochi_753.steadychunks.mixin.noise.") && states.fastNoisePresent()) {
                return false;
            }
            // 默认应用（无冲突的 Mixin）
            return true;
        }

        // states == null 且非遥测/诊断/调度类：保守拒绝（可能冲突）
        return false;
    }
}
