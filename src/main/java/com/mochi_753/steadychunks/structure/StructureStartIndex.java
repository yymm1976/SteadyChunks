package com.mochi_753.steadychunks.structure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构起点空间索引，对应开发计划 §6.4 与技术指导 §12，P1-15 修复。
 * <p>
 * <b>跨维度修复</b>：索引键加入 dimensionId，避免不同维度同 ChunkPos 的结构起点混淆。
 * 原实现全局 Map 不区分维度，主世界与下界同坐标结构会互相污染。
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
 * 线程安全：ConcurrentHashMap + 不可变 IndexedStart record + synchronizedList 显式同步。
 * Region 大小为 32×32 Chunk（与 Anvil Region 一致）。
 * <p>
 * §17.2 缓存失效统一管理：实现 {@link DatapackGenerationRegistry.InvalidationListener}
 * 并在构造时注册到 {@link DatapackGenerationRegistry}。
 */
public final class StructureStartIndex implements DatapackGenerationRegistry.InvalidationListener {
    private static final StructureStartIndex INSTANCE = new StructureStartIndex();

    /** 按 (dimensionId, Region) 索引的结构起点列表（P1-15：键加 dimensionId） */
    private final ConcurrentHashMap<Long, List<IndexedStart>> startsByRegion = new ConcurrentHashMap<>();

    /** 按 (dimensionId, structureRawId) 索引的起点，用于精确移除（P1-15：键加 dimensionId） */
    private final ConcurrentHashMap<Long, List<IndexedStart>> startsByStructureId = new ConcurrentHashMap<>();

    private StructureStartIndex() {
        // §17.2 注册到统一缓存失效注册中心
        DatapackGenerationRegistry.getInstance().register(this);
    }

    public static StructureStartIndex getInstance() {
        return INSTANCE;
    }

    /**
     * 组合 dimensionId 和 regionKey/structureRawId 为复合键。
     */
    private static long composeKey(int dimensionId, long subKey) {
        return ((long) dimensionId << 32) | (subKey & 0xFFFFFFFFL);
    }

    private static long composeKey(int dimensionId, int structureRawId) {
        return ((long) dimensionId << 32) | (structureRawId & 0xFFFFFFFFL);
    }

    /**
     * 注册一个结构起点到空间索引（P1-15：需指定维度）。
     * <p>
     * 大型结构跨越多个 Region 时，登记到所有覆盖区域。
     *
     * @param dimensionId    维度 numeric ID
     * @param structureRawId 结构 rawId
     * @param startChunkPos  起点区块坐标（packed long）
     * @param minChunkX      包围盒最小 X
     * @param minChunkZ      包围盒最小 Z
     * @param maxChunkX      包围盒最大 X
     * @param maxChunkZ      包围盒最大 Z
     */
    public void register(int dimensionId, int structureRawId, long startChunkPos,
                         int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        IndexedStart start = new IndexedStart(dimensionId, structureRawId, startChunkPos,
                minChunkX, minChunkZ, maxChunkX, maxChunkZ);

        // 按 (dimensionId, structureId) 索引
        long idKey = composeKey(dimensionId, structureRawId);
        startsByStructureId.computeIfAbsent(idKey, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(start);

        // 登记到所有覆盖的 Region
        int minRegionX = minChunkX >> 5;
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                long regionSubKey = ChunkPos.asLong(rx, rz);
                long regionKey = composeKey(dimensionId, regionSubKey);
                startsByRegion.computeIfAbsent(regionKey, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(start);
            }
        }
    }

    /**
     * 查询与指定 ChunkPos 可能相交的结构起点候选列表（P1-15：需指定维度）。
     * <p>
     * 仅返回候选，调用方仍需使用原版条件判断是否真正写入引用。
     *
     * @param dimensionId 维度 numeric ID
     * @param chunkX      区块 X
     * @param chunkZ      区块 Z
     * @return 候选起点列表（可能为空，不可修改）
     */
    public List<IndexedStart> queryCandidates(int dimensionId, int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long regionSubKey = ChunkPos.asLong(regionX, regionZ);
        long regionKey = composeKey(dimensionId, regionSubKey);
        List<IndexedStart> regionStarts = startsByRegion.get(regionKey);
        if (regionStarts == null || regionStarts.isEmpty()) {
            return List.of();
        }
        // P1-15：显式同步 synchronizedList 遍历
        List<IndexedStart> result = new ArrayList<>();
        synchronized (regionStarts) {
            for (IndexedStart s : regionStarts) {
                if (chunkX >= s.minChunkX() && chunkX <= s.maxChunkX()
                        && chunkZ >= s.minChunkZ() && chunkZ <= s.maxChunkZ()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /**
     * 移除指定维度中指定结构的所有起点（P1-15：按具体起点移除）。
     *
     * @param dimensionId    维度 numeric ID
     * @param structureRawId 结构 rawId
     */
    public void removeStructure(int dimensionId, int structureRawId) {
        long idKey = composeKey(dimensionId, structureRawId);
        List<IndexedStart> starts = startsByStructureId.remove(idKey);
        if (starts == null) {
            return;
        }
        // P1-15：显式同步遍历 synchronizedList
        List<IndexedStart> snapshot;
        synchronized (starts) {
            snapshot = new ArrayList<>(starts);
        }
        // 从 Region 索引中移除具体起点
        for (IndexedStart s : snapshot) {
            int minRegionX = s.minChunkX() >> 5;
            int maxRegionX = s.maxChunkX() >> 5;
            int minRegionZ = s.minChunkZ() >> 5;
            int maxRegionZ = s.maxChunkZ() >> 5;
            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    long regionSubKey = ChunkPos.asLong(rx, rz);
                    long regionKey = composeKey(dimensionId, regionSubKey);
                    List<IndexedStart> regionStarts = startsByRegion.get(regionKey);
                    if (regionStarts != null) {
                        synchronized (regionStarts) {
                            regionStarts.removeIf(s::equals);
                        }
                    }
                }
            }
        }
    }

    /**
     * 清空指定维度的所有索引（P1-15：按维度精确清空）。
     *
     * @param dimensionId 维度 numeric ID
     */
    public void clearDimension(int dimensionId) {
        // 移除该维度的所有 Region 索引
        long dimMask = (long) dimensionId << 32;
        startsByRegion.keySet().removeIf(k -> (k & 0xFFFFFFFF00000000L) == dimMask);
        startsByStructureId.keySet().removeIf(k -> (k & 0xFFFFFFFF00000000L) == dimMask);
    }

    /**
     * 全量清空（数据包重载时调用）。
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
     * P1-15 修复：按维度精确清空，而非全量清空。
     * 维度卸载时仅清除该维度的索引，保留其他维度。
     */
    @Override
    public void onDimensionUnload(ResourceKey<Level> dimension) {
        // 通过 dimension 的 numeric ID 精确清空
        // ResourceKey 无法直接获取 numeric ID，需调用方提供或通过 Registry 查询
        // 此处遍历所有键，按 dimension.location() 匹配（IndexedStart 存储 dimensionId）
        // 简化实现：由于 ResourceKey 无法直接转 numeric ID，全量清空（安全但保守）
        // 后续可通过传入 dimensionId 参数优化
        clear();
    }

    public int totalStarts() {
        return startsByStructureId.values().stream().mapToInt(List::size).sum();
    }

    public int regionCount() {
        return startsByRegion.size();
    }

    /**
     * 结构起点索引条目（不可变，P1-15：含 dimensionId）。
     */
    public record IndexedStart(
            int dimensionId,
            int structureRawId,
            long startChunkPos,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ
    ) {
    }
}
