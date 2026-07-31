package com.mochi_753.steadychunks.network;

import net.minecraft.world.level.ChunkPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 区块发送任务模型，对应开发计划 §5.3。
 * <p>
 * 封装单个区块发送所需的全部维度：
 * <ul>
 *   <li>目标玩家</li>
 *   <li>区块位置</li>
 *   <li>预计字节数（含光照）</li>
 *   <li>优先级（近处、重发、可见缺口）</li>
 *   <li>是否重发或更新</li>
 * </ul>
 */
public final class ChunkSendTask implements Comparable<ChunkSendTask> {
    private final UUID playerId;
    private final ChunkPos pos;
    private final long estimatedBytes;
    private final SendPriority priority;
    private final double distance;
    private final boolean resend;
    private final long enqueueNanos;

    public ChunkSendTask(UUID playerId, ChunkPos pos, long estimatedBytes,
                         SendPriority priority, double distance, boolean resend) {
        this.playerId = playerId;
        this.pos = pos;
        this.estimatedBytes = estimatedBytes;
        this.priority = priority;
        this.distance = distance;
        this.resend = resend;
        this.enqueueNanos = System.nanoTime();
    }

    public UUID playerId() { return playerId; }
    public ChunkPos pos() { return pos; }
    public long estimatedBytes() { return estimatedBytes; }
    public SendPriority priority() { return priority; }
    public double distance() { return distance; }
    public boolean resend() { return resend; }
    public long enqueueNanos() { return enqueueNanos; }

    @Override
    public int compareTo(ChunkSendTask other) {
        // 优先级高 > 距离近 > 年龄长
        int pCmp = Integer.compare(this.priority.priority, other.priority.priority);
        if (pCmp != 0) return -pCmp;
        int dCmp = Double.compare(this.distance, other.distance);
        if (dCmp != 0) return dCmp;
        return Long.compare(this.enqueueNanos, other.enqueueNanos);
    }

    /** 发送优先级 */
    public enum SendPriority {
        /** 重发或更新区块（最高） */
        RESEND(1000),
        /** 玩家可见缺口 */
        VISIBLE_GAP(900),
        /** 近处区块 */
        NEAR(800),
        /** 运动方向前方 */
        DIRECTIONAL(700),
        /** 正常发送 */
        NORMAL(500),
        /** 后台预取 */
        BACKGROUND(300);

        final int priority;
        SendPriority(int p) { this.priority = p; }
    }
}
