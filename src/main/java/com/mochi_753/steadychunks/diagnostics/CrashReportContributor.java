package com.mochi_753.steadychunks.diagnostics;

import com.mochi_753.steadychunks.completion.FullCommitQueue;
import com.mochi_753.steadychunks.config.CommonConfig;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.network.ChunkSendQuota;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.Watchdog;

/**
 * 崩溃报告贡献器，对应开发计划 §12.1。
 * <p>
 * 在崩溃报告生成时由 {@code MixinCrashReport} 调用，向报告追加 SteadyChunks 运行时状态，
 * 确保每个崩溃报告都附带任务和 permit 状态，便于定位问题。
 * <p>
 * 追加内容：
 * <ul>
 *   <li>调度器状态（enabled / inflight / readyQueue / permits）</li>
 *   <li>Watchdog 异常计数</li>
 *   <li>FULL 队列与发送配额状态</li>
 *   <li>生命周期泄漏检测</li>
 *   <li>当前预设与兼容模式</li>
 * </ul>
 */
public final class CrashReportContributor {

    private CrashReportContributor() {
    }

    /**
     * 生成 SteadyChunks 运行时状态摘要，追加到崩溃报告。
     *
     * @return 格式化的状态字符串
     */
    public static String collectState() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n-- SteadyChunks 状态 --\n");
        try {
            appendSchedulerState(sb);
        } catch (Throwable t) {
            sb.append("调度器状态采集失败: ").append(t.getMessage()).append('\n');
        }
        try {
            appendWatchdogState(sb);
        } catch (Throwable t) {
            sb.append("Watchdog 状态采集失败: ").append(t.getMessage()).append('\n');
        }
        try {
            appendQueueState(sb);
        } catch (Throwable t) {
            sb.append("队列状态采集失败: ").append(t.getMessage()).append('\n');
        }
        try {
            appendLifecycleState(sb);
        } catch (Throwable t) {
            sb.append("生命周期状态采集失败: ").append(t.getMessage()).append('\n');
        }
        try {
            appendConfigState(sb);
        } catch (Throwable t) {
            sb.append("配置状态采集失败: ").append(t.getMessage()).append('\n');
        }
        sb.append("-- SteadyChunks 状态结束 --\n");
        return sb.toString();
    }

    private static void appendSchedulerState(StringBuilder sb) {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        sb.append("调度器: enabled=").append(scheduler.isEnabled())
                .append(" inflight=").append(scheduler.inflightCount())
                .append(" pending=").append(scheduler.pendingCount())
                .append(" permitsMax=").append(scheduler.cpuPermitsMax())
                .append(" permitsAvailable=").append(scheduler.cpuPermitsAvailable())
                .append('\n');
    }

    private static void appendWatchdogState(StringBuilder sb) {
        Watchdog watchdog = ChunkScheduler.getInstance().watchdog();
        sb.append("Watchdog: scanCount=").append(watchdog.scanCount())
                .append(" totalAnomalies=").append(watchdog.totalAnomalies())
                .append(" unloadedDimensions=").append(watchdog.unloadedDimensionCount())
                .append('\n');
    }

    private static void appendQueueState(StringBuilder sb) {
        FullCommitQueue fullQueue = FullCommitQueue.getInstance();
        sb.append("FULL队列: enabled=").append(fullQueue.isEnabled())
                .append(" depth=").append(fullQueue.queueDepth())
                .append('\n');

        ChunkSendQuota sendQuota = ChunkSendQuota.getInstance();
        sb.append("发送配额: enabled=").append(sendQuota.isEnabled())
                .append('\n');
    }

    private static void appendLifecycleState(StringBuilder sb) {
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        sb.append("生命周期: globalTasks=").append(coordinator.globalTaskCount())
                .append(" shutdownMode=").append(coordinator.isShutdownMode())
                .append(" totalChunksUnloaded=").append(coordinator.totalChunksUnloaded())
                .append(" totalLeaksDetected=").append(coordinator.totalLeaksDetected())
                .append('\n');
    }

    private static void appendConfigState(StringBuilder sb) {
        sb.append("配置: preset=").append(CommonConfig.PRESET.get())
                .append(" strictCompat=").append(CommonConfig.STRICT_COMPATIBILITY.get())
                .append(" c2me=").append(CommonConfig.C2ME.get())
                .append(" fastnoise=").append(CommonConfig.FASTNOISE.get())
                .append(" byepregen=").append(CommonConfig.BYEPREGEN.get())
                .append('\n');
    }
}
