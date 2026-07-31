package com.mochi_753.steadychunks.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkSendQuota 单元测试，对应 P2-19。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>tryReserve 原子 reservation 在并发下不超额</li>
 *   <li>最低预算保障（minChunksPerTick）</li>
 *   <li>resetTick 后计数归零</li>
 *   <li>字节数与光照字节数限制生效</li>
 * </ul>
 */
class ChunkSendQuotaTest {

    private ChunkSendQuota quota;

    @BeforeEach
    void setUp() {
        quota = ChunkSendQuota.getInstance();
        quota.setEnabled(true);
        quota.setMaxChunksPerTick(5);
        quota.setMaxBytesPerTick(512 * 1024);
        quota.setMaxLightBytesPerTick(128 * 1024);
        quota.setMinChunksPerTick(1);
    }

    @AfterEach
    void tearDown() {
        quota.setEnabled(false);
        quota.resetTick();
        quota.clearPlayer(UUID.randomUUID());
    }

    @Test
    void tryReserveShouldRespectMaxChunks() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertTrue(quota.tryReserve(player, 1024, 0), "前 5 个应允许");
        }
        assertFalse(quota.tryReserve(player, 1024, 0), "第 6 个应被延迟");
    }

    @Test
    void tryReserveShouldRespectByteLimit() {
        UUID player = UUID.randomUUID();
        quota.setMaxChunksPerTick(100);
        quota.setMaxBytesPerTick(1000); // 1KB
        // 第一个 600 字节
        assertTrue(quota.tryReserve(player, 600, 0));
        // 第二个 600 字节，累计 1200 > 1000，但最低预算保障下：第一个总是允许
        // 由于已发 1 个（< minChunksPerTick=1 不成立），按正常配额走
        assertFalse(quota.tryReserve(player, 600, 0), "字节超限应延迟");
    }

    @Test
    void tryReserveShouldRespectLightByteLimit() {
        UUID player = UUID.randomUUID();
        quota.setMaxChunksPerTick(100);
        quota.setMaxLightBytesPerTick(500);
        assertTrue(quota.tryReserve(player, 0, 400));
        assertFalse(quota.tryReserve(player, 0, 400), "光照字节超限应延迟");
    }

    @Test
    void resetTickShouldClearCounters() {
        UUID player = UUID.randomUUID();
        long before = quota.totalSent();
        assertTrue(quota.tryReserve(player, 100, 0));
        assertTrue(quota.tryReserve(player, 100, 0));
        assertEquals(2, quota.totalSent() - before, "本测试新增 2 次发送");

        quota.resetTick();
        // resetTick 只重置每玩家 tick 计数，不重置 totalSent 累计
        // 再次获取应成功
        assertTrue(quota.tryReserve(player, 100, 0));
    }

    @Test
    void minChunksPerTickGuaranteesFirstChunk() {
        UUID player = UUID.randomUUID();
        quota.setMaxChunksPerTick(0); // 试图禁止
        quota.setMinChunksPerTick(1);
        // 最低预算保障：第一个总是允许（绕过软预算，但须满足硬上限）
        // 使用 700KB：超过软预算 512KB，但低于硬上限 2MB
        assertTrue(quota.tryReserve(player, 700 * 1024, 0),
                "最低预算保障第一个区块发送（绕过软预算）");
    }

    /**
     * 审查修复：最低保障可绕过软预算，但不能绕过硬安全上限。
     */
    @Test
    void minGuaranteeShouldNotBypassHardMaximum() {
        UUID player = UUID.randomUUID();
        quota.setMaxChunksPerTick(0);
        quota.setMinChunksPerTick(1);
        quota.setHardMaximumPacketBytes(100 * 1024); // 100KB 硬上限
        // 200KB 超过硬上限，即使最低保障也应拒绝
        assertFalse(quota.tryReserve(player, 200 * 1024, 0),
                "硬上限不可被最低保障绕过");
    }

    @Test
    void concurrentReservationShouldNotOversell() throws InterruptedException {
        UUID player = UUID.randomUUID();
        quota.setMaxChunksPerTick(20);
        quota.setMaxBytesPerTick(Long.MAX_VALUE / 2);
        quota.setMaxLightBytesPerTick(Long.MAX_VALUE / 2);
        quota.setMinChunksPerTick(0);

        final int threads = 100;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger success = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (quota.tryReserve(player, 100, 0)) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }

        start.countDown();
        done.await();

        assertEquals(20, success.get(), "并发 reservation 不应超过 maxChunksPerTick");
    }

    /**
     * 审查新发现 #3 修复验证：并发下字节限制不应被突破。
     * <p>
     * 旧实现字节检查用快照，两线程可同时通过检查后各自 addAndGet，导致字节总量超限。
     * 修复后用二次检查 + 回滚：CAS 成功后 addAndGet，再校验是否超限，超限则回滚。
     */
    @Test
    void concurrentReservationShouldNotExceedByteLimit() throws InterruptedException {
        UUID player = UUID.randomUUID();
        // maxChunks 放大避免 chunk 限制干扰，重点测字节限制
        quota.setMaxChunksPerTick(100);
        quota.setMaxBytesPerTick(10_000); // 10KB 总预算
        quota.setMaxLightBytesPerTick(Long.MAX_VALUE / 2);
        quota.setMinChunksPerTick(0);

        final int threads = 50;
        final long bytesPerChunk = 500; // 每个线程申请 500 字节
        // 理论上限：10_000 / 500 = 20 个成功
        final int expectedMax = 20;

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger success = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    if (quota.tryReserve(player, bytesPerChunk, 0)) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }

        start.countDown();
        done.await();

        assertTrue(success.get() <= expectedMax,
                "并发下成功数不应超过字节预算允许的上限 " + expectedMax + "，实际 " + success.get());
        assertTrue(success.get() > 0, "至少应有部分成功");
    }

    @Test
    void clearPlayerShouldRemoveState() {
        UUID player = UUID.randomUUID();
        quota.tryReserve(player, 100, 0);
        quota.clearPlayer(player);
        // 清理后再获取应成功（计数已重置）
        assertTrue(quota.tryReserve(player, 100, 0));
    }

    @Test
    void disabledQuotaShouldAlwaysAllow() {
        quota.setEnabled(false);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            assertTrue(quota.tryReserve(player, Long.MAX_VALUE, Long.MAX_VALUE),
                    "未启用时应全部放行");
        }
    }
}
