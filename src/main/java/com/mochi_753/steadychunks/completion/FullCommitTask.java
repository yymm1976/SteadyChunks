package com.mochi_753.steadychunks.completion;

import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * FULL 整合任务模型，对应开发计划 §5.1。
 * <p>
 * 主线程整合工作（FULL commit）的可延迟任务。每个任务封装：
 * <ul>
 *   <li>区块位置与维度</li>
 *   <li>预计成本（纳秒，用于时间预算）</li>
 *   <li>紧迫度等级（决定是否可延迟）</li>
 *   <li>需求玩家（用于公平性）</li>
 *   <li>依赖关键性（是否必须立即执行以解除依赖）</li>
 * </ul>
 * <p>
 * 技术指导 §10.3：必须区分可推迟、必须立即执行以解除依赖、原版线程约束要求立即执行三类。
 */
public final class FullCommitTask implements Comparable<FullCommitTask> {
    private final ChunkPos pos;
    private final Object dimension;
    private final long estimatedCostNanos;
    private final Urgency urgency;
    private final UUID nearestPlayer;
    private final double distance;
    private final boolean dependencyCritical;
    private final long enqueueNanos;
    private final Runnable commitAction;

    public FullCommitTask(ChunkPos pos, Object dimension, long estimatedCostNanos,
                          Urgency urgency, UUID nearestPlayer, double distance,
                          boolean dependencyCritical, Runnable commitAction) {
        this.pos = pos;
        this.dimension = dimension;
        this.estimatedCostNanos = estimatedCostNanos;
        this.urgency = urgency;
        this.nearestPlayer = nearestPlayer;
        this.distance = distance;
        this.dependencyCritical = dependencyCritical;
        this.enqueueNanos = System.nanoTime();
        this.commitAction = commitAction;
    }

    public ChunkPos pos() {
        return pos;
    }

    public Object dimension() {
        return dimension;
    }

    public long estimatedCostNanos() {
        return estimatedCostNanos;
    }

    public Urgency urgency() {
        return urgency;
    }

    public UUID nearestPlayer() {
        return nearestPlayer;
    }

    public double distance() {
        return distance;
    }

    public boolean dependencyCritical() {
        return dependencyCritical;
    }

    public long enqueueNanos() {
        return enqueueNanos;
    }

    public Runnable commitAction() {
        return commitAction;
    }

    /** 排队年龄（毫秒） */
    public long queueAgeMs() {
        return (System.nanoTime() - enqueueNanos) / 1_000_000L;
    }

    /**
     * 优先级比较：依赖关键 > 紧迫度高 > 距离近 > 年龄长。
     * 使用 long 避免浮点不稳定（技术指导 §6.1）。
     */
    @Override
    public int compareTo(FullCommitTask other) {
        // 1. 依赖关键任务永远最高
        if (this.dependencyCritical != other.dependencyCritical) {
            return this.dependencyCritical ? -1 : 1;
        }
        // 2. 紧迫度等级
        int urgencyCmp = Integer.compare(this.urgency.priority, other.urgency.priority);
        if (urgencyCmp != 0) {
            return -urgencyCmp; // 数值大者优先
        }
        // 3. 距离近优先
        int distCmp = Double.compare(this.distance, other.distance);
        if (distCmp != 0) {
            return distCmp;
        }
        // 4. 年龄长优先（先入队先执行）
        return Long.compare(this.enqueueNanos, other.enqueueNanos);
    }

    /**
     * 紧迫度等级，对应技术指导 §6 的 Urgency bucket。
     */
    public enum Urgency {
        /** 依赖解锁任务，必须立即执行 */
        DEPENDENCY_CRITICAL(1000),
        /** 玩家可见缺口 */
        VISIBLE_GAP(900),
        /** 近玩家区块 */
        NEAR_PLAYER(800),
        /** 运动方向前方 */
        DIRECTIONAL_FRONT(700),
        /** 正常探索 */
        NORMAL(500),
        /** 后台预取 */
        BACKGROUND(300),
        /** 已失去需求 */
        EXPIRED(100);

        final int priority;

        Urgency(int priority) {
            this.priority = priority;
        }
    }
}
