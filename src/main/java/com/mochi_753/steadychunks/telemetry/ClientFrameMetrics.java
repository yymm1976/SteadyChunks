package com.mochi_753.steadychunks.telemetry;

import java.util.concurrent.atomic.LongAdder;

/**
 * 客户端帧时间与区块接收指标，对应开发计划 §2.5（客户端部分）与 §2.6。
 * <p>
 * 采集：帧时间、Section 编译队列、区块接收批次、应用耗时、首次可见帧延迟。
 * 由客户端 Mixin 钩子推送数据，服务端通过自定义数据包接收（Phase 5 实现）。
 * <p>
 * 当前为服务端侧聚合视图，客户端原始采集在 {@code client.RenderPressureProbe}（Phase 5）。
 */
public final class ClientFrameMetrics {
    private final QuantileEstimator frameTime = new QuantileEstimator();
    private final QuantileEstimator chunkApplyTime = new QuantileEstimator();
    private final QuantileEstimator sectionCompileTime = new QuantileEstimator();
    private final LongAdder framesOver50ms = new LongAdder();
    private final LongAdder framesOver100ms = new LongAdder();
    private final LongAdder framesOver250ms = new LongAdder();
    private final LongAdder framesOver500ms = new LongAdder();
    private final LongAdder chunksReceived = new LongAdder();
    private volatile int sectionCompileQueueDepth = 0;
    private volatile int chunkReceiveQueueDepth = 0;

    /**
     * 记录一帧（纳秒）。
     */
    public void recordFrame(long frameNanos) {
        frameTime.record(frameNanos);
        long ms = frameNanos / 1_000_000;
        if (ms > 500) framesOver500ms.increment();
        if (ms > 250) framesOver250ms.increment();
        if (ms > 100) framesOver100ms.increment();
        if (ms > 50) framesOver50ms.increment();
    }

    public void recordChunkApply(long durationNanos) {
        chunkApplyTime.record(durationNanos);
        chunksReceived.increment();
    }

    public void recordSectionCompile(long durationNanos) {
        sectionCompileTime.record(durationNanos);
    }

    public void setSectionCompileQueueDepth(int depth) {
        sectionCompileQueueDepth = depth;
    }

    public void setChunkReceiveQueueDepth(int depth) {
        chunkReceiveQueueDepth = depth;
    }

    public QuantileEstimator frameTime() {
        return frameTime;
    }

    public QuantileEstimator chunkApplyTime() {
        return chunkApplyTime;
    }

    public QuantileEstimator sectionCompileTime() {
        return sectionCompileTime;
    }

    public long framesOver50ms() {
        return framesOver50ms.sum();
    }

    public long framesOver100ms() {
        return framesOver100ms.sum();
    }

    public long framesOver250ms() {
        return framesOver250ms.sum();
    }

    public long framesOver500ms() {
        return framesOver500ms.sum();
    }

    public long chunksReceived() {
        return chunksReceived.sum();
    }

    public int sectionCompileQueueDepth() {
        return sectionCompileQueueDepth;
    }

    public int chunkReceiveQueueDepth() {
        return chunkReceiveQueueDepth;
    }

    public void reset() {
        frameTime.reset();
        chunkApplyTime.reset();
        sectionCompileTime.reset();
        framesOver50ms.reset();
        framesOver100ms.reset();
        framesOver250ms.reset();
        framesOver500ms.reset();
        chunksReceived.reset();
    }
}
