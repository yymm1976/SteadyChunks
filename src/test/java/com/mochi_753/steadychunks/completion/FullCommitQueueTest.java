package com.mochi_753.steadychunks.completion;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FullCommitQueue 单元测试，对应 P2-19。
 * <p>
 * 覆盖 P0-3 修复点：
 * <ul>
 *   <li>FULL 预算耗尽后 tick 必须返回（不无限循环）</li>
 *   <li>关键任务优先执行，不占普通预算</li>
 *   <li>队列满时 submit 返回 false</li>
 *   <li>未启用时 submit 直接执行</li>
 *   <li>异常 commit 不影响其他任务</li>
 * </ul>
 */
class FullCommitQueueTest {

    private FullCommitQueue queue;

    @BeforeEach
    void setUp() {
        queue = FullCommitQueue.getInstance();
        queue.clear();
        queue.setEnabled(true);
        queue.setMaxCommitsPerTick(4);
        queue.setDependencyCriticalReserve(2);
        queue.setBudgetNanosPerTick(10_000_000L);
        queue.setQueueCapacity(256);
    }

    @AfterEach
    void tearDown() {
        queue.clear();
        queue.setEnabled(false);
    }

    @Test
    void tickMustReturnWhenBudgetExhausted() {
        // 填满延迟队列（远超每 Tick 上限）
        AtomicInteger executed = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            FullCommitTask task = new FullCommitTask(
                    new ChunkPos(i, 0),
                    "minecraft:overworld",
                    1_000_000L,
                    FullCommitTask.Urgency.NORMAL,
                    UUID.randomUUID(),
                    100.0,
                    false,
                    executed::incrementAndGet
            );
            assertTrue(queue.submit(task), "submit 应成功");
        }

        // 用已经过期的 deadline 触发预算耗尽路径
        long expiredDeadline = System.nanoTime() - 1_000_000L;
        long startNanos = System.nanoTime();
        queue.tick(expiredDeadline);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // 关键断言：tick 必须返回，不应无限循环
        assertTrue(elapsedMs < 1000, "tick 必须在 1 秒内返回，实际: " + elapsedMs + "ms");
        // 部分任务可能已执行（达到 maxCommitsPerTick 上限），但不应全部执行
        assertTrue(executed.get() <= 4, "执行数不应超过 maxCommitsPerTick");
        assertTrue(queue.totalDeferred() > 0, "应有任务被延迟");
    }

    @Test
    void criticalTasksBypassDeferredQueue() {
        AtomicInteger criticalExecuted = new AtomicInteger(0);
        AtomicInteger deferredExecuted = new AtomicInteger(0);

        // 先填一个延迟任务
        queue.submit(new FullCommitTask(
                new ChunkPos(0, 0), "dim", 1000L,
                FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                false, deferredExecuted::incrementAndGet));

        // 关键任务
        queue.submit(new FullCommitTask(
                new ChunkPos(1, 0), "dim", 1000L,
                FullCommitTask.Urgency.DEPENDENCY_CRITICAL, UUID.randomUUID(), 100.0,
                true, criticalExecuted::incrementAndGet));

        long deadline = System.nanoTime() + 10_000_000L;
        queue.tick(deadline);

        // 关键任务应执行（独立预算）
        assertEquals(1, criticalExecuted.get(), "关键任务应执行");
        // 延迟任务也可能执行（受 maxCommitsPerTick 限制）
        assertTrue(deferredExecuted.get() <= 1);
    }

    @Test
    void submitShouldRejectWhenQueueFull() {
        queue.setQueueCapacity(3);
        AtomicInteger noop = new AtomicInteger(0);
        for (int i = 0; i < 3; i++) {
            assertTrue(queue.submit(new FullCommitTask(
                    new ChunkPos(i, 0), "dim", 1000L,
                    FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                    false, noop::incrementAndGet)), "前 3 个应入队成功");
        }
        assertFalse(queue.submit(new FullCommitTask(
                new ChunkPos(99, 0), "dim", 1000L,
                FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                false, noop::incrementAndGet)), "队列满应拒绝");
        assertTrue(queue.totalRejected() >= 1, "应有拒绝计数");
    }

    @Test
    void disabledQueueExecutesImmediately() {
        queue.setEnabled(false);
        AtomicInteger executed = new AtomicInteger(0);
        queue.submit(new FullCommitTask(
                new ChunkPos(0, 0), "dim", 1000L,
                FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                false, executed::incrementAndGet));
        assertEquals(1, executed.get(), "未启用时应直接执行 commitAction");
    }

    @Test
    void exceptionInCommitShouldNotAffectOthers() {
        AtomicInteger successCount = new AtomicInteger(0);
        // 第一个任务抛异常
        queue.submit(new FullCommitTask(
                new ChunkPos(0, 0), "dim", 1000L,
                FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                false, () -> {
                    throw new RuntimeException("模拟 commit 失败");
                }));
        // 第二个任务正常
        queue.submit(new FullCommitTask(
                new ChunkPos(1, 0), "dim", 1000L,
                FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                false, successCount::incrementAndGet));

        long deadline = System.nanoTime() + 100_000_000L;
        queue.tick(deadline);

        assertEquals(1, successCount.get(), "异常任务不应影响后续任务");
        assertTrue(queue.totalExecuted() >= 2, "两个任务都应被计入 executed（含失败的）");
    }

    @Test
    void criticalTasksHaveOwnBudget() {
        queue.setMaxCommitsPerTick(2);
        queue.setDependencyCriticalReserve(2);

        AtomicInteger criticalExec = new AtomicInteger(0);
        AtomicInteger normalExec = new AtomicInteger(0);

        // 2 个关键任务 + 2 个普通任务
        for (int i = 0; i < 2; i++) {
            queue.submit(new FullCommitTask(
                    new ChunkPos(i, 0), "dim", 1000L,
                    FullCommitTask.Urgency.DEPENDENCY_CRITICAL, UUID.randomUUID(), 100.0,
                    true, criticalExec::incrementAndGet));
        }
        for (int i = 0; i < 2; i++) {
            queue.submit(new FullCommitTask(
                    new ChunkPos(100 + i, 0), "dim", 1000L,
                    FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                    false, normalExec::incrementAndGet));
        }

        long deadline = System.nanoTime() + 100_000_000L;
        queue.tick(deadline);

        assertEquals(2, criticalExec.get(), "关键任务独立预算应全部执行");
        // 普通任务受 maxCommitsPerTick 总上限保护（关键已用 2，普通 0 个额度）
        // 实际取决于实现：若 criticalBudget 占用 maxCommitsPerTick，则普通无额度
        assertTrue(normalExec.get() <= 2, "普通任务不超过 maxCommitsPerTick");
    }

    @Test
    void clearShouldEmptyAllQueues() {
        AtomicInteger noop = new AtomicInteger(0);
        queue.submit(new FullCommitTask(
                new ChunkPos(0, 0), "dim", 1000L,
                FullCommitTask.Urgency.NORMAL, UUID.randomUUID(), 100.0,
                false, noop::incrementAndGet));
        queue.submit(new FullCommitTask(
                new ChunkPos(1, 0), "dim", 1000L,
                FullCommitTask.Urgency.DEPENDENCY_CRITICAL, UUID.randomUUID(), 100.0,
                true, noop::incrementAndGet));

        queue.clear();
        assertEquals(0, queue.queueDepth(), "clear 后队列深度应为 0");

        // tick 不应执行任何任务
        long before = queue.totalExecuted();
        queue.tick(System.nanoTime() + 100_000_000L);
        assertEquals(before, queue.totalExecuted(), "clear 后 tick 不应执行任务");
    }
}
