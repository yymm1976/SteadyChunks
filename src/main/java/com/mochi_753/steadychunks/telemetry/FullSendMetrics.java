package com.mochi_753.steadychunks.telemetry;

import java.util.concurrent.atomic.LongAdder;

/**
 * FULL 整合与区块发送指标，对应开发计划 §2.5（服务端部分）。
 * <p>
 * 服务端记录：达到 FULL、进入主线程整合队列、开始整合、完成整合、
 * 进入发送队列、数据包构建、压缩、实际发送。
 * <p>
 * 用 {@link QuantileEstimator} 估算各环节耗时分布，
 * {@link LongAdder} 聚合队列深度峰值。
 */
public final class FullSendMetrics {
    private final QuantileEstimator fullCommitDuration = new QuantileEstimator();
    private final QuantileEstimator sendBuildDuration = new QuantileEstimator();
    private final QuantileEstimator sendCompressDuration = new QuantileEstimator();
    private final QuantileEstimator fullToSendDelay = new QuantileEstimator();
    private final LongAdder fullCommits = new LongAdder();
    private final LongAdder chunkSends = new LongAdder();
    private volatile int fullQueueDepthPeak = 0;
    private volatile int sendQueueDepthPeak = 0;
    private volatile int fullQueueDepthCurrent = 0;
    private volatile int sendQueueDepthCurrent = 0;

    public void recordFullCommit(long durationNanos) {
        fullCommitDuration.record(durationNanos);
        fullCommits.increment();
    }

    public void recordSendBuild(long durationNanos) {
        sendBuildDuration.record(durationNanos);
    }

    public void recordSendCompress(long durationNanos) {
        sendCompressDuration.record(durationNanos);
    }

    public void recordFullToSendDelay(long delayNanos) {
        fullToSendDelay.record(delayNanos);
    }

    public void recordSend() {
        chunkSends.increment();
    }

    public void setFullQueueDepth(int depth) {
        fullQueueDepthCurrent = depth;
        if (depth > fullQueueDepthPeak) {
            fullQueueDepthPeak = depth;
        }
    }

    public void setSendQueueDepth(int depth) {
        sendQueueDepthCurrent = depth;
        if (depth > sendQueueDepthPeak) {
            sendQueueDepthPeak = depth;
        }
    }

    public QuantileEstimator fullCommitDuration() {
        return fullCommitDuration;
    }

    public QuantileEstimator sendBuildDuration() {
        return sendBuildDuration;
    }

    public QuantileEstimator sendCompressDuration() {
        return sendCompressDuration;
    }

    public QuantileEstimator fullToSendDelay() {
        return fullToSendDelay;
    }

    public long fullCommits() {
        return fullCommits.sum();
    }

    public long chunkSends() {
        return chunkSends.sum();
    }

    public int fullQueueDepthPeak() {
        return fullQueueDepthPeak;
    }

    public int sendQueueDepthPeak() {
        return sendQueueDepthPeak;
    }

    public int fullQueueDepthCurrent() {
        return fullQueueDepthCurrent;
    }

    public int sendQueueDepthCurrent() {
        return sendQueueDepthCurrent;
    }

    public void reset() {
        fullCommitDuration.reset();
        sendBuildDuration.reset();
        sendCompressDuration.reset();
        fullToSendDelay.reset();
        fullCommits.reset();
        chunkSends.reset();
        fullQueueDepthPeak = 0;
        sendQueueDepthPeak = 0;
    }
}
