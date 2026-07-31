package com.mochi_753.steadychunks.completion;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 完成批次整形器，对应开发计划 §5.2，P1-12 修复 + 审查三队列独立预算修复。
 * <p>
 * <b>审查修复</b>：提交时按安全等级分入三个独立队列，每类拥有独立计数预算。
 * 旧实现先从单一 FIFO 队列取固定数量再排序，排在后面的依赖关键回调
 * 无法获得全局优先级。新实现在 submit 时即分队列，tick 时每类独立消费各自预算。
 * <p>
 * 避免多个阶段在同一时间窗口全部释放结果：
 * <ul>
 *   <li>对 LIGHT → FULL 建立完成预算</li>
 *   <li>对大量 Future 的回调进行分批排放</li>
 *   <li>禁止在单一 Tick 无上限执行完成回调</li>
 *   <li>记录回调积压和最长等待时间</li>
 * </ul>
 */
public final class CompletionBatchShaper {
    private static CompletionBatchShaper instance;

    // 审查修复：三类独立队列，submit 时即分流
    private final ConcurrentLinkedQueue<BatchedCompletion> criticalQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BatchedCompletion> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BatchedCompletion> deferrableQueue = new ConcurrentLinkedQueue<>();

    // 审查修复：每类独立计数预算，互不抢占
    private volatile int criticalBudgetPerTick = 8;
    private volatile int mainThreadBudgetPerTick = 4;
    private volatile int deferrableBudgetPerTick = 4;
    /** 每 Tick 时间预算（纳秒），三类共享截止时间 */
    private volatile long budgetNanosPerTick = 5_000_000L; // 5ms
    /** 队列总容量上限 */
    private volatile int queueCapacity = 512;

    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalDeferred = new AtomicLong(0);
    private final AtomicInteger peakBacklog = new AtomicInteger(0);
    private final AtomicLong maxWaitMs = new AtomicLong(0);
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private CompletionBatchShaper() {
    }

    public static synchronized CompletionBatchShaper getInstance() {
        if (instance == null) {
            instance = new CompletionBatchShaper();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        SteadyChunks.LOGGER.info("SteadyChunks 完成批次整形器: {}", on ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 提交一个完成回调（安全等级默认 DEFERRABLE）。
     */
    public boolean submit(long chunkPos, Runnable callback) {
        return submit(chunkPos, callback, CompletionSafety.DEFERRABLE);
    }

    /**
     * 提交一个完成回调，指定安全等级（审查修复：提交时即分队列）。
     * <p>
     * UNKNOWN 安全等级的回调走原版路径（立即执行），不进入整形队列。
     *
     * @return true 表示已入队或已执行，false 表示队列满
     */
    public boolean submit(long chunkPos, Runnable callback, CompletionSafety safety) {
        if (safety == CompletionSafety.UNKNOWN || !enabled.get()) {
            callback.run();
            return true;
        }
        int depth = pendingCount.get();
        if (depth >= queueCapacity) {
            totalDeferred.incrementAndGet();
            return false;
        }
        BatchedCompletion entry = new BatchedCompletion(chunkPos, callback, safety, System.nanoTime());
        switch (safety) {
            case DEPENDENCY_CRITICAL -> criticalQueue.offer(entry);
            case MAIN_THREAD_REQUIRED -> mainThreadQueue.offer(entry);
            case DEFERRABLE -> deferrableQueue.offer(entry);
            default -> { /* UNKNOWN 已提前返回 */ }
        }
        int newSize = pendingCount.incrementAndGet();
        peakBacklog.accumulateAndGet(newSize, Math::max);
        return true;
    }

    /**
     * 每 Tick 在主线程调用：按安全等级优先级批量执行完成回调（审查修复：三类独立预算）。
     * <p>
     * 执行顺序：DEPENDENCY_CRITICAL → MAIN_THREAD_REQUIRED → DEFERRABLE。
     * 每类拥有独立计数预算，互不抢占。时间预算共享截止时间。
     *
     * @param deadlineNanos 本 Tick 截止时间
     */
    public void tick(long deadlineNanos) {
        if (!enabled.get()) {
            return;
        }
        // 1. DEPENDENCY_CRITICAL：独立预算，最高优先级，不受时间预算约束
        drainQueue(criticalQueue, criticalBudgetPerTick, deadlineNanos, false);
        // 2. MAIN_THREAD_REQUIRED：独立预算，受时间预算约束
        drainQueue(mainThreadQueue, mainThreadBudgetPerTick, deadlineNanos, true);
        // 3. DEFERRABLE：独立预算，受时间预算约束
        drainQueue(deferrableQueue, deferrableBudgetPerTick, deadlineNanos, true);
    }

    /**
     * 从指定队列消费最多 budget 个任务。
     *
     * @param checkTimeBudget 是否检查时间预算（DEPENDENCY_CRITICAL 不检查，保证依赖解锁不被延迟）
     */
    private void drainQueue(ConcurrentLinkedQueue<BatchedCompletion> queue, int budget,
                            long deadlineNanos, boolean checkTimeBudget) {
        int executed = 0;
        while (executed < budget) {
            BatchedCompletion c = queue.poll();
            if (c == null) {
                break;
            }
            // 时间预算检查（DEPENDENCY_CRITICAL 跳过，保证依赖解锁优先）
            if (checkTimeBudget && executed > 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    // 预算耗尽，放回队列等待下 Tick
                    queue.offer(c);
                    totalDeferred.incrementAndGet();
                    break;
                }
            }
            pendingCount.decrementAndGet();
            long waitMs = c.queueAgeMs();
            maxWaitMs.accumulateAndGet(waitMs, Math::max);
            try {
                c.callback().run();
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn("完成回调执行失败: chunk={} {}", c.chunkPos(), t.getMessage());
            }
            totalProcessed.incrementAndGet();
            executed++;
        }
    }

    /**
     * 清空所有队列（如世界卸载）。
     */
    public void clear() {
        criticalQueue.clear();
        mainThreadQueue.clear();
        deferrableQueue.clear();
        pendingCount.set(0);
    }

    // === 配置访问器 ===

    /**
     * 兼容方法：将总量按 50%/25%/25% 分配到三类独立预算。
     * 优先使用 {@link #setCriticalBudgetPerTick} 等细粒度方法。
     */
    public void setMaxCallbacksPerTick(int total) {
        this.criticalBudgetPerTick = Math.max(1, total / 2);
        this.mainThreadBudgetPerTick = Math.max(1, total / 4);
        this.deferrableBudgetPerTick = Math.max(1, total / 4);
    }

    public void setCriticalBudgetPerTick(int max) { this.criticalBudgetPerTick = max; }
    public void setMainThreadBudgetPerTick(int max) { this.mainThreadBudgetPerTick = max; }
    public void setDeferrableBudgetPerTick(int max) { this.deferrableBudgetPerTick = max; }
    public void setBudgetNanosPerTick(long nanos) { this.budgetNanosPerTick = nanos; }
    public void setQueueCapacity(int cap) { this.queueCapacity = cap; }

    public int maxCallbacksPerTick() {
        return criticalBudgetPerTick + mainThreadBudgetPerTick + deferrableBudgetPerTick;
    }
    public int criticalBudgetPerTick() { return criticalBudgetPerTick; }
    public int mainThreadBudgetPerTick() { return mainThreadBudgetPerTick; }
    public int deferrableBudgetPerTick() { return deferrableBudgetPerTick; }
    public long budgetNanosPerTick() { return budgetNanosPerTick; }

    // === 诊断访问器 ===

    public int pendingCount() { return pendingCount.get(); }
    public long totalProcessed() { return totalProcessed.get(); }
    public long totalDeferred() { return totalDeferred.get(); }
    public int peakBacklog() { return peakBacklog.get(); }
    public long maxWaitMs() { return maxWaitMs.get(); }

    /**
     * 完成回调安全等级（P1-12）。
     */
    public enum CompletionSafety {
        /** 依赖关键：必须尽快执行，不延迟（如解锁下游任务） */
        DEPENDENCY_CRITICAL,
        /** 必须在主线程执行，但可延迟（如 FULL commit） */
        MAIN_THREAD_REQUIRED,
        /** 可延迟执行（如通知、统计更新） */
        DEFERRABLE,
        /** 未知安全等级：走原版路径，不进入整形队列 */
        UNKNOWN
    }

    /** 单个完成回调包装 */
    private record BatchedCompletion(long chunkPos, Runnable callback, CompletionSafety safety, long enqueueNanos) {
        long queueAgeMs() {
            return (System.nanoTime() - enqueueNanos) / 1_000_000L;
        }
    }
}
