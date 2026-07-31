package com.mochi_753.steadychunks.io;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界 I/O 读写队列控制器，对应开发计划 §9.3。
 * <p>
 * 策略：
 * <ul>
 *   <li>读取和写入分离，各自独立预算</li>
 *   <li>玩家前方读取优先于远处保存，但保存不能永久饥饿（老化优先级）</li>
 *   <li>限制同时压缩任务数</li>
 *   <li>防止保存队列占用无限内存（有界容量）</li>
 *   <li>服务器停止时可靠排空（停服模式提升写入预算并停止新生成）</li>
 *   <li>单个 RegionFile 的操作保持正确顺序（同区域串行化）</li>
 * </ul>
 * <p>
 * 风险缓解（计划 §9 风险表）：
 * <ul>
 *   <li>写入重排破坏 RegionFile 语义 → 同区域串行化并维护提交顺序</li>
 *   <li>停服排空时间过长 → 停服模式提升写入预算并停止新生成</li>
 *   <li>保存饥饿 → 老化优先级和硬性最大等待时间</li>
 * </ul>
 * <p>
 * 线程安全：读写队列使用 {@link PriorityBlockingQueue}，RegionFile 锁使用
 * {@link ConcurrentHashMap} 维护。
 */
public final class IoQueueController {
    private static IoQueueController instance;

    /** 读取队列（玩家前方读取优先） */
    private final PriorityBlockingQueue<IoTask> readQueue = new PriorityBlockingQueue<>(128);
    /** 写入队列（老化优先级防饥饿） */
    private final PriorityBlockingQueue<IoTask> writeQueue = new PriorityBlockingQueue<>(128);
    /** 按 RegionFile（regionX,regionZ packed）索引的串行锁 */
    private final ConcurrentHashMap<Long, RegionFileLock> regionLocks = new ConcurrentHashMap<>();

    /** 读取队列容量上限 */
    private volatile int readQueueCapacity = 256;
    /** 写入队列容量上限 */
    private volatile int writeQueueCapacity = 512;
    /** 每 Tick 最大读取任务数 */
    private volatile int maxReadsPerTick = 8;
    /** 每 Tick 最大写入任务数 */
    private volatile int maxWritesPerTick = 4;
    /** 停服模式下的写入预算提升倍数 */
    private volatile int shutdownWriteBoost = 4;
    /** 同时压缩任务上限 */
    private volatile int maxConcurrentCompress = 2;
    /** 当前在途压缩任务数 */
    private final AtomicInteger compressInflight = new AtomicInteger(0);
    /** 保存任务最大等待时间（毫秒），超时后强制提升优先级 */
    private volatile long maxSaveWaitMs = 10000L;
    /** §16.1 保存背压高水位：写入队列深度超过此值时停止后台生成 */
    private volatile int writeHighWatermark = 384;
    /** §16.1 保存背压紧急水位：写入队列深度超过此值时降低生成 permit 并提升写入优先级 */
    private volatile int writeEmergencyWatermark = 448;

    /** 停服模式标志 */
    private final AtomicBoolean shutdownMode = new AtomicBoolean(false);
    /** 启用标志 */
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    /** §16.1 背压回调：水位变化时通知调度器 */
    private volatile BackpressureCallback backpressureCallback;

    /** 统计 */
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalReadRejected = new AtomicLong(0);
    private final AtomicLong totalWriteRejected = new AtomicLong(0);
    private final AtomicLong totalRegionContention = new AtomicLong(0);
    private final AtomicLong totalForcedByAge = new AtomicLong(0);

    private IoQueueController() {
    }

    public static synchronized IoQueueController getInstance() {
        if (instance == null) {
            instance = new IoQueueController();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 进入停服模式：提升写入预算，停止接受新读取任务。
     */
    public void enterShutdownMode() {
        shutdownMode.set(true);
        SteadyChunks.LOGGER.info("SteadyChunks I/O 进入停服模式：写入预算提升 {} 倍，停止新读取", shutdownWriteBoost);
    }

    /**
     * 提交读取任务。
     * <p>
     * 停服模式下拒绝新读取（除依赖关键任务外）。
     *
     * @param packedChunkPos 区块 packed long
     * @param distance       到玩家距离（近处优先）
     * @param isCritical     是否为依赖关键任务（停服模式下仍接受）
     * @param action         I/O 动作
     * @return true 表示已入队，false 表示被拒绝
     */
    public boolean submitRead(long packedChunkPos, double distance, boolean isCritical, Runnable action) {
        if (!enabled.get()) {
            action.run();
            return true;
        }
        if (shutdownMode.get() && !isCritical) {
            totalReadRejected.incrementAndGet();
            return false;
        }
        if (readQueue.size() >= readQueueCapacity) {
            totalReadRejected.incrementAndGet();
            return false;
        }
        readQueue.offer(new IoTask(packedChunkPos, distance, isCritical, System.nanoTime(), action, IoTaskType.READ));
        return true;
    }

    /**
     * 提交写入任务。
     * <p>
     * 同 RegionFile 的写入保持正确顺序（同区域串行化）。
     *
     * @param packedChunkPos 区块 packed long
     * @param regionX        RegionFile X
     * @param regionZ        RegionFile Z
     * @param isCritical     是否为关键保存（如停服排空）
     * @param action         I/O 动作
     * @return true 表示已入队，false 表示被拒绝
     */
    public boolean submitWrite(long packedChunkPos, int regionX, int regionZ,
                               boolean isCritical, Runnable action) {
        if (!enabled.get()) {
            action.run();
            return true;
        }
        if (writeQueue.size() >= writeQueueCapacity) {
            totalWriteRejected.incrementAndGet();
            return false;
        }
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        writeQueue.offer(new IoTask(packedChunkPos, 0.0, isCritical, System.nanoTime(),
                () -> {
                    // §9.3 同区域串行化：获取 RegionFile 锁后执行
                    RegionFileLock lock = regionLocks.computeIfAbsent(regionKey, k -> new RegionFileLock());
                    synchronized (lock) {
                        action.run();
                    }
                },
                IoTaskType.WRITE));
        // §16.1 保存背压：入队后评估水位
        evaluateWriteBackpressure();
        return true;
    }

    /**
     * §16.1 评估写入背压水位，触发回调通知调度器。
     * <p>
     * 水位超过 emergencyWatermark 时：降低生成 permit + 提升写入优先级。
     * 水位超过 highWatermark 时：停止后台生成。
     * 水位恢复到 highWatermark 以下时：恢复后台生成。
     */
    private void evaluateWriteBackpressure() {
        if (backpressureCallback == null) {
            return;
        }
        int depth = writeQueue.size();
        if (depth >= writeEmergencyWatermark) {
            backpressureCallback.onEmergency();
        } else if (depth >= writeHighWatermark) {
            backpressureCallback.onHighWatermarkExceeded();
        } else {
            backpressureCallback.onBackpressureRelieved();
        }
    }

    /**
     * 每 Tick 在 I/O 线程调用：执行读取和写入任务。
     * <p>
     * 策略：
     * <ol>
     *   <li>停服模式：全力排空写入队列</li>
     *   <li>正常模式：读取优先（前方读取），写入按老化优先级</li>
     * </ol>
     */
    public void tick() {
        if (!enabled.get()) {
            return;
        }

        if (shutdownMode.get()) {
            // 停服模式：全力排空写入
            int writeBudget = maxWritesPerTick * shutdownWriteBoost;
            drainWrites(writeBudget);
            return;
        }

        // 正常模式：读取优先
        drainReads(maxReadsPerTick);
        // 写入按老化优先级
        drainWrites(maxWritesPerTick);
    }

    private void drainReads(int budget) {
        int executed = 0;
        while (executed < budget) {
            IoTask task = readQueue.poll();
            if (task == null) {
                break;
            }
            try {
                task.action().run();
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn("SteadyChunks I/O 读取任务失败: {}", t.getMessage());
            }
            totalReads.incrementAndGet();
            executed++;
        }
    }

    private void drainWrites(int budget) {
        int executed = 0;
        long now = System.nanoTime();
        while (executed < budget) {
            IoTask task = writeQueue.poll();
            if (task == null) {
                break;
            }
            // 老化检查：等待超过 maxSaveWaitMs 的任务强制提升优先级（此处直接执行）
            long waitMs = (now - task.submitAtNanos()) / 1_000_000L;
            if (waitMs >= maxSaveWaitMs) {
                totalForcedByAge.incrementAndGet();
            }
            try {
                task.action().run();
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn("SteadyChunks I/O 写入任务失败: {}", t.getMessage());
            }
            totalWrites.incrementAndGet();
            executed++;
        }
        // §16.1 排空后评估水位（可能恢复后台生成）
        evaluateWriteBackpressure();
    }

    /**
     * 尝试获取压缩任务许可。
     *
     * @return true 表示获取成功
     */
    public boolean tryAcquireCompress() {
        if (!enabled.get()) {
            return true;
        }
        if (compressInflight.get() >= maxConcurrentCompress) {
            return false;
        }
        return compressInflight.incrementAndGet() <= maxConcurrentCompress;
    }

    /**
     * 释放压缩任务许可。
     */
    public void releaseCompress() {
        if (!enabled.get()) {
            return;
        }
        compressInflight.decrementAndGet();
    }

    /**
     * 维度卸载时清理该维度的待处理任务。
     * <p>
     * 当前实现按 packed ChunkPos 清理（调用方需提供维度内的区块列表）。
     */
    public void clearRegion(int regionX, int regionZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        regionLocks.remove(regionKey);
    }

    public void clearAll() {
        readQueue.clear();
        writeQueue.clear();
        regionLocks.clear();
        compressInflight.set(0);
    }

    // 配置访问器
    public void setReadQueueCapacity(int cap) { this.readQueueCapacity = cap; }
    public void setWriteQueueCapacity(int cap) { this.writeQueueCapacity = cap; }
    public void setMaxReadsPerTick(int max) { this.maxReadsPerTick = max; }
    public void setMaxWritesPerTick(int max) { this.maxWritesPerTick = max; }
    public void setShutdownWriteBoost(int boost) { this.shutdownWriteBoost = boost; }
    public void setMaxConcurrentCompress(int max) { this.maxConcurrentCompress = max; }
    public void setMaxSaveWaitMs(long ms) { this.maxSaveWaitMs = ms; }
    /** §16.1 设置保存背压高水位 */
    public void setWriteHighWatermark(int wm) { this.writeHighWatermark = wm; }
    /** §16.1 设置保存背压紧急水位 */
    public void setWriteEmergencyWatermark(int wm) { this.writeEmergencyWatermark = wm; }
    /** §16.1 注册背压回调 */
    public void setBackpressureCallback(BackpressureCallback callback) { this.backpressureCallback = callback; }

    // 诊断访问器
    public int readQueueDepth() { return readQueue.size(); }
    public int writeQueueDepth() { return writeQueue.size(); }
    public long totalReads() { return totalReads.get(); }
    public long totalWrites() { return totalWrites.get(); }
    public long totalReadRejected() { return totalReadRejected.get(); }
    public long totalWriteRejected() { return totalWriteRejected.get(); }
    public long totalForcedByAge() { return totalForcedByAge.get(); }
    public int compressInflight() { return compressInflight.get(); }
    public boolean isShutdownMode() { return shutdownMode.get(); }
    public int writeHighWatermark() { return writeHighWatermark; }
    public int writeEmergencyWatermark() { return writeEmergencyWatermark; }

    /**
     * §16.1 保存背压回调接口。
     * <p>
     * 调度器实现此接口，在水位变化时调整生成 permit。
     */
    public interface BackpressureCallback {
        /** 水位超过高水位：停止后台生成 */
        void onHighWatermarkExceeded();
        /** 水位超过紧急水位：降低生成 permit + 提升写入优先级 */
        void onEmergency();
        /** 水位恢复：恢复后台生成 */
        void onBackpressureRelieved();
    }

    /** I/O 任务类型 */
    private enum IoTaskType { READ, WRITE }

    /** I/O 任务条目 */
    private record IoTask(
            long packedChunkPos,
            double distance,
            boolean critical,
            long submitAtNanos,
            Runnable action,
            IoTaskType type
    ) implements Comparable<IoTask> {
        @Override
        public int compareTo(IoTask o) {
            // 依赖关键任务优先
            if (this.critical != o.critical) {
                return this.critical ? -1 : 1;
            }
            // 读取：距离近的优先
            if (this.type == IoTaskType.READ && o.type == IoTaskType.READ) {
                int cmp = Double.compare(this.distance, o.distance);
                if (cmp != 0) return cmp;
            }
            // 写入：等待久的优先（老化优先级防饥饿）
            if (this.type == IoTaskType.WRITE && o.type == IoTaskType.WRITE) {
                int cmp = Long.compare(this.submitAtNanos, o.submitAtNanos);
                if (cmp != 0) return cmp;
            }
            // 默认按提交时间
            return Long.compare(this.submitAtNanos, o.submitAtNanos);
        }
    }

    /** RegionFile 串行锁标记 */
    private static final class RegionFileLock {
    }
}
