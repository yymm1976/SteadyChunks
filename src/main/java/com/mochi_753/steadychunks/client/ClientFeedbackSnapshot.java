package com.mochi_753.steadychunks.client;

import java.util.UUID;

/**
 * 客户端反馈快照，对应开发计划 §5.4。
 * <p>
 * 客户端只提供以下聚合值（不每帧发包，每 1-2 秒发送一次）：
 * <ul>
 *   <li>帧时间 P95 / P99 / 最大值</li>
 *   <li>Section 编译队列深度</li>
 *   <li>最近接收区块数</li>
 *   <li>区块应用耗时</li>
 *   <li>可见缺口数</li>
 * </ul>
 * <p>
 * 技术指导 §9.3：服务端只将其视为提示，不信任客户端提供的绝对值，
 * 不允许它改变游戏逻辑或获得额外权限。远程服务器必须允许关闭或忽略客户端反馈。
 * <p>
 * 网络传输使用 NeoForge 1.21.x 的 {@code CustomPacketPayload}。
 */
public final class ClientFeedbackSnapshot {
    private final UUID playerId;
    private final double p95FrameTimeMs;
    private final double p99FrameTimeMs;
    private final double maxFrameTimeMs;
    private final int sectionCompileQueueDepth;
    private final int chunksReceivedLastWindow;
    private final double chunkApplyTimeMs;
    private final int visibleGaps;
    private final long timestampNanos;
    /** 协议版本，用于兼容性检查 */
    private final int protocolVersion;

    public static final int PROTOCOL_VERSION = 1;

    public ClientFeedbackSnapshot(UUID playerId, double p95FrameTimeMs, double p99FrameTimeMs,
                                  double maxFrameTimeMs, int sectionCompileQueueDepth,
                                  int chunksReceivedLastWindow, double chunkApplyTimeMs,
                                  int visibleGaps) {
        this.playerId = playerId;
        this.p95FrameTimeMs = p95FrameTimeMs;
        this.p99FrameTimeMs = p99FrameTimeMs;
        this.maxFrameTimeMs = maxFrameTimeMs;
        this.sectionCompileQueueDepth = sectionCompileQueueDepth;
        this.chunksReceivedLastWindow = chunksReceivedLastWindow;
        this.chunkApplyTimeMs = chunkApplyTimeMs;
        this.visibleGaps = visibleGaps;
        this.timestampNanos = System.nanoTime();
        this.protocolVersion = PROTOCOL_VERSION;
    }

    public UUID playerId() { return playerId; }
    public double p95FrameTimeMs() { return p95FrameTimeMs; }
    public double p99FrameTimeMs() { return p99FrameTimeMs; }
    public double maxFrameTimeMs() { return maxFrameTimeMs; }
    public int sectionCompileQueueDepth() { return sectionCompileQueueDepth; }
    public int chunksReceivedLastWindow() { return chunksReceivedLastWindow; }
    public double chunkApplyTimeMs() { return chunkApplyTimeMs; }
    public int visibleGaps() { return visibleGaps; }
    public long timestampNanos() { return timestampNanos; }
    public int protocolVersion() { return protocolVersion; }

    /**
     * 判断反馈是否过期（超过 10 秒视为过期）。
     */
    public boolean isStale() {
        return System.nanoTime() - timestampNanos > 10_000_000_000L;
    }

    /**
     * 综合客户端压力评估，供服务端调度器参考。
     * <p>
     * 服务端不信任绝对值，仅用于趋势判断。
     */
    public ClientPressureLevel evaluatePressure() {
        // Section 编译队列暴涨是最强信号
        if (sectionCompileQueueDepth > 50) {
            return ClientPressureLevel.CRITICAL;
        }
        // 帧时间持续超标
        if (p95FrameTimeMs > 50 || maxFrameTimeMs > 150) {
            return ClientPressureLevel.ELEVATED;
        }
        // 可见缺口多
        if (visibleGaps > 20) {
            return ClientPressureLevel.ELEVATED;
        }
        return ClientPressureLevel.HEALTHY;
    }

    /** 客户端压力等级 */
    public enum ClientPressureLevel {
        HEALTHY, ELEVATED, CRITICAL, UNKNOWN
    }
}
