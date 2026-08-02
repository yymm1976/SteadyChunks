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
import com.mochi_753.steadychunks.diagnostics.CrashReportContributor;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.Watchdog;
import com.mochi_753.steadychunks.structure.CacheInvalidationReloadListener;
import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.TelemetryListeners;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

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

        // 0. P0 修复（类加载时机）：标记崩溃报告采集就绪。
        // 必须在任何组件可能被 CrashReport.preload 触达之前完成；
        // 本方法运行于 FML 构造阶段（bootstrap 之后），此时注册表已就绪。
        CrashReportContributor.markReady();

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
        // 第 10 轮 P0-3 修复：开门提前到 ServerAboutToStartEvent——在服务器加载任何
        // 内容（维度/出生区块）之前恢复注册门与任务接收，避免集成服务器第二世界
        // 早期 NOISE 被旧生命周期状态拒绝（ServerStartingEvent 发生得太晚）。
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerStarting);
        // 第 9 轮生产接线：服务器停止 → 生命周期停服排空（与 onServerStarting 配对）
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerStopping);
        // 第 10 轮 P0-3 修复：最终缓存清理与泄漏报告移到 ServerStoppedEvent
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onServerTick);
        // §17.2 订阅数据包重载事件，触发缓存统一失效
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onAddReloadListener);
        // 第 9 轮生产接线：维度加载/卸载 → 每维度生命周期开关（openDimension/onDimensionUnload），
        // 使 cancelDimension 定向取消与维度计数在实际游戏中生效（此前仅 GameTest/FaultInjector 调用）
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(ModuleBootstrap::onLevelUnload);

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
     * 第 10 轮 P0-3 修复：服务器“开始加载任何内容之前”开门——恢复注册门、调度器
     * 接收与 I/O 接收，并递增服务器生命周期代数。集成服务器同 JVM 第二世界的
     * 维度/出生区块加载发生在 ServerStartingEvent 之前，必须在此之前恢复接收。
     */
    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        LifecycleCleanupCoordinator.getInstance().onServerStart();
    }

    /**
     * 服务端启动时同步诊断开关，确保配置已加载。
     */
    private static void onServerStarting(ServerStartingEvent event) {
        // 第 10 轮 P0-3 修复：接收恢复已提前到 ServerAboutToStartEvent，
        // 此处只做配置同步与恢复线程启动。

        // 第 9 轮卡死修复：启动独立 drain 停摆恢复线程（忙转死锁破环，见 Watchdog）。
        Watchdog.getInstance().startRecoveryThread(ChunkScheduler.getInstance());

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
     * 第 9 轮生产接线：服务器停止时执行停服清理（关注册门、立即清空等待队列）。
     * 与 {@link #onServerStarting(ServerStartingEvent)} 配对，
     * 支持集成服务器回主菜单后再开新世界的完整生命周期。
     * <p>
     * 第 10 轮 P0-3 修复：不再在 Server Thread 上等待任务归零（旧 5 秒轮询会阻塞
     * pending 推进）——关注册门后立即 closeForShutdown 清 pending，运行中任务自然
     * 终结（迟到 lease 归零），最终清理与泄漏报告见 {@link #onServerStopped(ServerStoppedEvent)}。
     */
    private static void onServerStopping(ServerStoppingEvent event) {
        // 第 9 轮卡死修复：停止独立恢复线程（幂等，daemon 线程不阻塞停服）。
        Watchdog.getInstance().stopRecoveryThread();
        // 第 10 轮 P0-3 修复：无参版本——关注册门 + 立即 closeForShutdown 清 pending，
        // 不在 Server Thread 上 sleep 等待（旧 5 秒轮询会阻塞 pending 推进）。
        LifecycleCleanupCoordinator.getInstance().onServerShutdown();
    }

    /**
     * 第 10 轮 P0-3 修复：服务器完全停止后做最终缓存清理与泄漏报告
     * （运行中任务此刻应已终结，迟到 lease 已递减计数）。
     */
    private static void onServerStopped(ServerStoppedEvent event) {
        LifecycleCleanupCoordinator.getInstance().onServerStopped();
    }

    /**
     * 第 9 轮生产接线：维度加载 → 打开该维度的任务接收（accepting + generation++）。
     * 与 {@link #onLevelUnload(LevelEvent.Unload)} 配对，使每维度生命周期在实际游戏中生效。
     */
    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ChunkScheduler.getInstance().openDimension(serverLevel.dimension());
        }
    }

    /**
     * 第 9 轮生产接线：维度卸载 → 定向取消该维度等待任务、递增维度代数、清理维度
     * 残留（计数保留给已出队/运行中任务的迟到 lease）。
     * <p>
     * dimensionId 已不再被使用（Registry 统一通知后部分缓存不再使用该参数），传 0 保留兼容。
     */
    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ResourceKey<Level> dimension = serverLevel.dimension();
            LifecycleCleanupCoordinator.getInstance().onDimensionUnload(dimension, 0);
        }
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
        // 审查修复：各自使用独立预算，避免前一个队列耗尽整个 tick 预算
        FullCommitQueue fullQueue = FullCommitQueue.getInstance();
        CompletionBatchShaper batchShaper = CompletionBatchShaper.getInstance();
        fullQueue.tick(System.nanoTime() + fullQueue.budgetNanosPerTick());
        batchShaper.tick(System.nanoTime() + batchShaper.budgetNanosPerTick());

        // 发送配额：每 tick 先清零，再 drain 所有玩家队列（审查修复：接通 drain/poll/send 路径）
        ChunkSendQuota sendQuota = ChunkSendQuota.getInstance();
        sendQuota.resetTick();
        sendQuota.drainAll();
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
