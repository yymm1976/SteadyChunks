package com.mochi_753.steadychunks.governor;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.config.CommonConfig;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
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
        double longFrameRate = cf.framesOver50ms() > 0
                ? (double) cf.framesOver50ms() / Math.max(1, CONTROL_PERIOD_TICKS / 20.0)
                : 0;
        double heapPressure = sys.heapUsedPeak() > 0
                ? (double) sys.heapUsedCurrent() / Runtime.getRuntime().maxMemory()
                : 0;

        return new PressureSnapshot(
                p95Mspt, p99Mspt, p95Frame, p99Frame,
                longFrameRate, sys.processCpuLoad(), sys.worldgenCpuLoad(),
                heapPressure, sys.gcPauseMs(),
                fs.fullQueueDepthCurrent(), 0, // visibleChunkGaps 需要客户端反馈
                fs.sendQueueDepthCurrent(), cf.sectionCompileQueueDepth()
        );
    }

    /**
     * 将 AIMD 输出应用到调度器的阶段限制。
     * 紧急模式下额外限制 NOISE / FEATURES。
     */
    private void applyPermits(ChunkScheduler scheduler, Map<ChunkStatus, Integer> permits,
                              EmergencyMode.ModeState modeState) {
        for (var entry : permits.entrySet()) {
            int permit = entry.getValue();
            // 紧急模式下限制 NOISE / FEATURES
            if (modeState.emergency() && !modeState.recovering()) {
                if (entry.getKey() == ChunkStatus.NOISE
                        || entry.getKey() == ChunkStatus.FEATURES) {
                    permit = 0; // 暂停启动新的 NOISE / FEATURES
                }
            } else if (modeState.recovering()) {
                // 恢复窗口：按比例提升
                permit = (int) Math.max(1, permit * modeState.recoveryRatio());
            }
            scheduler.stageLimiter().setStageLimit(entry.getKey(), permit);
        }
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
