package com.mochi_753.steadychunks.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResourceBucket 单元测试，对应 P2-19。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>动态缩容时已获取 permit 继续运行</li>
 *   <li>动态扩容后可获取新 permit</li>
 *   <li>缩容后 available 不会为负</li>
 *   <li>CAS 并发获取不超额</li>
 * </ul>
 */
class ResourceBucketTest {

    @Test
    void shrinkShouldNotAffectAlreadyAcquiredPermits() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.NOISE_HEAVY, 4);
        // 获取 3 个
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertEquals(3, bucket.acquiredCount());
        assertEquals(1, bucket.availablePermits());

        // 缩容到 2
        bucket.setMaxPermits(2);
        assertEquals(2, bucket.maxPermits(), "max 应更新为 2");
        // 已获取的 3 个仍计入 acquired（任务继续运行）
        assertEquals(3, bucket.acquiredCount(), "缩容不影响已持有 permit");
        // available 应为 0（不能为负）
        assertEquals(0, bucket.availablePermits(), "缩容后可用 permit 不能为负");

        // 释放一个后：inUse=2，available=max(0, 2-2)=0（恰好达到 limit）
        assertTrue(bucket.release());
        assertEquals(2, bucket.acquiredCount());
        assertEquals(0, bucket.availablePermits(), "释放后 inUse==limit，available 仍为 0");
    }

    /**
     * 审查新发现 #1 修复验证：缩容后逐步释放，available 不应超过新 max。
     * <p>
     * 旧实现 release 无条件 incrementAndGet，全部释放后 available 会累积到旧 max，
     * 导致后续 tryAcquire 可获取超过新 max 的 permit。
     */
    @Test
    void releaseAfterShrinkShouldNotExceedNewMax() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.NOISE_HEAVY, 4);
        // 获取 3 个，缩容到 2
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        bucket.setMaxPermits(2);
        assertEquals(0, bucket.availablePermits());

        // 全部释放
        assertTrue(bucket.release());
        assertTrue(bucket.release());
        assertTrue(bucket.release());
        assertEquals(0, bucket.acquiredCount());

        // 关键断言：available 不应超过新 max=2
        assertEquals(2, bucket.availablePermits(), "全部释放后 available 不应超过新 max");
        assertEquals(2, bucket.maxPermits());

        // 后续 tryAcquire 只能获取 2 个（新 max），而非 4 个（旧 max）
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire(), "不应能获取超过新 max 的 permit");
    }

    /**
     * 审查新发现 #4 修复验证：tryAcquireWithReserve 单 CAS 校验保留额度。
     */
    @Test
    void tryAcquireWithReserveShouldProtectReservedPermits() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.STRUCTURE_PLANNING, 3);
        // reserve=1：普通任务在 available <= 1 时拒绝
        // available=3 > 1，普通任务可获取
        assertTrue(bucket.tryAcquireWithReserve(1));
        assertEquals(2, bucket.availablePermits());

        // available=2 > 1，普通任务可获取
        assertTrue(bucket.tryAcquireWithReserve(1));
        assertEquals(1, bucket.availablePermits());

        // available=1 <= 1，普通任务应被拒绝（保留给依赖解锁）
        assertFalse(bucket.tryAcquireWithReserve(1), "普通任务不应占用保留额度");

        // 但无 reserve 的 tryAcquire 仍可获取最后一个
        assertTrue(bucket.tryAcquire());
        assertEquals(0, bucket.availablePermits());

        // reserve=0 退化为普通 tryAcquire
        ResourceBucket bucket2 = new ResourceBucket(ResourceType.LIGHT, 2);
        assertTrue(bucket2.tryAcquireWithReserve(0));
        assertTrue(bucket2.tryAcquireWithReserve(0));
        assertFalse(bucket2.tryAcquireWithReserve(0));
    }

    @Test
    void expandShouldAllowNewAcquire() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.FEATURES_WRITE, 1);
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire(), "max=1 时第二个应失败");

        // 扩容到 3
        bucket.setMaxPermits(3);
        assertEquals(3, bucket.maxPermits());
        assertEquals(2, bucket.availablePermits(), "扩容后 available 增加 2");

        assertTrue(bucket.tryAcquire(), "扩容后可获取");
        assertTrue(bucket.tryAcquire(), "扩容后可获取第二个");
        assertFalse(bucket.tryAcquire(), "再次耗尽");
    }

    @Test
    void shrinkBelowOneIsClampedToOne() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.CPU_GENERAL, 5);
        bucket.setMaxPermits(0); // 应被钳制为 1
        assertEquals(1, bucket.maxPermits(), "max 不能低于 1");
    }

    @Test
    void casAcquireShouldNotOversellUnderConcurrency() throws InterruptedException {
        final int permits = 8;
        final int threads = 32;
        ResourceBucket bucket = new ResourceBucket(ResourceType.MAIN_THREAD_COMMIT, permits);

        Thread[] ts = new Thread[threads];
        int[] acquired = new int[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                if (bucket.tryAcquire()) {
                    acquired[idx] = 1;
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        int total = 0;
        for (int a : acquired) total += a;
        assertEquals(permits, total, "并发获取总数不应超过 permits");
        assertEquals(permits, bucket.acquiredCount());
        assertEquals(0, bucket.availablePermits());
    }

    @Test
    void releaseWithoutAcquireShouldReturnFalse() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.IO_READ, 2);
        assertFalse(bucket.release(), "无 permit 时释放应返回 false");
        assertEquals(0, bucket.acquiredCount());
    }
}
