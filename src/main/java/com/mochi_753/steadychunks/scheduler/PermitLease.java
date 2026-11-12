package com.mochi_753.steadychunks.scheduler;

import com.mochi_753.steadychunks.SteadyChunks;

/**
 * Permit 租约，对应 P1-08 修复。
 * <p>
 * {@link AutoCloseable} 语义，配合 try-with-resources 使用，保证 permit 在正常、
 * 异常和取消路径均被释放。通过 CAS 保证单次释放，防止双重释放导致 permit 泄漏。
 * <p>
 * 使用示例：
 * <pre>{@code
 * try (PermitLease lease = permit.tryAcquireLease()) {
 *     if (!lease.acquired()) {
 *         return; // 获取失败
 *     }
 *     // 执行任务
 * } // 自动释放
 * }</pre>
 */
public final class PermitLease implements AutoCloseable {
    private static final PermitLease EMPTY = new PermitLease(null);

    private final ResourceBucket bucket;
    private volatile boolean released = false;

    private PermitLease(ResourceBucket bucket) {
        this.bucket = bucket;
    }

    /** 获取成功时创建租约 */
    static PermitLease acquired(ResourceBucket bucket) {
        return new PermitLease(bucket);
    }

    /** 获取失败的空租约 */
    static PermitLease empty() {
        return EMPTY;
    }

    /**
     * 是否成功获取了 permit。
     */
    public boolean acquired() {
        return bucket != null;
    }

    /**
     * 释放 permit（CAS 保证单次释放）。
     * <p>
     * 可安全地多次调用，仅首次有效。配合 try-with-resources 自动调用。
     */
    @Override
    public void close() {
        if (bucket == null) {
            return;
        }
        if (released) {
            // 双重释放：记录但不崩溃
            return;
        }
        synchronized (this) {
            if (released) {
                return;
            }
            released = true;
        }
        if (!bucket.release()) {
            SteadyChunks.LOGGER.warn("SteadyChunks PermitLease: 检测到 permit 计数不一致（可能双重释放）");
        }
    }
}
