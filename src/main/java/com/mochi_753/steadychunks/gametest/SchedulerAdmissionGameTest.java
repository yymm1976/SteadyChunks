package com.mochi_753.steadychunks.gametest;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.ResourceType;
import com.mochi_753.steadychunks.scheduler.StageLimiter;
import com.mochi_753.steadychunks.scheduler.Watchdog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NOISE 准入、等待队列、清理与 permit 门控测试。
 * <p>
 * 阶段 2：测试拆分自 SchedulerAdmissionGameTest——共享
 * {@link SchedulerGameTestFixture}（统一清理/清洁断言/辅助方法），
 * 不再复制 reset 逻辑。
 */
@GameTestHolder("steadychunks")
public class SchedulerAdmissionGameTest {

    @GameTest(template = "empty", batch = "steady_admission_paused", timeoutTicks = 600)
    public void admissionPausedShouldBlockNewTasks(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 2);
        scheduler.setAdmissionPaused(true);

        // 暂停状态下新任务应进入等待队列，而不是执行
        CompletableFuture<ChunkResult<ChunkAccess>> gated = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        helper.assertTrue(!gated.isDone(), "admissionPaused 时任务应被挂起");
        helper.assertTrue(scheduler.pendingCount() == 1, "暂停时等待队列深度应为 1");

        // 即使 tick 多次，暂停任务也不应被 drain 启动
        for (int i = 0; i < 5; i++) {
            scheduler.tick();
        }
        helper.assertTrue(!gated.isDone(), "暂停期间任务不应被 drain 启动");

        // 恢复准入后任务应经 worldgen mailbox 完成
        scheduler.setAdmissionPaused(false);

        helper.succeedWhen(() -> {
            helper.assertTrue(gated.isDone(), "恢复准入后任务应完成");
            helper.assertTrue(scheduler.pendingCount() == 0, "恢复后等待队列应清空");
            resetScheduler(scheduler);
        });
        });
    }


    @GameTest(template = "empty", batch = "steady_clear_all", timeoutTicks = 600)
    public void clearAllShouldCompleteWaitingTasks(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // P0-3 修复：用未完成 Future 占满 permit，制造一个真正的等待任务
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, map, holder, () -> firstUnderlying);

        CompletableFuture<ChunkResult<ChunkAccess>> waiting = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        try {
            helper.assertTrue(scheduler.pendingCount() == 1,
                    "应存在 1 个等待任务（实际=" + scheduler.pendingCount()
                            + " noiseAvail=" + scheduler.stageLimiter().permit(ChunkStatus.NOISE).availablePermits()
                            + " inflight=" + scheduler.inflightCount()
                            + " cpuAvail=" + scheduler.cpuPermitsAvailable() + "）");

            // 模拟维度卸载/停服：clearAll 应以 error result 完成等待任务
            // （第 5 轮修复：异常完成会让原版 setFatalException，破坏真实区块生成链）
            scheduler.clearAll(new IllegalStateException("Dimension unload"));
            helper.assertTrue(waiting.isDone(), "clearAll 后等待任务应被完成");
            helper.assertTrue(!waiting.join().isSuccess(), "clearAll 后等待任务应以 error result 完成");
            helper.assertTrue(scheduler.pendingCount() == 0, "clearAll 后等待队列应清空");
        } finally {
            // 第 14 轮防御：断言失败中止也不得残留占位任务——残留的未完成任务占
            // global + NOISE permit，且 enabled/limit 未复位，会级联卡死后续批次
            // （实测：dim_poll_window 的 obtainHolder 真实生成排队 → getChunk 死等）
            if (!firstUnderlying.isDone()) {
                firstUnderlying.complete(ChunkResult.of(helper.getLevel().getChunk(0, 0)));
            }
            resetScheduler(scheduler);
        }
        helper.succeed();
        });
    }


    /**
     * P0/P1 修复验证（第 5 轮）：并发入队与 clearAll 的生命周期屏障。
     * 多线程反复 controlAdmission，主线程并发 clearAll，最终：
     * 等待队列清空、所有代理均完成、permit 全部释放、无残留任务。
     */
    @GameTest(template = "empty", batch = "steady_clear_concurrent", timeoutTicks = 2400)
    public void clearConcurrentAdmissionShouldNotLeaveTasks(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        // 预取已加载区块引用（后台线程不允许调用 getChunk 强制加载）
        ChunkAccess chunk00 = helper.getLevel().getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        scheduler.resetDiagnostics();

        // 后台提交线程：每线程 perThread 次 controlAdmission（completedFuture 立即完成）
        int threads = 4;
        int perThread = 100;
        List<CompletableFuture<ChunkResult<ChunkAccess>>> all = new CopyOnWriteArrayList<>();
        AtomicInteger remaining = new AtomicInteger(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        CompletableFuture<ChunkResult<ChunkAccess>> p = scheduler.controlAdmission(
                                ChunkStatus.NOISE, false, map, holder,
                                () -> CompletableFuture.completedFuture(ChunkResult.of(chunk00)));
                        all.add(p);
                    }
                } finally {
                    remaining.decrementAndGet();
                }
            });
        }

        // 主线程并发执行数次清空（resetForReload 语义：清理后恢复接收）
        for (int i = 0; i < 10; i++) {
            scheduler.clearAll(new IllegalStateException("并发清理 #" + i));
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(remaining.get() == 0, "提交线程应全部结束");
            helper.assertTrue(all.size() == threads * perThread, "代理总数应完整，实际: " + all.size());
            for (CompletableFuture<ChunkResult<ChunkAccess>> p : all) {
                helper.assertTrue(p.isDone(), "代理应全部完成（成功或异常）");
            }
            helper.assertTrue(scheduler.pendingCount() == 0, "等待队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "在途任务应归零");
            helper.assertTrue(scheduler.cpuPermitsAvailable() == scheduler.cpuPermitsMax(),
                    "全局 permit 应全部释放");
            pool.shutdownNow();
            resetScheduler(scheduler);
        });
        });
    }


    /**
     * P0-2 修复验证（第 5 轮）：紧急暂停状态下关闭调度器，积压任务仍应按 bypass
     * 有节奏恢复（bypass 优先级高于 admissionPaused），不会永久挂起。
     */
    @GameTest(template = "empty", batch = "steady_paused_disable", timeoutTicks = 600)
    public void pausedThenDisableShouldBypass(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        scheduler.setAdmissionPaused(true);

        // 暂停状态下入队一个任务（permit 充足，但 paused 阻止执行）
        CompletableFuture<ChunkResult<ChunkAccess>> gated = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        helper.assertTrue(!gated.isDone(), "paused 时任务应入队挂起");
        helper.assertTrue(scheduler.pendingCount() == 1, "等待队列深度应为 1");

        // 关闭调度器：bypass 应优先于 paused，逐步恢复积压任务
        scheduler.setEnabled(false);
        helper.assertTrue(scheduler.isBypassMode(), "关闭调度器应进入 bypass 模式");

        helper.succeedWhen(() -> {
            helper.assertTrue(gated.isDone(), "bypass 应恢复被暂停挡住的任务");
            helper.assertTrue(scheduler.pendingCount() == 0, "bypass 后等待队列应清空");
            resetScheduler(scheduler);
        });
        });
    }


    @GameTest(template = "empty", batch = "steady_noise_permit", timeoutTicks = 600)
    public void noisePermitOneShouldQueueAndResume(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // 1. 第一个 NOISE 任务：permit=1 可用，立即执行。
        // P0-3 修复：使用未完成的 Future 占住 permit（completedFuture 会立即释放 permit）。
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> first = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder, () -> firstUnderlying);
        helper.assertTrue(!first.isDone(), "第一个任务应占用 permit 并保持进行中");

        // 2. 第二个 NOISE 任务：permit 已耗尽，应进入等待队列（代理 Future 未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> second = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        helper.assertTrue(!second.isDone(), "第二个 NOISE 任务在 permit=1 时应被挂起");
        helper.assertTrue(scheduler.pendingCount() == 1, "等待队列深度应为 1，实际: " + scheduler.pendingCount());

        // 3. 完成第一个任务 → whenComplete 释放 permit → 第二个任务经 worldgen mailbox 恢复
        firstUnderlying.complete(ChunkResult.of(helper.getLevel().getChunk(0, 0)));

        // P0-3 修复：succeedWhen 逐 Tick 轮询，不在服务器线程阻塞等待。
        // 清理放在回调内，确保链路完全结束后才重置全局状态。
        helper.succeedWhen(() -> {
            helper.assertTrue(second.isDone(), "permit 释放后第二个任务应恢复完成");
            helper.assertTrue(scheduler.pendingCount() == 0, "恢复后等待队列应清空");
            resetScheduler(scheduler);
        });
        });
    }


    /**
     * 第 9 轮 P0-1 修复验证：直接获准（permit 充足，不入队）的 NOISE 任务
     * 同样计入完整生命周期计数——注册点已前移到 NOISE 准入入口（controlAdmission），
     * direct、排队（enqueuePending）与 fail-open（runUncontrolled）三条路径共享同一 lease。
     * <p>
     * 旧实现只在 enqueuePending 内注册：direct 任务 inflightCount 增加但
     * globalTaskCount/dimensionTaskCounts 不增加——停服等待 globalTaskCount 归零
     * 会漏掉正在运行的直接获准任务。本测试断言：direct 任务从注册到原 Future
     * 完成期间计数保持 +1，完成后归零。
     */
    @GameTest(template = "empty", batch = "steady_direct_count", timeoutTicks = 600)
    public void directAdmissionShouldBeCountedUntilCompletion(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkAccess chunk = helper.getLevel().getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        scheduler.resetDiagnostics();

        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        ResourceKey<Level> overworld = helper.getLevel().dimension();
        int before = coordinator.globalTaskCount();
        int dimBefore = coordinator.dimensionTaskCount(overworld);

        // permit 充足 → direct 路径（不入队）
        CompletableFuture<ChunkResult<ChunkAccess>> underlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> task = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder, () -> underlying);

        helper.assertTrue(scheduler.pendingCount() == 0, "permit 充足的任务不应入队");
        helper.assertTrue(!task.isDone(), "原 Future 未完成时直接获准任务应保持进行中");
        helper.assertTrue(coordinator.globalTaskCount() == before + 1,
                "直接获准任务应计入全局任务计数（完整生命周期）");
        helper.assertTrue(coordinator.dimensionTaskCount(overworld) == dimBefore + 1,
                "直接获准任务应计入维度任务计数");

        // 原 Future 完成：计数应归零（direct 路径 lease 绑定原 Future 终态）
        underlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(task.isDone(), "原 Future 完成后任务应完成");
        helper.assertTrue(coordinator.globalTaskCount() == before,
                "任务完成后全局任务计数应归零");
        helper.assertTrue(coordinator.dimensionTaskCount(overworld) == dimBefore,
                "任务完成后维度任务计数应归零");

        resetScheduler(scheduler);
        helper.succeed();
        });
    }


    /**
     * P0-1 修复验证（第 5/6 轮）：向 worldgen mailbox 提交恢复任务失败时，
     * 必须释放已持有的组合 permit 并以 <b>error result 正常完成</b>代理 Future。
     * 通过 {@link ChunkScheduler#setResumeExecutorOverride} 注入一个抛
     * {@code RejectedExecutionException} 的执行器模拟"mailbox 已停止接收"。
     * <p>
     * 第 6 轮修复：不再异常完成（completeExceptionally）——原版
     * {@code GenerationChunkHolder.lambda$applyStep$0} 会把异常完成的 Future 视为
     * 致命（MinecraftServer.setFatalException），与清理路径统一为 error result。
     */
    @GameTest(template = "empty", batch = "steady_mailbox_fail", timeoutTicks = 600)
    public void mailboxFailureShouldReturnErrorResult(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        // 第 11 轮缓解：等待批次结构加载的真实任务排空——override 生效期间真实任务
        // drain 会撞上模拟的 mailbox 拒绝/停滞而被 error 完成，区块卡 NOISE 之前 →
        // 后续强制同步加载（BE tick getChunk）死等忙转。
        waitForQueueDrain(scheduler);
        // 测试注入：提交恢复任务时抛异常（模拟 mailbox 停止接收/第三方改动）
        scheduler.setResumeExecutorOverride(runnable -> {
            throw new java.util.concurrent.RejectedExecutionException("模拟 mailbox 已停止接收");
        });

        // 第一个任务占住 permit（未完成，Server thread 直接执行，不走 mailbox）
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, map, holder, () -> firstUnderlying);

        // 第二个任务入队等待
        CompletableFuture<ChunkResult<ChunkAccess>> second = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        helper.assertTrue(!second.isDone(), "第二个任务应等待");
        helper.assertTrue(scheduler.pendingCount() == 1, "等待队列深度应为 1");

        // 完成第一个任务 → permit 释放 → drain 恢复第二个 → submitResumed 提交失败
        firstUnderlying.complete(ChunkResult.of(helper.getLevel().getChunk(0, 0)));

        helper.succeedWhen(() -> {
            helper.assertTrue(second.isDone(), "提交失败后代理应被完成");
            helper.assertTrue(!second.join().isSuccess(),
                    "提交失败后代理应以 error result 完成（异常完成会触发原版 setFatalException）");
            helper.assertTrue(scheduler.pendingCount() == 0, "提交失败后队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "提交失败后无在途任务");
            helper.assertTrue(scheduler.cpuPermitsAvailable() == scheduler.cpuPermitsMax(),
                    "提交失败后全局 permit 应全部释放");
            scheduler.setResumeExecutorOverride(null);
            resetScheduler(scheduler);
        });
        });
    }


    /**
     * 第 10 轮 P0-1 修复验证：originalOperation 同步抛异常时统一失败路径——
     * 释放组合 permit（global + NOISE）、回退 inflightCount、关闭 registration。
     * 旧实现只关 registration：一次同步异常永久消耗一个全局 permit + 一个 NOISE
     * permit + 一个 inflightCount，重复几次后所有 NOISE 都进等待队列。
     */
    @GameTest(template = "empty", batch = "steady_sync_throw", timeoutTicks = 600)
    public void originalOperationThrowsSynchronously(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();

        int beforeInflight = scheduler.inflightCount();
        int beforeCpu = scheduler.cpuPermitsAvailable();
        int beforeNoise = scheduler.stageLimiter().permit(ChunkStatus.NOISE).availablePermits();

        CompletableFuture<ChunkResult<ChunkAccess>> task = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> { throw new IllegalStateException("同步抛异常（P0-1 测试）"); });

        helper.assertTrue(task.isDone(), "同步异常任务应立即终态");
        helper.assertTrue(task.isCompletedExceptionally(), "任务应异常完成");
        helper.assertTrue(scheduler.inflightCount() == beforeInflight,
                "inflight 应回退到原值（P0-1：不得泄漏）");
        helper.assertTrue(scheduler.cpuPermitsAvailable() == beforeCpu,
                "全局 permit 应全部释放（P0-1）");
        helper.assertTrue(scheduler.stageLimiter().permit(ChunkStatus.NOISE).availablePermits() == beforeNoise,
                "NOISE permit 应全部释放（P0-1）");
        helper.assertTrue(coordinator.globalTaskCount() == 0,
                "registration 应归零（P0-1）");

        resetScheduler(scheduler);
        helper.succeed();
        });
    }

    // ---- 阶段 2：统一 fixture 委托（辅助方法与清理由 SchedulerGameTestFixture 提供） ----
    private static GenerationChunkHolder obtainHolder(GameTestHelper helper) {
        return SchedulerGameTestFixture.obtainHolder(helper);
    }

    private static GenerationChunkHolder obtainHolderForLevel(ServerLevel level) {
        return SchedulerGameTestFixture.obtainHolderForLevel(level);
    }

    private static void waitForQueueDrain(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.waitForQueueDrain(scheduler);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
        SchedulerGameTestFixture.awaitTrue(condition, message);
    }

    /** 重置调度器全局状态（统一清理 + 清洁硬断言 + 追踪复位，见 SchedulerGameTestFixture）。 */
    private static void resetScheduler(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.forceCleanupAfterFailure();
    }
}
