package com.mochi_753.steadychunks.io;

import com.mochi_753.steadychunks.telemetry.QuantileEstimator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 区块 I/O 各环节细分指标，对应开发计划 §9.1。
 * <p>
 * 按以下环节统计耗时与次数：
 * <ul>
 *   <li>RegionFile 等待（锁竞争）</li>
 *   <li>读取（磁盘 I/O）</li>
 *   <li>解压（Zlib/Zstd）</li>
 *   <li>NBT 解析</li>
 *   <li>序列化（NBT 写入）</li>
 *   <li>压缩</li>
 *   <li>写入（磁盘 I/O）</li>
 *   <li>fsync</li>
 *   <li>保存队列等待</li>
 *   <li>卸载队列等待</li>
 *   <li>主线程等待</li>
 * </ul>
 * <p>
 * 使用 {@link QuantileEstimator} 估算 P95/P99，{@link LongAdder} 聚合计数。
 * 仅诊断用途，不改变 I/O 行为。
 */
public final class IoStageMetrics {
    /** 按 I/O 环节索引的指标桶 */
    private final ConcurrentHashMap<IoStage, StageBucket> buckets = new ConcurrentHashMap<>();
    /** 累计读取字节数 */
    private final LongAdder totalReadBytes = new LongAdder();
    /** 累计写入字节数 */
    private final LongAdder totalWriteBytes = new LongAdder();

    public IoStageMetrics() {
        for (IoStage stage : IoStage.values()) {
            buckets.put(stage, new StageBucket(stage));
        }
    }

    /**
     * 记录一次 I/O 环节执行。
     *
     * @param stage         I/O 环节
     * @param durationNanos 耗时（纳秒）
     * @param bytes         涉及字节数（读取/写入/解压/压缩等，0 表示无）
     */
    public void record(IoStage stage, long durationNanos, long bytes) {
        StageBucket b = buckets.get(stage);
        if (b != null) {
            b.duration.record(durationNanos);
            b.count.increment();
            if (bytes > 0) {
                b.bytes.add(bytes);
            }
        }
        // 累计读写字节
        if (stage == IoStage.READ) {
            totalReadBytes.add(bytes);
        } else if (stage == IoStage.WRITE) {
            totalWriteBytes.add(bytes);
        }
    }

    /**
     * 便捷方法：记录一次 I/O 环节执行（无字节信息）。
     */
    public void record(IoStage stage, long durationNanos) {
        record(stage, durationNanos, 0L);
    }

    public StageBucket bucket(IoStage stage) {
        return buckets.get(stage);
    }

    public Map<IoStage, StageBucket> allBuckets() {
        return new ConcurrentHashMap<>(buckets);
    }

    public long totalReadBytes() {
        return totalReadBytes.sum();
    }

    public long totalWriteBytes() {
        return totalWriteBytes.sum();
    }

    public void reset() {
        for (StageBucket b : buckets.values()) {
            b.duration.reset();
            b.count.reset();
            b.bytes.reset();
        }
        totalReadBytes.reset();
        totalWriteBytes.reset();
    }

    /**
     * 生成人类可读的 Markdown 概览。
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 区块 I/O 环节指标\n\n");
        sb.append("| 环节 | 次数 | 总耗时(ms) | P95(ms) | P99(ms) | 最大(ms) | 字节 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (IoStage stage : IoStage.values()) {
            StageBucket b = buckets.get(stage);
            if (b == null || b.count.sum() == 0) {
                continue;
            }
            sb.append(String.format("| %s | %d | %.2f | %.2f | %.2f | %.2f | %d |\n",
                    stage.displayName(),
                    b.count.sum(),
                    b.duration.sumNanos() / 1_000_000.0,
                    b.duration.quantile(0.95) / 1_000_000.0,
                    b.duration.quantile(0.99) / 1_000_000.0,
                    b.duration.maxNanos() / 1_000_000.0,
                    b.bytes.sum()));
        }
        return sb.toString();
    }

    /** I/O 环节类型 */
    public enum IoStage {
        REGION_FILE_WAIT("RegionFile 等待"),
        READ("读取"),
        DECOMPRESS("解压"),
        NBT_PARSE("NBT 解析"),
        SERIALIZE("序列化"),
        COMPRESS("压缩"),
        WRITE("写入"),
        FSYNC("fsync"),
        SAVE_QUEUE_WAIT("保存队列等待"),
        UNLOAD_QUEUE_WAIT("卸载队列等待"),
        MAIN_THREAD_WAIT("主线程等待");

        private final String displayName;

        IoStage(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 单个 I/O 环节的指标桶 */
    public static final class StageBucket {
        public final IoStage stage;
        public final QuantileEstimator duration = new QuantileEstimator();
        public final LongAdder count = new LongAdder();
        public final LongAdder bytes = new LongAdder();

        StageBucket(IoStage stage) {
            this.stage = stage;
        }
    }
}
