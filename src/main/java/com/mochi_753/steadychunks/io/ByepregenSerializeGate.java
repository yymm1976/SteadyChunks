package com.mochi_753.steadychunks.io;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.bootstrap.ModuleStates;

/**
 * Bye-Pregen 序列化兼容桥，对应开发计划 §9.2。
 * <p>
 * 当 Bye-Pregen 的 GC-free 或序列化模块启用时：
 * <ul>
 *   <li>SteadyChunks 不注入同一序列化方法</li>
 *   <li>只进行队列调度和背压</li>
 *   <li>使用兼容测试确认其 Future 与回调线程模型</li>
 * </ul>
 * <p>
 * 本类作为 Mixin Gate 的运行时查询入口，由各序列化相关 Mixin 在注入前调用
 * {@link #shouldInjectSerialize()} 判断是否应注入。
 * <p>
 * 风险缓解（计划 §9 风险表）：重复序列化优化。
 * 通过 Mixin Gate 检测 Bye-Pregen 模块状态，避免 Mixin 冲突。
 */
public final class ByepregenSerializeGate {
    private static volatile ByepregenSerializeGate instance;

    /** Bye-Pregen 是否安装 */
    private volatile boolean byepregenPresent;
    /** Bye-Pregen 序列化模块是否启用（GC-free / palette rewrite） */
    private volatile boolean serializeModuleEnabled;
    /** 已初始化标志 */
    private volatile boolean initialized = false;

    private ByepregenSerializeGate() {
    }

    public static ByepregenSerializeGate getInstance() {
        if (instance == null) {
            synchronized (ByepregenSerializeGate.class) {
                if (instance == null) {
                    instance = new ByepregenSerializeGate();
                }
            }
        }
        return instance;
    }

    /**
     * 启动时初始化，基于 {@link ModuleStates} 填充状态。
     * <p>
     * 由 {@code ModuleBootstrap} 在兼容探测后调用一次。
     */
    public void initialize(ModuleStates states) {
        this.byepregenPresent = states.byepregenPresent();
        // Bye-Pregen 启用 palette rewrite 视为序列化模块启用
        // （ModuleStates 中 paletteRewriteEnabled 字段反映 Bye-Pregen 序列化模块状态）
        // 这里通过反射安全读取，避免直接字段访问
        this.serializeModuleEnabled = byepregenPresent && checkSerializeModule(states);
        this.initialized = true;

        SteadyChunks.LOGGER.info("SteadyChunks Bye-Pregen 序列化兼容桥: present={}, serializeModule={}",
                byepregenPresent, serializeModuleEnabled);

        if (serializeModuleEnabled) {
            SteadyChunks.LOGGER.info("SteadyChunks 检测到 Bye-Pregen 序列化模块，不注入同一序列化方法，仅做队列调度与背压");
        }
    }

    /**
     * 检查 Bye-Pregen 序列化模块是否启用。
     * <p>
     * 当前通过 ModuleStates.byepregenPresent() 判断。
     * 更精细的模块级检测（如单独的 GC-free 模块开关）可在后续扩展。
     */
    private boolean checkSerializeModule(ModuleStates states) {
        // Bye-Pregen 安装即视为序列化模块启用（Bye-Pregen 默认全模块启用）
        // 后续可扩展为读取 Bye-Pregen 配置判断单独模块开关
        return states.byepregenPresent();
    }

    /**
     * Mixin 注入点查询：是否应注入序列化优化 Mixin。
     * <p>
     * 当 Bye-Pregen 序列化模块启用时返回 false，避免冲突。
     * 未初始化时返回 false（保守策略，不注入）。
     *
     * @return true 表示 SteadyChunks 应注入序列化优化 Mixin
     */
    public boolean shouldInjectSerialize() {
        if (!initialized) {
            return false;
        }
        // Bye-Pregen 序列化模块启用时不注入
        return !serializeModuleEnabled;
    }

    /**
     * Mixin 注入点查询：是否应注入 GC-free 写入路径 Mixin。
     * <p>
     * 同 {@link #shouldInjectSerialize()} 逻辑。
     */
    public boolean shouldInjectGcFreeWrite() {
        return shouldInjectSerialize();
    }

    /**
     * Mixin 注入点查询：是否应注入 palette 优化 Mixin。
     * <p>
     * Bye-Pregen palette rewrite 启用时不注入。
     */
    public boolean shouldInjectPaletteOptimize() {
        if (!initialized) {
            return false;
        }
        return !serializeModuleEnabled;
    }

    /**
     * 是否仅做队列调度与背压（不注入 I/O 热路径）。
     * <p>
     * 当 Bye-Pregen 序列化模块启用时为 true。
     */
    public boolean queueOnlyMode() {
        return initialized && serializeModuleEnabled;
    }

    public boolean byepregenPresent() {
        return byepregenPresent;
    }

    public boolean serializeModuleEnabled() {
        return serializeModuleEnabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 数据包重载或配置变更时重新评估。
     * <p>
     * 当前实现仅重新检查 Bye-Pregen 存在性，模块级状态需扩展。
     */
    public void reevaluate(ModuleStates states) {
        initialize(states);
    }
}
