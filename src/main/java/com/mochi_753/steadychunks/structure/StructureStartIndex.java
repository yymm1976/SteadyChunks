package com.mochi_753.steadychunks.structure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构起点空间索引，对应开发计划 §6.4 与技术指导 §12。
 * <p>
 * 为 {@code STRUCTURE_REFERENCES} 阶段提供空间索引，加速结构引用建立：
 * <ol>
 *   <li>先按区域（Region）查候选起点</li>
 *   <li>再检查 Chunk 包围盒</li>
 *   <li>最后调用原版引用写入</li>
 * </ol>
 * <p>
 * 索引只是候选加速器，最终是否写入引用仍使用原版条件。
 * <p>
 * 生命周期约束（技术指导 §12）：
 * <ul>
 *   <li>索引生命周期不能超过对应结构数据</li>
 *   <li>区块卸载和任务取消必须清除</li>
 *   <li>大型结构跨越多个 Region 时登记到所有覆盖区域</li>
 * </ul>
 * <p>
 * 线程安全：ConcurrentHashMap + 不可变 IndexedStart record + CopyOnWrite 列表。
 * Region 大小为 32×32 Chunk（与 Anvil Region 一致）。
 * <p>
 * §17.2 缓存失效统一管理：实现 {@link DatapackGenerationRegistry.InvalidationListener}
 * 并在构造时注册到 {@link DatapackGenerationRegistry}。
 */
public final class StructureStartIndex implements DatapackGenerationRegistry.InvalidationListener {
    private static final StructureStartIndex INSTANCE = new StructureStartIndex();

    /** 按 Region 索引的结构起点列表 */
    private final ConcurrentHashMap<Long, List<IndexedStart>> startsByRegion = new ConcurrentHashMap<>();

    /** 按结构 rawId 索引的起点，用于精确移除 */
    private final ConcurrentHashMap<Integer, List<IndexedStart>> startsByStructureId = new ConcurrentHashMap<>();

    private StructureStartIndex() {
        // §17.2 注册到统一缓存失效注册中心
        DatapackGenerationRegistry.getInstance().register(this);
    }

    public static StructureStartIndex getInstance() {
        return INSTANCE;
    }

    /**
     * 注册一个结构起点到空间索引。
     * <p>
     * 大型结构跨越多个 Region 时，登记到所有覆盖区域。
     *
     * @param structureRawId 结构 rawId
     * @param startChunkPos  起点区块坐标（packed long）
     * @param minChunkX      包围盒最小 X
     * @param minChunkZ      包围盒最小 Z
     * @param maxChunkX      包围盒最大 X
     * @param maxChunkZ      包围盒最大 Z
     */
    public void register(int structureRawId, long startChunkPos,
                         int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        IndexedStart start = new IndexedStart(structureRawId, startChunkPos,
                minChunkX, minChunkZ, maxChunkX, maxChunkZ);

        // 按 structureId 索引
        startsByStructureId.computeIfAbsent(structureRawId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(start);

        // 登记到所有覆盖的 Region
        int minRegionX = minChunkX >> 5;
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                long regionKey = ChunkPos.asLong(rx, rz);
                startsByRegion.computeIfAbsent(regionKey, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(start);
            }
        }
    }

    /**
     * 查询与指定 ChunkPos 可能相交的结构起点候选列表。
     * <p>
     * 仅返回候选，调用方仍需使用原版条件判断是否真正写入引用。
     *
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @return 候选起点列表（可能为空，不可修改）
     */
    public List<IndexedStart> queryCandidates(int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        List<IndexedStart> regionStarts = startsByRegion.get(regionKey);
        if (regionStarts == null || regionStarts.isEmpty()) {
            return List.of();
        }
        // 过滤出包围盒包含此 Chunk 的起点
        List<IndexedStart> result = new ArrayList<>();
        for (IndexedStart s : regionStarts) {
            if (chunkX >= s.minChunkX() && chunkX <= s.maxChunkX()
                    && chunkZ >= s.minChunkZ() && chunkZ <= s.maxChunkZ()) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * 移除指定结构的所有起点（结构被取消或卸载时调用）。
     */
    public void removeStructure(int structureRawId) {
        List<IndexedStart> starts = startsByStructureId.remove(structureRawId);
        if (starts == null) {
            return;
        }
        // 从 Region 索引中移除
        for (IndexedStart s : starts) {
            int minRegionX = s.minChunkX() >> 5;
            int maxRegionX = s.maxChunkX() >> 5;
            int minRegionZ = s.minChunkZ() >> 5;
            int maxRegionZ = s.maxChunkZ() >> 5;
            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    long regionKey = ChunkPos.asLong(rx, rz);
                    List<IndexedStart> regionStarts = startsByRegion.get(regionKey);
                    if (regionStarts != null) {
                        regionStarts.removeIf(s::equals);
                    }
                }
            }
        }
    }

    /**
     * 清空指定维度的所有索引（维度卸载时调用）。
     * <p>
     * 当前实现按维度隔离需调用方维护多实例；此处提供全量清空。
     */
    public void clear() {
        startsByRegion.clear();
        startsByStructureId.clear();
    }

    /**
     * §17.2 实现 {@link DatapackGenerationRegistry.InvalidationListener}。
     * <p>
     * 数据包重载时结构配置可能变化（StructureSet、放置参数等），
     * 必须全量清空索引避免使用过期起点。
     */
    @Override
    public void onDatapackReload(long newGeneration) {
        clear();
    }

    /**
     * §17.2 实现 {@link DatapackGenerationRegistry.InvalidationListener}。
     * <p>
     * 当前索引未按维度隔离（全局 Map），维度卸载时全量清空。
     * 未来扩展可按维度分片存储，实现精确失效。
     */
    @Override
    public void onDimensionUnload(ResourceKey<Level> dimension) {
        clear();
    }

    public int totalStarts() {
        return startsByStructureId.values().stream().mapToInt(List::size).sum();
    }

    public int regionCount() {
        return startsByRegion.size();
    }

    /**
     * 结构起点索引条目（不可变）。
     */
    public record IndexedStart(
            int structureRawId,
            long startChunkPos,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ
    ) {
    }
}
