package com.mochi_753.steadychunks.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 报告导出器，对应开发计划 §2.7。
 * <p>
 * 导出格式：人类可读 Markdown 报告 + JSON 原始数据。
 * 输出到游戏目录下 {@code steadychunks-reports/} 子目录。
 */
public final class ReportExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private ReportExporter() {
    }

    /**
     * 导出 Markdown 与 JSON 报告到指定目录。
     *
     * @param outputDir 输出目录（如 {@code game/steadychunks-reports/}）
     * @return 生成的文件路径数组 [markdown, json]
     */
    public static Path[] export(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String ts = LocalDateTime.now().format(TS_FMT);
        Path mdPath = outputDir.resolve("steadychunks-report-" + ts + ".md");
        Path jsonPath = outputDir.resolve("steadychunks-report-" + ts + ".json");

        Files.writeString(mdPath, buildMarkdown());
        Files.writeString(jsonPath, buildJson());
        SteadyChunks.LOGGER.info("SteadyChunks 报告已导出：{} / {}", mdPath, jsonPath);
        return new Path[]{mdPath, jsonPath};
    }

    private static String buildMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# SteadyChunks Chunk Flight Recorder Report\n\n");
        sb.append("生成时间：").append(LocalDateTime.now()).append("\n\n");

        // 1. 概览
        sb.append("## 1. 概览\n\n");
        StageMetrics sm = ChunkFlightRecorder.stages();
        sb.append("- 阶段总完成数：").append(sm.totalCompleted()).append('\n');
        sb.append("- 阶段总失败数：").append(sm.totalFailed()).append('\n');
        sb.append("- 阶段总取消数：").append(sm.totalCancelled()).append('\n');
        sb.append("- 结构候选总数：").append(ChunkFlightRecorder.structures().totalCandidates()).append('\n');
        sb.append("- FEATURES 总执行数：").append(ChunkFlightRecorder.features().totalPlaced()).append('\n');
        sb.append("- FULL 整合数：").append(ChunkFlightRecorder.fullSend().fullCommits()).append('\n');
        sb.append("- 区块发送数：").append(ChunkFlightRecorder.fullSend().chunkSends()).append('\n');

        // 2. MSPT 与帧时间
        sb.append("\n## 2. MSPT 与帧时间\n\n");
        QuantileEstimator mspt = ChunkFlightRecorder.system().mspt();
        sb.append("| 指标 | P50 | P95 | P99 | 最大 | 单位 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        sb.append(quantileRow("MSPT", mspt, 1_000_000, "ms"));
        sb.append(quantileRow("帧时间", ChunkFlightRecorder.clientFrames().frameTime(), 1_000_000, "ms"));
        sb.append(quantileRow("区块应用", ChunkFlightRecorder.clientFrames().chunkApplyTime(), 1_000_000, "ms"));
        sb.append(quantileRow("Section 编译", ChunkFlightRecorder.clientFrames().sectionCompileTime(), 1_000_000, "ms"));

        // 3. 长帧事件
        sb.append("\n## 3. 长帧事件\n\n");
        ClientFrameMetrics cf = ChunkFlightRecorder.clientFrames();
        sb.append("- >50ms：").append(cf.framesOver50ms()).append('\n');
        sb.append("- >100ms：").append(cf.framesOver100ms()).append('\n');
        sb.append("- >250ms：").append(cf.framesOver250ms()).append('\n');
        sb.append("- >500ms：").append(cf.framesOver500ms()).append('\n');

        // 4. 阶段细分
        sb.append("\n## 4. 阶段细分\n\n");
        sb.append("| 阶段 | 完成数 | P50 | P95 | P99 | 最大 | 排队P99 | 失败 | 取消 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (StageMetrics.StageBucket b : sm.allBuckets()) {
            ChunkStatus s = b.status;
            sb.append("| ").append(s)
                    .append(" | ").append(b.completed())
                    .append(" | ").append(nanosToMs(b.execution.quantile(0.5)))
                    .append(" | ").append(nanosToMs(b.execution.quantile(0.95)))
                    .append(" | ").append(nanosToMs(b.execution.quantile(0.99)))
                    .append(" | ").append(nanosToMs(b.execution.maxNanos()))
                    .append(" | ").append(nanosToMs(b.queueWait.quantile(0.99)))
                    .append(" | ").append(b.failed.sum())
                    .append(" | ").append(b.cancelled.sum())
                    .append(" |\n");
        }

        // 5. 系统资源
        sb.append("\n## 5. 系统资源\n\n");
        SystemResourceMetrics sys = ChunkFlightRecorder.system();
        sb.append("- 堆峰值：").append(bytesToMB(sys.heapUsedPeak())).append(" MB\n");
        sb.append("- ProtoChunk 峰值：").append(sys.protoChunkPeak()).append('\n');
        sb.append("- 工作队列深度：").append(sys.workerQueueDepth()).append('\n');
        sb.append("- GC 次数：").append(sys.gcCount()).append('\n');
        sb.append("- GC 暂停：").append(sys.gcPauseMs()).append(" ms\n");
        sb.append("- 进程 CPU：").append(String.format("%.1f%%", sys.processCpuLoad() * 100)).append('\n');
        sb.append("- 世界生成 CPU：").append(String.format("%.1f%%", sys.worldgenCpuLoad() * 100)).append('\n');

        // 6. 结构长尾 Top 10
        sb.append("\n## 6. 结构长尾 Top 10（按 P99 耗时）\n\n");
        sb.append("| 结构 | 候选 | 成功 | P99 | 最大 | Piece 总数 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        ChunkFlightRecorder.structures().allBuckets().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().duration.quantile(0.99), a.getValue().duration.quantile(0.99)))
                .limit(10)
                .forEach(e -> {
                    StructureMetrics.StructureBucket b = e.getValue();
                    sb.append("| ").append(e.getKey())
                            .append(" | ").append(b.candidates.sum())
                            .append(" | ").append(b.starts.sum())
                            .append(" | ").append(nanosToMs(b.duration.quantile(0.99)))
                            .append(" | ").append(nanosToMs(b.duration.maxNanos()))
                            .append(" | ").append(b.pieces.sum())
                            .append(" |\n");
                });

        // 7. FULL 与发送
        sb.append("\n## 7. FULL 整合与发送\n\n");
        FullSendMetrics fs = ChunkFlightRecorder.fullSend();
        sb.append("| 指标 | P50 | P95 | P99 | 最大 |\n");
        sb.append("|---|---|---|---|---|\n");
        sb.append(quantileRow("FULL 整合", fs.fullCommitDuration(), 1_000_000, "ms"));
        sb.append(quantileRow("发送构建", fs.sendBuildDuration(), 1_000_000, "ms"));
        sb.append(quantileRow("发送压缩", fs.sendCompressDuration(), 1_000_000, "ms"));
        sb.append(quantileRow("FULL→发送延迟", fs.fullToSendDelay(), 1_000_000, "ms"));
        sb.append("\n- FULL 队列峰值：").append(fs.fullQueueDepthPeak()).append('\n');
        sb.append("- 发送队列峰值：").append(fs.sendQueueDepthPeak()).append('\n');

        return sb.toString();
    }

    private static String quantileRow(String name, QuantileEstimator q, long divisor, String unit) {
        return String.format("| %s | %.2f | %.2f | %.2f | %.2f | %s |\n",
                name,
                q.quantile(0.5) / (double) divisor,
                q.quantile(0.95) / (double) divisor,
                q.quantile(0.99) / (double) divisor,
                q.maxNanos() / (double) divisor,
                unit);
    }

    private static String nanosToMs(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }

    private static String bytesToMB(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String buildJson() {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", LocalDateTime.now().toString());
        root.addProperty("enabled", ChunkFlightRecorder.isEnabled());

        // 阶段
        JsonObject stages = new JsonObject();
        for (StageMetrics.StageBucket b : ChunkFlightRecorder.stages().allBuckets()) {
            JsonObject s = new JsonObject();
            s.addProperty("completed", b.completed());
            s.addProperty("failed", b.failed.sum());
            s.addProperty("cancelled", b.cancelled.sum());
            s.addProperty("p50_ms", b.execution.quantile(0.5) / 1_000_000.0);
            s.addProperty("p95_ms", b.execution.quantile(0.95) / 1_000_000.0);
            s.addProperty("p99_ms", b.execution.quantile(0.99) / 1_000_000.0);
            s.addProperty("max_ms", b.execution.maxNanos() / 1_000_000.0);
            stages.add(b.status.toString(), s);
        }
        root.add("stages", stages);

        // 系统
        JsonObject sys = new JsonObject();
        sys.addProperty("heap_peak_mb", ChunkFlightRecorder.system().heapUsedPeak() / (1024.0 * 1024.0));
        sys.addProperty("protochunk_peak", ChunkFlightRecorder.system().protoChunkPeak());
        sys.addProperty("gc_count", ChunkFlightRecorder.system().gcCount());
        sys.addProperty("gc_pause_ms", ChunkFlightRecorder.system().gcPauseMs());
        sys.addProperty("process_cpu", ChunkFlightRecorder.system().processCpuLoad());
        root.add("system", sys);

        return GSON.toJson(root);
    }
}
