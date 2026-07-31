package com.mochi_753.steadychunks.compat;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.bootstrap.ModuleStates;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin 冲突表，对应开发计划 §10.2。
 * <p>
 * 维护目标类/方法与 FastNoise / Bye-Pregen / SteadyChunks 注入的关系，
 * 提供注入顺序、冲突处理与回退路径。
 * <p>
 * 表结构（计划 §10.2）：
 * <ul>
 *   <li>目标类</li>
 *   <li>目标方法</li>
 *   <li>FastNoise 注入</li>
 *   <li>Bye-Pregen 注入</li>
 *   <li>SteadyChunks 注入</li>
 *   <li>注入顺序</li>
 *   <li>冲突处理</li>
 *   <li>回退路径</li>
 * </ul>
 * <p>
 * 风险缓解（计划 §10.2 风险表）：
 * <ul>
 *   <li>第三方内部配置没有稳定 API → 使用版本适配器和保守默认</li>
 *   <li>版本更新改变注入点 → CI 锁定测试版本，新版本进入兼容候选状态</li>
 * </ul>
 * <p>
 * {@link MixinPlugin} 在 Mixin 加载时调用 {@link #shouldApply} 判断是否应用。
 */
public final class MixinConflictTable {
    private static final MixinConflictTable INSTANCE = new MixinConflictTable();

    /** 按目标类+方法索引的冲突条目 */
    private final ConcurrentHashMap<String, ConflictEntry> entries = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private MixinConflictTable() {
    }

    public static MixinConflictTable getInstance() {
        return INSTANCE;
    }

    /**
     * 基于启动探测结果初始化冲突表。
     * <p>
     * 注册已知的冲突点和回退路径。
     */
    public void initialize(ModuleStates states) {
        if (initialized) {
            return;
        }

        // NOISE / BIOMES / SURFACE 热路径：FastNoise 注入
        registerConflict("net.minecraft.world.level.chunk.ChunkGenerator",
                "fillFromNoise", "FastNoise", "无", "SteadyChunks(调度)",
                "FastNoise 先，SteadyChunks 不注入同一方法",
                states.fastNoisePresent() ? "SteadyChunks 仅调度，不注入" : "SteadyChunks 可注入优化");

        registerConflict("net.minecraft.world.level.chunk.ChunkGenerator",
                "fillFromBiomes", "FastNoise", "无", "SteadyChunks(调度)",
                "FastNoise 先，SteadyChunks 不注入同一方法",
                states.fastNoisePresent() ? "SteadyChunks 仅调度" : "SteadyChunks 可注入");

        registerConflict("net.minecraft.world.level.chunk.ChunkGenerator",
                "buildSurface", "FastNoise", "无", "SteadyChunks(调度)",
                "FastNoise 先，SteadyChunks 不注入同一方法",
                states.fastNoisePresent() ? "SteadyChunks 仅调度" : "SteadyChunks 可注入");

        // Palette / 序列化：Bye-Pregen 注入
        registerConflict("net.minecraft.world.level.chunk.PalettedContainer",
                "count", "无", "Bye-Pregen", "无",
                "Bye-Pregen 独占",
                states.byepregenPresent() && states.paletteRewriteEnabled() ? "SteadyChunks 不注入" : "Vanilla");

        registerConflict("net.minecraft.world.level.chunk.storage.SerializableChunkData",
                "write", "无", "Bye-Pregen", "无",
                "Bye-Pregen 独占",
                states.byepregenPresent() ? "SteadyChunks 不注入序列化" : "Vanilla");

        // 光照：YALight 注入
        registerConflict("net.minecraft.world.level.lighting.LevelLightEngine",
                "runUpdates", "无", "Bye-Pregen(YALight)", "SteadyChunks(预算)",
                "YALight 先，SteadyChunks 仅做预算管理",
                states.yalightEnabled() ? "SteadyChunks 仅预算" : "SteadyChunks 可注入预算");

        // FULL 整合：SteadyChunks 独占
        registerConflict("net.minecraft.server.level.ServerChunkCache",
                "tick", "无", "无", "SteadyChunks",
                "SteadyChunks 独占",
                "SteadyChunks 注入 FULL 整合队列");

        // 区块发送：SteadyChunks 独占
        registerConflict("net.minecraft.server.level.ServerPlayer",
                "trackChunk", "无", "无", "SteadyChunks",
                "SteadyChunks 独占",
                "SteadyChunks 注入发送配额");

        initialized = true;
        SteadyChunks.LOGGER.info("SteadyChunks Mixin 冲突表已初始化: {} 条目", entries.size());
    }

    /**
     * 注册冲突条目。
     */
    public void registerConflict(String targetClass, String targetMethod,
                                 String fastNoiseInject, String byepregenInject, String steadychunksInject,
                                 String order, String fallback) {
        String key = key(targetClass, targetMethod);
        entries.put(key, new ConflictEntry(targetClass, targetMethod,
                fastNoiseInject, byepregenInject, steadychunksInject,
                order, fallback));
    }

    /**
     * MixinPlugin 查询：指定 Mixin 是否应应用。
     * <p>
     * 若冲突表中存在条目且回退路径表明 SteadyChunks 不注入，返回 false。
     *
     * @param targetClass  目标类
     * @param targetMethod 目标方法
     * @return true 表示应应用 Mixin
     */
    public boolean shouldApply(String targetClass, String targetMethod) {
        ConflictEntry entry = entries.get(key(targetClass, targetMethod));
        if (entry == null) {
            return true; // 未注册的 Mixin 默认应用
        }
        // 回退路径中包含"不注入"则不应用
        boolean shouldNotApply = entry.fallback().contains("不注入");
        if (shouldNotApply) {
            SteadyChunks.LOGGER.debug("SteadyChunks Mixin 冲突表: {}.{} 跳过注入（回退: {}）",
                    targetClass, targetMethod, entry.fallback());
        }
        return !shouldNotApply;
    }

    /**
     * 获取冲突表快照（诊断导出用）。
     */
    public List<ConflictEntry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public ConflictEntry get(String targetClass, String targetMethod) {
        return entries.get(key(targetClass, targetMethod));
    }

    private String key(String targetClass, String targetMethod) {
        return targetClass + "#" + targetMethod;
    }

    /**
     * 输出冲突表到日志。
     */
    public void logConflictTable() {
        SteadyChunks.LOGGER.info("=== SteadyChunks Mixin Conflict Table ===");
        for (ConflictEntry e : entries.values()) {
            SteadyChunks.LOGGER.info("  {}.{}: FN={} BP={} SC={} order={} fallback={}",
                    e.targetClass(), e.targetMethod(),
                    e.fastNoiseInject(), e.byepregenInject(), e.steadychunksInject(),
                    e.order(), e.fallback());
        }
        SteadyChunks.LOGGER.info("=== End Mixin Conflict Table ===");
    }

    /** 冲突条目 */
    public record ConflictEntry(
            String targetClass,
            String targetMethod,
            String fastNoiseInject,
            String byepregenInject,
            String steadychunksInject,
            String order,
            String fallback
    ) {
    }
}
