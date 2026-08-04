package com.mochi_753.steadychunks.diagnostics.inflight;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在途 NOISE 任务追踪门面（阶段 3）：全局单例，供 ChunkScheduler 各生命周期
 * 点记录事件；快照/活动表供阶段 4 停滞检测与事故转储。
 * <p>
 * 生产默认启用（有界内存、任务级事件频率、无强引用）；测试可用
 * {@link #setEnabled(boolean)} 关闭或 {@link #reset()} 复位。
 * <p>
 * 维度以紧凑整数 id 记录（维度数量有界；反查表用于转储可读性，不持有
 * Holder/Future 等重型对象）。
 */
public final class InflightDiagnostics {
    private static final TaskTraceRingBuffer RING = new TaskTraceRingBuffer();
    private static final InflightTaskRegistry REGISTRY = new InflightTaskRegistry(RING);
    private static volatile boolean enabled = true;

    /** 维度 → 紧凑整数 id 与反查表（有界，维度数级） */
    private static final ConcurrentHashMap<ResourceKey<Level>, Integer> DIMENSION_IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ResourceKey<Level>> DIMENSION_NAMES = new ConcurrentHashMap<>();
    private static final AtomicInteger DIMENSION_SOURCE = new AtomicInteger(1);

    private InflightDiagnostics() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 测试/低开销模式开关（生产保持 true）。 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 分配任务追踪 id；未启用时返回 -1（调用方传 -1 即全部跳过）。 */
    public static long allocateTaskId() {
        return enabled ? REGISTRY.allocateTaskId() : -1;
    }

    /** 记录事件；taskId < 0 或未启用时为空操作。 */
    public static void record(long taskId, TaskEventType type, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!enabled || taskId < 0) {
            return;
        }
        REGISTRY.record(taskId, type, dimensionId(dimension), chunkX, chunkZ);
    }

    private static int dimensionId(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return 0;
        }
        Integer id = DIMENSION_IDS.get(dimension);
        if (id == null) {
            id = DIMENSION_SOURCE.getAndIncrement();
            Integer prev = DIMENSION_IDS.putIfAbsent(dimension, id);
            if (prev != null) {
                id = prev;
            } else {
                DIMENSION_NAMES.put(id, dimension);
            }
        }
        return id;
    }

    /** 转储可读性：维度 id → 资源名。 */
    public static String dimensionName(int id) {
        ResourceKey<Level> key = DIMENSION_NAMES.get(id);
        return key == null ? ("dim#" + id) : key.location().toString();
    }

    /** 活动（未终态）任务数——停滞检测与测试清洁断言用。 */
    public static int activeTaskCount() {
        return REGISTRY.activeTaskCount();
    }

    /** 终态唯一性异常计数。 */
    public static long terminalAnomalyCount() {
        return REGISTRY.terminalAnomalyCount();
    }

    public static long totalRecorded() {
        return REGISTRY.totalRecorded();
    }

    public static List<TaskTraceEvent> ringSnapshot() {
        return REGISTRY.ringSnapshot();
    }

    public static List<InflightTaskRecord> activeSnapshot() {
        return REGISTRY.activeSnapshot();
    }

    /** 测试/生命周期复位：清空事件、活动表与计数。 */
    public static void reset() {
        REGISTRY.reset();
    }
}
