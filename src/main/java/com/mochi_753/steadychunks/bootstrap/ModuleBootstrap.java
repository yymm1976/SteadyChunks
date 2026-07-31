package com.mochi_753.steadychunks.bootstrap;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.client.ClientFeedbackAggregator;
import com.mochi_753.steadychunks.command.SteadyChunksCommands;
import com.mochi_753.steadychunks.completion.CompletionBatchShaper;
import com.mochi_753.steadychunks.completion.FullCommitQueue;
import com.mochi_753.steadychunks.config.CommonConfig;
import com.mochi_753.steadychunks.governor.ResourceGovernor;
import com.mochi_753.steadychunks.governor.RunMode;
import com.mochi_753.steadychunks.network.ChunkSendQuota;
import com.mochi_753.steadychunks.network.SteadyChunksPayloads;
import com.mochi_753.steadychunks.config.PresetApplier;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.structure.CacheInvalidationReloadListener;
import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.TelemetryListeners;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 启动引导：探测兼容性 → 初始化 MixinGate → 注册配置 → 注册命令 → 同步诊断 → 输出所有权表。
 * <p>
 * 调用时机：{@code SteadyChunks} 构造函数。
 * C2ME 互斥检查延后到 {@link FMLCommonSetupEvent}，确保配置已加载，可读取用户覆盖值。
 * 诊断同步与命令注册延后到 {@link ServerStartingEvent}，确保服务端就绪。
 */
public final class ModuleBootstrap {
    private ModuleBootstrap() {
    }

    /**
     * 在 Mod 构造阶段执行：探测、初始化 MixinGate、注册配置、订阅事件。
     */
    public static void bootstrap(IEventBus modEventBus, ModContainer container) {
        SteadyChunks.LOGGER.info("SteadyChunks bootstrap：开始兼容性探测");

        // 1. 探测兼容性
        ModuleStates states = CompatibilityProbe.probe();

        // 2. 初始化 MixinGate（供 MixinPlugin 在 Mixin 加载时查询）
        MixinGate.initialize(states);

        // 3. 注册配置（COMMON 类型，同步到服务端，不按世界存储）
        CommonConfig.register(container);

        // 4. 注册诊断监听器（StageWork → StageMetrics 等）
        TelemetryListeners.register();

        // 5. 订阅事件
        modEventBus.addListener(ModuleBootstrap::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerTick);
        // §17.2 订阅数据包重载事件，触发缓存统一失效
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onAddReloadListener);

        // 5.1 注册网络包（Phase 5）
        SteadyChunksPayloads.register(modEventBus);

        // 6. Phase 10：初始化兼容层（所有权表 + Mixin 冲突表 + 兼容 API）
        com.mochi_753.steadychunks.compat.OwnershipTableRegistry.getInstance().build(states);
        com.mochi_753.steadychunks.compat.MixinConflictTable.getInstance().initialize(states);
        com.mochi_753.steadychunks.compat.MixinConflictTable.getInstance().logConflictTable();
        com.mochi_753.steadychunks.compat.CompatApi.getInstance().setModuleStates(states);
        com.mochi_753.steadychunks.io.ByepregenSerializeGate.getInstance().initialize(states);

        SteadyChunks.LOGGER.info("SteadyChunks bootstrap：完成");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        CommonConfig.C2meMode mode = CommonConfig.C2ME.get();

        // Phase 10.5：使用 C2meMutexPolicy 评估互斥策略
        com.mochi_753.steadychunks.compat.C2meMutexPolicy.C2meMode policyMode =
                switch (mode) {
                    case REJECT -> com.mochi_753.steadychunks.compat.C2meMutexPolicy.C2meMode.REJECT;
                    case ANALYZER_ONLY -> com.mochi_753.steadychunks.compat.C2meMutexPolicy.C2meMode.ANALYZER_ONLY;
                    case FORCE_COEXIST -> com.mochi_753.steadychunks.compat.C2meMutexPolicy.C2meMode.FORCE_COEXIST;
                };
        com.mochi_753.steadychunks.compat.C2meMutexPolicy.C2meDecision decision =
                com.mochi_753.steadychunks.compat.C2meMutexPolicy.getInstance().evaluate(policyMode);

        switch (decision.action()) {
            case BLOCK_STARTUP -> {
                SteadyChunks.LOGGER.error(decision.message());
                throw new RuntimeException(decision.message());
            }
            case ANALYZER_ONLY -> SteadyChunks.LOGGER.warn(decision.message());
            case FORCE_COEXIST -> SteadyChunks.LOGGER.warn(decision.message());
            case CONTINUE -> {
                // 未检测到 C2ME，正常启动
                com.mochi_753.steadychunks.compat.C2meMutexPolicy.getInstance().logMigrationGuide();
            }
        }
    }

    /**
     * 服务端启动时同步诊断开关，确保配置已加载。
     */
    private static void onServerStarting(ServerStartingEvent event) {
        ChunkFlightRecorder.syncFromConfig();
        SteadyChunks.LOGGER.info("SteadyChunks 诊断已同步配置：enabled={}", ChunkFlightRecorder.isEnabled());

        // 同步调度器与治理器配置
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.syncFromConfig();

        ResourceGovernor governor = ResourceGovernor.getInstance();
        // 判断运行模式：集成服务器 vs 独立服务器
        boolean isDedicated = event.getServer().isDedicatedServer();
        governor.setRunMode(isDedicated ? RunMode.DEDICATED : RunMode.INTEGRATED);
        governor.syncFromConfig();

        // Phase 5：同步 FULL 整合、完成批次整形、发送配额配置
        FullCommitQueue fullQueue = FullCommitQueue.getInstance();
        fullQueue.setEnabled(CommonConfig.COMPLETION_ENABLED.get());
        fullQueue.setMaxCommitsPerTick(CommonConfig.FULL_MAX_COMMITS_PER_TICK.get());
        fullQueue.setDependencyCriticalReserve(CommonConfig.FULL_DEPENDENCY_RESERVE.get());
        fullQueue.setQueueCapacity(CommonConfig.FULL_QUEUE_CAPACITY.get());

        CompletionBatchShaper batchShaper = CompletionBatchShaper.getInstance();
        batchShaper.setEnabled(CommonConfig.COMPLETION_ENABLED.get());
        batchShaper.setMaxCallbacksPerTick(CommonConfig.BATCH_MAX_CALLBACKS_PER_TICK.get());

        ChunkSendQuota sendQuota = ChunkSendQuota.getInstance();
        sendQuota.setEnabled(CommonConfig.SEND_QUOTA_ENABLED.get());
        sendQuota.setMaxChunksPerTick(CommonConfig.SEND_MAX_CHUNKS_PER_TICK.get());
        sendQuota.setMinChunksPerTick(CommonConfig.SEND_MIN_CHUNKS_PER_TICK.get());
        sendQuota.setMaxBytesPerTick(CommonConfig.SEND_MAX_BYTES_PER_TICK_KB.get() * 1024L);
        sendQuota.setQueueCapacityPerPlayer(CommonConfig.SEND_QUEUE_CAPACITY_PER_PLAYER.get());

        ClientFeedbackAggregator aggregator = ClientFeedbackAggregator.getInstance();
        aggregator.setAcceptClientFeedback(CommonConfig.CLIENT_FEEDBACK_ENABLED.get());

        // §11.6 应用预设：在所有 syncFromConfig 之后调用，预设值覆盖配置默认值
        PresetApplier.apply(CommonConfig.PRESET.get());

        SteadyChunks.LOGGER.info("SteadyChunks 调度器: enabled={} 治理器: enabled={} 模式: {} FULL队列: {} 发送配额: {}",
                scheduler.isEnabled(), governor.isEnabled(),
                isDedicated ? "DEDICATED" : "INTEGRATED",
                fullQueue.isEnabled(), sendQuota.isEnabled());
    }

    /**
     * 服务端每 tick 调用：推进调度器、治理器、FULL 整合队列、完成批次整形、发送配额重置。
     * <p>
     * 调度器 tick 推进就绪队列，治理器 tick 每 20 tick（1 秒）评估一次压力并调整 permit。
     * FULL 整合与完成批次整形每 tick 按预算执行。
     * 发送配额每 tick 重置。
     */
    private static void onServerTick(ServerTickEvent.Pre event) {
        ChunkScheduler.getInstance().tick();
        ResourceGovernor.getInstance().tick();

        // Phase 5：FULL 整合与完成批次整形
        long tickDeadline = System.nanoTime() + 10_000_000L; // 10ms 预算
        FullCommitQueue.getInstance().tick(tickDeadline);
        CompletionBatchShaper.getInstance().tick(tickDeadline);

        // 发送配额每 tick 重置
        ChunkSendQuota.getInstance().resetTick();
    }

    /**
     * 注册服务端命令。
     */
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        SteadyChunksCommands.register(event.getDispatcher());
    }

    /**
     * §17.2 数据包重载事件：注册缓存失效监听器。
     * <p>
     * 在 reload 完成后由 {@link CacheInvalidationReloadListener} 触发
     * {@link com.mochi_753.steadychunks.structure.DatapackGenerationRegistry#fireDatapackReload}
     * 统一失效所有注册缓存。
     */
    private static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new CacheInvalidationReloadListener());
        SteadyChunks.LOGGER.debug("SteadyChunks 已注册数据包重载缓存失效监听器");
    }
}
