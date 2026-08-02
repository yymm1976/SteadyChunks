package com.mochi_753.steadychunks.config;

import com.mochi_753.steadychunks.SteadyChunks;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * SteadyChunks 通用配置（COMMON 类型：同步到服务端，不按世界存储）。
 * <p>
 * 当前实现 {@code [general]} 与 {@code [compatibility]} 两个区段，对应开发计划 §8.2 中的核心配置。
 * 后续 Phase 会按需扩展：
 * <ul>
 *   <li>Phase 3：{@code [scheduler]} {@code [stage_limits]}</li>
 *   <li>Phase 4：{@code [governor]}</li>
 *   <li>Phase 5：{@code [completion]}</li>
 *   <li>Phase 6：{@code [structure]}</li>
 *   <li>Phase 2：{@code [telemetry]}</li>
 * </ul>
 * 服务端专用配置（如发送配额、玩家公平性）后续放入 {@code ServerConfig}；
 * 客户端专用配置（如帧时间反馈、可选编译节流）放入 {@code ClientConfig}。
 * <p>
 * NeoForge 配置机制：{@link ModConfigSpec} 在静态初始化时构建；
 * {@link ModContainer#registerConfig} 注册到 FML；运行时通过 {@code xxx.get()} 读取当前值。
 */
public final class CommonConfig {
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<Preset> PRESET;
    /** P1-5 修复：高级覆盖开关。true 时预设不覆盖用户在 [scheduler]/[stage_limits] 等处的显式数值配置。 */
    public static final ModConfigSpec.BooleanValue USE_ADVANCED_OVERRIDES;
    public static final ModConfigSpec.BooleanValue STRICT_COMPATIBILITY;

    public static final ModConfigSpec.EnumValue<CompatMode> FASTNOISE;
    public static final ModConfigSpec.EnumValue<CompatMode> BYEPREGEN;
    public static final ModConfigSpec.EnumValue<C2meMode> C2ME;
    public static final ModConfigSpec.EnumValue<UnknownChunkSystemMode> UNKNOWN_CHUNK_SYSTEM;

    // [telemetry] 诊断观测设置（Phase 2）
    public static final ModConfigSpec.BooleanValue BASIC_METRICS;
    public static final ModConfigSpec.BooleanValue HIGH_DETAIL;
    public static final ModConfigSpec.BooleanValue EXPORT_SPIKE_CONTEXT;
    public static final ModConfigSpec.BooleanValue AUTOMATIC_UPLOAD;

    // [scheduler] 调度器设置（Phase 3，对应计划 §3.3）
    public static final ModConfigSpec.BooleanValue SCHEDULER_ENABLED;
    public static final ModConfigSpec.IntValue MAX_INFLIGHT;

    // [stage_limits] 阶段并发限制（Phase 3，对应计划 §3.3）
    public static final ModConfigSpec.IntValue LIMIT_STRUCTURE_STARTS;
    public static final ModConfigSpec.IntValue LIMIT_NOISE;
    public static final ModConfigSpec.IntValue LIMIT_FEATURES;
    public static final ModConfigSpec.IntValue LIMIT_LIGHT;

    // [governor] 资源治理设置（Phase 4，对应计划 §4.2-4.5）
    public static final ModConfigSpec.BooleanValue GOVERNOR_ENABLED;
    public static final ModConfigSpec.DoubleValue TARGET_P95_MSPT;
    public static final ModConfigSpec.DoubleValue HARD_MSPT;
    public static final ModConfigSpec.DoubleValue TARGET_PROCESS_CPU;
    public static final ModConfigSpec.DoubleValue HEAP_PRESSURE;
    public static final ModConfigSpec.DoubleValue LONG_FRAME_MS;
    public static final ModConfigSpec.DoubleValue EMERGENCY_FRAME_MS;
    public static final ModConfigSpec.IntValue INCREASE_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue DECREASE_COOLDOWN_SECONDS;

    // [completion] FULL 整合与完成批次整形（Phase 5，对应计划 §5.1-5.2）
    public static final ModConfigSpec.BooleanValue COMPLETION_ENABLED;
    public static final ModConfigSpec.IntValue FULL_MAX_COMMITS_PER_TICK;
    public static final ModConfigSpec.IntValue FULL_DEPENDENCY_RESERVE;
    public static final ModConfigSpec.IntValue FULL_QUEUE_CAPACITY;
    public static final ModConfigSpec.IntValue BATCH_MAX_CALLBACKS_PER_TICK;

    // [send] 区块发送配额（Phase 5，对应计划 §5.3）
    public static final ModConfigSpec.BooleanValue SEND_QUOTA_ENABLED;
    public static final ModConfigSpec.IntValue SEND_MAX_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue SEND_MIN_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue SEND_MAX_BYTES_PER_TICK_KB;
    public static final ModConfigSpec.IntValue SEND_QUEUE_CAPACITY_PER_PLAYER;

    // [client_feedback] 客户端反馈（Phase 5，对应计划 §5.4）
    public static final ModConfigSpec.BooleanValue CLIENT_FEEDBACK_ENABLED;
    public static final ModConfigSpec.BooleanValue CLIENT_COMPILE_GOVERNANCE_ENABLED;
    public static final ModConfigSpec.IntValue CLIENT_MAX_SECTION_REBUILDS_PER_FRAME;

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // [general] 通用设置
        builder.comment("SteadyChunks 通用设置").push("general");
        ENABLED = builder.comment("是否启用 SteadyChunks 主功能")
                .define("enabled", true);
        PRESET = builder.comment("预设：smooth_integrated（单人冒险整合包）/ balanced（普通服务器）/ throughput_server（独立服务器或预生成）")
                .defineEnum("preset", Preset.SMOOTH_INTEGRATED);
        USE_ADVANCED_OVERRIDES = builder.comment("P1-5：高级数值覆盖。false（默认）= 预设数值覆盖下方高级配置；true = 预设仅控制 enabled 开关，所有数值以你的显式配置为准")
                .define("use_advanced_overrides", false);
        STRICT_COMPATIBILITY = builder.comment("严格兼容模式：检测到无法确认的兼容性时关闭优化而非猜测")
                .define("strict_compatibility", true);
        builder.pop();

        // [compatibility] 兼容性设置（参见 ADR-002 与 compatibility-ownership.md §6）
        builder.comment("兼容性设置").push("compatibility");
        FASTNOISE = builder.comment("FastNoise 兼容模式：auto（自动探测）/ force_enable / disable")
                .defineEnum("fastnoise", CompatMode.AUTO);
        BYEPREGEN = builder.comment("Bye-Pregen 兼容模式：auto / force_enable / disable")
                .defineEnum("byepregen", CompatMode.AUTO);
        C2ME = builder.comment("C2ME 互斥策略：reject（默认阻止启动）/ analyzer_only（仅启用诊断器）/ force_coexist（开发测试用）")
                .defineEnum("c2me", C2meMode.REJECT);
        UNKNOWN_CHUNK_SYSTEM = builder.comment("未知区块系统处理：disable_risky_modules（默认）/ force_enable")
                .defineEnum("unknown_chunk_system", UnknownChunkSystemMode.DISABLE_RISKY_MODULES);
        builder.pop();

        // [telemetry] 诊断观测设置（Phase 2，对应计划 §8.2）
        builder.comment("诊断观测设置").push("telemetry");
        BASIC_METRICS = builder.comment("基础计数：开启时开销低于 3%，记录阶段/结构/FEATURES/FULL/发送等核心指标")
                .define("basic_metrics", true);
        HIGH_DETAIL = builder.comment("高精度分析模式：开销较高，不作为日常默认，记录完整事件流与调用栈指纹")
                .define("high_detail", false);
        EXPORT_SPIKE_CONTEXT = builder.comment("尖峰事件导出：超长帧/MSPT 突增时保留前后完整事件上下文")
                .define("export_spike_context", true);
        AUTOMATIC_UPLOAD = builder.comment("自动上传性能数据（默认关闭，本项目不上传任何数据）")
                .define("automatic_upload", false);
        builder.pop();

        // [scheduler] 调度器设置（Phase 3，对应计划 §3.3）
        builder.comment("区块调度器设置").push("scheduler");
        SCHEDULER_ENABLED = builder.comment("是否启用固定预算调度器（关闭后恢复原版路径）")
                .define("enabled", false);
        MAX_INFLIGHT = builder.comment("同时在途的区块任务总数上限")
                .defineInRange("max_inflight", 64, 1, 512);
        builder.pop();

        // [stage_limits] 阶段并发限制（Phase 3，对应计划 §3.3）
        builder.comment("阶段并发令牌上限").push("stage_limits");
        LIMIT_STRUCTURE_STARTS = builder.comment("STRUCTURE_STARTS 并发上限")
                .defineInRange("structure_starts", 2, 1, 8);
        LIMIT_NOISE = builder.comment("NOISE 并发上限")
                .defineInRange("noise", 8, 1, 8);
        LIMIT_FEATURES = builder.comment("FEATURES 并发上限（写入密集，建议保守）")
                .defineInRange("features", 1, 1, 4);
        LIMIT_LIGHT = builder.comment("LIGHT 并发上限")
                .defineInRange("light", 2, 1, 8);
        builder.pop();

        // [governor] 资源治理设置（Phase 4，对应计划 §4.2-4.5）
        builder.comment("自适应资源治理设置").push("governor");
        GOVERNOR_ENABLED = builder.comment("是否启用自适应资源治理器（需先启用调度器）")
                .define("enabled", false);
        TARGET_P95_MSPT = builder.comment("目标 P95 MSPT（毫秒），低于此值视为健康")
                .defineInRange("target_p95_mspt", 40.0, 10.0, 100.0);
        HARD_MSPT = builder.comment("MSPT 硬上限（毫秒），超过触发临界响应")
                .defineInRange("hard_mspt", 48.0, 20.0, 200.0);
        TARGET_PROCESS_CPU = builder.comment("进程 CPU 目标上限 [0,1]")
                .defineInRange("target_process_cpu", 0.75, 0.1, 1.0);
        HEAP_PRESSURE = builder.comment("堆压力阈值 [0,1]")
                .defineInRange("heap_pressure", 0.78, 0.3, 0.95);
        LONG_FRAME_MS = builder.comment("长帧阈值（毫秒）")
                .defineInRange("long_frame_ms", 50.0, 16.0, 500.0);
        EMERGENCY_FRAME_MS = builder.comment("紧急帧阈值（毫秒），超过触发紧急模式")
                .defineInRange("emergency_frame_ms", 150.0, 50.0, 1000.0);
        INCREASE_COOLDOWN_SECONDS = builder.comment("AIMD 增加 permit 冷却（秒，P1-10：原 _ticks 已改名，1 秒 = 1 控制周期）")
                .defineInRange("increase_cooldown_seconds", 10, 1, 60);
        DECREASE_COOLDOWN_SECONDS = builder.comment("AIMD 减少 permit 冷却（秒，P1-10：原 _ticks 已改名，1 秒 = 1 控制周期）")
                .defineInRange("decrease_cooldown_seconds", 1, 1, 30);
        builder.pop();

        // [completion] FULL 整合与完成批次整形（Phase 5，对应计划 §5.1-5.2）
        builder.comment("FULL 整合与完成批次整形").push("completion");
        COMPLETION_ENABLED = builder.comment("是否启用 FULL 整合队列与完成批次整形")
                .define("enabled", false);
        FULL_MAX_COMMITS_PER_TICK = builder.comment("每 Tick 最大 FULL 整合区块数")
                .defineInRange("full_max_commits_per_tick", 8, 1, 64);
        FULL_DEPENDENCY_RESERVE = builder.comment("依赖关键任务的每 Tick 保底数量")
                .defineInRange("full_dependency_reserve", 2, 0, 16);
        FULL_QUEUE_CAPACITY = builder.comment("FULL 整合队列容量上限")
                .defineInRange("full_queue_capacity", 256, 32, 2048);
        BATCH_MAX_CALLBACKS_PER_TICK = builder.comment("每 Tick 最大完成回调执行数")
                .defineInRange("batch_max_callbacks_per_tick", 16, 1, 128);
        builder.pop();

        // [send] 区块发送配额（Phase 5，对应计划 §5.3）
        builder.comment("区块发送配额").push("send");
        SEND_QUOTA_ENABLED = builder.comment("是否启用区块发送配额")
                .define("enabled", false);
        SEND_MAX_CHUNKS_PER_TICK = builder.comment("每玩家每 Tick 最大发送区块数")
                .defineInRange("max_chunks_per_tick", 5, 1, 32);
        SEND_MIN_CHUNKS_PER_TICK = builder.comment("每玩家每 Tick 最低发送区块数（防缺块）")
                .defineInRange("min_chunks_per_tick", 1, 0, 8);
        SEND_MAX_BYTES_PER_TICK_KB = builder.comment("每玩家每 Tick 最大发送字节数（KB）")
                .defineInRange("max_bytes_per_tick_kb", 512, 64, 4096);
        SEND_QUEUE_CAPACITY_PER_PLAYER = builder.comment("每玩家发送队列容量上限")
                .defineInRange("queue_capacity_per_player", 128, 16, 1024);
        builder.pop();

        // [client_feedback] 客户端反馈（Phase 5，对应计划 §5.4-5.5）
        builder.comment("客户端反馈与编译治理").push("client_feedback");
        CLIENT_FEEDBACK_ENABLED = builder.comment("是否接受客户端反馈（关闭则纯服务端模式）")
                .define("enabled", true);
        CLIENT_COMPILE_GOVERNANCE_ENABLED = builder.comment("是否启用客户端编译治理（仅原版渲染器）")
                .define("compile_governance_enabled", false);
        CLIENT_MAX_SECTION_REBUILDS_PER_FRAME = builder.comment("每帧最大 Section 重建提交数（仅原版渲染器）")
                .defineInRange("max_section_rebuilds_per_frame", 8, 1, 64);
        builder.pop();

        SPEC = builder.build();
    }

    private CommonConfig() {
    }

    /**
     * 注册到 ModContainer。由 {@code ModuleBootstrap.bootstrap} 调用一次。
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        SteadyChunks.LOGGER.info("SteadyChunks CommonConfig 已注册");
    }

    /** 预设枚举，对应计划 §11.6 三套预设 */
    public enum Preset {
        SMOOTH_INTEGRATED,
        BALANCED,
        THROUGHPUT_SERVER
    }

    /** FastNoise / Bye-Pregen 兼容模式 */
    public enum CompatMode {
        AUTO,
        FORCE_ENABLE,
        DISABLE
    }

    /** C2ME 互斥策略，对应 ADR-002 */
    public enum C2meMode {
        REJECT,
        ANALYZER_ONLY,
        FORCE_COEXIST
    }

    /** 未知区块系统处理策略，对应 compatibility-ownership.md §6.3 */
    public enum UnknownChunkSystemMode {
        DISABLE_RISKY_MODULES,
        FORCE_ENABLE
    }
}
