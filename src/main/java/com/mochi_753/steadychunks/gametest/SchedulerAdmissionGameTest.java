package com.mochi_753.steadychunks.gametest;

import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.ResourceType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.concurrent.CompletableFuture;

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
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        // 强制加载测试区块（GameTest 模板中心）
        ChunkAccess chunk = helper.getLevel().getChunk(0, 0);
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

        // 模拟维度卸载/停服：clearAll 应异常完成等待任务
        scheduler.clearAll(new IllegalStateException("Dimension unload"));
        helper.assertTrue(waiting.isDone(), "clearAll 后等待任务应被完成");
        helper.assertTrue(waiting.isCompletedExceptionally(), "clearAll 后等待任务应异常完成");
        helper.assertTrue(scheduler.pendingCount() == 0, "clearAll 后等待队列应清空");

        // 完成第一个任务，避免其挂起影响后续测试
        firstUnderlying.complete(ChunkResult.of(helper.getLevel().getChunk(0, 0)));
        resetScheduler(scheduler);
        helper.succeed();
    }
}
