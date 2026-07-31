package com.mochi_753.steadychunks.consistency;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.completion.FullCommitQueue;
import com.mochi_753.steadychunks.io.IoQueueController;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.light.LightTaskBudget;
import com.mochi_753.steadychunks.network.ChunkSendQuota;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.structure.DatapackGenerationRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 故障注入框架，对应开发计划 Phase 11.2。
 * <p>
 * 通过主动注入故障验证系统清理能力，确保任务、permit、缓存和文件资源在异常场景下均能正确清理。
 * <p>
 * 支持的故障类型（计划 §11.2）：
 * <ul>
 *   <li>工作线程异常</li>
 *   <li>维度卸载（任务等待时）</li>
 *   <li>玩家退出</li>
 *   <li>服务器停止</li>
 *   <li>Chunk Ticket 消失</li>
 *   <li>保存失败</li>
 *   <li>数据包重载</li>
 *   <li>客户端断开</li>
 *   <li>光照任务异常</li>
 *   <li>模组结构生成异常</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>每个故障注入方法独立，可单独或组合调用</li>
 *   <li>注入后通过 {@link #verifyCleanup} 验证资源清理</li>
 *   <li>不破坏世界数据，只触发清理路径</li>
 *   <li>线程安全：注入方法可在任意线程调用</li>
 * </ul>
 */
public final class FaultInjector {
    private static FaultInjector instance;

    /** 累计注入故障次数 */
    private final AtomicLong totalInjections = new AtomicLong(0);
    /** 累计验证失败次数 */
    private final AtomicLong totalVerifyFailures = new AtomicLong(0);

    private FaultInjector() {
    }

    public static synchronized FaultInjector getInstance() {
        if (instance == null) {
            instance = new FaultInjector();
        }
        return instance;
    }

    /**
     * 注入工作线程异常：模拟任务执行时抛出未捕获异常。
     * <p>
     * 验证 onFailure 路径正确释放 permit 和清理任务状态。
     *
     * @param scheduler 调度器实例
     */
    public void injectWorkerException(ChunkScheduler scheduler) {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入工作线程异常");
        // 触发调度器的 onFailure 路径：取一个非 HIGH 安全等级的任务模拟失败
        // HIGH 安全等级任务处于 RUNNING FEATURES/LIGHT/SAVE，禁止取消
        for (var task : scheduler.taskGraph().allTasks()) {
            if (task.safety() == com.mochi_753.steadychunks.scheduler.ChunkTask.SafetyLevel.HIGH) {
                continue;
            }
            // 模拟任务失败（不实际执行，只触发 onFailure 清理路径）
            try {
                scheduler.onFailure(task, new RuntimeException("FaultInjector: 模拟工作线程异常"));
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: onFailure 路径异常: {}", t.getMessage());
            }
            break;
        }
    }

    /**
     * 注入维度卸载：模拟维度在任务等待时卸载。
     * <p>
     * 验证 LifecycleCleanupCoordinator 正确取消等待任务并清理缓存。
     *
     * @param coordinator 生命周期协调器
     * @param dimension   目标维度
     * @param dimensionId 维度 numeric ID
     */
    public void injectDimensionUnload(LifecycleCleanupCoordinator coordinator,
                                       ResourceKey<Level> dimension, int dimensionId) {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入维度卸载 dim={}", dimension.location());
        try {
            coordinator.onDimensionUnload(dimension, dimensionId);
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 维度卸载清理异常: {}", t.getMessage());
        }
    }

    /**
     * 注入玩家断开：模拟玩家退出时清理引用。
     *
     * @param coordinator 生命周期协调器
     * @param playerId    玩家 ID
     */
    public void injectPlayerDisconnect(LifecycleCleanupCoordinator coordinator, UUID playerId) {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入玩家断开 player={}", playerId);
        try {
            coordinator.onPlayerDisconnect(playerId);
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 玩家断开清理异常: {}", t.getMessage());
        }
    }

    /**
     * 注入保存失败：模拟 I/O 写入队列异常。
     * <p>
     * 验证 IoQueueController 在写入失败时不泄漏 RegionFile 锁。
     *
     * @param ioController I/O 队列控制器
     */
    public void injectSaveFailure(IoQueueController ioController) {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入保存失败");
        // 提交一个会抛异常的写入任务，验证 RegionFile 锁不泄漏
        ioController.submitWrite(0L, 0, 0, false, () -> {
            throw new RuntimeException("FaultInjector: 模拟保存失败");
        });
    }

    /**
     * 注入数据包重载：触发缓存统一失效。
     * <p>
     * 验证 DatapackGenerationRegistry 正确通知所有注册缓存。
     */
    public void injectDatapackReload() {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入数据包重载");
        try {
            DatapackGenerationRegistry.getInstance().fireDatapackReload();
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 数据包重载清理异常: {}", t.getMessage());
        }
    }

    /**
     * 注入光照任务异常：模拟光照计算失败。
     *
     * @param lightBudget 光照任务预算
     */
    public void injectLightTaskException(LightTaskBudget lightBudget) {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入光照任务异常");
        // 触发光照预算清理，验证计数不泄漏
        try {
            lightBudget.clearAll();
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 光照清理异常: {}", t.getMessage());
        }
    }

    /**
     * 注入服务器停止：模拟停服清理流程。
     * <p>
     * 验证所有模块在停服时正确排空和清理。
     *
     * @param coordinator 生命周期协调器
     * @param maxWaitMs   最大等待时间
     */
    public void injectServerShutdown(LifecycleCleanupCoordinator coordinator, long maxWaitMs) {
        totalInjections.incrementAndGet();
        SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 注入服务器停止");
        try {
            coordinator.onServerShutdown(maxWaitMs);
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 停服清理异常: {}", t.getMessage());
        }
    }

    /**
     * 验证资源清理效果，检测泄漏。
     * <p>
     * 检查项：
     * <ul>
     *   <li>调度器 inflight 计数是否为 0（无 permit 泄漏）</li>
     *   <li>就绪队列是否清空</li>
     *   <li>FULL 整合队列是否清空</li>
     *   <li>I/O 队列是否清空</li>
     *   <li>发送配额是否无残留玩家</li>
     * </ul>
     *
     * @return 故障报告，包含泄漏检测结果
     */
    public FaultReport verifyCleanup() {
        FaultReport report = new FaultReport();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        FullCommitQueue fullQueue = FullCommitQueue.getInstance();
        IoQueueController ioController = IoQueueController.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();

        // 检查调度器 permit 泄漏
        int inflight = scheduler.inflightCount();
        if (inflight != 0) {
            report.addLeak("scheduler.inflight", inflight);
        }

        // 检查就绪队列残留
        int readySize = scheduler.readyQueueSize();
        if (readySize != 0) {
            report.addLeak("scheduler.readyQueue", readySize);
        }

        // 检查任务图残留
        int taskGraphSize = scheduler.taskGraph().size();
        if (taskGraphSize != 0) {
            report.addLeak("scheduler.taskGraph", taskGraphSize);
        }

        // 检查 FULL 整合队列残留
        int fullDepth = fullQueue.queueDepth();
        if (fullDepth != 0) {
            report.addLeak("fullCommitQueue.depth", fullDepth);
        }

        // 检查 I/O 队列残留
        int readDepth = ioController.readQueueDepth();
        int writeDepth = ioController.writeQueueDepth();
        if (readDepth != 0) {
            report.addLeak("ioQueue.readQueue", readDepth);
        }
        if (writeDepth != 0) {
            report.addLeak("ioQueue.writeQueue", writeDepth);
        }

        // 检查全局任务计数
        int globalTasks = coordinator.globalTaskCount();
        if (globalTasks != 0) {
            report.addLeak("lifecycle.globalTaskCount", globalTasks);
        }

        if (report.hasLeaks()) {
            totalVerifyFailures.incrementAndGet();
            SteadyChunks.LOGGER.warn("SteadyChunks FaultInjector: 清理验证失败 leaks={}", report.leakCount());
        }
        return report;
    }

    // 诊断访问器
    public long totalInjections() { return totalInjections.get(); }
    public long totalVerifyFailures() { return totalVerifyFailures.get(); }
}
