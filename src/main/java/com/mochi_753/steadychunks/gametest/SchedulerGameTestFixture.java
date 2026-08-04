package com.mochi_753.steadychunks.gametest;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightDiagnostics;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightTaskRecord;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.ResourceType;
import com.mochi_753.steadychunks.scheduler.Watchdog;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.function.BooleanSupplier;

/**
 * 阶段 2：统一 GameTest 隔离 fixture——所有调度器/恢复测试共享的清理、清洁断言
 * 与辅助方法。
 * <p>
 * 背景（round 14 实测）：单个测试断言失败中止后，残留的 Future、permit、Watchdog
 * 线程、override 或 probe 会污染后续批次（clear_all 出现实际等待数 2、
 * noiseAvail=0、inflight=1 级联十连挂）。不再依靠每个测试手写不一致的清理逻辑，
 * 统一在此恢复全局状态；清洁断言在测试结尾校验无残留。
 * <p>
 * 关键清理顺序：<b>先清队列再取消暂停</b>——{@code setAdmissionPaused(false)} 会
 * 同步触发 requestDrain，队列残留任务会被 drain 抢走并以永不完成的原操作执行
 * （inflight/permit 永久残留，实测级联失败）。
 */
public final class SchedulerGameTestFixture {
    private SchedulerGameTestFixture() {
    }

    /**
     * 统一清理（幂等）：停止恢复线程、清 override/探针、清活动批次与停服批次、
     * 先清队列再复位暂停/启用/permit、复位诊断指标。
     * <p>
     * 测试断言失败中止时同样必须执行（finally 中调用）。
     * <p>
     * 第 11 轮实测（拆分后首轮 FAIL）：clearAll 的 error 完成会让原版对真实生成
     * 任务<b>立即批量重试</b>（重试风暴：12ms 内 ~150 个重新入队）。若测试随后
     * 暂停准入，风暴任务会涌入队列并污染精确计数断言（capture 捞走 150 个真实
     * 任务，"第一级后队列应清空"失败）。因此清理最后一步：启用调度器 + 等待
     * 重试任务正常 drain+执行（最多 3 秒），测试开始时队列干净。
     */
    public static void resetGlobalState() {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        // 1. 清测试钩子（生产路径不可见，先于一切复位）
        wd.setStallCheckIgnorePausedForTest(false);
        wd.setPreCaptureProbe(null);
        scheduler.setResumeExecutorOverride(null);
        scheduler.setEnqueueProbeHook(null);
        // 2. 停恢复线程（含停服批次异步处置收尾）
        wd.stopRecoveryThread();
        // 3. 先清队列（error 完成所有等待任务 → 终态 → lease/permit 释放）
        //    再取消暂停（unpause 同步 drain 时队列已空，无残留任务被抢走）
        scheduler.clearAll(new IllegalStateException("GameTest fixture cleanup"));
        scheduler.setAdmissionPaused(false);
        // 4. 重试风暴排空：NOISE 限恢复 8 → 启用 → 等风暴任务到达并正常 drain
        //    + 执行完成。等待采用"先等到达再等排空"，不用固定 sleep 掩盖竞态。
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        scheduler.setEnabled(true);
        long deadline = System.currentTimeMillis() + 100;
        while (scheduler.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        waitForQueueDrain(scheduler);
        scheduler.setEnabled(false);
        scheduler.resetDiagnostics();
        wd.resetRecoveryMetrics();
        // 5. 阶段 3 追踪诊断：清理完成后活动任务/终态异常应归零。非零说明有任务
        //    未走完终态路径（在途泄漏的早期信号）——只告警不阻断（诊断语义，
        //    避免把环境噪声升级为硬失败；阶段 4 停滞检测会用同一数据出事故快照）。
        int traceActive = InflightDiagnostics.activeTaskCount();
        long traceAnomalies = InflightDiagnostics.terminalAnomalyCount();
        if (traceActive != 0 || traceAnomalies != 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("SteadyChunks 追踪诊断: 活动任务=").append(traceActive)
                    .append(" 终态异常=").append(traceAnomalies);
            for (InflightTaskRecord r : InflightDiagnostics.activeSnapshot()) {
                sb.append("\n  taskId=").append(r.taskId)
                        .append(" last=").append(r.lastType)
                        .append(" ageMs=").append((System.nanoTime() - r.lastNanos) / 1_000_000)
                        .append(" thread=").append(r.lastThreadId)
                        .append(" dim=").append(InflightDiagnostics.dimensionName(r.dimensionId))
                        .append(" chunk=(").append(r.chunkX).append(',').append(r.chunkZ).append(')');
            }
            SteadyChunks.LOGGER.warn(sb.toString());
        }
    }

    /**
     * 清洁状态断言：非故意泄漏测试结束时统一校验——队列/在途/未受控/permit/
     * 活动批次/停服批次/生命周期计数/钩子全部归零。故意创建未完成 Future 的
     * 测试必须先在 finally 中完成或取消占位 Future，再调用本方法。
     */
    public static void assertCleanState() {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        if (scheduler.pendingCount() != 0) {
            throw new GameTestAssertException("清洁状态失败: pendingCount=" + scheduler.pendingCount());
        }
        if (scheduler.inflightCount() != 0) {
            throw new GameTestAssertException("清洁状态失败: inflightCount=" + scheduler.inflightCount());
        }
        if (scheduler.uncontrolledNoiseActive() != 0) {
            throw new GameTestAssertException("清洁状态失败: uncontrolledNoiseActive=" + scheduler.uncontrolledNoiseActive());
        }
        if (scheduler.cpuPermitsMax() - scheduler.cpuPermitsAvailable() != 0) {
            throw new GameTestAssertException("清洁状态失败: globalPermitInUse="
                    + (scheduler.cpuPermitsMax() - scheduler.cpuPermitsAvailable()));
        }
        int noiseInUse = scheduler.stageLimiter().permit(ChunkStatus.NOISE) == null ? 0
                : scheduler.stageLimiter().permit(ChunkStatus.NOISE).maxPermits()
                - scheduler.stageLimiter().permit(ChunkStatus.NOISE).availablePermits();
        if (noiseInUse != 0) {
            throw new GameTestAssertException("清洁状态失败: noisePermitInUse=" + noiseInUse);
        }
        if (wd.hasActiveRecoveryBatch()) {
            throw new GameTestAssertException("清洁状态失败: activeRecovery 非空");
        }
        if (wd.shutdownBatchCount() != 0) {
            throw new GameTestAssertException("清洁状态失败: shutdownBatchCount=" + wd.shutdownBatchCount());
        }
        if (coordinator.globalTaskCount() != 0) {
            throw new GameTestAssertException("清洁状态失败: lifecycle activeTasks=" + coordinator.globalTaskCount());
        }
        if (scheduler.resumeExecutorOverride() != null || scheduler.enqueueProbeHook() != null) {
            throw new GameTestAssertException("清洁状态失败: 测试钩子未清空");
        }
        if (wd.isStallCheckIgnorePausedForTest() || wd.preCaptureProbe() != null) {
            throw new GameTestAssertException("清洁状态失败: Watchdog 测试钩子未清空");
        }
        if (scheduler.isAdmissionPaused() || scheduler.isBypassMode() || scheduler.isFailOpen()) {
            throw new GameTestAssertException("清洁状态失败: paused/bypass/failOpen 未复位");
        }
    }

    /**
     * 获取测试用原版 ChunkMap 与一个可见区块的 GenerationChunkHolder。
     * 必须在 {@code setEnabled(true)} 之前调用（真实生成会被调度器拦截并占用 permit）。
     */
    public static GenerationChunkHolder obtainHolderForLevel(ServerLevel level) {
        ChunkMap map = level.getChunkSource().chunkMap;
        ChunkAccess chunk = level.getChunk(0, 0);
        GenerationChunkHolder holder = map.getVisibleChunkIfPresent(chunk.getPos().toLong());
        if (holder == null) {
            throw new IllegalStateException("测试区块 holder 不存在: " + chunk.getPos());
        }
        return holder;
    }

    /** 等待调度器等待队列排空（最多 3 秒）。 */
    public static void waitForQueueDrain(ChunkScheduler scheduler) {
        for (int i = 0; i < 300 && scheduler.pendingCount() > 0; i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 轮询等待条件成立（最多 3 秒）——异步终态/指标断言用。 */
    public static void awaitTrue(BooleanSupplier condition, String message) {
        for (int i = 0; i < 300; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GameTestAssertException(message + "（等待被中断）");
            }
        }
        throw new GameTestAssertException(message);
    }

    /** 便捷：主世界 holder（辅助方法入口保持兼容）。 */
    public static GenerationChunkHolder obtainHolder(GameTestHelper helper) {
        return obtainHolderForLevel(helper.getLevel());
    }
}
