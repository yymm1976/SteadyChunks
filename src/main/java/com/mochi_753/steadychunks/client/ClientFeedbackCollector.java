package com.mochi_753.steadychunks.client;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端反馈采集器，对应开发计划 §5.4。
 * <p>
 * 客户端侧：采集本机帧时间、Section 编译队列、接收区块数、应用耗时、可见缺口，
 * 每 1-2 秒聚合一次发送到服务端。
 * <p>
 * 技术指导 §9.3：不每帧发包，每 1-2 秒发送聚合值。
 */
public final class ClientFeedbackCollector {
    private static ClientFeedbackCollector instance;

    /** 帧时间采样窗口（纳秒） */
    private final Deque<Long> frameTimeSamples = new ArrayDeque<>();
    private static final int MAX_SAMPLES = 600; // 约 30 秒 @ 20fps
    /** 发送间隔（纳秒），2 秒 */
    private static final long SEND_INTERVAL_NANOS = 2_000_000_000L;
    private volatile long lastSendNanos = 0;

    private final AtomicInteger sectionCompileQueueDepth = new AtomicInteger(0);
    private final AtomicInteger chunksReceivedInWindow = new AtomicInteger(0);
    private final AtomicLong chunkApplyTimeNanos = new AtomicLong(0);
    private final AtomicInteger visibleGaps = new AtomicInteger(0);

    private volatile UUID localPlayerId = null;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private ClientFeedbackCollector() {
    }

    public static synchronized ClientFeedbackCollector getInstance() {
        if (instance == null) {
            instance = new ClientFeedbackCollector();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setLocalPlayerId(UUID id) {
        this.localPlayerId = id;
    }

    /**
     * 采集一帧的帧时间（由 MixinMinecraft 调用）。
     *
     * @param frameTimeNanos 本帧耗时（纳秒）
     */
    public void recordFrame(long frameTimeNanos) {
        if (!enabled.get()) {
            return;
        }
        synchronized (frameTimeSamples) {
            frameTimeSamples.addLast(frameTimeNanos);
            while (frameTimeSamples.size() > MAX_SAMPLES) {
                frameTimeSamples.removeFirst();
            }
        }
    }

    public void setSectionCompileQueueDepth(int depth) {
        sectionCompileQueueDepth.set(depth);
    }

    public void onChunkReceived() {
        chunksReceivedInWindow.incrementAndGet();
    }

    public void onChunkApplied(long applyNanos) {
        chunkApplyTimeNanos.accumulateAndGet(applyNanos, Math::max);
    }

    public void setVisibleGaps(int gaps) {
        visibleGaps.set(gaps);
    }

    /**
     * 尝试构建反馈快照。每 2 秒最多构建一次。
     *
     * @return 反馈快照，null 表示未到发送时间或未启用
     */
    public ClientFeedbackSnapshot tryBuildSnapshot() {
        if (!enabled.get() || localPlayerId == null) {
            return null;
        }
        long now = System.nanoTime();
        if (now - lastSendNanos < SEND_INTERVAL_NANOS) {
            return null;
        }
        lastSendNanos = now;

        double p95, p99, max;
        synchronized (frameTimeSamples) {
            if (frameTimeSamples.isEmpty()) {
                return null;
            }
            long[] sorted = frameTimeSamples.stream().mapToLong(Long::longValue).sorted().toArray();
            p95 = sorted[(int) (sorted.length * 0.95)] / 1_000_000.0;
            p99 = sorted[(int) (sorted.length * 0.99)] / 1_000_000.0;
            max = sorted[sorted.length - 1] / 1_000_000.0;
        }

        ClientFeedbackSnapshot snapshot = new ClientFeedbackSnapshot(
                localPlayerId, p95, p99, max,
                sectionCompileQueueDepth.get(),
                chunksReceivedInWindow.getAndSet(0),
                chunkApplyTimeNanos.getAndSet(0) / 1_000_000.0,
                visibleGaps.get()
        );

        // 清理旧样本（只保留最近 10 秒）
        synchronized (frameTimeSamples) {
            long cutoff = now - 10_000_000_000L;
            frameTimeSamples.removeIf(t -> t < cutoff);
        }

        return snapshot;
    }

    /**
     * 重置采集器（玩家断开或换维度时）。
     */
    public void reset() {
        synchronized (frameTimeSamples) {
            frameTimeSamples.clear();
        }
        sectionCompileQueueDepth.set(0);
        chunksReceivedInWindow.set(0);
        chunkApplyTimeNanos.set(0);
        visibleGaps.set(0);
        lastSendNanos = 0;
    }
}
