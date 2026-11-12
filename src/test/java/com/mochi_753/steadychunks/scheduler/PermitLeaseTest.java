package com.mochi_753.steadychunks.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PermitLease 单元测试，对应 P2-19。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>双重 close 不释放两次 permit</li>
 *   <li>try-with-resources 异常路径仍释放 permit</li>
 *   <li>empty lease close 无副作用</li>
 * </ul>
 */
class PermitLeaseTest {

    @Test
    void doubleCloseShouldNotReleaseTwice() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.NOISE_HEAVY, 1);
        assertTrue(bucket.tryAcquire(), "首次获取应成功");
        assertEquals(1, bucket.acquiredCount(), "已获取计数应为 1");

        PermitLease lease = PermitLease.acquired(bucket);
        lease.close();
        assertEquals(0, bucket.acquiredCount(), "首次 close 后已获取应为 0");
        assertEquals(1, bucket.availablePermits(), "permit 应已回收");

        // 第二次 close 不应再次释放
        lease.close();
        assertEquals(0, bucket.acquiredCount(), "二次 close 后已获取仍应为 0");
        assertEquals(1, bucket.availablePermits(), "二次 close 不应增加可用 permit");
    }

    @Test
    void tryWithResourcesShouldReleaseOnException() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.CPU_GENERAL, 2);
        // 正常路径：先 tryAcquire 再创建 lease
        assertTrue(bucket.tryAcquire(), "预先获取 permit 应成功");
        try (PermitLease outer = PermitLease.acquired(bucket)) {
            assertEquals(1, bucket.acquiredCount());
        }
        assertEquals(0, bucket.acquiredCount(), "正常 try-with-resources 应释放");
        assertEquals(2, bucket.availablePermits());

        // 异常路径：try-with-resources 保证异常时也释放
        assertTrue(bucket.tryAcquire(), "预先获取 permit 应成功");
        assertThrows(RuntimeException.class, () -> {
            try (PermitLease inner = PermitLease.acquired(bucket)) {
                assertEquals(1, bucket.acquiredCount());
                throw new RuntimeException("模拟任务异常");
            }
        }, "内部异常应向上抛出");

        assertEquals(0, bucket.acquiredCount(), "异常路径仍应释放 permit");
        assertEquals(2, bucket.availablePermits(), "异常路径后 permit 应全部回收");
    }

    @Test
    void emptyLeaseCloseIsNoop() {
        PermitLease empty = PermitLease.empty();
        assertFalse(empty.acquired(), "空 lease 不应视为已获取");
        // 多次 close 不应抛异常
        assertDoesNotThrow(() -> empty.close());
        assertDoesNotThrow(() -> empty.close());
    }

    @Test
    void emptyLeaseFromFailedAcquireShouldNotAffectBucket() {
        ResourceBucket bucket = new ResourceBucket(ResourceType.LIGHT, 1);
        // 耗尽 permit
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire(), "已耗尽时应获取失败");

        // 获取失败的 lease 不应影响 bucket
        PermitLease lease = bucket.tryAcquire() ? PermitLease.acquired(bucket) : PermitLease.empty();
        assertFalse(lease.acquired());
        lease.close(); // 不应增加 available
        assertEquals(0, bucket.availablePermits(), "空 lease close 不应增加可用 permit");
    }
}
