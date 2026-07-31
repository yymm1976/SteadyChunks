package com.mochi_753.steadychunks.completion;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 完成批次整形器，对应开发计划 §5.2。
 * <p>
 * 避免多个阶段在同一时间窗口全部释放结果：
 * <ul>
 *   <li>对 LIGHT → FULL 建立完成预算</li>
 *   <li>对大量 Future 的回调进行分批排放</li>
 *   <li>禁止在单一 Tick 无上限执行完成回调</li>
 *   <li>记录回调积压和最长等待时间</li>
 * </ul>
 * <p>
 * 工作原理：工作线程完成的 Future 回调不立即执行，而是进入待处理队列，
 * 主线程每 Tick 按预算批量执行。防止一个 Tick 内涌入大量完成回调导致主线程卡顿。
 */
public final class CompletionBatchShaper {
    private static CompletionBatchShaper instance;

    /** 待处理的完成回调队列 */
    private final List<BatchedCompletion> pending = new ArrayList<>();
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
     * 提交一个完成回调。
     * <p>
     * 工作线程调用此方法，回调不立即执行，进入待处理队列。
     *
     * @param chunkPos 区块位置（用于诊断）
     * @param callback 完成回调
     * @return true 表示已入队，false 表示队列满
     */
    public boolean submit(long chunkPos, Runnable callback) {
        if (!enabled.get()) {
            callback.run();
            return true;
        }
        synchronized (pending) {
            if (pending.size() >= queueCapacity) {
                totalDeferred.incrementAndGet();
                return false;
            }
            pending.add(new BatchedCompletion(chunkPos, callback));
            int size = pending.size();
            pendingCount.set(size);
            peakBacklog.accumulateAndGet(size, Math::max);
        }
        return true;
    }

    /**
     * 每 Tick 在主线程调用：按预算批量执行完成回调。
     *
     * @param deadlineNanos 本 Tick 截止时间
     */
    public void tick(long deadlineNanos) {
        if (!enabled.get() || pending.isEmpty()) {
            return;
        }

        List<BatchedCompletion> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            // 取出本 Tick 批次
            int batchSize = Math.min(maxCallbacksPerTick, pending.size());
            batch = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                batch.add(pending.remove(0));
            }
            pendingCount.set(pending.size());
        }

        int executed = 0;
        for (BatchedCompletion c : batch) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 && executed > 0) {
                // 预算耗尽，剩余放回队列
                synchronized (pending) {
                    pending.add(0, c);
                    for (int i = batch.size() - 1; i > executed; i--) {
                        if (i < batch.size()) {
                            pending.add(0, batch.get(i));
                        }
                    }
                    pendingCount.set(pending.size());
                }
                totalDeferred.addAndGet(batch.size() - executed);
                break;
            }

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
     * 清空队列（如世界卸载）。
     */
    public void clear() {
        synchronized (pending) {
            pending.clear();
            pendingCount.set(0);
        }
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

    /** 单个完成回调包装 */
    private record BatchedCompletion(long chunkPos, Runnable callback, long enqueueNanos) {
        BatchedCompletion(long chunkPos, Runnable callback) {
            this(chunkPos, callback, System.nanoTime());
        }

        long queueAgeMs() {
            return (System.nanoTime() - enqueueNanos) / 1_000_000L;
        }
    }
}
