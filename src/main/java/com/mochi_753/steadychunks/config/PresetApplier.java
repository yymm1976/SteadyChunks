package com.mochi_753.steadychunks.config;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.client.ClientCompileGovernor;
import com.mochi_753.steadychunks.client.ClientFeedbackAggregator;
import com.mochi_753.steadychunks.completion.CompletionBatchShaper;
import com.mochi_753.steadychunks.completion.FullCommitQueue;
import com.mochi_753.steadychunks.governor.ResourceGovernor;
import com.mochi_753.steadychunks.network.ChunkSendQuota;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 预设应用器，对应开发计划 §11.6。
 * <p>
 * 将三套预设（smooth_integrated / balanced / throughput_server）的参数集应用到运行时组件。
 * 在 {@code ModuleBootstrap.onServerStarting} 中于 {@code syncFromConfig} 之后调用，
 * 预设值覆盖配置默认值，确保选择预设即可获得完整可用的工作配置。
 * <p>
 * 预设定位（§11.6）：
 * <ul>
 *   <li><b>smooth_integrated</b>：单人冒险整合包，为客户端渲染保留核心，严格平滑</li>
 *   <li><b>balanced</b>：普通服务器，稳定性与吞吐量平衡</li>
 *   <li><b>throughput_server</b>：独立服务器或预生成，较高并发，仍保留背压与完成平滑</li>
 * </ul>
 */
public final class PresetApplier {
    private PresetApplier() {
    }

    /**
     * 应用指定预设到所有运行时组件。
     * <p>
     * 调用时机：服务端启动时，在各自组件 {@code syncFromConfig} 之后调用。
     *
     * @param preset 目标预设
     */
    public static void apply(CommonConfig.Preset preset) {
        PresetParams params = paramsFor(preset);
        applyToScheduler(params);
        applyToGovernor(params);
        applyToCompletion(params);
        applyToSendQuota(params);
        applyToClientFeedback(params);
        SteadyChunks.LOGGER.info("SteadyChunks 预设已应用: {} (maxInflight={} features={} sendMax={})",
                preset, params.maxInflight, params.limitFeatures, params.sendMaxChunksPerTick);
    }

    /**
     * 返回指定预设的参数集。
     */
    static PresetParams paramsFor(CommonConfig.Preset preset) {
        return switch (preset) {
            case SMOOTH_INTEGRATED -> new PresetParams(
                    /* maxInflight */ 32,
                    /* limitStructureStarts */ 1, /* limitNoise */ 2,
                    /* limitFeatures */ 1, /* limitLight */ 2,
                    /* governorEnabled */ true, /* targetP95Mspt */ 40.0, /* hardMspt */ 48.0,
                    /* completionEnabled */ true, /* fullMaxCommitsPerTick */ 4,
                    /* fullDependencyReserve */ 2, /* fullQueueCapacity */ 128,
                    /* batchMaxCallbacksPerTick */ 8,
                    /* sendEnabled */ true, /* sendMaxChunksPerTick */ 3,
                    /* sendMinChunksPerTick */ 1, /* sendMaxBytesPerTickKb */ 384,
                    /* sendQueueCapacityPerPlayer */ 96,
                    /* clientFeedbackEnabled */ true, /* compileGovernanceEnabled */ true,
                    /* maxSectionRebuildsPerFrame */ 6
            );
            case BALANCED -> new PresetParams(
                    /* maxInflight */ 64,
                    /* limitStructureStarts */ 2, /* limitNoise */ 3,
                    /* limitFeatures */ 2, /* limitLight */ 3,
                    /* governorEnabled */ true, /* targetP95Mspt */ 45.0, /* hardMspt */ 50.0,
                    /* completionEnabled */ true, /* fullMaxCommitsPerTick */ 8,
                    /* fullDependencyReserve */ 2, /* fullQueueCapacity */ 256,
                    /* batchMaxCallbacksPerTick */ 16,
                    /* sendEnabled */ true, /* sendMaxChunksPerTick */ 5,
                    /* sendMinChunksPerTick */ 1, /* sendMaxBytesPerTickKb */ 512,
                    /* sendQueueCapacityPerPlayer */ 128,
                    /* clientFeedbackEnabled */ true, /* compileGovernanceEnabled */ false,
                    /* maxSectionRebuildsPerFrame */ 8
            );
            case THROUGHPUT_SERVER -> new PresetParams(
                    /* maxInflight */ 128,
                    /* limitStructureStarts */ 2, /* limitNoise */ 4,
                    /* limitFeatures */ 3, /* limitLight */ 4,
                    /* governorEnabled */ true, /* targetP95Mspt */ 50.0, /* hardMspt */ 55.0,
                    /* completionEnabled */ true, /* fullMaxCommitsPerTick */ 16,
                    /* fullDependencyReserve */ 4, /* fullQueueCapacity */ 512,
                    /* batchMaxCallbacksPerTick */ 32,
                    /* sendEnabled */ true, /* sendMaxChunksPerTick */ 8,
                    /* sendMinChunksPerTick */ 2, /* sendMaxBytesPerTickKb */ 1024,
                    /* sendQueueCapacityPerPlayer */ 192,
                    /* clientFeedbackEnabled */ false, /* compileGovernanceEnabled */ false,
                    /* maxSectionRebuildsPerFrame */ 8
            );
        };
    }

    private static void applyToScheduler(PresetParams p) {
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setMaxInflight(p.maxInflight);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.STRUCTURE_STARTS, p.limitStructureStarts);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.NOISE, p.limitNoise);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.FEATURES, p.limitFeatures);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.LIGHT, p.limitLight);
    }

    private static void applyToGovernor(PresetParams p) {
        ResourceGovernor governor = ResourceGovernor.getInstance();
        governor.setEnabled(p.governorEnabled);
        // 阈值通过 syncFromConfig 读取配置，预设仅控制启用状态与运行模式
        // 预设的 targetP95/hardMspt 通过配置默认值体现，此处不覆盖以保留用户调优空间
    }

    private static void applyToCompletion(PresetParams p) {
        FullCommitQueue fullQueue = FullCommitQueue.getInstance();
        fullQueue.setEnabled(p.completionEnabled);
        fullQueue.setMaxCommitsPerTick(p.fullMaxCommitsPerTick);
        fullQueue.setDependencyCriticalReserve(p.fullDependencyReserve);
        fullQueue.setQueueCapacity(p.fullQueueCapacity);

        CompletionBatchShaper batchShaper = CompletionBatchShaper.getInstance();
        batchShaper.setEnabled(p.completionEnabled);
        batchShaper.setMaxCallbacksPerTick(p.batchMaxCallbacksPerTick);
    }

    private static void applyToSendQuota(PresetParams p) {
        ChunkSendQuota sendQuota = ChunkSendQuota.getInstance();
        sendQuota.setEnabled(p.sendEnabled);
        sendQuota.setMaxChunksPerTick(p.sendMaxChunksPerTick);
        sendQuota.setMinChunksPerTick(p.sendMinChunksPerTick);
        sendQuota.setMaxBytesPerTick(p.sendMaxBytesPerTickKb * 1024L);
        sendQuota.setQueueCapacityPerPlayer(p.sendQueueCapacityPerPlayer);
    }

    private static void applyToClientFeedback(PresetParams p) {
        ClientFeedbackAggregator aggregator = ClientFeedbackAggregator.getInstance();
        aggregator.setAcceptClientFeedback(p.clientFeedbackEnabled);

        ClientCompileGovernor compileGov = ClientCompileGovernor.getInstance();
        compileGov.setEnabled(p.compileGovernanceEnabled);
        compileGov.setMaxSectionRebuildsPerFrame(p.maxSectionRebuildsPerFrame);
    }

    /**
     * 单个预设的完整参数集。
     * <p>
     * 字段命名对应 {@link CommonConfig} 中的配置项，便于对照。
     */
    static final class PresetParams {
        final int maxInflight;
        final int limitStructureStarts;
        final int limitNoise;
        final int limitFeatures;
        final int limitLight;
        final boolean governorEnabled;
        final double targetP95Mspt;
        final double hardMspt;
        final boolean completionEnabled;
        final int fullMaxCommitsPerTick;
        final int fullDependencyReserve;
        final int fullQueueCapacity;
        final int batchMaxCallbacksPerTick;
        final boolean sendEnabled;
        final int sendMaxChunksPerTick;
        final int sendMinChunksPerTick;
        final int sendMaxBytesPerTickKb;
        final int sendQueueCapacityPerPlayer;
        final boolean clientFeedbackEnabled;
        final boolean compileGovernanceEnabled;
        final int maxSectionRebuildsPerFrame;

        PresetParams(int maxInflight,
                     int limitStructureStarts, int limitNoise, int limitFeatures, int limitLight,
                     boolean governorEnabled, double targetP95Mspt, double hardMspt,
                     boolean completionEnabled, int fullMaxCommitsPerTick,
                     int fullDependencyReserve, int fullQueueCapacity,
                     int batchMaxCallbacksPerTick,
                     boolean sendEnabled, int sendMaxChunksPerTick,
                     int sendMinChunksPerTick, int sendMaxBytesPerTickKb,
                     int sendQueueCapacityPerPlayer,
                     boolean clientFeedbackEnabled, boolean compileGovernanceEnabled,
                     int maxSectionRebuildsPerFrame) {
            this.maxInflight = maxInflight;
            this.limitStructureStarts = limitStructureStarts;
            this.limitNoise = limitNoise;
            this.limitFeatures = limitFeatures;
            this.limitLight = limitLight;
            this.governorEnabled = governorEnabled;
            this.targetP95Mspt = targetP95Mspt;
            this.hardMspt = hardMspt;
            this.completionEnabled = completionEnabled;
            this.fullMaxCommitsPerTick = fullMaxCommitsPerTick;
            this.fullDependencyReserve = fullDependencyReserve;
            this.fullQueueCapacity = fullQueueCapacity;
            this.batchMaxCallbacksPerTick = batchMaxCallbacksPerTick;
            this.sendEnabled = sendEnabled;
            this.sendMaxChunksPerTick = sendMaxChunksPerTick;
            this.sendMinChunksPerTick = sendMinChunksPerTick;
            this.sendMaxBytesPerTickKb = sendMaxBytesPerTickKb;
            this.sendQueueCapacityPerPlayer = sendQueueCapacityPerPlayer;
            this.clientFeedbackEnabled = clientFeedbackEnabled;
            this.compileGovernanceEnabled = compileGovernanceEnabled;
            this.maxSectionRebuildsPerFrame = maxSectionRebuildsPerFrame;
        }
    }
}
