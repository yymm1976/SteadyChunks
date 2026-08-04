package com.mochi_753.steadychunks.diagnostics;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightDiagnostics;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightTaskRecord;
import com.mochi_753.steadychunks.diagnostics.inflight.TaskTraceEvent;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 阶段 4：在途停滞事故快照记录器（<b>只诊断，不自动修复</b>）。
 * <p>
 * 检测到停滞/恢复事件时，把当时的可观测状态落盘到
 * {@code run-server/steadychunks-incidents/<时间戳>-<类型>/}：
 * <ul>
 *   <li>{@code incident.txt}：事件汇总（类型、时间、关键计数、调度器状态）；</li>
 *   <li>{@code active-tasks.txt}：追踪活动任务表（每任务最后状态/驻留时长/线程/坐标）；</li>
 *   <li>{@code ring-events.txt}：环形缓冲最近事件（默认最近 2000 条，按写入顺序）；</li>
 *   <li>{@code threads.txt}：相关线程栈（Server thread / worldgen / Watchdog / 调度器）。</li>
 * </ul>
 * 同类事件限流（默认每 5 分钟一条），防忙转期间日志与磁盘风暴；写入失败只告警
 * 不抛异常（诊断路径不得破坏主链路）。
 */
public final class IncidentRecorder {
    /** 事故目录根（相对服务器工作目录 run-server/） */
    private static final Path BASE = Paths.get("steadychunks-incidents");
    /** 同类事件限流间隔（毫秒） */
    private static final long THROTTLE_MILLIS = 5 * 60 * 1000L;
    /** 环形缓冲转储上限（条） */
    private static final int RING_DUMP_LIMIT = 2000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<String, Long> LAST_RECORDED = new ConcurrentHashMap<>();

    private IncidentRecorder() {
    }

    /** 在途停滞：pending==0 但活动任务长期无状态变化（Watchdog 周期检测）。 */
    public static void recordInflightStall(ChunkScheduler scheduler) {
        if (!throttle("inflight-stall")) {
            return;
        }
        writeIncident("inflight-stall", sb -> sb
                .append("pending=").append(scheduler.pendingCount())
                .append(" inflight=").append(scheduler.inflightCount())
                .append(" uncontrolled=").append(scheduler.uncontrolledNoiseActive())
                .append(" traceActive=").append(InflightDiagnostics.activeTaskCount())
                .append(" traceTotal=").append(InflightDiagnostics.totalRecorded())
                .append(" terminalAnomalies=").append(InflightDiagnostics.terminalAnomalyCount())
                .append(" paused=").append(scheduler.isAdmissionPaused())
                .append(" enabled=").append(scheduler.isEnabled())
                .append(" failOpen=").append(scheduler.isFailOpen())
                .append(" drainWip=").append(scheduler.drainWipValue()));
    }

    /** drain 停滞触发两级恢复（第一级形成批次）。 */
    public static void recordDrainStall(ChunkScheduler scheduler, long batchId, int taskCount) {
        if (!throttle("drain-stall")) {
            return;
        }
        writeIncident("drain-stall", sb -> sb
                .append("pending=").append(scheduler.pendingCount())
                .append(" inflight=").append(scheduler.inflightCount())
                .append(" batchId=").append(batchId)
                .append(" capturedTasks=").append(taskCount)
                .append(" paused=").append(scheduler.isAdmissionPaused())
                .append(" enabled=").append(scheduler.isEnabled()));
    }

    /** UNSAFE_EMERGENCY 第二级升级。 */
    public static void recordUnsafeEscalation(ChunkScheduler scheduler, long batchId, int unsafeCount) {
        if (!throttle("unsafe-escalation")) {
            return;
        }
        writeIncident("unsafe-escalation", sb -> sb
                .append("pending=").append(scheduler.pendingCount())
                .append(" inflight=").append(scheduler.inflightCount())
                .append(" batchId=").append(batchId)
                .append(" completedUnsafely=").append(unsafeCount));
    }

    private static boolean throttle(String type) {
        long now = System.currentTimeMillis();
        Long last = LAST_RECORDED.get(type);
        if (last != null && now - last < THROTTLE_MILLIS) {
            return false;
        }
        LAST_RECORDED.put(type, now);
        return true;
    }

    private static void writeIncident(String type, Consumer<StringBuilder> summary) {
        String dirName = LocalDateTime.now().format(STAMP) + "-" + type;
        try {
            Path dir = BASE.resolve(dirName);
            Files.createDirectories(dir);
            StringBuilder summaryText = new StringBuilder();
            summaryText.append("type=").append(type).append('\n')
                    .append("time=").append(LocalDateTime.now()).append('\n');
            summary.accept(summaryText);
            writeFile(dir.resolve("incident.txt"), summaryText.toString());
            writeFile(dir.resolve("active-tasks.txt"), dumpActiveTasks());
            writeFile(dir.resolve("ring-events.txt"), dumpRingEvents());
            writeFile(dir.resolve("threads.txt"), dumpThreads());
            SteadyChunks.LOGGER.warn("SteadyChunks 事故快照已写入: {}（只诊断，不自动修复）", dir);
        } catch (IOException | RuntimeException ex) {
            SteadyChunks.LOGGER.warn("写入事故快照失败: {}", ex.toString());
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            out.print(content);
        }
    }

    /** 活动任务表转储（按创建时间升序）。 */
    private static String dumpActiveTasks() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 活动任务表（未终态任务，last 为最后状态变化）\n");
        for (InflightTaskRecord r : InflightDiagnostics.activeSnapshot()) {
            sb.append("taskId=").append(r.taskId)
                    .append(" createdMs=").append(r.createdNanos / 1_000_000)
                    .append(" last=").append(r.lastType)
                    .append(" lastMs=").append(r.lastNanos / 1_000_000)
                    .append(" ageMs=").append((System.nanoTime() - r.lastNanos) / 1_000_000)
                    .append(" thread=").append(r.lastThreadId)
                    .append(" dim=").append(InflightDiagnostics.dimensionName(r.dimensionId))
                    .append(" chunk=(").append(r.chunkX).append(',').append(r.chunkZ).append(")\n");
        }
        return sb.toString();
    }

    /** 环形缓冲最近事件转储（默认最近 2000 条）。 */
    private static String dumpRingEvents() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 环形缓冲事件（写入顺序；事件 nanoTime 为 System.nanoTime）\n");
        List<TaskTraceEvent> events = InflightDiagnostics.ringSnapshot();
        int from = Math.max(0, events.size() - RING_DUMP_LIMIT);
        for (int i = from; i < events.size(); i++) {
            TaskTraceEvent e = events.get(i);
            sb.append(e.taskId()).append(' ').append(e.type())
                    .append(" t=").append(e.nanoTime())
                    .append(" th=").append(e.threadId())
                    .append(" dim=").append(InflightDiagnostics.dimensionName(e.dimensionId()))
                    .append(" chunk=(").append(e.chunkX()).append(',').append(e.chunkZ()).append(")\n");
        }
        return sb.toString();
    }

    /** 相关线程栈（Server thread / worldgen / Watchdog / 调度器 / Worker 前缀）。 */
    private static String dumpThreads() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 相关线程栈（Server thread / worldgen / Watchdog / 调度器 / Worker）\n");
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        stacks.forEach((t, frames) -> {
            String name = t.getName();
            if (name.contains("Server thread") || name.contains("Chunk")
                    || name.contains("worldgen") || name.contains("Watchdog")
                    || name.contains("steady") || name.contains("Worker")
                    || name.contains("IO-Worker")) {
                sb.append("\n--- ").append(name).append(" (id=").append(t.threadId())
                        .append(" state=").append(t.getState()).append(") ---\n");
                for (StackTraceElement f : frames) {
                    sb.append("  at ").append(f).append('\n');
                }
            }
        });
        return sb.toString();
    }
}
