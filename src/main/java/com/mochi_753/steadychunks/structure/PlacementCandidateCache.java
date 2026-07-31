package com.mochi_753.steadychunks.structure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 结构选址候选快速否决缓存，对应开发计划 §6.2 与技术指导 §11.2。
 * <p>
 * 缓存安全的只读判断结果：某 StructureSet 在某 ChunkPos 是否为 placement 候选。
 * <p>
 * 缓存键必须包含：
 * <ul>
 *   <li>维度 ID</li>
 *   <li>StructureSet 标识（rawId）</li>
 *   <li>ChunkPos（packed long）</li>
 *   <li>数据包 generation（重载时全量失效）</li>
 * </ul>
 * <p>
 * 不得缓存：
 * <ul>
 *   <li>依赖随机数当前位置的最终 Piece 选择</li>
 *   <li>依赖动态 Processor 或 datapack 状态的最终方块结果</li>
 *   <li>整个 StructureStart</li>
 *   <li>跨世界共享的可变结构对象</li>
 * </ul>
 * <p>
 * 线程安全：使用 ConcurrentHashMap，键为复合 record（hash 正确）。
 * 容量限制：超过 {@link #MAX_ENTRIES} 时清空（简单策略，避免 LRU 的复杂度）。
 * <p>
 * §17.2 缓存失效统一管理：实现 {@link DatapackGenerationRegistry.InvalidationListener}
 * 并在构造时注册到 {@link DatapackGenerationRegistry}。
 */
public final class PlacementCandidateCache implements DatapackGenerationRegistry.InvalidationListener {
    private static final PlacementCandidateCache INSTANCE = new PlacementCandidateCache();

    /** 最大缓存条目数，超过时全量清空（避免 LRU 链表开销） */
    private static final int MAX_ENTRIES = 4096;

    private final ConcurrentHashMap<CacheKey, Byte> cache = new ConcurrentHashMap<>();
    /** 当前数据包 generation，由 {@link #onDatapackReload} 递增 */
    private final AtomicLong datapackGeneration = new AtomicLong(0);
    /** 命中计数（诊断用） */
    private final AtomicLong hits = new AtomicLong(0);
    /** 未命中计数（诊断用） */
    private final AtomicLong misses = new AtomicLong(0);

    private PlacementCandidateCache() {
        // §17.2 注册到统一缓存失效注册中心
        DatapackGenerationRegistry.getInstance().register(this);
    }

    public static PlacementCandidateCache getInstance() {
        return INSTANCE;
    }

    /**
     * 查询缓存的结构选址候选判断结果。
     *
     * @param dimensionId    维度 ID
     * @param structureSetId StructureSet rawId
     * @param packedChunkPos ChunkPos.packedLong
     * @return {@link #UNKNOWN}、{@link #CANDIDATE_NO} 或 {@link #CANDIDATE_YES}；UNKNOWN 表示未缓存
     */
    public byte lookup(int dimensionId, int structureSetId, long packedChunkPos) {
        CacheKey key = new CacheKey(dimensionId, structureSetId, packedChunkPos, datapackGeneration.get());
        Byte cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        return UNKNOWN;
    }

    /**
     * 记录选址候选判断结果到缓存。
     *
     * @param dimensionId    维度 ID
     * @param structureSetId StructureSet rawId
     * @param packedChunkPos ChunkPos.packedLong
     * @param result         {@link #CANDIDATE_NO} 或 {@link #CANDIDATE_YES}
     */
    public void store(int dimensionId, int structureSetId, long packedChunkPos, byte result) {
        if (result == UNKNOWN) {
            return;
        }
        if (cache.size() > MAX_ENTRIES) {
            cache.clear();
        }
        CacheKey key = new CacheKey(dimensionId, structureSetId, packedChunkPos, datapackGeneration.get());
        cache.put(key, result);
    }

    /**
     * 数据包重载时调用，递增 generation 使旧缓存自动失效（不再被 lookup 命中）。
     */
    public void onDatapackReload() {
        datapackGeneration.incrementAndGet();
        cache.clear();
    }

    /**
     * 维度卸载时清除该维度的缓存条目。
     */
    public void invalidateDimension(int dimensionId) {
        cache.entrySet().removeIf(e -> e.getKey().dimensionId() == dimensionId);
    }

    /**
     * §17.2 实现 {@link DatapackGenerationRegistry.InvalidationListener}。
     * <p>
     * 由 {@link DatapackGenerationRegistry#fireDatapackReload} 统一触发，
     * 委托到现有 {@link #onDatapackReload} 完成实际失效。
     */
    @Override
    public void onDatapackReload(long newGeneration) {
        onDatapackReload();
    }

    /**
     * §17.2 实现 {@link DatapackGenerationRegistry.InvalidationListener}。
     * <p>
     * 当前缓存键使用 dimensionId（int），无法从 ResourceKey 精确推导，
     * 维度卸载时全量清空缓存（维度卸载不频繁，影响可接受）。
     * 未来扩展可维护 ResourceKey → dimensionId 映射实现精确失效。
     */
    @Override
    public void onDimensionUnload(ResourceKey<Level> dimension) {
        cache.clear();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
        hits.set(0);
        misses.set(0);
    }

    /** 未缓存（lookup 返回值） */
    public static final byte UNKNOWN = 0;
    /** 已缓存：不是候选 */
    public static final byte CANDIDATE_NO = 1;
    /** 已缓存：是候选 */
    public static final byte CANDIDATE_YES = 2;

    /**
     * 复合缓存键。record 自带 equals/hashCode，适合作为 ConcurrentHashMap 键。
     */
    private record CacheKey(
            int dimensionId,
            int structureSetId,
            long packedChunkPos,
            long datapackGeneration
    ) {
    }
}
