package com.mochi_753.steadychunks.diagnostics.inflight;

/**
 * 单条在途任务追踪事件（值类型 record，无强引用）。
 * <p>
 * 字段全部为原始类型/枚举——环形缓冲可安全覆盖最旧条目，不影响任务回收；
 * 线程只记 id（转储时无需解析线程名，避免持有线程对象）。
 */
public record TaskTraceEvent(
        long taskId,
        TaskEventType type,
        long nanoTime,
        long threadId,
        int dimensionId,
        int chunkX,
        int chunkZ) {
}
