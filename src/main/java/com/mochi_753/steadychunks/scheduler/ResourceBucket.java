package com.mochi_753.steadychunks.scheduler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资源桶，对应 P1-08/P1-09 修复。
 * <p>
 * 使用 {@link AtomicInteger} 替代 {@link java.util.concurrent.Semaphore}，
 * 避免 {@code setMaxPermits} 时整体替换 Semaphore 的竞态问题。
 * <p>
 * <b>共享语义</b>：同一 {@link ResourceType} 的多个 ChunkStatus 阶段
 * 共享同一个 ResourceBucket，真正实现资源约束（而非每阶段独立计数）。
 * 例如 BIOMES / NOISE / SURFACE 共享 NOISE_HEAVY 桶，三者总并发受限于桶上限。
 * <p>
 * <b>动态缩容</b>：setMaxPermits 直接调整 max AtomicInteger，
 * 当前已获取的 permit 不受影响，可用数按 delta 调整，无 Semaphore 替换。
 */
public final class ResourceBucket {
    private final ResourceType type;
    /** 当前可用 permit 数（CAS 减少获取、增加释放） */
    private final AtomicInteger available;
    /** permit 上限（可动态调整） */
    private final AtomicInteger max;
    /** 当前已获取 permit 数（统计 + 双重释放检测） */
    private final AtomicInteger acquired;

    public ResourceBucket(ResourceType type, int initialPermits) {
        this.type = type;
        this.available = new AtomicInteger(initialPermits);
        this.max = new AtomicInteger(initialPermits);
        this.acquired = new AtomicInteger(0);
    }

    /**
     * CAS 获取一个 permit。
     *
     * @return true 表示获取成功
     */
    public boolean tryAcquire() {
        while (true) {
            int cur = available.get();
            if (cur <= 0) {
                return false;
            }
            if (available.compareAndSet(cur, cur - 1)) {
                acquired.incrementAndGet();
                return true;
            }
        }
    }

    /**
     * CAS 释放一个 permit，检测双重释放。
     *
     * @return true 表示释放成功，false 表示检测到双重释放（已无已获取 permit）
     */
    public boolean release() {
        while (true) {
            int cur = acquired.get();
            if (cur <= 0) {
                // 双重释放检测：没有已获取的 permit 可释放
                return false;
            }
            if (acquired.compareAndSet(cur, cur - 1)) {
                available.incrementAndGet();
                return true;
            }
        }
    }

    public int availablePermits() {
        return available.get();
    }

    public int acquiredCount() {
        return acquired.get();
    }

    public int maxPermits() {
        return max.get();
    }

    /**
     * 动态调整 permit 上限（Phase 4 AIMD 调用）。
     * <p>
     * 直接调整 AtomicInteger，无需替换 Semaphore。
     * 已被任务持有的 permit 不受影响，可用数按 delta 调整。
     */
    public void setMaxPermits(int newMax) {
        if (newMax < 1) {
            newMax = 1;
        }
        while (true) {
            int oldMax = max.get();
            if (max.compareAndSet(oldMax, newMax)) {
                int delta = newMax - oldMax;
                if (delta > 0) {
                    // 扩容：可用数增加
                    available.addAndGet(delta);
                } else if (delta < 0) {
                    // 缩容：可用数减少，但不能低于 0
                    while (true) {
                        int curAvail = available.get();
                        int newAvail = Math.max(0, curAvail + delta);
                        if (available.compareAndSet(curAvail, newAvail)) {
                            break;
                        }
                    }
                }
                break;
            }
        }
    }

    public ResourceType type() {
        return type;
    }
}
