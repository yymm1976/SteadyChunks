package com.mochi_753.steadychunks.scheduler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资源桶，对应 P1-08/P1-09 修复 + 审查新发现 #1/#4 修复。
 * <p>
 * 使用单一 {@link AtomicInteger inUse} 计数器 + {@code limit} 上限，
 * 替代旧设计的 available/acquired/max 三计数器。
 * <p>
 * <b>设计要点</b>：
 * <ul>
 *   <li>单计数器消除 available 与 acquired 的一致性问题</li>
 *   <li>缩容只修改 limit，不影响已持有 permit 的任务</li>
 *   <li>available = max(0, limit - inUse)，缩容后不会超额</li>
 *   <li>单 CAS 同时校验 limit 与 reserve，消除 check-then-act 竞态</li>
 * </ul>
 * <p>
 * <b>共享语义</b>：同一 {@link ResourceType} 的多个 ChunkStatus 阶段
 * 共享同一个 ResourceBucket，真正实现资源约束（而非每阶段独立计数）。
 * 例如 BIOMES / NOISE / SURFACE 共享 NOISE_HEAVY 桶，三者总并发受限于桶上限。
 */
public final class ResourceBucket {
    private final ResourceType type;
    /** 当前已获取 permit 数（CAS 增减） */
    private final AtomicInteger inUse = new AtomicInteger(0);
    /** permit 上限（可动态调整，volatile 保证可见性） */
    private volatile int limit;

    public ResourceBucket(ResourceType type, int initialLimit) {
        this.type = type;
        this.limit = initialLimit;
    }

    /**
     * CAS 获取一个 permit。
     *
     * @return true 表示获取成功
     */
    public boolean tryAcquire() {
        return tryAcquireWithReserve(0);
    }

    /**
     * CAS 获取一个 permit，单 CAS 同时校验保留额度（审查新发现 #4 修复）。
     * <p>
     * 普通任务在 {@code inUse >= limit - reserve} 时拒绝，将保留额度留给依赖解锁任务。
     * 相比"先检查 reserve 再 tryAcquire"两步操作，单 CAS 消除 check-then-act 竞态。
     *
     * @param reserve 依赖解锁保留额度（普通任务不能占用）
     * @return true 表示获取成功
     */
    public boolean tryAcquireWithReserve(int reserve) {
        while (true) {
            int current = inUse.get();
            int effectiveLimit = Math.max(0, limit - reserve);
            if (current >= effectiveLimit) {
                return false;
            }
            if (inUse.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * CAS 获取 permit 并返回 {@link PermitLease}（AutoCloseable）。
     * <p>
     * 配合 try-with-resources 保证 permit 在正常、异常、取消路径均被释放。
     * 保留额度语义与 {@link #tryAcquireWithReserve} 一致。
     *
     * @param reserve 依赖解锁保留额度（普通任务不能占用）
     * @return PermitLease，{@link PermitLease#acquired()} 为 true 表示获取成功
     */
    public PermitLease tryAcquireLease(int reserve) {
        if (tryAcquireWithReserve(reserve)) {
            return PermitLease.acquired(this);
        }
        return PermitLease.empty();
    }

    /**
     * CAS 释放一个 permit，检测双重释放。
     *
     * @return true 表示释放成功，false 表示检测到双重释放（已无已获取 permit）
     */
    public boolean release() {
        while (true) {
            int current = inUse.get();
            if (current <= 0) {
                return false;
            }
            if (inUse.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    /**
     * 当前可用 permit 数（缩容后可能为 0，不会超过 limit）。
     */
    public int availablePermits() {
        return Math.max(0, limit - inUse.get());
    }

    /**
     * 当前已获取 permit 数。
     */
    public int acquiredCount() {
        return inUse.get();
    }

    /**
     * permit 上限。
     */
    public int maxPermits() {
        return limit;
    }

    /**
     * 动态调整 permit 上限（Phase 4 AIMD 调用）。
     * <p>
     * 只修改 limit，不影响已持有 permit 的任务。
     * 已有任务自然完成，在下降到新上限以下之前不再批准新任务。
     * 缩容后 available = max(0, limit - inUse)，不会出现旧设计的超额问题。
     */
    public void setMaxPermits(int newLimit) {
        if (newLimit < 1) {
            newLimit = 1;
        }
        this.limit = newLimit;
    }

    public ResourceType type() {
        return type;
    }
}
