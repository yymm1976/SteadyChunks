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

    /**
     * 第 11 轮缓解：等待调度器等待队列排空（最多 3 秒）。用于 override 类测试
     * （mailbox_fail / mailbox_escalate / mailbox_reject）设置 override 之前——
     * 避免批次结构加载的真实生成任务在 override 生效期间 drain（被 error 完成
     * 导致区块卡 NOISE 之前，后续强制同步加载死等忙转）。
     */
    private static void waitForQueueDrain(ChunkScheduler scheduler) {
        for (int i = 0; i < 300 && scheduler.pendingCount() > 0; i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 第 14 轮适配：轮询等待条件成立（最多 3 秒）——stopRecoveryThread 自第 14 轮
     * 起异步处置批次（emergency 虚拟线程完成），终态/指标断言须轮询。
     */
    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
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

    /**
     * 第 11 轮 P0 修复验证：两级恢复的第二级（UNSAFE_EMERGENCY）必须能强制完成
     * 第一级遗留任务——第一级把任务移出队列并形成 RecoveryBatch（保留引用），
     * mailbox 停滞（override executor 接收但不运行）时批次任务 proxy 未完成，
     * escalateRecoveryBatch 直接 complete（旧实现第二级对已清空队列找不到任务）。
     */
    @GameTest(template = "empty", batch = "steady_mailbox_escalate", timeoutTicks = 600)
    public void mailboxRecoveryMustEscalateToUnsafe(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务：admissionPaused 保证入队且 drainOwnedPass 跳过（不依赖 permit 状态）。
        // paused 保持到 beginMailboxRecovery 之后——防止并发 drain（worldgen 完成回调
        // 触发 requestDrain）在批次形成前 poll 走任务导致空批次。
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1,
                "paused 时任务应入队等待（实际=" + scheduler.pendingCount() + "）");

        // override：接收但不运行（模拟 mailbox 停滞）
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });

        // 第一级：capture（任务移出队列）→ 提交（override 接收但不运行）。
        // 第 13 轮起 beginMailboxRecovery 拆为 capture + submit（批次发布先于提交）
        var batch = scheduler.captureRecoveryBatch();
        int rejected = scheduler.submitRecoveryBatch(batch);
        scheduler.setAdmissionPaused(false);
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时批次任务 proxy 不应完成");
        helper.assertTrue(rejected == 0, "override 接收不拒绝，不应有拒绝计数");

        // 第二级：对批次内未完成任务直接 complete（UNSAFE_EMERGENCY）
        int unsafe = scheduler.escalateRecoveryBatch(batch);
        helper.assertTrue(unsafe == 1, "第二级应强制完成 1 个遗留任务");
        helper.assertTrue(queued.isDone(), "批次任务应被强制完成");

        // 清理
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        helper.succeed();
    }

    /**
     * 第 11 轮 P1 修复验证：mailbox 立即拒绝（execute 抛异常）的任务直接完成，
     * 必须计入 rejectedAndCompletedUnsafely（旧实现漏计 unsafe 指标）。
     */
    @GameTest(template = "empty", batch = "steady_mailbox_reject", timeoutTicks = 600)
    public void mailboxRejectionMustCountUnsafeRecovery(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（admissionPaused 保证入队且不被 drain；paused 保持到
        // beginMailboxRecovery 之后，防并发 drain 提前 poll 走任务）
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1,
                "paused 时任务应入队等待（实际=" + scheduler.pendingCount() + "）");

        // override：立即拒绝（mailbox 停滞/关闭）
        scheduler.setResumeExecutorOverride(command -> {
            throw new java.util.concurrent.RejectedExecutionException("mailbox closed");
        });

        var batch = scheduler.captureRecoveryBatch();
        int rejected = scheduler.submitRecoveryBatch(batch);
        scheduler.setAdmissionPaused(false);
        helper.assertTrue(rejected == 1,
                "mailbox 拒绝的任务应计入拒绝计数（实际=" + rejected + "）");
        helper.assertTrue(queued.isDone(), "被拒绝的任务应直接完成（不永久挂起）");

        // 清理
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        helper.succeed();
    }

    /**
     * 第 11 轮 P0/P1 修复验证：旧服务器生命周期的迟到 lease 不得污染新服务器计数。
     * onServerStopped 后开启新生命周期（ServerLifecycle 原子替换），旧任务迟到
     * close 只递减旧 counter——新生命周期计数不受影响（旧实现共享 globalTaskCount，
     * 迟到 close 会把新计数抹掉甚至减成负数）。
     */
    @GameTest(template = "empty", batch = "steady_late_lease", timeoutTicks = 600)
    public void lateOldServerLeaseMustNotAffectNewServerCounter(GameTestHelper helper) {
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
     * 第 11 轮 P1 修复验证：stop 后立即 start（不等待旧线程退出）也必须留下
     * 一条存活恢复线程——线程代数让旧线程退出、新线程立即接管（旧实现 start
     * 看到旧线程仍 alive 直接 return，新服务器无线程）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_immediate", timeoutTicks = 600)
    public void watchdogImmediateStopStartMustLeaveLiveThread(GameTestHelper helper) {
        Watchdog wd = Watchdog.getInstance();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();

        // 连续 stop/start，不等待旧线程退出（模拟生产事件中停服后立即开新世界）
        wd.stopRecoveryThread();
        wd.startRecoveryThread(scheduler);
        wd.stopRecoveryThread();
        wd.startRecoveryThread(scheduler);

        helper.assertTrue(wd.isRecoveryThreadAlive(),
                "连续 stop/start 后必须存在一条存活恢复线程（P1 竞态）");

        // 收尾：停止
        wd.stopRecoveryThread();
        helper.succeed();
    }

    /**
     * 第 12 轮 P1 修复验证：Watchdog 自动恢复状态机端到端——不再直接调用
     * Scheduler API，而是经 {@link Watchdog#checkDrainStallForTest}（注入时钟）
     * 驱动完整状态机：
     * 停滞 3 次 → 第一级形成批次（activeRecoveryBatch 保存、pending 归零）
     * → deadline 前不升级 → deadline 后第二级直接完成（unsafe +1）→ 批次清空。
     * <p>
     * 手法：任务全程保持 paused（drain 永不抢走任务，队列快照稳定——取消暂停会
     * 同步触发 requestDrain 立即抢走任务，第 1 版因此失败）；停滞检测的 paused
     * 排除项经 {@code setStallCheckIgnorePausedForTest} 临时忽略，先验证排除项
     * 本身（不置开关时 3 次检测不得触发），再验证触发路径。
     * <p>
     * 指标隔离：测试前后 {@code resetRecoveryMetrics()}——刻意触发的状态机恢复
     * 不污染跨批次累计值（正确性硬门槛 steady_watchdog_disabled 断言指标为 0）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_state_machine", timeoutTicks = 600)
    public void watchdogStateMachineMustRecoverEndToEnd(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务并保持 paused（drain 永不抢走任务）
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1,
                "paused 时任务应入队等待（实际=" + scheduler.pendingCount() + "）");

        // override：接收但不运行（模拟 mailbox 停滞）
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        // 真实线程先睡 1 秒，测试在其睡眠窗口内驱动状态机（不竞争）
        wd.startRecoveryThread(scheduler);

        // 排除项验证：未置测试开关时，paused 队列 3 次检测不得触发恢复
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "paused 排除项生效：3 次检测不得触发恢复");
        helper.assertTrue(!queued.isDone(), "排除项阶段任务不应被完成");
        helper.assertTrue(scheduler.pendingCount() == 1, "排除项阶段任务应仍在队列");

        // 置测试开关（忽略 paused 排除项）→ 停滞 3 次 → 第一级触发
        wd.setStallCheckIgnorePausedForTest(true);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);

        helper.assertTrue(wd.hasActiveRecoveryBatch(), "停滞 3 次后第一级应形成活动批次");
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时批次任务 proxy 不应完成");
        helper.assertTrue(wd.totalRecoveries() == 1, "第一级应计入 totalRecoveries");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0, "deadline 前不得升级 unsafe");

        // deadline 前：批次保持，不升级
        wd.checkDrainStallForTest(scheduler, wd.activeRecoveryBatchDeadlineNanos() - 1);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "deadline 前批次应保持活动");
        helper.assertTrue(!queued.isDone(), "deadline 前任务不应被强制完成");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0, "deadline 前 unsafe 不得增加");

        // deadline 后：第二级直接完成（UNSAFE_EMERGENCY）
        wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L);
        helper.assertTrue(queued.isDone(), "deadline 后批次任务应被强制完成");
        helper.assertTrue(!queued.join().isSuccess(), "强制完成应为 error result");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 1,
                "第二级应计入 totalUnsafeRecoveries（实际=" + wd.totalUnsafeRecoveries() + "）");
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "批次终态后应清空");
        helper.assertTrue(scheduler.pendingCount() == 0, "批次处理后队列应为空");

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        wd.stopRecoveryThread();
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
    }

    /**
     * 第 12 轮 P0 修复验证：停服必须处置活动恢复批次，禁止静默遗弃。
     * <p>
     * 旧实现 stopRecoveryThread 直接 {@code activeRecoveryBatch = null}：批次任务已
     * 不在 pending 队列，随后 closeForShutdown 看不到它们 → proxy 永不终态 →
     * TaskRegistration 不关闭 → 旧 ServerLifecycle 永久残留计数。
     * <p>
     * 新实现：stop 锁内脱离批次、锁外同步强制完成（UNSAFE，计入独立的
     * {@code totalUnsafeShutdownRecoveries}，不混入运行期指标）；随后
     * onServerShutdown/onServerStopped 无残留可见。
     * 批次形成手法同 {@link #watchdogStateMachineMustRecoverEndToEnd}（paused +
     * 测试开关）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_shutdown_batch", timeoutTicks = 600)
    public void shutdownMustTerminateActiveRecoveryBatch(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持，drain 不抢）→ mailbox 停滞 override → 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.startRecoveryThread(scheduler);

        // 测试开关 + 停滞 3 次 → 第一级形成活动批次（mailbox 停滞，proxy 未完成）
        wd.setStallCheckIgnorePausedForTest(true);
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时批次任务 proxy 不应完成");
        helper.assertTrue(coordinator.globalTaskCount() == 1, "批次任务仍持有注册 lease");

        // P0 修复验证：停服（stopRecoveryThread）必须处置活动批次——第 14 轮起
        // 为异步 emergency 分派（停服不被回调阻塞），终态/指标轮询确认
        wd.stopRecoveryThread();
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "停服后活动批次应已脱离（同步）");
        awaitTrue(() -> queued.isDone(), "停服后批次任务 proxy 必须终态");
        helper.assertTrue(!queued.join().isSuccess(), "停服处置应为 error result");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "停服处置后 registration 应归零");
        awaitTrue(() -> wd.totalUnsafeShutdownRecoveries() == 1,
                "停服处置应计入独立指标 totalUnsafeShutdownRecoveries（实际="
                        + wd.totalUnsafeShutdownRecoveries() + "）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0,
                "停服处置不得混入运行期 UNSAFE_EMERGENCY 指标");

        // 后续停服流程：closeForShutdown 看不到批次任务也不得泄漏
        wd.setStallCheckIgnorePausedForTest(false);
        coordinator.onServerShutdown();
        helper.assertTrue(scheduler.pendingCount() == 0, "停服清理后等待队列应清空");
        helper.assertTrue(coordinator.globalTaskCount() == 0, "停服清理后无残留任务计数");
        coordinator.onServerStopped();
        coordinator.onServerStart(); // 恢复接收，避免污染后续批次
        helper.assertTrue(!coordinator.isShutdownMode(), "恢复后应退出停服模式");

        // 清理
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
    }

    /**
     * 第 13 轮 P0 修复验证（窗口 A）：mailbox 提交调用本身阻塞时，批次必须已对
     * Watchdog 可见——捕获与发布零间隙，提交在批次发布之后锁外执行。
     * <p>
     * 旧实现 beginMailboxRecovery 先逐个 execute 全部返回后才形成批次：
     * executor 阻塞时任务已离队但批次未发布，停服/第二级都看不到（任务所有权
     * 不可见窗口）。
     * <p>
     * 流程：提交线程驱动停滞 3 次 → 锁内捕获+发布 → 锁外 submit → execute 阻塞
     * → 断言批次可见 → stopRecoveryThread 处置 → proxy 终态 + 计数归零。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_blocking_submit", timeoutTicks = 600)
    public void blockingMailboxSubmissionMustRemainRecoverable(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持，drain 不抢）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);

        // override：execute 进入后阻塞（模拟 mailbox 提交调用本身停滞，
        // 而非"接收但不运行"——第 12 轮测试未覆盖此形态）
        CountDownLatch enteredExecute = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        scheduler.setResumeExecutorOverride(command -> {
            enteredExecute.countDown();
            try {
                releaseExecute.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 放行后不再运行 command（任务已由停服处置，幂等）
        });
        wd.setStallCheckIgnorePausedForTest(true);

        // 提交线程驱动第一级：capture+发布（锁内）→ submit（锁外 execute 阻塞）
        ExecutorService pool = Executors.newSingleThreadExecutor();
        long now = System.nanoTime();
        pool.submit(() -> {
            wd.checkDrainStallForTest(scheduler, now);
            wd.checkDrainStallForTest(scheduler, now);
            wd.checkDrainStallForTest(scheduler, now);
        });

        // 等 execute 被调用（提交线程已进入阻塞）——窗口 A 修复验证：
        // 批次必须已在 execute 之前发布
        try {
            helper.assertTrue(enteredExecute.await(5, TimeUnit.SECONDS),
                    "execute 应被调用（提交线程阻塞在 override 内）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.fail("等待 execute 被中断");
        }
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "提交阻塞期间批次必须已发布（窗口 A）");
        helper.assertTrue(scheduler.pendingCount() == 0, "任务已移出队列");
        helper.assertTrue(!queued.isDone(), "proxy 未完成（execute 尚未放行）");
        helper.assertTrue(coordinator.globalTaskCount() == 1, "批次任务仍持有注册 lease");

        // 停服：stopRecoveryThread 必须能看到批次并处置（任意 phase 幂等；
        // 第 14 轮起异步 emergency 分派——轮询终态）
        wd.stopRecoveryThread();
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "批次应已脱离（同步）");
        awaitTrue(() -> queued.isDone(), "停服后 proxy 必须终态");
        helper.assertTrue(!queued.join().isSuccess(), "应为 error result");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "registration 归零");
        awaitTrue(() -> wd.totalUnsafeShutdownRecoveries() == 1,
                "停服处置计入 shutdown 指标（实际=" + wd.totalUnsafeShutdownRecoveries() + "）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0,
                "停服处置不得混入运行期指标");

        // 放行阻塞的 execute：提交线程完成阶段 5（批次已被停服处置 → 仅补指标）
        releaseExecute.countDown();
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
    }

    /**
     * 第 13 轮 P0 修复验证（窗口 B）：第二级升级期间批次必须保持可见——第一个
     * proxy 的同步回调阻塞时，activeRecovery 不得清空，其他线程可幂等完成剩余
     * 任务（旧实现先 {@code activeRecoveryBatch = null} 再锁外 escalate：
     * 第一个 complete 的回调阻塞时，剩余任务只存在于 Watchdog 线程局部变量，
     * 停服/第二线程都找不到）。
     * <p>
     * 流程：形成 2 任务批次（WAITING_MAILBOX）→ 驱动线程 deadline 升级
     * （任务 1 complete 触发阻塞回调，驱动线程卡住）→ 断言批次仍可见 →
     * 第二线程幂等完成任务 2 → 全部终态后清空 → 放行回调 → 指标归并。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_blocking_escalate", timeoutTicks = 600)
    public void blockingCompletionCallbackMustKeepBatchVisible(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 2 个任务（paused 保持）→ 启动恢复线程 → override 接收但不运行
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> t1 = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        CompletableFuture<ChunkResult<ChunkAccess>> t2 = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 2, "paused 时 2 个任务应入队等待");
        wd.startRecoveryThread(scheduler);
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.setStallCheckIgnorePausedForTest(true);

        // 任务 1 的同步回调阻塞（模拟下游回调卡住）
        CountDownLatch callbackBlocked = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        t1.whenComplete((result, ex) -> {
            callbackBlocked.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 第一级：停滞 3 次 → 批次形成（2 任务，WAITING_MAILBOX）
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        helper.assertTrue(!t1.isDone() && !t2.isDone(), "mailbox 停滞时任务 proxy 不应完成");

        // 驱动线程 deadline 升级：任务 1 的 complete 触发阻塞回调 → 驱动线程卡住
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L));
        try {
            helper.assertTrue(callbackBlocked.await(5, TimeUnit.SECONDS),
                    "任务 1 的同步回调应被触发（驱动线程卡在 escalate 内）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.fail("等待回调被中断");
        }

        // 窗口 B 修复验证：升级期间批次必须仍可见（未清空）
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "升级期间批次必须保持发布（窗口 B）");

        // 第二线程幂等完成剩余任务（评审：第二线程可以继续幂等完成其余任务）
        wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L);
        helper.assertTrue(t2.isDone(), "第二线程应完成剩余任务（幂等）");
        helper.assertTrue(!t2.join().isSuccess(), "应为 error result");
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "全部终态后批次应清空");

        // 放行任务 1 回调 → 驱动线程 escalate 返回（complete 幂等，指标归并）
        releaseCallback.countDown();
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(t1.isDone(), "任务 1 应终态");
        helper.assertTrue(coordinator.globalTaskCount() == 0, "registration 全部归零");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 2,
                "两个任务都应计入 totalUnsafeRecoveries（实际=" + wd.totalUnsafeRecoveries() + "）");

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        wd.stopRecoveryThread();
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
    }

    /**
     * 第 14 轮 P0-1 修复验证：阻塞的 mailbox 提交不得阻断运行期自动二级恢复。
     * <p>
     * 旧实现提交由唯一监督线程同步执行——execute 阻塞时监督线程被卡，
     * MAILBOX_SUBMITTING 分支"不推进"，两秒 deadline 永远不检查 → 运行期
     * 自动 UNSAFE_EMERGENCY 永不发生（只能在停服回收）。
     * <p>
     * 新实现：提交移出监督线程（独立 recoverySubmitter），CAPTURED/
     * MAILBOX_SUBMITTING/WAITING_MAILBOX 统一允许 deadline 超时升级。
     * 本测试<b>不调用 stopRecoveryThread</b>：execute 永久阻塞 → deadline 后
     * 第二级必须自动强制完成（unsafe==1）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_submit_escalate", timeoutTicks = 600)
    public void blockingMailboxSubmissionMustEscalateWithoutShutdown(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);

        // override：execute 永久阻塞（提交在独立线程——监督线程不受影响）
        CountDownLatch enteredExecute = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        scheduler.setResumeExecutorOverride(command -> {
            enteredExecute.countDown();
            try {
                releaseExecute.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        wd.setStallCheckIgnorePausedForTest(true);

        // 形成批次：提交分派到独立线程并阻塞在 execute
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        try {
            helper.assertTrue(enteredExecute.await(5, TimeUnit.SECONDS),
                    "execute 应由独立提交线程调用并阻塞");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(!queued.isDone(), "提交阻塞时 proxy 不应完成");

        // P0-1 修复验证：不调用 stopRecoveryThread——deadline 后运行期自动第二级
        wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L);
        awaitTrue(() -> queued.isDone(), "deadline 后批次任务应被第二级强制完成（不依赖提交完成）");
        helper.assertTrue(!queued.join().isSuccess(), "强制完成应为 error result");
        awaitTrue(() -> wd.totalUnsafeRecoveries() == 1,
                "第二级应计入 totalUnsafeRecoveries（实际=" + wd.totalUnsafeRecoveries() + "）");
        awaitTrue(() -> !wd.hasActiveRecoveryBatch(), "批次终态后应清空");

        // 放行提交线程（幂等：任务已由第二级完成）
        releaseExecute.countDown();

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        wd.stopRecoveryThread();
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
    }

    /**
     * 第 14 轮 P0-2 修复验证：停服仍可发生在"判定恢复"与"捕获发布"之间——
     * 旧线程此时不得捕获并发布新批次。
     * <p>
     * 旧实现阶段 1 锁内判定 startFirstLevel 后释放锁，阶段 3 重新取锁无条件
     * 捕获发布：两个锁区间之间 stopRecoveryThread 可能先执行（看不到批次），
     * 旧线程随后仍发布 → 停服后任务被捕获而无人处置；快速 stop/start 时旧线程
     * 还可能把旧服务器批次发布到新 Watchdog。
     * <p>
     * 新实现：阶段 3 捕获前重新验证 stopRecovery + 恢复代数 + 已有批次。
     * 探针（preCaptureProbe）在"判定与捕获之间"暂停驱动线程，主线程执行
     * stop→start 后放行——旧线程必须放弃（任务仍在队列）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_stop_start_publish", timeoutTicks = 600)
    public void stoppedWatchdogMustNotPublishNewBatch(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.setStallCheckIgnorePausedForTest(true);
        ExecutorService pool = null;
        try {
            // 探针：阶段 1 判定后、阶段 3 重新取锁前阻塞驱动线程（仅触发时调用一次）
            CountDownLatch probeReached = new CountDownLatch(1);
            CountDownLatch releaseProbe = new CountDownLatch(1);
            wd.setPreCaptureProbe(() -> {
                probeReached.countDown();
                try {
                    releaseProbe.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // 驱动线程：第 3 次调用触发第一级判定 → 停在探针
            pool = Executors.newSingleThreadExecutor();
            long now = System.nanoTime();
            AtomicReference<Throwable> driverFailure = new AtomicReference<>();
            pool.submit(() -> {
                try {
                    wd.checkDrainStallForTest(scheduler, now);
                    wd.checkDrainStallForTest(scheduler, now);
                    wd.checkDrainStallForTest(scheduler, now);
                } catch (Throwable t) {
                    driverFailure.set(t);
                }
            });
            try {
                helper.assertTrue(probeReached.await(5, TimeUnit.SECONDS),
                        "探针应被触发（判定与捕获之间）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 停服 + 立即重启（评审场景：快速 stop/start——旧线程代数已过期）
            wd.stopRecoveryThread();
            wd.startRecoveryThread(scheduler);
            // 第 14 轮修复：stop→start 的新线程 1 秒后醒来——立即清 paused 排除
            // 开关，新线程检测时恢复"paused 不触发"语义（探针等待可能使测试体
            // 超过 1 秒，否则新线程会触发第一级 capture 并提交到永不运行的
            // override，代理永不完成残留——实测引发 processUnloads 忙转卡死）
            wd.setStallCheckIgnorePausedForTest(false);

            // 放行 → 旧驱动线程阶段 3 重新验证：代数不匹配 → 不得捕获发布
            releaseProbe.countDown();
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            helper.assertTrue(driverFailure.get() == null, "驱动线程不应异常");
            helper.assertTrue(!wd.hasActiveRecoveryBatch(), "stop/start 后旧线程不得发布新批次（P0-2）");
            helper.assertTrue(scheduler.pendingCount() == 1, "任务应仍在队列（未被捕获）");
        } finally {
            // 第 14 轮防御：断言失败中止也不得残留 override/开关/探针/恢复线程
            if (pool != null) {
                pool.shutdownNow();
            }
            wd.setStallCheckIgnorePausedForTest(false);
            scheduler.setResumeExecutorOverride(null);
            wd.stopRecoveryThread();
            // 第 14 轮根因修复：先清队列再复位——resetScheduler 的取消暂停会同步
            // 触发 requestDrain，队列残留任务（本测试故意保留的验证任务）会被
            // drain 抢走并以永不完成的原操作执行（inflight++ + 占 NOISE permit
            // 永久残留——实测 clear_all noiseAvail=0 inflight=1，级联 10 连挂）
            scheduler.clearAll(new IllegalStateException("GameTest cleanup"));
            resetScheduler(scheduler);
            wd.resetRecoveryMetrics();
        }
        helper.succeed();
    }

    /**
     * 第 14 轮 P0-3 修复验证：停服不得被同步 Future 回调无限阻塞。
     * <p>
     * 旧实现 stopRecoveryThread 在 ServerStopping 调用线程同步 escalate——
     * proxy.complete 同步运行挂载的回调，阻塞回调会卡住 ServerStopping，
     * finishServerShutdown 无法执行（与"不在 Server Thread 等待"停服目标冲突）。
     * <p>
     * 新实现：批次交给 emergency 分派器（每任务独立虚拟线程），stop 立即返回。
     * 本测试：任务回调阻塞 → stop 后流程必须能继续（finishServerShutdown 等价物）
     * → 放行回调 → 终态/指标最终归零。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_stop_nonblocking", timeoutTicks = 600)
    public void stopRecoveryThreadMustNotBlockOnCompletionCallback(GameTestHelper helper) {
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.setStallCheckIgnorePausedForTest(true);

        // 任务回调阻塞（模拟下游回调卡住）
        CountDownLatch callbackBlocked = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        queued.whenComplete((result, ex) -> {
            callbackBlocked.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 形成批次
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时 proxy 不应完成");

        // P0-3 修复验证：stop 必须立即返回（异步 emergency 分派）——
        // 若同步 complete，测试线程会卡在回调 await 而无法执行后续断言
        wd.stopRecoveryThread();
        try {
            helper.assertTrue(callbackBlocked.await(5, TimeUnit.SECONDS),
                    "emergency 虚拟线程应已触发回调（stop 不阻塞，回调在独立线程）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "批次应已脱离（同步）");

        // 回调仍阻塞期间：停服后续流程必须能继续（finishServerShutdown 等价物）
        coordinator.onServerShutdown();
        helper.assertTrue(scheduler.pendingCount() == 0, "停服清理后等待队列应清空");

        // 放行回调 → 虚拟线程完成 → 终态/指标归零
        releaseCallback.countDown();
        awaitTrue(() -> queued.isDone(), "任务应终态");
        helper.assertTrue(!queued.join().isSuccess(), "停服处置应为 error result");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "registration 应归零");
        awaitTrue(() -> wd.totalUnsafeShutdownRecoveries() == 1,
                "停服处置应计入 shutdown 指标（实际=" + wd.totalUnsafeShutdownRecoveries() + "）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0, "不得混入运行期指标");
        coordinator.onServerStopped();
        coordinator.onServerStart(); // 恢复接收，避免污染后续批次
        helper.assertTrue(!coordinator.isShutdownMode(), "恢复后应退出停服模式");

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
    }
}
