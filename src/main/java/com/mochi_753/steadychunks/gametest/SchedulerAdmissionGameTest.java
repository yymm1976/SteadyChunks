package com.mochi_753.steadychunks.gametest;

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
 * 调度器 NOISE 准入 GameTest（审查建议第 8 项 + P0-3 修复）。
 * <p>
 * 在真实 Minecraft 运行环境中验证调度器核心链路：
 * <ol>
 *   <li>NOISE permit=1 时，第二个 controlAdmission 被挂起（进入等待队列）</li>
 *   <li>第一个任务完成后 permit 释放，第二个任务自动恢复并完成</li>
 *   <li>admissionPaused 时新任务进入等待队列且不被 drain 启动</li>
 *   <li>clearAll 异常完成所有等待任务，无永久未完成 Future</li>
 * </ol>
 * <p>
 * P0-3 修复：用<b>未完成</b>的 CompletableFuture 占住 permit（已完成 Future 会
 * 立即触发 whenComplete 释放 permit，无法制造等待）；断言使用
 * {@link GameTestHelper#succeedWhen} 逐 Tick 轮询，不在服务器线程阻塞等待。
 * <p>
 * 并发隔离：三个测试共享全局调度器单例，必须分到不同 batch（NeoForge/vanilla
 * 的 batch 串行执行），否则并发运行时 clearAll 会清空其他测试的等待队列。
 * <p>
 * 运行方式：{@code ./gradlew runGameTestServer}（或 IDE run config "gameTestServer"）。
 */
@GameTestHolder("steadychunks")
public class SchedulerAdmissionGameTest {

    /**
     * 获取测试用原版 ChunkMap 与一个可见区块的 GenerationChunkHolder。
     * <p>
     * 恢复执行通过原 worldgen mailbox 提交（P0-2），需要真实 map/holder。
     * <p>
     * 必须在 {@code setEnabled(true)} 之前调用：getChunk 会强制加载测试区块，
     * 若调度器已启用，真实区块生成的 NOISE 任务会经 Mixin 进入调度器并占用
     * permit，干扰测试自身的 controlAdmission 断言。
     */
    private static GenerationChunkHolder obtainHolder(GameTestHelper helper) {
        return obtainHolderForLevel(helper.getLevel());
    }

    /**
     * 获取指定维度（ServerLevel）的测试用 ChunkMap 与 (0,0) 区块的 GenerationChunkHolder。
     * 必须在 {@code setEnabled(true)} 之前调用（真实生成会被调度器拦截并占用 permit）。
     */
    private static GenerationChunkHolder obtainHolderForLevel(ServerLevel level) {
        ChunkMap map = level.getChunkSource().chunkMap;
        // 强制加载测试区块
        ChunkAccess chunk = level.getChunk(0, 0);
        GenerationChunkHolder holder = map.getVisibleChunkIfPresent(chunk.getPos().toLong());
        if (holder == null) {
            throw new IllegalStateException("测试区块 holder 不存在: " + chunk.getPos());
        }
        return holder;
    }

    /** 重置调度器全局状态，避免测试间泄漏。 */
    private static void resetScheduler(ChunkScheduler scheduler) {
        scheduler.setAdmissionPaused(false);
        scheduler.setEnabled(false);
        scheduler.clearAll(new IllegalStateException("GameTest cleanup"));
        scheduler.resetDiagnostics();
        // 第 9 轮卡死修复：测试内部把 NOISE_HEAVY 桶压到 1（验证准入排队），若不恢复，
        // 后续批次的结构区块加载/真实生成全部在 NOISE 并发=1 下排队滞留 → 生成任务
        // 长期不终结（refCount 不归零）→ toDrop 滞留 → processUnloads 忙转螺旋。
        // 第 10 轮修复：显式恢复测试值 8（不读 CommonConfig.LIMIT_NOISE——生产默认
        // 3 保持原样，不得用生产配置修测试稳定性）。
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
    }

    /**
     * 真实新区块生成测试（第 4 轮审查建议第 3 项）：
     * 完整经过 {@code ChunkGenerationTask.scheduleChunkInLayer} → {@code @WrapOperation}
     * 的 Mixin 拦截链，而非直接调用 {@code controlAdmission}。
     * <p>
     * 断言：
     * <ul>
     *   <li>Mixin 拦截计数 > 0（真实 NOISE 任务确实进入调度器）</li>
     *   <li>NOISE permit=1 时真实最大并发 ≤ 1（{@code maxActiveNoise}）</li>
     *   <li>区块达到 FULL；等待队列清空；在途归零；全局 permit 全部释放</li>
     * </ul>
     * 注意：所有断言放在 succeedWhen 回调内，用 GameTestAssertException 驱动逐 Tick
     * 轮询，未完成时不会提前 succeed，也不会阻塞服务器线程。
     */
    @GameTest(template = "empty", batch = "steady_real_gen", timeoutTicks = 1200)
    public void realGenerationShouldCapNoiseConcurrency(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        scheduler.resetDiagnostics();

        // 请求远处未生成的新区块（异步，不阻塞服务器线程）。
        // getChunkFuture 触发完整生成管线，NOISE 阶段经 Mixin 的 @WrapOperation 进入调度器。
        ChunkPos target = new ChunkPos(32, 32);
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                helper.getLevel().getChunkSource().getChunkFuture(target.x, target.z, ChunkStatus.FULL, true);

        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "远处新区块应完成生成");
            ChunkResult<ChunkAccess> result = future.join();
            helper.assertTrue(result.isSuccess(), "远处新区块应生成成功");
            ChunkAccess chunk = result.orElse(null);
            helper.assertTrue(chunk != null, "区块结果不应为空");
            helper.assertTrue(chunk.getHighestGeneratedStatus().isOrAfter(ChunkStatus.FULL), "区块应达到 FULL");
            // Mixin 应拦截到真实 NOISE 任务（拦截计数 > 0）
            helper.assertTrue(scheduler.mixinInterceptCount() > 0, "Mixin 应拦截真实 NOISE 任务");
            // NOISE permit=1：真实最大并发不得超过 1
            helper.assertTrue(scheduler.maxActiveNoise() <= 1,
                    "NOISE 最大并发应 <= 1，实际: " + scheduler.maxActiveNoise());
            // 生成完成后：等待队列清空、在途归零、全局 permit 全部释放
            helper.assertTrue(scheduler.pendingCount() == 0, "生成完成后等待队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "生成完成后在途任务应归零");
            helper.assertTrue(scheduler.cpuPermitsAvailable() == scheduler.cpuPermitsMax(),
                    "生成完成后全局 permit 应全部释放");
            resetScheduler(scheduler);
        });
    }

    @GameTest(template = "empty", batch = "steady_noise_permit", timeoutTicks = 600)
    public void noisePermitOneShouldQueueAndResume(GameTestHelper helper) {
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
    }

    @GameTest(template = "empty", batch = "steady_admission_paused", timeoutTicks = 600)
    public void admissionPausedShouldBlockNewTasks(GameTestHelper helper) {
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
    }

    @GameTest(template = "empty", batch = "steady_clear_all", timeoutTicks = 600)
    public void clearAllShouldCompleteWaitingTasks(GameTestHelper helper) {
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
        helper.assertTrue(scheduler.pendingCount() == 1, "应存在 1 个等待任务");

        // 模拟维度卸载/停服：clearAll 应以 error result 完成等待任务
        // （第 5 轮修复：异常完成会让原版 setFatalException，破坏真实区块生成链）
        scheduler.clearAll(new IllegalStateException("Dimension unload"));
        helper.assertTrue(waiting.isDone(), "clearAll 后等待任务应被完成");
        helper.assertTrue(!waiting.join().isSuccess(), "clearAll 后等待任务应以 error result 完成");
        helper.assertTrue(scheduler.pendingCount() == 0, "clearAll 后等待队列应清空");

        // 完成第一个任务，避免其挂起影响后续测试
        firstUnderlying.complete(ChunkResult.of(helper.getLevel().getChunk(0, 0)));
        resetScheduler(scheduler);
        helper.succeed();
    }

    /**
     * P0-2 修复验证（第 5 轮）：紧急暂停状态下关闭调度器，积压任务仍应按 bypass
     * 有节奏恢复（bypass 优先级高于 admissionPaused），不会永久挂起。
     */
    @GameTest(template = "empty", batch = "steady_paused_disable", timeoutTicks = 600)
    public void pausedThenDisableShouldBypass(GameTestHelper helper) {
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
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
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
    }

    /**
     * P0/P1 修复验证（第 5 轮）：并发入队与 clearAll 的生命周期屏障。
     * 多线程反复 controlAdmission，主线程并发 clearAll，最终：
     * 等待队列清空、所有代理均完成、permit 全部释放、无残留任务。
     */
    @GameTest(template = "empty", batch = "steady_clear_concurrent", timeoutTicks = 2400)
    public void clearConcurrentAdmissionShouldNotLeaveTasks(GameTestHelper helper) {
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

    /**
     * P1-1 修复验证（第 6 轮）：维度卸载时定向取消该维度的等待任务，
     * 不影响其他维度的任务（cancelDimension 的维度过滤）。
     */
    @GameTest(template = "empty", batch = "steady_dim_unload", timeoutTicks = 600)
    public void dimensionUnloadShouldCancelOnlyThatDimension(GameTestHelper helper) {
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
     * P1-1 修复验证（第 7 轮）：真实双维度隔离——卸载下界只取消下界任务，
     * 主世界任务不受影响并正常完成。
     */
    @GameTest(template = "empty", batch = "steady_dim_isolation", timeoutTicks = 600)
    public void dimensionUnloadShouldCancelOnlyTargetDimension(GameTestHelper helper) {
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
     * P0 修复验证（第 7 轮）：维度卸载发生在"任务已 poll 出队、已提交 mailbox 但未运行"
     * 的窗口时，任务应在运行前被维度生命周期校验拒绝（不依赖全局 generation）。
     * <p>
     * 与全局 {@link #closeAfterPollBeforeSubmitShouldRejectTask} 的区别：触发条件是
     * 单维度卸载（cancelDimension），主世界任务不受影响。
     */
    @GameTest(template = "empty", batch = "steady_dim_poll_window", timeoutTicks = 600)
    public void dimensionUnloadAfterPollBeforeSubmitShouldReject(GameTestHelper helper) {
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
        helper.assertTrue(coordinator.globalTaskCount() == 2,
                "重启后全局计数应为 2（旧任务 A 残留 + 新任务 B）");

        // 旧任务 A 迟到完成：计数递减（不产生负数）
        aUnderlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(taskA.isDone(), "旧任务 A 应已完成");
        helper.assertTrue(coordinator.globalTaskCount() == 1,
                "迟到 lease 关闭后全局计数应为 1（不得为负数）");

        // 新任务 B 完成：计数归零
        bUnderlying.complete(ChunkResult.of(chunk));
        helper.assertTrue(taskB.isDone(), "新任务 B 应已完成");
        helper.assertTrue(coordinator.globalTaskCount() == 0,
                "全部任务完成后全局计数应归零");

        resetScheduler(scheduler);
        helper.succeed();
    }

    /**
     * 第 10 轮 P0-1 修复验证：originalOperation 同步抛异常时统一失败路径——
     * 释放组合 permit（global + NOISE）、回退 inflightCount、关闭 registration。
     * 旧实现只关 registration：一次同步异常永久消耗一个全局 permit + 一个 NOISE
     * permit + 一个 inflightCount，重复几次后所有 NOISE 都进等待队列。
     */
    @GameTest(template = "empty", batch = "steady_sync_throw", timeoutTicks = 600)
    public void originalOperationThrowsSynchronously(GameTestHelper helper) {
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
    }

    /**
     * 第 10 轮 P0-4 修复验证：Watchdog 恢复线程支持多服务器生命周期——
     * stop 后再次 start 必须创建新线程（旧实现 recoveryStarted 永久为 true，
     * 集成服务器第二个世界起恢复线程不再启动）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_lifecycle", timeoutTicks = 600)
    public void watchdogRestartsAcrossServerLifecycle(GameTestHelper helper) {
        Watchdog wd = Watchdog.getInstance();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();

        // 先确保停止（ServerStarting 可能已启动恢复线程）
        wd.stopRecoveryThread();
        for (int i = 0; i < 100 && wd.isRecoveryThreadAlive(); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        helper.assertTrue(!wd.isRecoveryThreadAlive(), "stop 后线程应退出");

        // 重启（模拟第二个服务器生命周期）
        wd.startRecoveryThread(scheduler);
        helper.assertTrue(wd.isRecoveryThreadAlive(), "重启后线程应存活（P0-4）");

        // 再次停止收尾
        wd.stopRecoveryThread();
        for (int i = 0; i < 100 && wd.isRecoveryThreadAlive(); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        helper.assertTrue(!wd.isRecoveryThreadAlive(), "再次 stop 后线程应退出");
        helper.succeed();
    }

    /**
     * 第 10 轮评审要求：正确性测试禁用 Watchdog 并断言恢复数为零。
     * <p>
     * 本测试停止恢复线程（后续批次在无恢复保护下运行——硬门槛：调度链必须
     * 不依赖 Watchdog 自愈也能通过），并断言此前从未发生过自动恢复
     * （totalRecoveries==0 且 totalUnsafeRecoveries==0），防止 Watchdog
     * 掩盖核心调度链竞态。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_disabled", timeoutTicks = 600)
    public void watchdogMustNotRecoverDuringCorrectnessTests(GameTestHelper helper) {
        Watchdog wd = Watchdog.getInstance();
        // 此前任何批次的自动恢复都会使此断言失败（暴露调度链竞态）
        helper.assertTrue(wd.totalRecoveries() == 0,
                "正确性测试期间不得发生任何自动恢复（Watchdog 掩盖竞态）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0,
                "正确性测试期间不得发生 UNSAFE_EMERGENCY 恢复");
        // 禁用恢复线程：后续批次在无 Watchdog 保护下运行（硬门槛）
        wd.stopRecoveryThread();
        helper.succeed();
    }
}
