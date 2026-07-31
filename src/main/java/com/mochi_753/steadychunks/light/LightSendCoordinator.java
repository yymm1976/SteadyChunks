package com.mochi_753.steadychunks.light;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 光照完成与区块发送协调器，对应开发计划 §8.3。
 * <p>
 * 协调 LIGHT → FULL → send 链路，避免完成洪峰：
 * <ul>
 *   <li>避免多个相邻区块在同 Tick 同时进入发送</li>
 *   <li>近处完整光照区块优先</li>
 *   <li>防止因发送节流导致已完成光照数据长期积压</li>
 *   <li>记录光照完成至首次发送延迟</li>
 * </ul>
 * <p>
 * 风险缓解（计划 §8 风险表）：完成整形造成可见黑块。
 * 只有完整光照后才发送，并设置最大等待时间，超时后强制发送。
 * <p>
 * 线程安全：光照完成回调来自工作线程，发送在主线程。
 * 使用 {@link PriorityBlockingQueue} 跨线程传递就绪区块。
 */
public final class LightSendCoordinator {
    private static LightSendCoordinator instance;

    /** 每玩家待发送（光照已完成）队列 */
    private final ConcurrentHashMap<UUID, PriorityBlockingQueue<LightReadyEntry>> playerReadyQueues = new ConcurrentHashMap<>();
    /** 每玩家本 Tick 已发送计数（避免相邻区块同 Tick 洪峰） */
    private final ConcurrentHashMap<UUID, AtomicLong> tickSent = new ConcurrentHashMap<>();
    /** 每玩家本 Tick 最大发送数（分散发送） */
    private volatile int maxSendPerPlayerPerTick = 2;
    /** 最大等待时间（毫秒），超时后强制发送避免黑块 */
    private volatile long maxWaitMs = 5000L;

    /** 统计：光照完成至首次发送延迟（纳秒） */
    private final com.mochi_753.steadychunks.telemetry.QuantileEstimator sendDelay = new com.mochi_753.steadychunks.telemetry.QuantileEstimator();
    /** 统计：累计超时强制发送 */
    private final AtomicLong totalForcedByTimeout = new AtomicLong(0);
    /** 统计：累计因相邻分散延迟 */
    private final AtomicLong totalDeferredForSpacing = new AtomicLong(0);

    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private LightSendCoordinator() {
    }

    public static synchronized LightSendCoordinator getInstance() {
        if (instance == null) {
            instance = new LightSendCoordinator();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setMaxSendPerPlayerPerTick(int max) {
        this.maxSendPerPlayerPerTick = max;
    }

    public void setMaxWaitMs(long ms) {
        this.maxWaitMs = ms;
    }

    /**
     * 通知光照完成，区块进入待发送队列。
     * <p>
     * 由光照完成回调（工作线程）调用。
     *
     * @param playerId    请求该区块的玩家
     * @param packedChunkPos 区块 packed long
     * @param distance    到玩家的棋盘距离（越小优先级越高）
     */
    public void onLightComplete(UUID playerId, long packedChunkPos, double distance) {
        if (!enabled.get()) {
            return;
        }
        PriorityBlockingQueue<LightReadyEntry> queue = playerReadyQueues.computeIfAbsent(
                playerId, k -> new PriorityBlockingQueue<>(64));
        queue.offer(new LightReadyEntry(packedChunkPos, distance, System.nanoTime()));
    }

    /**
     * 每 Tick 在主线程调用：为玩家发送已光照完成的区块。
     * <p>
     * 策略：
     * <ol>
     *   <li>每玩家每 Tick 最多发送 {@link #maxSendPerPlayerPerTick} 个（分散避免洪峰）</li>
     *   <li>近处优先（队列优先级）</li>
     *   <li>等待超过 {@link #maxWaitMs} 的强制发送（避免黑块）</li>
     * </ol>
     *
     * @param playerId 玩家 ID
     * @param sender   发送回调，返回 true 表示发送成功
     */
    public void tickPlayer(UUID playerId, SendCallback sender) {
        if (!enabled.get()) {
            return;
        }
        PriorityBlockingQueue<LightReadyEntry> queue = playerReadyQueues.get(playerId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        AtomicLong sent = tickSent.computeIfAbsent(playerId, k -> new AtomicLong(0));
        long now = System.nanoTime();

        int sentThisTick = 0;
        while (sentThisTick < maxSendPerPlayerPerTick) {
            LightReadyEntry entry = queue.peek();
            if (entry == null) {
                break;
            }
            // 超时强制发送
            long waitMs = (now - entry.readyAtNanos) / 1_000_000L;
            boolean forced = waitMs >= maxWaitMs;
            // 分散策略：未超时且已达本 Tick 上限则停止
            if (!forced && sentThisTick >= maxSendPerPlayerPerTick) {
                totalDeferredForSpacing.incrementAndGet();
                break;
            }
            // 移出队列
            queue.poll();
            // 执行发送
            boolean ok = sender.send(playerId, entry.packedChunkPos, entry.distance);
            if (ok) {
                sent.incrementAndGet();
                sentThisTick++;
                // 记录延迟
                sendDelay.record(now - entry.readyAtNanos);
                if (forced) {
                    totalForcedByTimeout.incrementAndGet();
                }
            } else {
                // 发送失败（如配额不足），重新入队等待下次
                queue.offer(entry);
                break;
            }
        }
    }

    /**
     * 每 Tick 重置发送计数（主线程开始时调用）。
     */
    public void resetTick() {
        for (AtomicLong c : tickSent.values()) {
            c.set(0);
        }
    }

    /**
     * 玩家断开或维度卸载时清理。
     */
    public void clearPlayer(UUID playerId) {
        playerReadyQueues.remove(playerId);
        tickSent.remove(playerId);
    }

    /**
     * 清空所有状态。
     */
    public void clearAll() {
        playerReadyQueues.clear();
        tickSent.clear();
    }

    // 诊断访问器
    public int queueDepth(UUID playerId) {
        PriorityBlockingQueue<LightReadyEntry> q = playerReadyQueues.get(playerId);
        return q != null ? q.size() : 0;
    }

    public long totalForcedByTimeout() {
        return totalForcedByTimeout.get();
    }

    public long totalDeferredForSpacing() {
        return totalDeferredForSpacing.get();
    }

    public com.mochi_753.steadychunks.telemetry.QuantileEstimator sendDelay() {
        return sendDelay;
    }

    /** 发送回调接口 */
    @FunctionalInterface
    public interface SendCallback {
        /**
         * @param playerId    玩家
         * @param packedChunkPos 区块
         * @param distance    距离
         * @return true 表示发送成功，false 表示配额不足或失败
         */
        boolean send(UUID playerId, long packedChunkPos, double distance);
    }

    /** 待发送条目（按距离升序、等待时间降序） */
    private record LightReadyEntry(
            long packedChunkPos,
            double distance,
            long readyAtNanos
    ) implements Comparable<LightReadyEntry> {
        @Override
        public int compareTo(LightReadyEntry o) {
            // 距离近的优先；距离相同时等待久的优先
            int cmp = Double.compare(this.distance, o.distance);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(this.readyAtNanos, o.readyAtNanos);
        }
    }
}
