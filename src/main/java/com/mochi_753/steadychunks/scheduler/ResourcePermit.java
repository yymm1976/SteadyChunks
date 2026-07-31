package com.mochi_753.steadychunks.scheduler;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 资源令牌，基于 {@link Semaphore} 的并发许可。
 * <p>
 * 对应开发计划 §3.2 与技术指导 §7.1：调度器为阶段建立资源令牌，任务必须先获取 permit 才能执行。
 * <p>
 * 设计要点：
 * <ul>
 *   <li> permits 可动态调整（Phase 4 AIMD 控制器调用 {@link #setMaxPermits}）</li>
 *   <li> 使用 tryAcquire 非阻塞获取，避免任务线程阻塞死锁</li>
 *   <li> release 在正常、异常和取消路径均必须调用（验收标准 §3 验收）</li>
 *   <li> 依赖解锁任务可使用保留 permit（§7.1）</li>
 *   <li> setMaxPermits 通过锁保护，避免 semaphore 替换竞态</li>
 * </ul>
 */
public final class ResourcePermit {
    private final ResourceType type;
    /** 当前信号量，permit 变更时整体替换（避免 Semaphore 动态扩缩的复杂度） */
    private volatile Semaphore semaphore;
    private final AtomicInteger maxPermits;
    /** 依赖解锁保留 permit 数量（普通任务不能占用） */
    private volatile int dependencyReserve;
    /** 当前已获取 permit 数（统计用，非控制用） */
    private final AtomicInteger acquired = new AtomicInteger(0);
    /** 保护 tryAcquire/release/setMaxPermits 的临界区，避免 semaphore 替换竞态 */
    private final ReentrantLock lock = new ReentrantLock();

    public ResourcePermit(ResourceType type, int initialPermits) {
        this.type = type;
        this.semaphore = new Semaphore(initialPermits, true);
        this.maxPermits = new AtomicInteger(initialPermits);
        // 默认保留 1 个 permit 给依赖解锁任务
        this.dependencyReserve = Math.min(1, Math.max(0, initialPermits - 1));
    }

    /**
     * 非阻塞获取一个 permit。
     *
     * @return true 表示获取成功，调用方在任务完成后必须调用 {@link #release}
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            if (semaphore.tryAcquire()) {
                acquired.incrementAndGet();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放一个 permit。在正常完成、异常和取消路径均必须调用。
     */
    public void release() {
        lock.lock();
        try {
            // 防御性检查：避免 release 多于 acquire 导致 permit 超限
            if (acquired.get() <= 0) {
                return;
            }
            semaphore.release();
            acquired.decrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int acquiredCount() {
        return acquired.get();
    }

    public int maxPermits() {
        return maxPermits.get();
    }

    public int dependencyReserve() {
        return dependencyReserve;
    }

    public void setDependencyReserve(int reserve) {
        // reserve 不能超过 maxPermits - 1，至少留 1 个给普通任务
        int max = maxPermits.get();
        this.dependencyReserve = Math.min(reserve, Math.max(0, max - 1));
    }

    /**
     * 动态调整 permit 上限（Phase 4 AIMD 调用）。
     * <p>
     * 实现方式：创建新 Semaphore 并迁移可用 permit 数。
     * 已被任务持有的 permit 不受影响（它们 release 到新 Semaphore）。
     * 通过锁保护，避免 tryAcquire/release 与 semaphore 替换的竞态。
     */
    public void setMaxPermits(int newMax) {
        if (newMax < 1) {
            newMax = 1;
        }
        lock.lock();
        try {
            int oldMax = maxPermits.getAndSet(newMax);
            if (newMax == oldMax) {
                return;
            }
            // 计算新信号量的可用数：新上限减去当前已获取数
            int currentlyAcquired = acquired.get();
            int newAvailable = Math.max(0, newMax - currentlyAcquired);
            Semaphore newSem = new Semaphore(newAvailable, true);
            semaphore = newSem;
            // 同步调整 dependencyReserve
            this.dependencyReserve = Math.min(dependencyReserve, Math.max(0, newMax - 1));
        } finally {
            lock.unlock();
        }
    }

    public ResourceType type() {
        return type;
    }
}
