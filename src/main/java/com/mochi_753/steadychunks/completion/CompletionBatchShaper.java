package com.mochi_753.steadychunks.completion;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 完成批次整形器，对应开发计划 §5.2，P1-12 修复。
 * <p>
 * <b>修复内容</b>：
 * <ul>
 *   <li>{@code ArrayList.remove(0)}（O(n)）改为 {@link ConcurrentLinkedQueue} + {@link ArrayDeque}</li>
 *   <li>按 {@link CompletionSafety} 分级：DEPENDENCY_CRITICAL 优先，MAIN_THREAD_REQUIRED 次之，DEFERRABLE 最后</li>
 *   <li>未知安全等级的回调走原版路径（立即执行），不进入整形队列</li>
 * </ul>
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

    /** 待处理的完成回调队列（线程安全，工作线程 submit，主线程 drain） */
    private final ConcurrentLinkedQueue<BatchedCompletion> pending = new ConcurrentLinkedQueue<>();
    /** 每 Tick 最大回调执行数 */
    private volatile int maxCallbacksPerTick = 16;
    /** 每 Tick 时间预算（纳秒） */
    private volatile long budgetNanosPerTick = 5_000_000L; // 5ms
    /** 队列容量上限 */
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
     *
     * @param chunkPos 区块位置（用于诊断）
     * @param callback 完成回调
     * @return true 表示已入队，false 表示队列满
     */
    public boolean submit(long chunkPos, Runnable callback) {
        return submit(chunkPos, callback, CompletionSafety.DEFERRABLE);
    }

    /**
     * 提交一个完成回调，指定安全等级（P1-12）。
     * <p>
     * <b>UNKNOWN</b> 安全等级的回调走原版路径（立即执行），不进入整形队列，
     * 避免未知回调被延迟导致语义错误。
     *
     * @param chunkPos 区块位置（用于诊断）
     * @param callback 完成回调
     * @param safety   安全等级
     * @return true 表示已入队或已执行，false 表示队列满
     */
    public boolean submit(long chunkPos, Runnable callback, CompletionSafety safety) {
        // UNKNOWN 或未启用时走原版路径
        if (safety == CompletionSafety.UNKNOWN || !enabled.get()) {
            callback.run();
            return true;
        }
        int depth = pendingCount.get();
        if (depth >= queueCapacity) {
            totalDeferred.incrementAndGet();
            return false;
        }
        pending.offer(new BatchedCompletion(chunkPos, callback, safety, System.nanoTime()));
        int newSize = pendingCount.incrementAndGet();
        peakBacklog.accumulateAndGet(newSize, Math::max);
        return true;
    }

    /**
     * 每 Tick 在主线程调用：按安全等级优先级批量执行完成回调（P1-12）。
     * <p>
     * 执行顺序：DEPENDENCY_CRITICAL → MAIN_THREAD_REQUIRED → DEFERRABLE。
     * 使用 ArrayDeque 按优先级分组后批量执行，避免 ArrayList.remove(0) 的 O(n) 开销。
     *
     * @param deadlineNanos 本 Tick 截止时间
     */
    public void tick(long deadlineNanos) {
        if (!enabled.get() || pending.isEmpty()) {
            return;
        }

        // 按安全等级分组到 ArrayDeque（主线程操作，无需加锁）
        ArrayDeque<BatchedCompletion> critical = new ArrayDeque<>();
        ArrayDeque<BatchedCompletion> mainThread = new ArrayDeque<>();
        ArrayDeque<BatchedCompletion> deferrable = new ArrayDeque<>();

        int drained = 0;
        BatchedCompletion c;
        while (drained < maxCallbacksPerTick && (c = pending.poll()) != null) {
            switch (c.safety()) {
                case DEPENDENCY_CRITICAL -> critical.add(c);
                case MAIN_THREAD_REQUIRED -> mainThread.add(c);
                case DEFERRABLE -> deferrable.add(c);
                case UNKNOWN -> critical.add(c); // UNKNOWN 不会入队，防御性处理
            }
            drained++;
            pendingCount.decrementAndGet();
        }

        int executed = 0;
        int budget = maxCallbacksPerTick;

        // 1. DEPENDENCY_CRITICAL 优先（不占普通预算）
        while (!critical.isEmpty() && executed < budget) {
            executed += executeOne(critical.pollFirst(), deadlineNanos, executed);
        }
        // 2. MAIN_THREAD_REQUIRED 次之
        while (!mainThread.isEmpty() && executed < budget) {
            executed += executeOne(mainThread.pollFirst(), deadlineNanos, executed);
        }
        // 3. DEFERRABLE 最后，受时间预算约束
        while (!deferrable.isEmpty() && executed < budget) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 && executed > 0) {
                // 预算耗尽，剩余放回队列
                for (BatchedCompletion back : deferrable) {
                    pending.offer(back);
                    pendingCount.incrementAndGet();
                }
                totalDeferred.addAndGet(deferrable.size());
                break;
            }
            executed += executeOne(deferrable.pollFirst(), deadlineNanos, executed);
        }

        // mainThread 和 critical 中未执行的（理论上不应有）放回队列
        for (BatchedCompletion back : critical) {
            pending.offer(back);
            pendingCount.incrementAndGet();
        }
        for (BatchedCompletion back : mainThread) {
            pending.offer(back);
            pendingCount.incrementAndGet();
        }
    }

    /**
     * 执行单个回调，返回执行数（0 或 1）。
     */
    private int executeOne(BatchedCompletion c, long deadlineNanos, int executed) {
        long waitMs = c.queueAgeMs();
        maxWaitMs.accumulateAndGet(waitMs, Math::max);
        try {
            c.callback().run();
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("完成回调执行失败: chunk={} {}", c.chunkPos(), t.getMessage());
        }
        totalProcessed.incrementAndGet();
        return 1;
    }

    /**
     * 清空队列（如世界卸载）。
     */
    public void clear() {
        pending.clear();
        pendingCount.set(0);
    }

    // 配置访问器
    public void setMaxCallbacksPerTick(int max) { this.maxCallbacksPerTick = max; }
    public void setBudgetNanosPerTick(long nanos) { this.budgetNanosPerTick = nanos; }
    public void setQueueCapacity(int cap) { this.queueCapacity = cap; }
    public int maxCallbacksPerTick() { return maxCallbacksPerTick; }
    public long budgetNanosPerTick() { return budgetNanosPerTick; }

    // 诊断访问器
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
