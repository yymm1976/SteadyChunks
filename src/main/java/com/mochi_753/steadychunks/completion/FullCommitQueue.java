package com.mochi_753.steadychunks.completion;

import com.mochi_753.steadychunks.SteadyChunks;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FULL 整合队列，对应开发计划 §5.1。
 * <p>
 * 将可安全延迟的主线程整合工作放入有界队列，每 Tick 按预算执行：
 * <ul>
 *   <li>每 Tick 最大整合区块数</li>
 *   <li>每 Tick 最大预计成本（时间预算）</li>
 *   <li>近处和即将可见区块优先</li>
 *   <li>依赖其他任务的区块保底（独立预算）</li>
 *   <li>多玩家公平</li>
 * </ul>
 * <p>
 * 技术指导 §10.3：必须支持"关键任务旁路"——依赖关键任务立即执行，不进延迟队列。
 * <p>
 * 线程安全：队列使用 {@link PriorityBlockingQueue}，提交可来自工作线程，
 * 执行只在主线程 {@link #tick(long)} 中进行。
 */
public final class FullCommitQueue {
    private static FullCommitQueue instance;

    /** 延迟整合队列（可推迟的任务） */
    private final PriorityBlockingQueue<FullCommitTask> deferredQueue = new PriorityBlockingQueue<>(64);
    /** 关键任务队列（依赖关键任务，主线程优先排空，P0-3 线程安全修复） */
    private final java.util.concurrent.ConcurrentLinkedQueue<FullCommitTask> criticalQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 队列容量上限（防内存膨胀） */
    private volatile int queueCapacity = 256;
    /** 每 Tick 最大整合区块数 */
    private volatile int maxCommitsPerTick = 8;
    /** 每 Tick 时间预算（纳秒） */
    private volatile long budgetNanosPerTick = 10_000_000L; // 10ms
    /** 依赖关键任务的独立预算（每 Tick 保底数量） */
    private volatile int dependencyCriticalReserve = 2;

    /** 当前队列深度 */
    private final AtomicInteger queueDepth = new AtomicInteger(0);
    /** 累计执行任务数 */
    private final AtomicLong totalExecuted = new AtomicLong(0);
    /** 累计因预算不足被延迟的任务数 */
    private final AtomicLong totalDeferred = new AtomicLong(0);
    /** 累计因队列满被拒绝的任务数 */
    private final AtomicLong totalRejected = new AtomicLong(0);
    /** 最大回调积压（峰值） */
    private final AtomicInteger peakBacklog = new AtomicInteger(0);
    /** 最长等待时间（毫秒） */
    private final AtomicLong maxWaitMs = new AtomicLong(0);

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    /** P1-4 修复：任务接收开关。clear() 时先置 false，拒绝并发新提交，再清理队列。 */
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);

    private FullCommitQueue() {
    }

    public static synchronized FullCommitQueue getInstance() {
        if (instance == null) {
            instance = new FullCommitQueue();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        SteadyChunks.LOGGER.info("SteadyChunks FULL 整合队列: {}", on ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 提交整合任务（P0-3 线程安全修复 + 审查线程契约修复）。
     * <p>
     * <b>线程安全修复</b>：依赖关键任务不再在提交线程直接执行，
     * 而是入 criticalQueue，由主线程 tick() 优先排空。
     * <p>
     * <b>审查线程契约修复</b>：
     * <ul>
     *   <li>禁用时不再于调用线程直接执行 commit，而是通过服务器主线程
     *       {@code server.execute()} 执行，保持"FULL commit 必须主线程"的类契约。</li>
     *   <li>关键队列与普通队列共享同一容量上限（queueDepth），防止依赖风暴无限增长。</li>
     * </ul>
     *
     * @return true 表示已接受（入队或已调度执行），false 表示队列满被拒绝
     */
    public boolean submit(FullCommitTask task) {
        // P1-4：生命周期屏障 —— clear() 后不再接收新任务
        if (!acceptingTasks.get()) {
            totalRejected.incrementAndGet();
            return false;
        }
        if (!enabled.get()) {
            // 审查修复：禁用时经服务器主线程执行，不改变原版线程语义。
            // ServerLifecycleHooks.getCurrentServer() 为 null（如单元测试）时退化为直接执行。
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(() -> executeCommit(task));
            } else {
                executeCommit(task);
            }
            return true;
        }
        // P1-4 修复：CAS 原子预留容量（消除 check-then-act 竞态，多提交线程不会超限入队）
        if (!reserveQueueSlot()) {
            totalRejected.incrementAndGet();
            SteadyChunks.LOGGER.debug("FULL 整合队列已满，拒绝: {} (depth={})", task.pos(), queueDepth.get());
            return false;
        }
        // 依赖关键任务：入 criticalQueue，由主线程 tick 优先执行（P0-3 线程安全）
        boolean offered = task.dependencyCritical()
                ? criticalQueue.offer(task)
                : deferredQueue.offer(task);
        if (!offered) {
            // 入队失败：回滚预留容量
            queueDepth.decrementAndGet();
            totalRejected.incrementAndGet();
            return false;
        }
        peakBacklog.accumulateAndGet(queueDepth.get(), Math::max);
        return true;
    }

    /**
     * P1-4 修复：CAS 原子预留一个队列槽位。
     * <p>
     * 旧实现"检查 queueDepth >= capacity 后再 incrementAndGet"存在 check-then-act 竞态：
     * 多个提交线程可同时通过检查后全部入队，导致队列超过上限。
     *
     * @return true 表示预留成功（调用方必须随后入队并计入实际深度）
     */
    private boolean reserveQueueSlot() {
        while (true) {
            int current = queueDepth.get();
            if (current >= queueCapacity) {
                return false;
            }
            if (queueDepth.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 每 Tick 在主线程调用：按预算执行队列中的整合任务（P0-3 线程安全修复）。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>先排空 criticalQueue（依赖关键任务，有限数量，不占普通预算）</li>
     *   <li>再按优先级执行 deferredQueue，直到数量或时间预算耗尽</li>
     * </ol>
     *
     * @param deadlineNanos 本 Tick 截止时间（System.nanoTime() + budget）
     */
    public void tick(long deadlineNanos) {
        if (!enabled.get()) {
            return;
        }
        int executed = 0;
        int deferred = 0;

        // 1. 先排空关键队列（依赖关键任务旁路，不占普通预算，但仍受 maxCommitsPerTick 上限保护）
        int criticalBudget = Math.min(dependencyCriticalReserve, maxCommitsPerTick);
        while (executed < criticalBudget) {
            FullCommitTask task = criticalQueue.poll();
            if (task == null) {
                break;
            }
            queueDepth.decrementAndGet();
            long waitMs = task.queueAgeMs();
            maxWaitMs.accumulateAndGet(waitMs, Math::max);
            executeCommit(task);
            totalExecuted.incrementAndGet();
            executed++;
        }

        // 2. 执行延迟队列，受 maxCommitsPerTick 总上限和时间预算约束
        while (executed < maxCommitsPerTick) {
            FullCommitTask task = deferredQueue.poll();
            if (task == null) {
                break;
            }
            queueDepth.decrementAndGet();

            // 时间预算检查
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 && executed >= dependencyCriticalReserve) {
                // 预算耗尽且已执行保底数量，剩余延迟
                // 注意：必须 break 而非 continue，否则循环会立即再次取出同一任务重新入队，
                // 导致 executed 不增长、deadline 仍过期，形成主线程无限循环（P0-3 修复）
                deferredQueue.offer(task);
                queueDepth.incrementAndGet();
                totalDeferred.incrementAndGet();
                deferred++;
                break;
            }

            // 更新最长等待
            long waitMs = task.queueAgeMs();
            maxWaitMs.accumulateAndGet(waitMs, Math::max);

            // 执行（审查修复：完成 completion Future）
            executeCommit(task);
            totalExecuted.incrementAndGet();
            executed++;
        }

        if (deferred > 0 && totalDeferred.get() % 100 == 0) {
            SteadyChunks.LOGGER.debug("FULL 整合积压: deferred={} depth={}", deferred, queueDepth.get());
        }
    }

    /**
     * 执行单个整合任务并完成 completion Future（审查修复）。
     * <p>
     * 无论 commitAction 是否抛异常，completion Future 都会被完成：
     * <ul>
     *   <li>正常完成 → {@code completion.complete(null)}</li>
     *   <li>抛异常 → {@code completion.completeExceptionally(ex)}</li>
     * </ul>
     * 异常仅记录日志，不向上传播，避免单个任务失败中断整批执行。
     */
    private void executeCommit(FullCommitTask task) {
        Throwable error = null;
        try {
            task.commitAction().run();
        } catch (Throwable t) {
            error = t;
            SteadyChunks.LOGGER.warn("FULL 整合任务执行失败: {} {}", task.pos(), t.getMessage());
        }
        CompletableFuture<Void> completion = task.completion();
        if (completion != null) {
            if (error != null) {
                completion.completeExceptionally(error);
            } else {
                completion.complete(null);
            }
        }
    }

    /**
     * 清空队列（如世界卸载）。
     * <p>
     * 审查修复：不静默丢弃任务，对每个任务的 completion Future 给出明确异常，
     * 避免依赖这些任务完成通知的调用方永久等待。
     * <p>
     * P1-4 修复：先置 acceptingTasks=false 拒绝并发新提交（生命周期屏障），
     * 再清理队列，避免清理后并发提交被 queueDepth.set(0) 错误覆盖计数。
     */
    public void clear() {
        // 生命周期屏障：拒绝新任务（停服/卸载场景语义正确）
        acceptingTasks.set(false);
        CancellationException cause = new CancellationException("Server stopping / world unload");
        FullCommitTask task;
        while ((task = criticalQueue.poll()) != null) {
            queueDepth.decrementAndGet();
            completeWithCancellation(task, cause);
        }
        while ((task = deferredQueue.poll()) != null) {
            queueDepth.decrementAndGet();
            completeWithCancellation(task, cause);
        }
        queueDepth.set(0);
        // 停服清理完成：允许后续提交（如单测/世界重载场景）
        acceptingTasks.set(true);
    }

    /**
     * 异常完成任务的 completion Future（如存在）。
     */
    private void completeWithCancellation(FullCommitTask task, CancellationException cause) {
        CompletableFuture<Void> completion = task.completion();
        if (completion != null) {
            completion.completeExceptionally(cause);
        }
    }

    // 配置访问器
    public void setQueueCapacity(int cap) { this.queueCapacity = cap; }
    public void setMaxCommitsPerTick(int max) { this.maxCommitsPerTick = max; }
    public void setBudgetNanosPerTick(long nanos) { this.budgetNanosPerTick = nanos; }
    public void setDependencyCriticalReserve(int reserve) { this.dependencyCriticalReserve = reserve; }
    public int maxCommitsPerTick() { return maxCommitsPerTick; }
    public long budgetNanosPerTick() { return budgetNanosPerTick; }
    public int dependencyCriticalReserve() { return dependencyCriticalReserve; }

    // 诊断访问器
    public int queueDepth() { return queueDepth.get(); }
    public long totalExecuted() { return totalExecuted.get(); }
    public long totalDeferred() { return totalDeferred.get(); }
    public long totalRejected() { return totalRejected.get(); }
    public int peakBacklog() { return peakBacklog.get(); }
    public long maxWaitMs() { return maxWaitMs.get(); }
}
