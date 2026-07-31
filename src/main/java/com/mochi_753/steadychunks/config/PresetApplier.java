package com.mochi_753.steadychunks.config;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.client.ClientCompileGovernor;
import com.mochi_753.steadychunks.completion.CompletionBatchShaper;
import com.mochi_753.steadychunks.completion.FullCommitQueue;
import com.mochi_753.steadychunks.network.ChunkSendQuota;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 预设应用器，对应开发计划 §11.6，P0-4 修复。
 * <p>
 * <b>配置优先级修复</b>：预设不再无条件覆盖用户显式配置。
 * 正确的优先级为：代码默认值 → 预设值 → 用户显式配置 → 兼容性安全覆盖。
 * <p>
 * 当前实现策略：仅应用数值类参数（maxInflight / 并发上限 / 队列容量等），
 * 不强制改变 enabled 开关。enabled 开关完全由用户配置决定，
 * 避免用户明确关闭的模块被预设重新开启。
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
     * 应用指定预设的数值参数到运行时组件。
     * <p>
     * <b>P0-4 修复</b>：不强制设置 enabled 开关。
     * 仅当组件已被用户启用时，预设的数值参数才生效。
     * 这样用户明确关闭的模块不会被预设重新开启。
     * <p>
     * <b>P1-5 修复</b>：数值优先级明确二选一。
     * {@code use_advanced_overrides=false}（默认）：预设数值覆盖下方高级配置；
     * {@code use_advanced_overrides=true}：预设仅控制 enabled 开关，
     * 所有数值以用户的显式配置为准（跳过预设数值应用）。
     *
     * @param preset 目标预设
     */
    public static void apply(CommonConfig.Preset preset) {
        // P1-5：用户启用高级覆盖时，预设不再覆盖用户显式数值
        if (CommonConfig.USE_ADVANCED_OVERRIDES.get()) {
            SteadyChunks.LOGGER.info("SteadyChunks 预设: use_advanced_overrides=true，跳过预设数值应用（用户显式配置优先）");
            return;
        }
        PresetParams params = paramsFor(preset);
        // 仅应用数值参数，不改变 enabled 状态
        // enabled 完全由 CommonConfig 中的用户配置决定
        applyToScheduler(params);
        applyToCompletion(params);
        applyToSendQuota(params);
        applyToClientFeedback(params);
        SteadyChunks.LOGGER.info("SteadyChunks 预设数值已应用: {} (maxInflight={} features={} sendMax={})",
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
        // P0-4 修复：不强制 setEnabled，仅当用户已启用调度器时应用数值
        if (!scheduler.isEnabled()) {
            return;
        }
        scheduler.setMaxInflight(p.maxInflight);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.STRUCTURE_STARTS, p.limitStructureStarts);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.NOISE, p.limitNoise);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.FEATURES, p.limitFeatures);
        scheduler.stageLimiter().setStageLimit(ChunkStatus.LIGHT, p.limitLight);
    }

    private static void applyToCompletion(PresetParams p) {
        FullCommitQueue fullQueue = FullCommitQueue.getInstance();
        // P0-4 修复：不强制 setEnabled，仅当用户已启用时应用数值
        if (fullQueue.isEnabled()) {
            fullQueue.setMaxCommitsPerTick(p.fullMaxCommitsPerTick);
            fullQueue.setDependencyCriticalReserve(p.fullDependencyReserve);
            fullQueue.setQueueCapacity(p.fullQueueCapacity);
        }

        CompletionBatchShaper batchShaper = CompletionBatchShaper.getInstance();
        if (batchShaper.isEnabled()) {
            batchShaper.setMaxCallbacksPerTick(p.batchMaxCallbacksPerTick);
        }
    }

    private static void applyToSendQuota(PresetParams p) {
        ChunkSendQuota sendQuota = ChunkSendQuota.getInstance();
        // P0-4 修复：不强制 setEnabled，仅当用户已启用时应用数值
        if (!sendQuota.isEnabled()) {
            return;
        }
        sendQuota.setMaxChunksPerTick(p.sendMaxChunksPerTick);
        sendQuota.setMinChunksPerTick(p.sendMinChunksPerTick);
        sendQuota.setMaxBytesPerTick(p.sendMaxBytesPerTickKb * 1024L);
        sendQuota.setQueueCapacityPerPlayer(p.sendQueueCapacityPerPlayer);
    }

    private static void applyToClientFeedback(PresetParams p) {
        // P0-4 修复：不强制改变 enabled 开关
        // 客户端编译治理仅在用户已启用时调整数值
        ClientCompileGovernor compileGov = ClientCompileGovernor.getInstance();
        if (compileGov.isEnabled()) {
            compileGov.setMaxSectionRebuildsPerFrame(p.maxSectionRebuildsPerFrame);
        }
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
