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
        // 最低预算保障：第一个总是允许
        assertTrue(quota.tryReserve(player, 1_000_000_000, 1_000_000_000),
                "最低预算保障第一个区块发送");
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
