package com.mochi_753.steadychunks.telemetry;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.LongAdder;

/**
 * 线程与系统资源指标，对应开发计划 §2.6。
 * <p>
 * 采集：进程 CPU、服务端主线程 MSPT、客户端帧时间、堆占用、对象分配率、
 * GC 次数与暂停、工作队列深度、同时驻留 ProtoChunk 数、区块发送队列深度。
 * <p>
 * 用 JMX 读取堆/GC 信息（低频采样，避免开销），MSPT 由服务端 Tick 事件推送。
 */
public final class SystemResourceMetrics {
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final QuantileEstimator mspt = new QuantileEstimator();
    private final LongAdder gcPauseMs = new LongAdder();
    private final LongAdder gcCount = new LongAdder();
    private volatile long heapUsedPeak = 0;
    private volatile long heapUsedCurrent = 0;
    private volatile int protoChunkPeak = 0;
    private volatile int protoChunkCurrent = 0;
    private volatile int workerQueueDepth = 0;
    private volatile double processCpuLoad = 0;
    private volatile double worldgenCpuLoad = 0;
    private long lastGcCount = 0;

    /**
     * 记录一个 MSPT 样本（毫秒）。
     */
    public void recordMspt(long msptMs) {
        mspt.record(msptMs * 1_000_000L);
    }

    /**
     * 每 tick 采样堆与 GC 状态。
     */
    public void sampleHeap() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        heapUsedCurrent = heap.getUsed();
        if (heap.getUsed() > heapUsedPeak) {
            heapUsedPeak = heap.getUsed();
        }
        // MemoryMXBean 无 getGarbageCollectorMXBeans 方法，通过 ManagementFactory 获取
        long currentGcCount = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> b.getCollectionCount() < 0 ? 0 : b.getCollectionCount())
                .sum();
        if (currentGcCount > lastGcCount) {
            long deltaCount = currentGcCount - lastGcCount;
            gcCount.add(deltaCount);
            lastGcCount = currentGcCount;
        }
    }

    public void recordGcPause(long pauseMs) {
        gcPauseMs.add(pauseMs);
    }

    public void setProtoChunkCount(int count) {
        protoChunkCurrent = count;
        if (count > protoChunkPeak) {
            protoChunkPeak = count;
        }
    }

    public void setWorkerQueueDepth(int depth) {
        workerQueueDepth = depth;
    }

    public void setProcessCpuLoad(double load) {
        processCpuLoad = load;
    }

    public void setWorldgenCpuLoad(double load) {
        worldgenCpuLoad = load;
    }

    public QuantileEstimator mspt() {
        return mspt;
    }

    public long heapUsedPeak() {
        return heapUsedPeak;
    }

    public long heapUsedCurrent() {
        return heapUsedCurrent;
    }

    public int protoChunkPeak() {
        return protoChunkPeak;
    }

    public int protoChunkCurrent() {
        return protoChunkCurrent;
    }

    public int workerQueueDepth() {
        return workerQueueDepth;
    }

    public long gcCount() {
        return gcCount.sum();
    }

    public long gcPauseMs() {
        return gcPauseMs.sum();
    }

    public double processCpuLoad() {
        return processCpuLoad;
    }

    public double worldgenCpuLoad() {
        return worldgenCpuLoad;
    }

    public void reset() {
        mspt.reset();
        gcPauseMs.reset();
        gcCount.reset();
        heapUsedPeak = 0;
        protoChunkPeak = 0;
        lastGcCount = 0;
    }
}
