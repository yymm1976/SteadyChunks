package com.mochi_753.steadychunks.governor;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.ResourceType;
import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.QuantileEstimator;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自适应资源治理器，对应开发计划 §4 交付物。
 * <p>
 * 整合 {@link PressureSnapshot}、{@link AimdController}、{@link EmergencyMode}，
 * 从 {@link ChunkFlightRecorder} 采集指标，输出到 {@link ChunkScheduler} 调整 permit。
 * <p>
 * 控制周期：每 20 tick（1 秒）评估一次，避免过频振荡（§4.5）。
 */
public final class ResourceGovernor {
    private static ResourceGovernor instance;

    private final AimdController aimd = new AimdController();
    private final EmergencyMode emergency = new EmergencyMode();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile RunMode runMode = RunMode.INTEGRATED;

    /** 控制周期（tick），§4.5 要求不短于任务典型完成时间 */
    private static final int CONTROL_PERIOD_TICKS = 20;
    private int ticksSinceLastControl = 0;

    /** 阈值配置，从 CommonConfig 同步 */
    private volatile PressureSnapshot.ThresholdConfig thresholds;

    // P0-6 修复：滑动窗口增量指标，替代生命周期累计值
    /** 上次控制周期的 GC 暂停累计值（用于计算增量） */
    private long prevGcPauseMs = 0;
    /** 上次控制周期的长帧累计值（用于计算增量） */
    private long prevFramesOver50ms = 0;

    private ResourceGovernor() {
        thresholds = new PressureSnapshot.ThresholdConfig(
                40.0, 48.0, 0.75, 0.78, 50.0, 150.0
        );
    }

    public static synchronized ResourceGovernor getInstance() {
        if (instance == null) {
            instance = new ResourceGovernor();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        SteadyChunks.LOGGER.info("SteadyChunks 资源治理器: {}", on ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setRunMode(RunMode mode) {
        this.runMode = mode;
    }

    /**
     * 每 tick 调用。每 {@link #CONTROL_PERIOD_TICKS} tick 执行一次控制循环。
     */
    public void tick() {
        if (!enabled.get()) {
            return;
        }
        ticksSinceLastControl++;
        if (ticksSinceLastControl < CONTROL_PERIOD_TICKS) {
            return;
        }
        ticksSinceLastControl = 0;

        // 1. 采集压力快照
        PressureSnapshot snapshot = collectSnapshot();
        // 2. 评估压力等级
        PressureSnapshot.PressureLevel level = snapshot.evaluateLevel(thresholds);
        // 3. AIMD 控制器调整 permit
        Map<ChunkStatus, Integer> newPermits = aimd.tick(level);
        // 4. 紧急模式评估
        EmergencyMode.ModeState modeState = emergency.tick(level);

        // 5. 应用到调度器
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        if (scheduler.isEnabled()) {
            applyPermits(scheduler, newPermits, modeState);
        }

        // 6. 日志（§4.5：所有控制变化写入日志）
        if (level != PressureSnapshot.PressureLevel.HEALTHY) {
            SteadyChunks.LOGGER.info("SteadyChunks 治理器: level={} emergency={} msptP95={}ms",
                    level, emergency.isActive(), String.format("%.1f", snapshot.p95Mspt));
        }
    }

    /**
     * 从 ChunkFlightRecorder 采集指标构建压力快照。
     * <p>
     * P0-6 修复：GC 暂停和长帧使用控制周期增量，而非生命周期累计值。
     * 旧实现将累计值除以控制周期，旧卡顿会持续影响所有未来窗口。
     * 新实现计算本周期增量，只反映最近一个控制窗口的实际压力。
     */
    private PressureSnapshot collectSnapshot() {
        var sys = ChunkFlightRecorder.system();
        var cf = ChunkFlightRecorder.clientFrames();
        var fs = ChunkFlightRecorder.fullSend();

        QuantileEstimator mspt = sys.mspt();
        QuantileEstimator frameTime = cf.frameTime();

        double p95Mspt = mspt.quantile(0.95) / 1_000_000.0;
        double p99Mspt = mspt.quantile(0.99) / 1_000_000.0;
        double p95Frame = frameTime.quantile(0.95) / 1_000_000.0;
        double p99Frame = frameTime.quantile(0.99) / 1_000_000.0;

        // P0-6 修复：长帧率使用本周期增量，而非累计值除以周期数
        long currentFramesOver50ms = cf.framesOver50ms();
        long frameDelta = Math.max(0, currentFramesOver50ms - prevFramesOver50ms);
        prevFramesOver50ms = currentFramesOver50ms;
        double longFrameRate = frameDelta;

        double heapPressure = sys.heapUsedPeak() > 0
                ? (double) sys.heapUsedCurrent() / Runtime.getRuntime().maxMemory()
                : 0;

        // P0-6 修复：GC 暂停使用本周期增量，而非累计值
        long currentGcPauseMs = sys.gcPauseMs();
        long gcDelta = Math.max(0, currentGcPauseMs - prevGcPauseMs);
        prevGcPauseMs = currentGcPauseMs;

        return new PressureSnapshot(
                p95Mspt, p99Mspt, p95Frame, p99Frame,
                longFrameRate, sys.processCpuLoad(), sys.worldgenCpuLoad(),
                heapPressure, gcDelta,
                fs.fullQueueDepthCurrent(), 0, // visibleChunkGaps 需要客户端反馈
                fs.sendQueueDepthCurrent(), cf.sectionCompileQueueDepth()
        );
    }

    /**
     * 将 AIMD 输出应用到调度器的阶段限制。
     * <p>
     * P1-2 修复（第 6 轮）：过渡期<b>只控制 NOISE_HEAVY 资源组</b>，不再按 ChunkStatus
     * 聚合写共享桶。旧实现按 ResourceType 聚合（同组取最小值）后写入共享桶，而
     * BIOMES/NOISE/SURFACE 共享 NOISE_HEAVY 桶：AIMD 乘性下降会降低 BIOMES/SURFACE，
     * 加性增长轮换列表却不含它们，导致共享桶被旧值永久压住（min 聚合后额度卡死，
     * 继续增加 NOISE 也无效）。
     * <p>
     * 目前仅 NOISE 阶段真实接入调度器，因此只应用 {@code ChunkStatus.NOISE} 对应的
     * permit，不维护还未真实接入阶段的 AIMD 状态。后续接入 STRUCTURE_STARTS/FEATURES
     * 时再恢复按 {@link ResourceType} 的完整控制。
     * <p>
     * P0-4 修复：紧急模式使用 admissionPaused 标志而非 permit=0。
     * 旧实现 setMaxPermits(0) 被 ResourceBucket 钳制为 1，实际仍允许 1 个任务。
     */
    private void applyPermits(ChunkScheduler scheduler, Map<ChunkStatus, Integer> permits,
                              EmergencyMode.ModeState modeState) {
        // P0-4 修复：紧急模式使用 admissionPaused 标志
        boolean shouldPause = modeState.emergency() && !modeState.recovering();
        scheduler.setAdmissionPaused(shouldPause);

        // P1-2 修复（第 6 轮）：只应用 NOISE 的 permit 到 NOISE_HEAVY 桶
        Integer noisePermit = permits.get(ChunkStatus.NOISE);
        if (noisePermit == null) {
            return;
        }
        if (modeState.recovering()) {
            noisePermit = (int) Math.max(1, noisePermit * modeState.recoveryRatio());
        }
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, noisePermit);
    }

    /**
     * 从配置同步治理器参数。
     */
    public void syncFromConfig() {
        setEnabled(CommonConfig.GOVERNOR_ENABLED.get());
        thresholds = new PressureSnapshot.ThresholdConfig(
                CommonConfig.TARGET_P95_MSPT.get(),
                CommonConfig.HARD_MSPT.get(),
                CommonConfig.TARGET_PROCESS_CPU.get(),
                CommonConfig.HEAP_PRESSURE.get(),
                CommonConfig.LONG_FRAME_MS.get(),
                CommonConfig.EMERGENCY_FRAME_MS.get()
        );
        // P1-10：配置单位改为秒，1 秒 = 1 控制周期（20 tick）
        aimd.setIncreaseCooldownPeriods(CommonConfig.INCREASE_COOLDOWN_SECONDS.get());
        aimd.setDecreaseCooldownPeriods(CommonConfig.DECREASE_COOLDOWN_SECONDS.get());
    }

    // 诊断访问器
    public AimdController aimd() { return aimd; }
    public EmergencyMode emergency() { return emergency; }
    public RunMode runMode() { return runMode; }
}
