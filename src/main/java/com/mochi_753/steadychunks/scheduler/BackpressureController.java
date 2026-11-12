package com.mochi_753.steadychunks.scheduler;

/**
 * 背压控制器，对应开发计划 §3.4。
 * <p>
 * 当队列或 ProtoChunk 数超过阈值时：
 * <ul>
 *   <li>不继续启动远处早期阶段</li>
 *   <li>优先推进近处半成品区块</li>
 *   <li>降低非玩家直接需求的任务优先级</li>
 *   <li>阻止预取或预测任务扩张</li>
 *   <li>保留服务器关键任务的 CPU 余量</li>
 * </ul>
 */
public final class BackpressureController {
    /** ProtoChunk 驻留数阈值 */
    private volatile int protoChunkThreshold = 128;
    /** 总在途任务数阈值 */
    private volatile int inflightThreshold = 64;
    /** 队列深度阈值 */
    private volatile int queueDepthThreshold = 256;
    /** 当前 ProtoChunk 数（由 telemetry 推送） */
    private volatile int currentProtoChunks = 0;
    /** 当前在途任务数 */
    private volatile int currentInflight = 0;
    /** 当前队列深度 */
    private volatile int currentQueueDepth = 0;

    /**
     * 判断是否应该施加背压。
     *
     * @return 背压级别：NONE / SOFT / HARD
     */
    public BackpressureLevel evaluate() {
        boolean protoOverflow = currentProtoChunks > protoChunkThreshold;
        boolean inflightOverflow = currentInflight > inflightThreshold;
        boolean queueOverflow = currentQueueDepth > queueDepthThreshold;

        if (protoOverflow && inflightOverflow) {
            return BackpressureLevel.HARD;
        }
        if (protoOverflow || inflightOverflow || queueOverflow) {
            return BackpressureLevel.SOFT;
        }
        return BackpressureLevel.NONE;
    }

    /**
     * 在背压下判断是否允许启动新任务。
     *
     * @param task 待启动任务
     * @param level 当前背压级别
     * @return true 表示允许启动
     */
    public boolean allowNewTask(ChunkTask task, BackpressureLevel level) {
        return switch (level) {
            case NONE -> true;
            // SOFT：允许近处和半成品，阻止远处早期阶段
            case SOFT -> task.progress() > 0 || task.distance() < 32
                    || task.safety() == ChunkTask.SafetyLevel.HIGH;
            // HARD：仅允许依赖解锁和近处完成
            case HARD -> task.safety() == ChunkTask.SafetyLevel.HIGH
                    || (task.progress() > 0.5 && task.distance() < 16);
        };
    }

    public void setProtoChunkCount(int count) {
        this.currentProtoChunks = count;
    }

    public void setInflightCount(int count) {
        this.currentInflight = count;
    }

    public void setQueueDepth(int depth) {
        this.currentQueueDepth = depth;
    }

    public void setProtoChunkThreshold(int threshold) {
        this.protoChunkThreshold = threshold;
    }

    public void setInflightThreshold(int threshold) {
        this.inflightThreshold = threshold;
    }

    public void setQueueDepthThreshold(int threshold) {
        this.queueDepthThreshold = threshold;
    }

    public int protoChunkThreshold() {
        return protoChunkThreshold;
    }

    public int inflightThreshold() {
        return inflightThreshold;
    }

    /** 背压级别 */
    public enum BackpressureLevel {
        /** 无背压，正常调度 */
        NONE,
        /** 软背压：限制远处早期阶段，优先近处 */
        SOFT,
        /** 硬背压：仅允许依赖解锁和近处完成 */
        HARD
    }
}
