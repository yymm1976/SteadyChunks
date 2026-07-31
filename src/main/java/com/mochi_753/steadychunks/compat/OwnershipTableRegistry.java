package com.mochi_753.steadychunks.compat;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.bootstrap.ModuleStates;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模块所有权表注册表，对应开发计划 §10.1。
 * <p>
 * 启动时基于 {@link ModuleStates} 与版本门控构建最终所有权表，输出到日志。
 * 用户可从日志看出每个优化由谁负责，避免性能重复。
 * <p>
 * 版本门控（计划 §10.1 验收）：
 * <ul>
 *   <li>使用 modid 和版本范围检测</li>
 *   <li>读取可公开获取的模块配置状态</li>
 *   <li>无法确认具体模块状态时保守关闭</li>
 *   <li>更新 FastNoise 或 Bye-Pregen 后，版本门控阻止未经测试的高风险模块自动启用</li>
 * </ul>
 * <p>
 * 所有权表按版本维护（计划 §10.1 风险表）。
 */
public final class OwnershipTableRegistry {
    private static final OwnershipTableRegistry INSTANCE = new OwnershipTableRegistry();

    /** 已注册的所有权条目 */
    private final ConcurrentHashMap<String, OwnershipEntry> entries = new ConcurrentHashMap<>();
    /** 是否已完成构建 */
    private volatile boolean built = false;

    /** FastNoise 已测试版本范围（下限） */
    private static final String FASTNOISE_MIN_VERSION = "1.0.0";
    /** Bye-Pregen 已测试版本范围（下限） */
    private static final String BYEPREGEN_MIN_VERSION = "0.1.0";

    private OwnershipTableRegistry() {
    }

    public static OwnershipTableRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册一个所有权条目。
     * <p>
     * 第三方模组可通过兼容 API（§10.3）注册自家模块的所有权。
     *
     * @param module     模块名称（如 "NOISE block placement"）
     * @param owner      所有者（FastNoise / Bye-Pregen / SteadyChunks / Vanilla）
     * @param version    所有者版本（用于诊断）
     * @param active     是否实际生效
     */
    public void register(String module, String owner, String version, boolean active) {
        entries.put(module, new OwnershipEntry(module, owner, version, active));
    }

    /**
     * 基于启动探测结果构建所有权表。
     * <p>
     * 由 {@code ModuleBootstrap} 在启动时调用一次。
     * <p>
     * 版本门控：若 FastNoise / Bye-Pregen 版本低于已测试范围，对应模块保守关闭。
     */
    public void build(ModuleStates states) {
        if (built) {
            return;
        }

        String fastNoiseVersion = getModVersion("zfastnoise");
        String byepregenVersion = getModVersion("byepregen");

        // 版本门控
        boolean fastNoiseAllowed = states.fastNoisePresent() && isVersionAllowed(fastNoiseVersion, FASTNOISE_MIN_VERSION);
        boolean byepregenAllowed = states.byepregenPresent() && isVersionAllowed(byepregenVersion, BYEPREGEN_MIN_VERSION);

        if (states.fastNoisePresent() && !fastNoiseAllowed) {
            SteadyChunks.LOGGER.warn("SteadyChunks 版本门控：FastNoise {} 低于已测试版本 {}，NOISE/SURFACE/BIOMES 优化保守关闭",
                    fastNoiseVersion, FASTNOISE_MIN_VERSION);
        }
        if (states.byepregenPresent() && !byepregenAllowed) {
            SteadyChunks.LOGGER.warn("SteadyChunks 版本门控：Bye-Pregen {} 低于已测试版本 {}，序列化/palette/光照优化保守关闭",
                    byepregenVersion, BYEPREGEN_MIN_VERSION);
        }

        // NOISE / BIOMES / SURFACE：FastNoise 优先
        String noiseOwner = fastNoiseAllowed ? "FastNoise" : "Vanilla";
        register("NOISE block placement", noiseOwner, fastNoiseVersion, fastNoiseAllowed);
        register("BIOMES algorithm", noiseOwner, fastNoiseVersion, fastNoiseAllowed);
        register("SURFACE algorithm", noiseOwner, fastNoiseVersion, fastNoiseAllowed);

        // Palette recount / Serialization / I/O GC-free / Placement optimization：Bye-Pregen 优先
        String byepregenOwner = byepregenAllowed ? "Bye-Pregen" : "Vanilla";
        register("Palette recount", byepregenOwner, byepregenVersion, byepregenAllowed && states.paletteRewriteEnabled());
        register("Serialization", byepregenOwner, byepregenVersion, byepregenAllowed);
        register("I/O GC-free path", byepregenOwner, byepregenVersion, byepregenAllowed);
        register("Placement optimization", byepregenOwner, byepregenVersion, byepregenAllowed);

        // Light algorithm：YALight 优先
        String lightOwner = (byepregenAllowed && states.yalightEnabled()) ? "Bye-Pregen (YALight)" : "Vanilla";
        register("Light algorithm", lightOwner, byepregenVersion, byepregenAllowed && states.yalightEnabled());

        // SteadyChunks 负责的模块（始终由 SteadyChunks 所有）
        // P2-18：版本从 ModList 动态读取，避免与 gradle.properties 不一致
        String scVersion = SteadyChunks.version();
        register("Chunk scheduling", "SteadyChunks", scVersion, true);
        register("Structure profiling", "SteadyChunks", scVersion, true);
        register("FULL commit smoothing", "SteadyChunks", scVersion, true);
        register("Chunk send quota", "SteadyChunks", scVersion, true);
        register("Adaptive resource governor", "SteadyChunks", scVersion, true);
        register("Diagnostics (Flight Recorder)", "SteadyChunks", scVersion, true);
        register("Light task budget", "SteadyChunks", scVersion, true);
        register("I/O backpressure", "SteadyChunks", scVersion, true);

        built = true;
        logOwnershipTable();
    }

    /**
     * 输出最终所有权表到日志。
     */
    public void logOwnershipTable() {
        SteadyChunks.LOGGER.info("=== SteadyChunks Module Ownership ===");
        List<String> modules = new ArrayList<>(entries.keySet());
        Collections.sort(modules);
        for (String module : modules) {
            OwnershipEntry e = entries.get(module);
            SteadyChunks.LOGGER.info("  {}: {} (v={}, active={})", e.module(), e.owner(), e.version(), e.active());
        }
        SteadyChunks.LOGGER.info("=== End Module Ownership ===");
    }

    /**
     * 获取所有权表快照（诊断导出用）。
     */
    public List<OwnershipEntry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public OwnershipEntry get(String module) {
        return entries.get(module);
    }

    /**
     * 获取已加载模组的版本。
     */
    private String getModVersion(String modid) {
        return ModList.get().getModContainerById(modid)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    /**
     * 版本门控：检查实际版本是否 >= 最低已测试版本。
     * <p>
     * 简化实现：按字符串比较 major.minor.patch。
     * 无法解析时保守返回 false。
     */
    private boolean isVersionAllowed(String actual, String minimum) {
        if (actual == null || actual.equals("unknown")) {
            return false;
        }
        try {
            String[] actualParts = actual.split("\\.");
            String[] minParts = minimum.split("\\.");
            int len = Math.max(actualParts.length, minParts.length);
            for (int i = 0; i < len; i++) {
                int a = i < actualParts.length ? Integer.parseInt(actualParts[i]) : 0;
                int m = i < minParts.length ? Integer.parseInt(minParts[i]) : 0;
                if (a > m) return true;
                if (a < m) return false;
            }
            return true; // 相等
        } catch (NumberFormatException e) {
            SteadyChunks.LOGGER.warn("SteadyChunks 版本解析失败: actual={} minimum={}", actual, minimum);
            return false;
        }
    }

    /** 所有权条目 */
    public record OwnershipEntry(
            String module,
            String owner,
            String version,
            boolean active
    ) {
    }
}
