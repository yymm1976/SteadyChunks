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
 * 服务器/维度生命周期、lease 计数与维度卸载隔离测试。
 * <p>
 * 阶段 2：测试拆分自 SchedulerAdmissionGameTest——共享
 * {@link SchedulerGameTestFixture}（统一清理/清洁断言/辅助方法），
 * 不再复制 reset 逻辑。
 */
@GameTestHolder("steadychunks")
public class SchedulerLifecycleGameTest {

    /**
     * 第 11 轮 P0/P1 修复验证：旧服务器生命周期的迟到 lease 不得污染新服务器计数。
     * onServerStopped 后开启新生命周期（ServerLifecycle 原子替换），旧任务迟到
     * close 只递减旧 counter——新生命周期计数不受影响（旧实现共享 globalTaskCount，
     * 迟到 close 会把新计数抹掉甚至减成负数）。
     */
    @GameTest(template = "empty", batch = "steady_late_lease", timeoutTicks = 600)
    public void lateOldServerLeaseMustNotAffectNewServerCounter(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkAccess chunk = helper.getLevel().getChunk(0, 0);
        ResourceKey<Level> dim = helper.getLevel().dimension();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();

        // 生命周期 1：任务 A（未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> aUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> taskA = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder, () -> aUnderlying);
        helper.assertTrue(coordinator.globalTaskCount() == 1, "生命周期 1 计数应为 1");
        helper.assertTrue(coordinator.dimensionTaskCount(dim) == 1, "生命周期 1 维度计数应为 1");

        // 停服 → 停止（旧生命周期终结）→ 新服务器启动（生命周期 2 原子替换）
        coordinator.onServerShutdown();
        coordinator.onServerStopped();
        coordinator.onServerStart();
        helper.assertTrue(!coordinator.isShutdownMode(), "新服务器应恢复接收");

        // 生命周期 2：任务 B
        CompletableFuture<ChunkResult<ChunkAccess>> bUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> taskB = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder, () -> bUnderlying);
        helper.assertTrue(!taskB.isDone(), "新任务 B 应被接受");
        helper.assertTrue(coordinator.globalTaskCount() == 1,
                "新生命周期计数应为 1（仅 B；A 在旧 lifecycle）");
        // 第 12 轮 P1 修复验证：维度计数也随生命周期隔离——旧 A 与新 B 不再共享
        // 同一个维度 counter（旧实现维度 Map 全局共享，重叠窗口内为 2 → detectLeaks 假泄漏）
        helper.assertTrue(coordinator.dimensionTaskCount(dim) == 1,
                "新生命周期维度计数应为 1（仅 B；A 在旧 lifecycle 维度计数不污染）");
        helper.assertTrue(coordinator.detectLeaks() == 0,
                "重叠窗口内全局与维度计数必须同代一致（无假泄漏）");

        // 旧任务 A 迟到完成：旧 lease 只递减旧 counter
        aUnderlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(taskA.isDone(), "旧任务 A 应已完成");
        helper.assertTrue(coordinator.globalTaskCount() == 1,
                "旧 lease 迟到 close 不得污染新生命周期计数");
        helper.assertTrue(coordinator.dimensionTaskCount(dim) == 1,
                "旧 lease 迟到 close 不得污染新生命周期维度计数");

        // B 完成：归零
        bUnderlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(taskB.isDone(), "新任务 B 应已完成");
        helper.assertTrue(coordinator.globalTaskCount() == 0,
                "全部完成后全局计数应归零");
        helper.assertTrue(coordinator.dimensionTaskCount(dim) == 0,
                "全部完成后维度计数应归零");

        resetScheduler(scheduler);
        helper.succeed();
    }


    /**
     * 第 9 轮 P1 修复验证：停服超时强制清理后不再清零任务计数——运行中任务的迟到
     * lease 会把计数正确递减到零（不产生负数）；新服务器生命周期通过 onServerStart()
     * 恢复接收（集成服务器回主菜单后再开新世界的场景）。
     * <p>
     * 旧实现 forceClearAll 执行 dimensionTaskCounts.clear() + globalTaskCount.set(0)：
     * 已运行任务完成后 decrementAndGet 产生负数；且 shutdownMode 无恢复入口，
     * 静态单例永久拒绝后续世界注册。修复后：计数保留由迟到 lease 归零，
     * onServerStart() 恢复 shutdownMode、调度器 accepting 与 I/O 队列状态。
     */
    @GameTest(template = "empty", batch = "steady_server_restart", timeoutTicks = 600)
    public void serverRestartShouldUseNewLifecycleGeneration(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkAccess chunk = helper.getLevel().getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);

        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();

        // 生命周期 1：运行中的任务 A（未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> aUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> taskA = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder, () -> aUnderlying);
        helper.assertTrue(coordinator.globalTaskCount() == 1, "任务 A 注册后全局计数应为 1");

        // 停服：先关注册门 + 立即清空 pending（第 10 轮 P0-3：不在 Server Thread 等待）
        coordinator.onServerShutdown();
        helper.assertTrue(coordinator.isShutdownMode(), "停服后应处于停服模式");
        helper.assertTrue(coordinator.globalTaskCount() == 1,
                "停服强制清理不得清零仍有 lease 的全局计数");
        helper.assertTrue(scheduler.pendingCount() == 0, "停服清理后等待队列应清空");

        // 服务器重启：恢复接收（修复前 shutdownMode 无恢复入口，永久拒绝新世界）
        coordinator.onServerStart();
        helper.assertTrue(!coordinator.isShutdownMode(), "服务器重启后应退出停服模式");

        // 生命周期 2：新任务 B 注册成功
        CompletableFuture<ChunkResult<ChunkAccess>> bUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> taskB = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder, () -> bUnderlying);
        helper.assertTrue(!taskB.isDone(), "重启后新任务 B 应被接受（原 Future 未完成）");
        // 第 11 轮 P0/P1 修复：计数按服务器生命周期隔离——旧任务 A 在旧 lifecycle，
        // globalTaskCount 只反映当前 lifecycle（B）
        helper.assertTrue(coordinator.globalTaskCount() == 1,
                "重启后当前生命周期计数应为 1（仅新任务 B；旧任务 A 在旧 lifecycle 不污染）");

        // 旧任务 A 迟到完成：只递减旧 lifecycle 计数（不产生负数、不影响新计数）
        aUnderlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(taskA.isDone(), "旧任务 A 应已完成");
        helper.assertTrue(coordinator.globalTaskCount() == 1,
                "旧 lease 迟到关闭不得影响新生命周期计数（仍为 B 的 1）");

        // 新任务 B 完成：计数归零
        bUnderlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(taskB.isDone(), "新任务 B 应已完成");
        helper.assertTrue(coordinator.globalTaskCount() == 0,
                "全部任务完成后全局计数应归零");

        resetScheduler(scheduler);
        helper.succeed();
    }


    /**
     * 第 8 轮 P1 修复验证：维度卸载发生在 enqueuePending 已通过维度检查、读取维度代数
     * 之后、任务入队之前的窗口（比"poll 后卸载"更早一个竞态窗口）。
     * <p>
     * 旧实现入队后二次校验只复查全局 lifecycleGeneration：此时全局代数未变，
     * 任务以旧维度代数入队并残留等待队列，继续持有已卸载维度的 ChunkMap/Holder/
     * 原操作/代理 Future。修复后二次校验调用完整 lifecycleValid（全局 accepting +
     * 全局代数 + 维度 accepting + 维度代数），任务立即被移除并以 error result 完成。
     * <p>
     * 竞态窗口由 enqueueProbeHook（测试专用探针）制造：enqueuer 线程停在
     * "维度检查已通过、尚未入队"，主线程 cancelDimension，再放行入队。
     */
    @GameTest(template = "empty", batch = "steady_dim_enqueue_window", timeoutTicks = 600)
    public void dimensionUnloadDuringEnqueueShouldReject(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "下界应已加载");

        GenerationChunkHolder overworldHolder = obtainHolderForLevel(overworld);
        GenerationChunkHolder netherHolder = obtainHolderForLevel(nether);
        ChunkMap overworldMap = overworld.getChunkSource().chunkMap;
        ChunkMap netherMap = nether.getChunkSource().chunkMap;
        ChunkAccess overworldChunk = overworld.getChunk(0, 0);
        ChunkAccess netherChunk = nether.getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // 占唯一 NOISE permit 的任务（主世界，未完成）——下界任务将走 enqueuePending 等待
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, overworldMap, overworldHolder, () -> firstUnderlying);

        // 竞态窗口探针：enqueuer 线程通过维度检查、读取维度代数后阻塞在入队前
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scheduler.setEnqueueProbeHook(() -> {
            reached.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        AtomicReference<CompletableFuture<ChunkResult<ChunkAccess>>> netherTask = new AtomicReference<>();
        Thread enqueuer = new Thread(() -> {
            netherTask.set(scheduler.controlAdmission(
                    ChunkStatus.NOISE, false, netherMap, netherHolder,
                    () -> CompletableFuture.completedFuture(ChunkResult.of(netherChunk))));
        }, "steady-dim-enqueuer");
        enqueuer.setDaemon(true);
        enqueuer.start();

        try {
            helper.assertTrue(reached.await(5, TimeUnit.SECONDS),
                    "enqueuePending 应已通过维度检查并阻塞在入队前");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 卸载下界：关闭生命周期 + 递增维度代数。任务尚未入队，队列 removeIf 找不到它；
        // 放行后入队时二次校验（完整 lifecycleValid 含维度）必须将其立即移除。
        scheduler.cancelDimension(Level.NETHER, "Dimension unloaded");

        release.countDown();
        try {
            enqueuer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler.setEnqueueProbeHook(null);

        CompletableFuture<ChunkResult<ChunkAccess>> task = netherTask.get();
        helper.assertTrue(task != null && task.isDone(), "入队后应立即被二次校验移除并完成");
        helper.assertTrue(!task.join().isSuccess(), "维度已卸载的任务应以 error result 完成");
        helper.assertTrue(scheduler.pendingCount() == 0, "任务不应残留等待队列");
        helper.assertTrue(LifecycleCleanupCoordinator.getInstance().dimensionTaskCount(Level.NETHER) == 0,
                "下界维度任务计数应归零（注册后立即被拒绝关闭 lease）");

        // 完成占位任务，收尾恢复
        firstUnderlying.complete(ChunkResult.of(overworldChunk));
        helper.succeedWhen(() -> {
            helper.assertTrue(scheduler.inflightCount() == 0, "占位任务完成后无在途任务");
            scheduler.openDimension(Level.NETHER);
            resetScheduler(scheduler);
        });
    }


    /**
     * P1-1 修复验证（第 7 轮）：真实双维度隔离——卸载下界只取消下界任务，
     * 主世界任务不受影响并正常完成。
     */
    @GameTest(template = "empty", batch = "steady_dim_isolation", timeoutTicks = 600)
    public void dimensionUnloadShouldCancelOnlyTargetDimension(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "下界应已加载");

        // setEnabled 前获取各维度 holder 与区块引用（避免调度器拦截真实生成）
        GenerationChunkHolder overworldHolder = obtainHolderForLevel(overworld);
        GenerationChunkHolder netherHolder = obtainHolderForLevel(nether);
        ChunkMap overworldMap = overworld.getChunkSource().chunkMap;
        ChunkMap netherMap = nether.getChunkSource().chunkMap;
        ChunkAccess overworldChunk = overworld.getChunk(0, 0);
        ChunkAccess netherChunk = nether.getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // 占唯一 NOISE permit 的任务（主世界，未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, overworldMap, overworldHolder, () -> firstUnderlying);

        // A：主世界等待任务；B：下界等待任务
        CompletableFuture<ChunkResult<ChunkAccess>> waitingA = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, overworldMap, overworldHolder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(overworldChunk)));
        CompletableFuture<ChunkResult<ChunkAccess>> waitingB = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, netherMap, netherHolder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(netherChunk)));
        helper.assertTrue(scheduler.pendingCount() == 2, "应存在 2 个等待任务");

        // 卸载下界：B 被取消，A 保留
        scheduler.cancelDimension(Level.NETHER, "Dimension unloaded");
        helper.assertTrue(!waitingA.isDone(), "主世界任务 A 不应受影响");
        helper.assertTrue(waitingB.isDone() && !waitingB.join().isSuccess(),
                "下界任务 B 应以 error result 完成");
        helper.assertTrue(scheduler.pendingCount() == 1, "卸载下界后队列深度应为 1");

        // 完成第一个任务 → permit 释放 → drain 恢复 A → 正常完成
        firstUnderlying.complete(ChunkResult.of(overworldChunk));

        helper.succeedWhen(() -> {
            helper.assertTrue(waitingA.isDone() && waitingA.join().isSuccess(),
                    "主世界任务 A 应正常完成");
            helper.assertTrue(scheduler.pendingCount() == 0, "A 完成后队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "A 完成后无在途任务");
            // 恢复下界维度生命周期（后续测试可能使用）
            scheduler.openDimension(Level.NETHER);
            resetScheduler(scheduler);
        });
    }


    /**
     * 第 9 轮 P0-2 修复验证：旧 generation 的迟到 lease 不得串改维度重新加载后
     * 新 generation 的任务计数。
     * <p>
     * 旧实现 lease.close 按维度 key 重新 computeIfPresent 递减：维度卸载
     * （onDimensionUnload 无条件 remove entry）后重载产生的新 counter，会被旧任务
     * A 的迟到 close 误减（新任务 B 的计数被清掉）。修复后 lease 捕获注册时的
     * 实际 counter 对象，close 用 {@code remove(dimension, counter)} 只删除自己
     * 对应的 counter——key 已映射新对象时不匹配，删除无操作。
     */
    @GameTest(template = "empty", batch = "steady_dim_lease_generation", timeoutTicks = 600)
    public void oldDimensionLeaseMustNotDecrementReloadedDimension(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "下界应已加载");
        GenerationChunkHolder netherHolder = obtainHolderForLevel(nether);
        ChunkMap netherMap = nether.getChunkSource().chunkMap;
        ChunkAccess netherChunk = nether.getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);

        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();

        // 旧 generation 任务 A：permit 充足 → direct 执行（注册旧 counter，计数=1）
        CompletableFuture<ChunkResult<ChunkAccess>> aUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> taskA = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, netherMap, netherHolder, () -> aUnderlying);
        helper.assertTrue(coordinator.dimensionTaskCount(Level.NETHER) == 1,
                "任务 A 注册后下界维度计数应为 1");

        // 维度卸载：cancelDimension + 移除 counter entry（模拟生产 LevelEvent.Unload）
        coordinator.onDimensionUnload(Level.NETHER, 0);
        helper.assertTrue(coordinator.dimensionTaskCount(Level.NETHER) == 0,
                "维度卸载后旧 counter entry 应被移除");

        // 维度重新加载：恢复接收（模拟生产 LevelEvent.Load）
        scheduler.openDimension(Level.NETHER);

        // 新 generation 任务 B：注册新 counter（计数=1）
        CompletableFuture<ChunkResult<ChunkAccess>> bUnderlying = new CompletableFuture<>();
        CompletableFuture<ChunkResult<ChunkAccess>> taskB = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, netherMap, netherHolder, () -> bUnderlying);
        helper.assertTrue(coordinator.dimensionTaskCount(Level.NETHER) == 1,
                "维度重载后新任务 B 注册计数应为 1（新 counter）");

        // 旧任务 A 完成：迟到 lease 关闭——不得误减 B 的新 counter
        aUnderlying.complete(ChunkResult.of(netherChunk));
        helper.assertTrue(taskA.isDone(), "旧任务 A 应已完成");
        helper.assertTrue(coordinator.dimensionTaskCount(Level.NETHER) == 1,
                "旧 lease 关闭不得串改重载后新 generation 的计数（B 的计数应保持 1）");

        // 新任务 B 完成：计数归零并移除
        bUnderlying.complete(ChunkResult.of(netherChunk));
        helper.assertTrue(taskB.isDone(), "新任务 B 应已完成");
        helper.assertTrue(coordinator.dimensionTaskCount(Level.NETHER) == 0,
                "新任务完成后维度计数应归零并移除");

        resetScheduler(scheduler);
        helper.succeed();
    }


    /**
     * P0 修复验证（第 7 轮）：维度卸载发生在"任务已 poll 出队、已提交 mailbox 但未运行"
     * 的窗口时，任务应在运行前被维度生命周期校验拒绝（不依赖全局 generation）。
     * <p>
     * 与全局 {@link #closeAfterPollBeforeSubmitShouldRejectTask} 的区别：触发条件是
     * 单维度卸载（cancelDimension），主世界任务不受影响。
     */
    @GameTest(template = "empty", batch = "steady_dim_poll_window", timeoutTicks = 600)
    public void dimensionUnloadAfterPollBeforeSubmitShouldReject(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "下界应已加载");

        GenerationChunkHolder overworldHolder = obtainHolderForLevel(overworld);
        GenerationChunkHolder netherHolder = obtainHolderForLevel(nether);
        ChunkMap overworldMap = overworld.getChunkSource().chunkMap;
        ChunkMap netherMap = nether.getChunkSource().chunkMap;
        ChunkAccess overworldChunk = overworld.getChunk(0, 0);
        ChunkAccess netherChunk = nether.getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // 占唯一 NOISE permit 的任务（主世界，未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, overworldMap, overworldHolder, () -> firstUnderlying);

        // 下界任务：NOISE permit 被占 → 入队等待
        CompletableFuture<ChunkResult<ChunkAccess>> netherTask = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, netherMap, netherHolder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(netherChunk)));
        helper.assertTrue(!netherTask.isDone(), "下界任务应入队等待");
        helper.assertTrue(scheduler.pendingCount() == 1, "等待队列深度应为 1");

        // 带屏障的恢复执行器：捕获 runnable，等待主线程 cancelDimension 后再放行
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scheduler.setResumeExecutorOverride(runnable -> {
            submitted.countDown();
            try {
                release.await(); // 阻塞在提交线程（后台完成回调线程），主线程不阻塞
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runnable.run(); // cancelDimension 后运行 → runnable 内维度 lifecycleValid 失败
        });

        // 后台线程完成第一个任务 → whenComplete → requestDrain → poll 下界任务 → 阻塞在提交
        Thread completer = new Thread(() -> firstUnderlying.complete(ChunkResult.of(overworldChunk)),
                "steady-dim-completer");
        completer.setDaemon(true);
        completer.start();

        // 等待 drain 已 poll 下界任务并阻塞在提交（维度 ADMITTED_NOT_SUBMITTED 窗口）
        try {
            helper.assertTrue(submitted.await(5, TimeUnit.SECONDS),
                    "drain 应已 poll 下界任务并阻塞在提交");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 卸载下界（关闭下界生命周期 + 递增维度代数）——下界任务已出队，队列无法再找到它
        scheduler.cancelDimension(Level.NETHER, "Dimension unloaded");

        // 放行屏障 → runnable 校验维度 lifecycleValid 失败 → error 完成 + 释放 permit
        release.countDown();

        helper.succeedWhen(() -> {
            helper.assertTrue(netherTask.isDone(), "卸载后已出队下界任务应以 error result 完成");
            helper.assertTrue(!netherTask.join().isSuccess(), "下界任务不应执行原操作（error result）");
            helper.assertTrue(scheduler.pendingCount() == 0, "关闭后队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "关闭后无在途任务");
            helper.assertTrue(scheduler.cpuPermitsAvailable() == scheduler.cpuPermitsMax(),
                    "关闭后全局 permit 应全部释放");
            scheduler.setResumeExecutorOverride(null);
            scheduler.openDimension(Level.NETHER);
            resetScheduler(scheduler);
        });
    }


    /**
     * P1-1 修复验证（第 6 轮）：维度卸载时定向取消该维度的等待任务，
     * 不影响其他维度的任务（cancelDimension 的维度过滤）。
     */
    @GameTest(template = "empty", batch = "steady_dim_unload", timeoutTicks = 600)
    public void dimensionUnloadShouldCancelOnlyThatDimension(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // 第一个任务占住唯一 NOISE permit（未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, map, holder, () -> firstUnderlying);

        // 两个等待任务（NOISE permit 被占 → 入队）
        CompletableFuture<ChunkResult<ChunkAccess>> waitingA = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        CompletableFuture<ChunkResult<ChunkAccess>> waitingB = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(helper.getLevel().getChunk(0, 0))));
        helper.assertTrue(scheduler.pendingCount() == 2, "应存在 2 个等待任务");

        ResourceKey<Level> overworld = helper.getLevel().dimension();

        // 取消非目标维度：等待任务不受影响
        scheduler.cancelDimension(Level.NETHER, "Dimension unloaded");
        helper.assertTrue(!waitingA.isDone(), "非目标维度取消不应影响任务 A");
        helper.assertTrue(!waitingB.isDone(), "非目标维度取消不应影响任务 B");
        helper.assertTrue(scheduler.pendingCount() == 2, "非目标维度取消后队列深度应保持 2");

        // 取消目标维度：等待任务全部以 error result 完成
        scheduler.cancelDimension(overworld, "Dimension unloaded");
        helper.assertTrue(waitingA.isDone(), "目标维度取消后任务 A 应完成");
        helper.assertTrue(!waitingA.join().isSuccess(), "任务 A 应以 error result 完成");
        helper.assertTrue(waitingB.isDone(), "目标维度取消后任务 B 应完成");
        helper.assertTrue(!waitingB.join().isSuccess(), "任务 B 应以 error result 完成");
        helper.assertTrue(scheduler.pendingCount() == 0, "目标维度取消后队列应清空");

        // 恢复目标维度生命周期（cancelDimension 关闭了该维度接收，后续测试仍使用主世界）
        scheduler.openDimension(overworld);
        // 第 13 轮修复：恢复下界生命周期（本测试也 cancelDimension(NETHER)——
        // 批次顺序变化后 steady_dim_enqueue_window 的下界 enqueuer 会因维度门
        // 拒绝而失败；对称于 steady_dim_isolation/poll_window 的恢复）
        scheduler.openDimension(Level.NETHER);

        // 完成第一个任务，避免其挂起影响后续测试
        firstUnderlying.complete(ChunkResult.of(helper.getLevel().getChunk(0, 0)));
        resetScheduler(scheduler);
        helper.succeed();
    }


    /**
     * P1-2 修复验证（第 6 轮）：共享资源桶 NOISE_HEAVY 的动态额度应能恢复，
     * 不被 BIOMES/SURFACE 的旧值永久压住。
     * <p>
     * ResourceGovernor 第 6 轮起只写 {@code NOISE_HEAVY}（不再按 ChunkStatus 做
     * min 聚合），因此共享桶恢复只取决于对该资源组的写入。本测试验证共享桶
     * （BIOMES/NOISE/SURFACE 共享同一 ResourceBucket）在降额后重新设置能正确恢复上限。
     * 注：applyPermits 为私有且依赖 ChunkFlightRecorder 遥测采集，此处通过
     * StageLimiter 共享桶语义间接验证 P1-2 修复后的恢复行为。
     */
    @GameTest(template = "empty", batch = "steady_governor_recover", timeoutTicks = 600)
    public void governorShouldRecoverSharedResource(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(false); // 纯 StageLimiter 操作，不涉及准入
        StageLimiter limiter = scheduler.stageLimiter();

        // 模拟压力下降：NOISE_HEAVY 降为 1（BIOMES/NOISE/SURFACE 共享同一桶）
        limiter.setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        helper.assertTrue(limiter.permit(ChunkStatus.NOISE).maxPermits() == 1,
                "降额后 NOISE 共享桶上限应为 1");
        helper.assertTrue(limiter.permit(ChunkStatus.BIOMES).maxPermits() == 1,
                "降额后 BIOMES 共享桶上限应为 1");
        helper.assertTrue(limiter.permit(ChunkStatus.SURFACE).maxPermits() == 1,
                "降额后 SURFACE 共享桶上限应为 1");

        // 模拟健康恢复：重新设置 NOISE_HEAVY 为 2（不应被旧值压住）
        limiter.setResourceLimit(ResourceType.NOISE_HEAVY, 2);
        helper.assertTrue(limiter.permit(ChunkStatus.NOISE).maxPermits() == 2,
                "恢复后 NOISE 共享桶上限应为 2");
        helper.assertTrue(limiter.permit(ChunkStatus.BIOMES).maxPermits() == 2,
                "恢复后 BIOMES 共享桶上限应为 2");
        helper.assertTrue(limiter.permit(ChunkStatus.SURFACE).maxPermits() == 2,
                "恢复后 SURFACE 共享桶上限应为 2");

        // 还原默认（后续测试用）
        limiter.setResourceLimit(ResourceType.NOISE_HEAVY, 3);
        resetScheduler(scheduler);
        helper.succeed();
    }


    /**
     * P0-2 修复验证（第 6 轮）：已出队但尚未提交 mailbox 的任务在关闭后应被拒绝。
     * <p>
     * 覆盖 ADMITTED_NOT_SUBMITTED 生命周期空档：
     * <ol>
     *   <li>任务从等待队列 poll 出（已获取组合 permit）；</li>
     *   <li>阻塞在恢复执行器提交（override executor 捕获 runnable 并等待屏障）；</li>
     *   <li>此时 closeForShutdown（generation++ / acceptingTasks=false）——任务已不在
     *       队列，stopAcceptingAndClear 无法找到它；</li>
     *   <li>放行屏障后 mailbox runnable 运行前校验 lifecycleValid 失败 → error result
     *       完成 + 释放组合 permit，原操作不执行。</li>
     * </ol>
     */
    @GameTest(template = "empty", batch = "steady_close_after_poll", timeoutTicks = 600)
    public void closeAfterPollBeforeSubmitShouldRejectTask(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        // 预取已加载区块引用（后台线程不允许调用 getChunk 强制加载）
        ChunkAccess chunk00 = helper.getLevel().getChunk(0, 0);

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);

        // 第一个任务占住唯一 NOISE permit（未完成）
        CompletableFuture<ChunkResult<ChunkAccess>> firstUnderlying = new CompletableFuture<>();
        scheduler.controlAdmission(ChunkStatus.NOISE, false, map, holder, () -> firstUnderlying);

        // 第二个任务：NOISE permit 被占 → 入队等待
        CompletableFuture<ChunkResult<ChunkAccess>> second = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> CompletableFuture.completedFuture(ChunkResult.of(chunk00)));
        helper.assertTrue(!second.isDone(), "第二个任务应入队等待");
        helper.assertTrue(scheduler.pendingCount() == 1, "等待队列深度应为 1");

        // 带屏障的恢复执行器：捕获 runnable，等待主线程 closeForShutdown 后再放行。
        // execute 本身不抛异常（返回），阻塞发生在提交线程（后台完成回调线程）。
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Runnable> captured = new AtomicReference<>();
        scheduler.setResumeExecutorOverride(runnable -> {
            captured.set(runnable);
            submitted.countDown();
            try {
                release.await(); // 阻塞在提交线程，主线程不阻塞
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runnable.run(); // closeForShutdown 后运行 → runnable 内 lifecycleValid 失败
        });

        // 后台线程完成第一个任务 → whenComplete → requestDrain → poll 第二个任务 →
        // submitResumed → override executor 捕获 runnable 并阻塞
        Thread completer = new Thread(() -> firstUnderlying.complete(ChunkResult.of(chunk00)),
                "steady-test-completer");
        completer.setDaemon(true);
        completer.start();

        // 等待 drain 已 poll 第二个任务并阻塞在提交（ADMITTED_NOT_SUBMITTED 窗口已建立）
        try {
            helper.assertTrue(submitted.await(5, TimeUnit.SECONDS),
                    "drain 应已 poll 任务并阻塞在提交");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 关闭调度器（generation++ / acceptingTasks=false）——第二个任务已出队，队列无法再找到它
        scheduler.closeForShutdown(new IllegalStateException("Server stopping"));

        // 放行屏障 → runnable 校验 lifecycleValid 失败 → error 完成 + 释放 permit
        release.countDown();

        helper.succeedWhen(() -> {
            helper.assertTrue(second.isDone(), "关闭后已出队任务应以 error result 完成");
            helper.assertTrue(!second.join().isSuccess(), "已出队任务不应执行原操作（error result）");
            helper.assertTrue(scheduler.pendingCount() == 0, "关闭后队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "关闭后无在途任务");
            helper.assertTrue(scheduler.cpuPermitsAvailable() == scheduler.cpuPermitsMax(),
                    "关闭后全局 permit 应全部释放");
            scheduler.setResumeExecutorOverride(null);
            resetScheduler(scheduler);
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

    /** 重置调度器全局状态（统一清理顺序，见 SchedulerGameTestFixture）。 */
    private static void resetScheduler(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.resetGlobalState();
    }
}
