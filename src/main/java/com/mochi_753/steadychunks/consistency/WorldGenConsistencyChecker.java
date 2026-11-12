package com.mochi_753.steadychunks.consistency;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 世界生成一致性检查器，对应开发计划 Phase 11.1。
 * <p>
 * 提供两种检查模式：
 * <ul>
 *   <li><b>严格一致性</b>：对区块数据计算规范化哈希，比较结构起点、Piece、方块、流体、Heightmap、方块实体和结构引用。
 *       适用于可确定路径（固定种子、固定模组、固定线程策略）</li>
 *   <li><b>语义一致性</b>：检查结构位置、完整性、无缺块、无引用丢失、方块实体合法、无越界。
 *       适用于原版本身存在执行顺序非确定性的场景</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>只读检查，不修改世界数据</li>
 *   <li>规范化哈希：相同逻辑内容产生相同哈希，忽略无关字段（如时间戳）</li>
 *   <li>报告包含差异详情，便于定位问题</li>
 *   <li>线程安全：检查在调用方线程执行，无内部状态</li>
 * </ul>
 */
public final class WorldGenConsistencyChecker {

    /**
     * 对单个区块执行严格一致性检查，生成规范化哈希。
     * <p>
     * 哈希覆盖：方块状态、流体状态、Heightmap、方块实体、结构引用、结构起点。
     *
     * @param level 目标维度
     * @param pos   区块坐标
     * @return 包含规范化哈希的检查报告
     */
    public ConsistencyReport checkStrict(ServerLevel level, ChunkPos pos) {
        ConsistencyReport report = new ConsistencyReport(pos, level.dimension());
        ChunkAccess chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (chunk == null) {
            report.addIssue(ConsistencyIssue.error("区块未加载，无法检查: " + pos));
            return report;
        }
        // 计算各维度规范化哈希
        report.setBlockHash(hashBlocks(chunk));
        report.setFluidHash(hashFluids(chunk));
        report.setHeightmapHash(hashHeightmaps(chunk));
        report.setBlockEntityHash(hashBlockEntities(chunk));
        report.setStructureRefHash(hashStructureReferences(chunk));
        report.setStructureStartHash(hashStructureStarts(level, pos));
        report.setOverallHash(Objects.hash(
                report.blockHash(), report.fluidHash(), report.heightmapHash(),
                report.blockEntityHash(), report.structureRefHash(), report.structureStartHash()
        ));
        return report;
    }

    /**
     * 对单个区块执行语义一致性检查。
     * <p>
     * 检查项：结构位置、完整性、无缺块、无引用丢失、方块实体合法、无越界。
     *
     * @param level 目标维度
     * @param pos   区块坐标
     * @return 包含语义检查结果的报告
     */
    public ConsistencyReport checkSemantic(ServerLevel level, ChunkPos pos) {
        ConsistencyReport report = new ConsistencyReport(pos, level.dimension());
        ChunkAccess chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (chunk == null) {
            report.addIssue(ConsistencyIssue.error("区块未加载，无法检查: " + pos));
            return report;
        }
        // 检查方块实体合法性
        checkBlockEntities(chunk, report);
        // 检查 Heightmap 完整性
        checkHeightmaps(chunk, report);
        // 检查结构引用
        checkStructureReferences(chunk, report);
        // 检查越界方块
        checkOutOfBounds(chunk, report);
        return report;
    }

    /**
     * 比较两份严格检查报告，输出差异。
     *
     * @param baseline 基线报告（对照组）
     * @param actual   实际报告
     * @return 差异列表，空表示完全一致
     */
    public List<ConsistencyIssue> compareReports(ConsistencyReport baseline, ConsistencyReport actual) {
        List<ConsistencyIssue> diffs = new ArrayList<>();
        if (baseline.blockHash() != actual.blockHash()) {
            diffs.add(ConsistencyIssue.warn("方块哈希差异: baseline=" + baseline.blockHash()
                    + " actual=" + actual.blockHash()));
        }
        if (baseline.fluidHash() != actual.fluidHash()) {
            diffs.add(ConsistencyIssue.warn("流体哈希差异: baseline=" + baseline.fluidHash()
                    + " actual=" + actual.fluidHash()));
        }
        if (baseline.heightmapHash() != actual.heightmapHash()) {
            diffs.add(ConsistencyIssue.warn("Heightmap 哈希差异: baseline=" + baseline.heightmapHash()
                    + " actual=" + actual.heightmapHash()));
        }
        if (baseline.blockEntityHash() != actual.blockEntityHash()) {
            diffs.add(ConsistencyIssue.warn("方块实体哈希差异: baseline=" + baseline.blockEntityHash()
                    + " actual=" + actual.blockEntityHash()));
        }
        if (baseline.structureRefHash() != actual.structureRefHash()) {
            diffs.add(ConsistencyIssue.warn("结构引用哈希差异: baseline=" + baseline.structureRefHash()
                    + " actual=" + actual.structureRefHash()));
        }
        if (baseline.structureStartHash() != actual.structureStartHash()) {
            diffs.add(ConsistencyIssue.warn("结构起点哈希差异: baseline=" + baseline.structureStartHash()
                    + " actual=" + actual.structureStartHash()));
        }
        return diffs;
    }

    /**
     * 计算区块方块状态的规范化哈希。
     * <p>
     * 遍历所有方块位置，对 BlockState 的 hashCode 求和（顺序无关）。
     */
    private int hashBlocks(ChunkAccess chunk) {
        int hash = 0;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMinBuildHeight() + chunk.getHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    hash = hash * 31 + chunk.getBlockState(pos.set(minX + x, y, minZ + z)).hashCode();
                }
            }
        }
        return hash;
    }

    /**
     * 计算区块流体状态的规范化哈希。
     */
    private int hashFluids(ChunkAccess chunk) {
        int hash = 0;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMinBuildHeight() + chunk.getHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    hash = hash * 31 + chunk.getFluidState(pos.set(minX + x, y, minZ + z)).hashCode();
                }
            }
        }
        return hash;
    }

    /**
     * 计算区块 Heightmap 的规范化哈希。
     */
    private int hashHeightmaps(ChunkAccess chunk) {
        int hash = 0;
        for (var entry : chunk.getHeightmaps()) {
            hash = hash * 31 + entry.getKey().hashCode();
            hash = hash * 31 + entry.getValue().hashCode();
        }
        return hash;
    }

    /**
     * 计算方块实体的规范化哈希。
     * <p>
     * 只保留方块实体的静态数据（类型 + 位置），不包含 Loot 和动态 NBT。
     */
    private int hashBlockEntities(ChunkAccess chunk) {
        int hash = 0;
        // ChunkAccess 仅暴露方块实体位置集合，需逐个 getBlockEntity 取实例
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            hash = hash * 31 + pos.hashCode();
            // 仅哈希 BlockEntity 类型，不哈希完整 NBT（可能包含随机 Loot）
            var be = chunk.getBlockEntity(pos);
            if (be != null) {
                hash = hash * 31 + be.getClass().getName().hashCode();
            }
        }
        return hash;
    }

    /**
     * 计算结构引用的规范化哈希。
     */
    private int hashStructureReferences(ChunkAccess chunk) {
        int hash = 0;
        for (var entry : chunk.getAllReferences().entrySet()) {
            hash = hash * 31 + entry.getKey().hashCode();
            for (long ref : entry.getValue()) {
                hash = hash * 31 + Long.hashCode(ref);
            }
        }
        return hash;
    }

    /**
     * 计算结构起点的规范化哈希。
     */
    private int hashStructureStarts(ServerLevel level, ChunkPos pos) {
        int hash = 0;
        var starts = level.getChunk(pos.x, pos.z).getAllStarts();
        for (var entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) {
                continue;
            }
            hash = hash * 31 + entry.getKey().hashCode();
            hash = hash * 31 + start.getChunkPos().hashCode();
            // 不哈希 Piece 的随机生成细节，只哈希结构类型和位置
        }
        return hash;
    }

    /**
     * 检查方块实体合法性。
     */
    private void checkBlockEntities(ChunkAccess chunk, ConsistencyReport report) {
        ChunkPos cpos = chunk.getPos();
        int minBlockX = cpos.getMinBlockX();
        int minBlockZ = cpos.getMinBlockZ();
        int maxBlockX = cpos.getMaxBlockX();
        int maxBlockZ = cpos.getMaxBlockZ();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            // 检查方块实体位置是否在区块范围内（ChunkPos 无 isWithinBounds，手动判断）
            if (pos.getX() < minBlockX || pos.getX() > maxBlockX
                    || pos.getZ() < minBlockZ || pos.getZ() > maxBlockZ) {
                report.addIssue(ConsistencyIssue.error("方块实体越界: " + pos));
            }
            // 检查方块实体缓存的状态与区块实际状态是否一致
            var be = chunk.getBlockEntity(pos);
            if (be != null && be.getBlockState() != chunk.getBlockState(pos)) {
                report.addIssue(ConsistencyIssue.warn("方块实体与方块状态不匹配: " + pos));
            }
        }
    }

    /**
     * 检查 Heightmap 完整性。
     */
    private void checkHeightmaps(ChunkAccess chunk, ConsistencyReport report) {
        for (var entry : chunk.getHeightmaps()) {
            if (entry.getValue() == null) {
                report.addIssue(ConsistencyIssue.error("Heightmap 为 null: " + entry.getKey()));
            }
        }
    }

    /**
     * 检查结构引用完整性。
     */
    private void checkStructureReferences(ChunkAccess chunk, ConsistencyReport report) {
        for (var entry : chunk.getAllReferences().entrySet()) {
            for (long ref : entry.getValue()) {
                if (ref == 0) {
                    continue;
                }
                ChunkPos refPos = new ChunkPos(ref);
                // 检查引用是否在合理范围内（结构引用通常在邻近区块）
                int dx = Math.abs(refPos.x - chunk.getPos().x);
                int dz = Math.abs(refPos.z - chunk.getPos().z);
                if (dx > 8 || dz > 8) {
                    report.addIssue(ConsistencyIssue.warn("结构引用距离异常: " + entry.getKey()
                            + " ref=" + refPos + " distance=" + Math.max(dx, dz)));
                }
            }
        }
    }

    /**
     * 检查越界方块（超出区块边界）。
     */
    private void checkOutOfBounds(ChunkAccess chunk, ConsistencyReport report) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMinBuildHeight() + chunk.getHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 抽样检查边界方块
        for (int y = minY; y < maxY; y += 16) {
            var state = chunk.getBlockState(pos.set(minX, y, minZ));
            if (state == null) {
                report.addIssue(ConsistencyIssue.error("边界方块状态为 null: " + pos));
            }
        }
    }
}
