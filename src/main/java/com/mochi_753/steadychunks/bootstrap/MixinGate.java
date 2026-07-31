package com.mochi_753.steadychunks.bootstrap;

import org.jetbrains.annotations.Nullable;

/**
 * Mixin 应用决策中心，对应开发计划 P0-5 修复。
 * <p>
 * 决策分两类：
 * <ul>
 *   <li><b>早期静态决策</b>：MixinPlugin 在类转换阶段调用 {@link #shouldApplyMixin}，
 *       此时模组构造函数可能尚未执行，{@link #states} 为 null。
 *       此时必须安全默认：<b>拒绝</b>有冲突风险的 Mixin，<b>接受</b>无冲突的诊断类 Mixin。</li>
 *   <li><b>晚期运行决策</b>：模组构造完成后 {@link #states} 已填充，
 *       可按模块前缀精确判断让路。</li>
 * </ul>
 * <p>
 * 核心安全原则：Mixin 在类加载和转换阶段工作，不能依赖之后才执行的普通模组构造阶段。
 * null 时默认拒绝有风险的 Mixin，避免在探测完成前注入冲突代码。
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
     *   <li>若 {@link #states} 为 null（早期静态阶段，bootstrap 未完成）：
     *       仅允许诊断类 Mixin（telemetry / diagnostics 包），拒绝其他可能有冲突的 Mixin。</li>
     *   <li>若 {@link #states} 已填充（晚期运行阶段）：
     *       按类名前缀匹配模块，查 ModuleStates 决定让路。</li>
     * </ol>
     *
     * @param mixinClassName Mixin 类的全限定名
     * @return true 表示应用，false 表示跳过
     */
    public static boolean shouldApplyMixin(String mixinClassName) {
        if (states == null) {
            // 早期静态决策：bootstrap 未完成时，仅允许无冲突风险的诊断类 Mixin
            // telemetry / diagnostics 包仅观测不修改原版逻辑，可安全应用
            // 其他 Mixin（io / structure / features / light 等）可能与 FastNoise/Bye-Pregen 冲突，必须拒绝
            return mixinClassName.contains(".telemetry.")
                    || mixinClassName.contains(".diagnostics.");
        }
        // 晚期运行决策：按模块前缀查 ModuleStates 让路
        // Bye-Pregen 已重写 IO，SteadyChunks IO Mixin 让路
        if (mixinClassName.startsWith("com.mochi_753.steadychunks.mixin.io.") && states.byepregenPresent()) {
            return false;
        }
        // FastNoise 已重写噪声生成，SteadyChunks 噪声相关 Mixin 让路
        if (mixinClassName.startsWith("com.mochi_753.steadychunks.mixin.noise.") && states.fastNoisePresent()) {
            return false;
        }
        // 默认应用（诊断、遥测、无冲突的调度 Mixin）
        return true;
    }
}
