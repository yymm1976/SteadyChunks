package com.mochi_753.steadychunks.gametest;

import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.ResourceType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 调度器 NOISE 准入 GameTest（审查建议的第 8 项）。
 * <p>
 * 在真实 Minecraft 运行环境中验证调度器核心链路（无需真实区块生成）：
 * <ol>
 *   <li>NOISE permit=1 时，第二个 controlAdmission 被挂起（进入等待队列）</li>
 *   <li>第一个任务完成后 permit 释放，第二个任务自动恢复并完成</li>
 *   <li>admissionPaused 时新任务进入等待队列且不被 drain 启动</li>
 *   <li>clearAll 异常完成所有等待任务，无永久未完成 Future</li>
 * </ol>
 * <p>
 * 运行方式：{@code ./gradlew runGameTestServer}（或 IDE run config "gameTestServer"）。
 */
@GameTestHolder("steadychunks")
public class SchedulerAdmissionGameTest {

    @GameTest(template = "empty")
    public void noisePermitOneShouldQueueAndResume(GameTestHelper helper) {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        // 强制 NOISE 资源上限为 1（覆盖配置默认值）
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        scheduler.setAdmissionPaused(false);

        try {
            // 已完成的结果（模拟原版 applyStep 同步完成）
            CompletableFuture<ChunkResult<ChunkAccess>> doneFuture = CompletableFuture.completedFuture(
                    ChunkResult.of(helper.getLevel().getChunk(0, 0)));

            // 1. 第一个 NOISE 任务：permit=1 可用，立即执行（返回完成 Future）
            CompletableFuture<ChunkResult<ChunkAccess>> first = scheduler.controlAdmission(
                    ChunkStatus.NOISE, false, () -> doneFuture);
            helper.assertTrue(first.isDone(), "第一个 NOISE 任务应获得 permit 并立即执行");

            // 2. 第二个 NOISE 任务：permit 已耗尽，应进入等待队列（代理 Future 未完成）
            CompletableFuture<ChunkResult<ChunkAccess>> second = scheduler.controlAdmission(
                    ChunkStatus.NOISE, false, () -> doneFuture);
            helper.assertFalse(second.isDone(), "第二个 NOISE 任务在 permit=1 时应被挂起");
            helper.assertTrue(scheduler.pendingCount() == 1, "等待队列深度应为 1，实际: " + scheduler.pendingCount());

            // 3. 等待第一个任务完成（whenComplete 已释放 permit）并恢复第二个任务
            try {
                helper.assertTrue(
                        second.get(30, TimeUnit.SECONDS) != null,
                        "permit 释放后第二个任务应在 30 秒内恢复完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待第二个任务被中断", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("第二个任务异常完成", e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                throw new RuntimeException("第二个任务 30 秒内未完成（可能永久挂起）", e);
            }
            helper.assertTrue(scheduler.pendingCount() == 0, "恢复后等待队列应清空");
        } finally {
            // 清理：避免影响其他测试（GameTest 会自动重置结构模板）
            scheduler.setEnabled(false);
            scheduler.clearAll(new IllegalStateException("GameTest cleanup"));
        }
    }

    @GameTest(template = "empty")
    public void admissionPausedShouldBlockNewTasks(GameTestHelper helper) {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 2);
        scheduler.setAdmissionPaused(true);

        try {
            CompletableFuture<ChunkResult<ChunkAccess>> doneFuture = CompletableFuture.completedFuture(
                    ChunkResult.of(helper.getLevel().getChunk(0, 0)));

            // 暂停状态下新任务应进入等待队列，而不是执行
            CompletableFuture<ChunkResult<ChunkAccess>> gated = scheduler.controlAdmission(
                    ChunkStatus.NOISE, false, () -> doneFuture);
            helper.assertFalse(gated.isDone(), "admissionPaused 时任务应被挂起");
            helper.assertTrue(scheduler.pendingCount() == 1, "暂停时等待队列深度应为 1");

            // 即使 tick 多次，暂停任务也不应被 drain 启动
            for (int i = 0; i < 5; i++) {
                scheduler.tick();
            }
            helper.assertFalse(gated.isDone(), "暂停期间任务不应被 drainPending 启动");

            // 恢复准入后任务应完成
            scheduler.setAdmissionPaused(false);
            try {
                helper.assertTrue(
                        gated.get(30, TimeUnit.SECONDS) != null,
                        "恢复准入后任务应在 30 秒内完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待恢复任务被中断", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("恢复任务异常完成", e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                throw new RuntimeException("恢复任务 30 秒内未完成（可能永久挂起）", e);
            }
        } finally {
            scheduler.setEnabled(false);
            scheduler.setAdmissionPaused(false);
            scheduler.clearAll(new IllegalStateException("GameTest cleanup"));
        }
    }

    @GameTest(template = "empty")
    public void clearAllShouldCompleteWaitingTasks(GameTestHelper helper) {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        scheduler.setAdmissionPaused(false);

        try {
            CompletableFuture<ChunkResult<ChunkAccess>> doneFuture = CompletableFuture.completedFuture(
                    ChunkResult.of(helper.getLevel().getChunk(0, 0)));

            // 占满 permit，制造一个等待任务
            scheduler.controlAdmission(ChunkStatus.NOISE, false, () -> doneFuture);
            CompletableFuture<ChunkResult<ChunkAccess>> waiting = scheduler.controlAdmission(
                    ChunkStatus.NOISE, false, () -> doneFuture);
            helper.assertTrue(scheduler.pendingCount() == 1, "应存在 1 个等待任务");

            // 模拟维度卸载/停服：clearAll 应异常完成等待任务
            scheduler.clearAll(new IllegalStateException("Dimension unload"));
            helper.assertTrue(waiting.isDone(), "clearAll 后等待任务应被完成");
            helper.assertTrue(waiting.isCompletedExceptionally(), "clearAll 后等待任务应异常完成");
            helper.assertTrue(scheduler.pendingCount() == 0, "clearAll 后等待队列应清空");
        } finally {
            scheduler.setEnabled(false);
            scheduler.clearAll(new IllegalStateException("GameTest cleanup"));
        }
    }
}
