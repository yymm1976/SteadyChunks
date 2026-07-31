package com.mochi_753.steadychunks.telemetry;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chunk Flight Recorder：SteadyChunks 诊断观测的汇总入口。
 * <p>
 * 对应开发计划 Phase 2 的核心交付物。聚合所有指标模块，提供：
 * <ul>
 *   <li>全局开关（{@link #setEnabled}）与配置联动</li>
 *   <li>各指标模块的统一访问点</li>
 *   <li>尖峰事件捕获（{@link #spikeBuffer}）</li>
 *   <li>对 {@link ThreadInstrumentation} 的启用联动</li>
 * </ul>
 * <p>
 * 设计原则（计划 §2 验收）：
 * <ul>
 *   <li>诊断关闭时开销低于 1%：通过 {@link #enabled} 短路所有 {@code record} 调用</li>
 *   <li>基础计数开启时低于 3%：使用 {@link LongAdder} 与 {@link QuantileEstimator}</li>
 *   <li>不改变区块生成输出和任务顺序：所有 Mixin 钩子仅观测，不修改返回值</li>
 * </ul>
 */
public final class ChunkFlightRecorder {
    private static final AtomicBoolean enabled = new AtomicBoolean(false);

    /** 各类指标模块，单例 */
    private static final StageMetrics stageMetrics = new StageMetrics();
    private static final StructureMetrics structureMetrics = new StructureMetrics();
    private static final FeatureMetrics featureMetrics = new FeatureMetrics();
    private static final ClientFrameMetrics clientFrameMetrics = new ClientFrameMetrics();
    private static final FullSendMetrics fullSendMetrics = new FullSendMetrics();
    private static final SystemResourceMetrics systemMetrics = new SystemResourceMetrics();
    /** Phase 8：FULL 最终化细分指标 */
    private static final FullFinalizationMetrics fullFinalizationMetrics = new FullFinalizationMetrics();
    /** Phase 9：I/O 各环节细分指标 */
    private static final com.mochi_753.steadychunks.io.IoStageMetrics ioStageMetrics = new com.mochi_753.steadychunks.io.IoStageMetrics();

    /** 尖峰前后完整事件缓冲（计划 §2 风险缓解：尖峰前后才保留完整事件） */
    private static final RingEventBuffer spikeBuffer = new RingEventBuffer(2048);

    /** 高精度分析模式（开销较高，不作为日常默认，计划 §2 验收） */
    private static volatile boolean highDetail = false;

    private ChunkFlightRecorder() {
    }

    /**
     * 启用或关闭诊断。启用时联动 {@link ThreadInstrumentation#ENABLED}。
     */
    public static void setEnabled(boolean on) {
        enabled.set(on);
        ThreadInstrumentation.ENABLED = on;
        SteadyChunks.LOGGER.info("SteadyChunks Chunk Flight Recorder: {}", on ? "enabled" : "disabled");
    }

    public static boolean isEnabled() {
        return enabled.get();
    }

    public static void setHighDetail(boolean on) {
        highDetail = on;
    }

    public static boolean isHighDetail() {
        return highDetail;
    }

    /**
     * 从配置同步开关状态。由 {@code ModuleBootstrap} 在服务器启动时调用。
     */
    public static void syncFromConfig() {
        // Phase 2：基础计数默认开启（开销 < 3%），高精度默认关闭
        boolean basicEnabled = CommonConfig.ENABLED.get() && CommonConfig.BASIC_METRICS.get();
        setEnabled(basicEnabled);
        setHighDetail(CommonConfig.HIGH_DETAIL.get());
    }

    public static StageMetrics stages() {
        return stageMetrics;
    }

    public static StructureMetrics structures() {
        return structureMetrics;
    }

    public static FeatureMetrics features() {
        return featureMetrics;
    }

    public static ClientFrameMetrics clientFrames() {
        return clientFrameMetrics;
    }

    public static FullSendMetrics fullSend() {
        return fullSendMetrics;
    }

    public static SystemResourceMetrics system() {
        return systemMetrics;
    }

    /**
     * Phase 8：FULL 最终化细分指标。
     */
    public static FullFinalizationMetrics fullFinalization() {
        return fullFinalizationMetrics;
    }

    /**
     * Phase 9：I/O 各环节细分指标。
     */
    public static com.mochi_753.steadychunks.io.IoStageMetrics ioStage() {
        return ioStageMetrics;
    }

    public static RingEventBuffer spikeBuffer() {
        return spikeBuffer;
    }

    /**
     * 记录一次尖峰事件（如超长帧、MSPT 突增）。
     */
    public static void recordSpike(long timestampNanos, long encodedId) {
        if (enabled.get()) {
            spikeBuffer.record(timestampNanos, encodedId);
        }
    }

    /**
     * 重置所有指标（如开始新一轮基准）。
     */
    public static void reset() {
        stageMetrics.reset();
        structureMetrics.reset();
        featureMetrics.reset();
        clientFrameMetrics.reset();
        fullSendMetrics.reset();
        systemMetrics.reset();
        fullFinalizationMetrics.reset();
        ioStageMetrics.reset();
        spikeBuffer.reset();
    }
}
