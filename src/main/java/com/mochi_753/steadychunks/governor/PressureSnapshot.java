package com.mochi_753.steadychunks.governor;

/**
 * 系统压力快照，对应开发计划 §4.2。
 * <p>
 * 采用滑动窗口聚合，不直接对单次样本反应。
 * 控制器输入：P95/P99 MSPT、P95/P99 帧时间、长帧事件率、进程 CPU、
 * 世界生成线程 CPU、堆压力、最近 GC 暂停、FULL 等待队列、可见区块缺口、
 * 区块发送队列、客户端 Section 编译队列。
 */
public final class PressureSnapshot {
    /** P95 MSPT（毫秒） */
    public final double p95Mspt;
    /** P99 MSPT（毫秒） */
    public final double p99Mspt;
    /** P95 帧时间（毫秒） */
    public final double p95FrameTime;
    /** P99 帧时间（毫秒） */
    public final double p99FrameTime;
    /** 长帧事件率（每秒长帧数） */
    public final double longFrameRate;
    /** 进程 CPU 使用率 [0,1] */
    public final double processCpuLoad;
    /** 世界生成线程 CPU 使用率 [0,1] */
    public final double worldgenCpuLoad;
    /** 堆压力 [0,1]（已用堆 / 最大堆） */
    public final double heapPressure;
    /** 最近 GC 暂停（毫秒） */
    public final long recentGcPauseMs;
    /** FULL 等待队列深度 */
    public final int fullQueueDepth;
    /** 可见区块缺口数 */
    public final int visibleChunkGaps;
    /** 区块发送队列深度 */
    public final int sendQueueDepth;
    /** 客户端 Section 编译队列深度 */
    public final int sectionCompileQueueDepth;
    /** 快照时间戳（纳秒） */
    public final long timestampNanos;

    public PressureSnapshot(double p95Mspt, double p99Mspt,
                            double p95FrameTime, double p99FrameTime,
                            double longFrameRate, double processCpuLoad,
                            double worldgenCpuLoad, double heapPressure,
                            long recentGcPauseMs, int fullQueueDepth,
                            int visibleChunkGaps, int sendQueueDepth,
                            int sectionCompileQueueDepth) {
        this.p95Mspt = p95Mspt;
        this.p99Mspt = p99Mspt;
        this.p95FrameTime = p95FrameTime;
        this.p99FrameTime = p99FrameTime;
        this.longFrameRate = longFrameRate;
        this.processCpuLoad = processCpuLoad;
        this.worldgenCpuLoad = worldgenCpuLoad;
        this.heapPressure = heapPressure;
        this.recentGcPauseMs = recentGcPauseMs;
        this.fullQueueDepth = fullQueueDepth;
        this.visibleChunkGaps = visibleChunkGaps;
        this.sendQueueDepth = sendQueueDepth;
        this.sectionCompileQueueDepth = sectionCompileQueueDepth;
        this.timestampNanos = System.nanoTime();
    }

    /**
     * 综合压力等级评估，对应 §4.3 AIMD 输入。
     *
     * @param thresholds 阈值配置
     * @return 压力等级：HEALTHY / ELEVATED / CRITICAL
     */
    public PressureLevel evaluateLevel(ThresholdConfig thresholds) {
        int criticalCount = 0;
        int elevatedCount = 0;

        // MSPT 检查
        if (p95Mspt > thresholds.hardMspt) {
            criticalCount++;
        } else if (p95Mspt > thresholds.targetP95Mspt) {
            elevatedCount++;
        }

        // 帧时间检查
        if (p95FrameTime > thresholds.emergencyFrameMs) {
            criticalCount++;
        } else if (p95FrameTime > thresholds.longFrameMs) {
            elevatedCount++;
        }

        // CPU 检查
        if (processCpuLoad > thresholds.targetProcessCpu) {
            elevatedCount++;
        }

        // 堆压力检查
        if (heapPressure > thresholds.heapPressure) {
            elevatedCount++;
        }

        // GC 暂停检查
        if (recentGcPauseMs > 50) {
            criticalCount++;
        }

        // 客户端编译队列暴涨
        if (sectionCompileQueueDepth > 50) {
            criticalCount++;
        }

        // 区块发送队列暴涨
        if (sendQueueDepth > 100) {
            criticalCount++;
        }

        if (criticalCount >= 2) {
            return PressureLevel.CRITICAL;
        }
        if (criticalCount >= 1 || elevatedCount >= 2) {
            return PressureLevel.ELEVATED;
        }
        return PressureLevel.HEALTHY;
    }

    /** 压力等级 */
    public enum PressureLevel {
        /** 健康：可缓慢增加 permit */
        HEALTHY,
        /** 升高：保持当前 permit，不增不减 */
        ELEVATED,
        /** 临界：立即减少高成本阶段 permit */
        CRITICAL
    }

    /** 阈值配置，对应 [governor] 配置区段 */
    public static final class ThresholdConfig {
        public final double targetP95Mspt;
        public final double hardMspt;
        public final double targetProcessCpu;
        public final double heapPressure;
        public final double longFrameMs;
        public final double emergencyFrameMs;

        public ThresholdConfig(double targetP95Mspt, double hardMspt,
                               double targetProcessCpu, double heapPressure,
                               double longFrameMs, double emergencyFrameMs) {
            this.targetP95Mspt = targetP95Mspt;
            this.hardMspt = hardMspt;
            this.targetProcessCpu = targetProcessCpu;
            this.heapPressure = heapPressure;
            this.longFrameMs = longFrameMs;
            this.emergencyFrameMs = emergencyFrameMs;
        }
    }
}
