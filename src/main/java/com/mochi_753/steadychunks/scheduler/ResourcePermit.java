package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;

/**
 * 资源令牌，对应开发计划 §3.2 与技术指导 §7.1，P1-08 修复。
 * <p>
 * <b>修复内容</b>：
 * <ul>
 *   <li>内部委托 {@link ResourceBucket}（AtomicInteger），不再使用 Semaphore</li>
 *   <li>{@link #release()} 通过 CAS 检测双重释放，防止 permit 泄漏</li>
 *   <li>{@link #setMaxPermits} 直接调整 AtomicInteger，无 Semaphore 替换竞态</li>
 *   <li>新增 {@link #tryAcquireLease()} 返回 {@link PermitLease}（AutoCloseable）</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li> permits 可动态调整（Phase 4 AIMD 控制器调用 {@link #setMaxPermits}）</li>
 *   <li> 使用 tryAcquire 非阻塞获取，避免任务线程阻塞死锁</li>
 *   <li> release 在正常、异常和取消路径均必须调用（验收标准 §3 验收）</li>
 *   <li> 依赖解锁任务可使用保留 permit（§7.1）</li>
 * </ul>
 */
public final class ResourcePermit {
    private final ResourceType type;
    /** 资源桶（AtomicInteger 计数器，可被同 ResourceType 的多个阶段共享） */
    private final ResourceBucket bucket;
    /** 依赖解锁保留 permit 数量（普通任务不能占用） */
    private volatile int dependencyReserve;

    public ResourcePermit(ResourceType type, int initialPermits) {
        this.type = type;
        this.bucket = new ResourceBucket(type, initialPermits);
        // 默认保留 1 个 permit 给依赖解锁任务
        this.dependencyReserve = Math.min(1, Math.max(0, initialPermits - 1));
    }

    /**
     * 包级构造：使用共享 ResourceBucket（P1-09 同 ResourceType 共享桶）。
     */
    ResourcePermit(ResourceType type, ResourceBucket sharedBucket, int dependencyReserve) {
        this.type = type;
        this.bucket = sharedBucket;
        this.dependencyReserve = Math.min(dependencyReserve, Math.max(0, sharedBucket.maxPermits() - 1));
    }

    /**
     * 非阻塞获取一个 permit。
     *
     * @return true 表示获取成功，调用方在任务完成后必须调用 {@link #release}
     */
    public boolean tryAcquire() {
        return bucket.tryAcquire();
    }

    /**
     * 非阻塞获取一个 permit，返回 {@link PermitLease}（AutoCloseable）。
     * <p>
     * 推荐使用此方法配合 try-with-resources，保证 permit 在异常路径也被释放。
     *
     * @return PermitLease，{@link PermitLease#acquired()} 为 true 表示获取成功
     */
    public PermitLease tryAcquireLease() {
        if (bucket.tryAcquire()) {
            return PermitLease.acquired(bucket);
        }
        return PermitLease.empty();
    }

    /**
     * 释放一个 permit。在正常完成、异常和取消路径均必须调用。
     * <p>
     * P1-08 修复：通过 CAS 检测双重释放，防止 permit 泄漏。
     */
    public void release() {
        if (!bucket.release()) {
            SteadyChunks.LOGGER.warn("SteadyChunks ResourcePermit: 检测到双重释放 type={}（已忽略）", type);
        }
    }

    public int availablePermits() {
        return bucket.availablePermits();
    }

    public int acquiredCount() {
        return bucket.acquiredCount();
    }

    public int maxPermits() {
        return bucket.maxPermits();
    }

    public int dependencyReserve() {
        return dependencyReserve;
    }

    public void setDependencyReserve(int reserve) {
        // reserve 不能超过 maxPermits - 1，至少留 1 个给普通任务
        int max = bucket.maxPermits();
        this.dependencyReserve = Math.min(reserve, Math.max(0, max - 1));
    }

    /**
     * 动态调整 permit 上限（Phase 4 AIMD 调用）。
     * <p>
     * P1-08 修复：直接调整 AtomicInteger，无 Semaphore 替换竞态。
     * 已被任务持有的 permit 不受影响。
     */
    public void setMaxPermits(int newMax) {
        bucket.setMaxPermits(newMax);
        // 同步调整 dependencyReserve
        this.dependencyReserve = Math.min(dependencyReserve, Math.max(0, newMax - 1));
    }

    public ResourceType type() {
        return type;
    }

    /**
     * 返回内部资源桶（供 StageLimiter 共享，P1-09）。
     */
    ResourceBucket bucket() {
        return bucket;
    }
}
