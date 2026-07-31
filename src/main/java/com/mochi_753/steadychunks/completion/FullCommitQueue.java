package com.mochi_753.steadychunks.completion;

import com.mochi_753.steadychunks.SteadyChunks;

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
     * 提交整合任务。
     * <p>
     * 依赖关键任务直接执行（技术指导 §10.3 关键任务旁路）；
     * 其余进入延迟队列等待预算。
     *
     * @return true 表示已接受（执行或入队），false 表示队列满被拒绝
     */
    public boolean submit(FullCommitTask task) {
        if (!enabled.get()) {
            // 未启用时直接执行，不延迟
            task.commitAction().run();
            return true;
        }
        // 依赖关键任务：立即执行（不占用普通预算）
        if (task.dependencyCritical()) {
            task.commitAction().run();
            totalExecuted.incrementAndGet();
            return true;
        }
        // 容量检查
        if (queueDepth.get() >= queueCapacity) {
            totalRejected.incrementAndGet();
            SteadyChunks.LOGGER.debug("FULL 整合队列已满，拒绝: {} (depth={})", task.pos(), queueDepth.get());
            return false;
        }
        deferredQueue.offer(task);
        int depth = queueDepth.incrementAndGet();
        peakBacklog.accumulateAndGet(depth, Math::max);
        return true;
    }

    /**
     * 每 Tick 在主线程调用：按预算执行队列中的整合任务。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>先执行依赖关键任务（已直接执行，此处处理延迟队列中标记为 critical 的）</li>
     *   <li>再按优先级执行普通任务，直到数量或时间预算耗尽</li>
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
                deferredQueue.offer(task);
                queueDepth.incrementAndGet();
                totalDeferred.incrementAndGet();
                deferred++;
                continue;
            }

            // 更新最长等待
            long waitMs = task.queueAgeMs();
            maxWaitMs.accumulateAndGet(waitMs, Math::max);

            // 执行
            try {
                task.commitAction().run();
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn("FULL 整合任务执行失败: {} {}", task.pos(), t.getMessage());
            }
            totalExecuted.incrementAndGet();
            executed++;
        }

        if (deferred > 0 && totalDeferred.get() % 100 == 0) {
            SteadyChunks.LOGGER.debug("FULL 整合积压: deferred={} depth={}", deferred, queueDepth.get());
        }
    }

    /**
     * 清空队列（如世界卸载）。
     */
    public void clear() {
        deferredQueue.clear();
        queueDepth.set(0);
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
