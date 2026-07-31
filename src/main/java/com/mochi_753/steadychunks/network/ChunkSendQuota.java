package com.mochi_753.steadychunks.network;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 区块发送配额控制器，对应开发计划 §5.3。
 * <p>
 * 按玩家和时间窗口限制：
 * <ul>
 *   <li>新区块数据包数</li>
 *   <li>总字节数</li>
 *   <li>光照数据量</li>
 *   <li>高优先级近处区块优先</li>
 *   <li>重发或更新区块</li>
 * </ul>
 * <p>
 * 技术指导 §10.2：区块已经完成并处于合法状态后，控制发送节奏。
 * 发送策略不得破坏协议顺序或导致玩家等待已经生成好的最近区块。
 */
public final class ChunkSendQuota {
    private static ChunkSendQuota instance;

    /** 每玩家每 Tick 最大发送区块数 */
    private volatile int maxChunksPerTick = 5;
    /** 每玩家每 Tick 最大字节数 */
    private volatile long maxBytesPerTick = 512 * 1024; // 512KB
    /** 每玩家每 Tick 最大光照字节数 */
    private volatile long maxLightBytesPerTick = 128 * 1024; // 128KB
    /** 最低发送预算（防止速度感知导致缺块） */
    private volatile int minChunksPerTick = 1;
    /** 队列容量上限（每玩家） */
    private volatile int queueCapacityPerPlayer = 128;

    /** 每玩家发送队列 */
    private final ConcurrentHashMap<UUID, PriorityBlockingQueue<ChunkSendTask>> playerQueues = new ConcurrentHashMap<>();
    /** 每玩家本 Tick 已发送区块数 */
    private final ConcurrentHashMap<UUID, AtomicLong> tickChunkSent = new ConcurrentHashMap<>();
    /** 每玩家本 Tick 已发送字节数 */
    private final ConcurrentHashMap<UUID, AtomicLong> tickBytesSent = new ConcurrentHashMap<>();
    /** 每玩家本 Tick 已发送光照字节数 */
    private final ConcurrentHashMap<UUID, AtomicLong> tickLightBytesSent = new ConcurrentHashMap<>();

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalDeferred = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);

    private ChunkSendQuota() {
    }

    public static synchronized ChunkSendQuota getInstance() {
        if (instance == null) {
            instance = new ChunkSendQuota();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        SteadyChunks.LOGGER.info("SteadyChunks 区块发送配额: {}", on ? "enabled" : "disabled");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 提交发送任务到玩家队列。
     *
     * @return true 表示已入队，false 表示队列满
     */
    public boolean submit(ChunkSendTask task) {
        if (!enabled.get()) {
            return true; // 未启用时直接放行，由原版发送
        }
        PriorityBlockingQueue<ChunkSendTask> queue = playerQueues.computeIfAbsent(
                task.playerId(), k -> new PriorityBlockingQueue<>(64));
        if (queue.size() >= queueCapacityPerPlayer) {
            totalRejected.incrementAndGet();
            return false;
        }
        queue.offer(task);
        return true;
    }

    /**
     * 尝试为玩家获取发送许可（非阻塞）。
     * <p>
     * 由发送路径在构建数据包前调用。
     *
     * @return true 表示允许发送，调用方应发送后调用 {@link #recordSent}
     */
    public boolean tryAcquire(UUID playerId, long estimatedBytes, long estimatedLightBytes) {
        if (!enabled.get()) {
            return true;
        }
        AtomicLong chunks = tickChunkSent.computeIfAbsent(playerId, k -> new AtomicLong());
        AtomicLong bytes = tickBytesSent.computeIfAbsent(playerId, k -> new AtomicLong());
        AtomicLong lightBytes = tickLightBytesSent.computeIfAbsent(playerId, k -> new AtomicLong());

        long currentChunks = chunks.get();
        long currentBytes = bytes.get();
        long currentLight = currentLight(lightBytes);

        // 最低预算保障：即使超限也允许最低发送
        if (currentChunks < minChunksPerTick) {
            return true;
        }
        // 正常配额检查
        if (currentChunks >= maxChunksPerTick) {
            totalDeferred.incrementAndGet();
            return false;
        }
        if (currentBytes + estimatedBytes > maxBytesPerTick) {
            totalDeferred.incrementAndGet();
            return false;
        }
        if (currentLight + estimatedLightBytes > maxLightBytesPerTick) {
            totalDeferred.incrementAndGet();
            return false;
        }
        return true;
    }

    private long currentLight(AtomicLong lightBytes) {
        return lightBytes.get();
    }

    /**
     * 记录已发送的区块（发送成功后调用）。
     */
    public void recordSent(UUID playerId, long bytes, long lightBytes) {
        if (!enabled.get()) {
            return;
        }
        tickChunkSent.computeIfAbsent(playerId, k -> new AtomicLong()).incrementAndGet();
        tickBytesSent.computeIfAbsent(playerId, k -> new AtomicLong()).addAndGet(bytes);
        tickLightBytesSent.computeIfAbsent(playerId, k -> new AtomicLong()).addAndGet(lightBytes);
        totalSent.incrementAndGet();
    }

    /**
     * 每 Tick 重置所有玩家的发送计数。
     * <p>
     * 在主线程 Tick 开始时调用。
     */
    public void resetTick() {
        for (AtomicLong count : tickChunkSent.values()) {
            count.set(0);
        }
        for (AtomicLong bytes : tickBytesSent.values()) {
            bytes.set(0);
        }
        for (AtomicLong light : tickLightBytesSent.values()) {
            light.set(0);
        }
    }

    /**
     * 玩家断开或维度卸载时清理。
     */
    public void clearPlayer(UUID playerId) {
        playerQueues.remove(playerId);
        tickChunkSent.remove(playerId);
        tickBytesSent.remove(playerId);
        tickLightBytesSent.remove(playerId);
    }

    // 配置访问器
    public void setMaxChunksPerTick(int max) { this.maxChunksPerTick = max; }
    public void setMaxBytesPerTick(long max) { this.maxBytesPerTick = max; }
    public void setMaxLightBytesPerTick(long max) { this.maxLightBytesPerTick = max; }
    public void setMinChunksPerTick(int min) { this.minChunksPerTick = min; }
    public void setQueueCapacityPerPlayer(int cap) { this.queueCapacityPerPlayer = cap; }
    public int maxChunksPerTick() { return maxChunksPerTick; }
    public long maxBytesPerTick() { return maxBytesPerTick; }
    public int minChunksPerTick() { return minChunksPerTick; }

    // 诊断访问器
    public long totalSent() { return totalSent.get(); }
    public long totalDeferred() { return totalDeferred.get(); }
    public long totalRejected() { return totalRejected.get(); }
    public int playerQueueDepth(UUID playerId) {
        PriorityBlockingQueue<ChunkSendTask> q = playerQueues.get(playerId);
        return q != null ? q.size() : 0;
    }
    public int totalQueueDepth() {
        int sum = 0;
        for (PriorityBlockingQueue<ChunkSendTask> q : playerQueues.values()) {
            sum += q.size();
        }
        return sum;
    }
}
