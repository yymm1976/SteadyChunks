package com.mochi_753.steadychunks.scheduler;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家与维度公平性管理器，对应开发计划 §3.5。
 * <p>
 * 防止：
 * <ul>
 *   <li>多玩家时单个高速玩家占满全部资源</li>
 *   <li>新维度任务被旧维度积压压制</li>
 *   <li>远处任务永久饥饿</li>
 * </ul>
 * <p>
 * 实现方式：每玩家配额 + 每维度配额 + 年龄提升。
 */
public final class FairnessManager {
    /** 每玩家当前运行任务数 */
    private final ConcurrentHashMap<UUID, AtomicLong> playerRunning = new ConcurrentHashMap<>();
    /** 每维度当前运行任务数 */
    private final ConcurrentHashMap<Object, AtomicLong> dimensionRunning = new ConcurrentHashMap<>();
    /** 每玩家最大并发任务数（配置项） */
    private volatile int maxPerPlayer = 16;
    /** 每维度最大并发任务数（配置项） */
    private volatile int maxPerDimension = 32;

    /**
     * 记录任务开始执行。
     */
    public void onTaskStart(ChunkTask task) {
        for (UUID player : task.requestingPlayers()) {
            playerRunning.computeIfAbsent(player, k -> new AtomicLong()).incrementAndGet();
        }
        dimensionRunning.computeIfAbsent(task.dimension(), k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 记录任务完成或取消。
     */
    public void onTaskEnd(ChunkTask task) {
        for (UUID player : task.requestingPlayers()) {
            AtomicLong count = playerRunning.get(player);
            if (count != null) {
                count.decrementAndGet();
            }
        }
        AtomicLong dimCount = dimensionRunning.get(task.dimension());
        if (dimCount != null) {
            dimCount.decrementAndGet();
        }
    }

    /**
     * 计算公平性惩罚因子 [0,1]。
     * <p>
     * 已有大量在运行任务的玩家/维度获得更低优先级，防止垄断。
     *
     * @return 1.0 表示无惩罚，趋近 0 表示严重超额
     */
    public double fairnessFactor(ChunkTask task) {
        // 取需求玩家中运行数最少的（最饥饿的玩家）
        double playerFactor = 1.0;
        for (UUID player : task.requestingPlayers()) {
            long running = playerRunning.getOrDefault(player, new AtomicLong()).get();
            double factor = 1.0 - (double) running / maxPerPlayer;
            playerFactor = Math.min(playerFactor, Math.max(0.1, factor));
        }
        // 维度配额
        long dimRunning = dimensionRunning.getOrDefault(task.dimension(), new AtomicLong()).get();
        double dimFactor = 1.0 - (double) dimRunning / maxPerDimension;
        dimFactor = Math.max(0.1, dimFactor);
        return playerFactor * dimFactor;
    }

    public void setMaxPerPlayer(int max) {
        this.maxPerPlayer = Math.max(1, max);
    }

    public void setMaxPerDimension(int max) {
        this.maxPerDimension = Math.max(1, max);
    }

    public int playerRunningCount(UUID player) {
        return (int) playerRunning.getOrDefault(player, new AtomicLong()).get();
    }

    public int dimensionRunningCount(Object dimension) {
        return (int) dimensionRunning.getOrDefault(dimension, new AtomicLong()).get();
    }
}
