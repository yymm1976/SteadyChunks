package com.mochi_753.steadychunks.structure;

import com.mochi_753.steadychunks.structure.StructureStartIndex.IndexedStart;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StructureStartIndex 单元测试，对应 P2-19。
 * <p>
 * 覆盖 P1-15 修复点：
 * <ul>
 *   <li>不同维度相同 ChunkPos 不互相污染</li>
 *   <li>removeStructure 按具体维度+结构移除</li>
 *   <li>clearDimension 仅清空指定维度</li>
 *   <li>跨 Region 大型结构登记到所有覆盖区域</li>
 *   <li>queryCandidates 不返回其他维度的起点</li>
 * </ul>
 */
class StructureStartIndexTest {

    @AfterEach
    void clearIndex() {
        StructureStartIndex.getInstance().clear();
    }

    @Test
    void differentDimensionsWithSameChunkPosShouldNotContaminate() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        // 主世界 (0,0) 注册一个结构
        long start1 = ChunkPos.asLong(0, 0);
        idx.register(0, 100, start1, 0, 0, 2, 2);

        // 下界 (0,0) 注册另一个结构
        long start2 = ChunkPos.asLong(0, 0);
        idx.register(-1, 200, start2, 0, 0, 2, 2);

        // 查询主世界 (1,1) 应只返回主世界结构
        List<IndexedStart> overworldCandidates = idx.queryCandidates(0, 1, 1);
        assertEquals(1, overworldCandidates.size(), "主世界查询应只返回主世界结构");
        assertEquals(0, overworldCandidates.get(0).dimensionId(), "候选维度应为 0");
        assertEquals(100, overworldCandidates.get(0).structureRawId(), "结构 ID 应为 100");

        // 查询下界 (1,1) 应只返回下界结构
        List<IndexedStart> netherCandidates = idx.queryCandidates(-1, 1, 1);
        assertEquals(1, netherCandidates.size(), "下界查询应只返回下界结构");
        assertEquals(-1, netherCandidates.get(0).dimensionId(), "候选维度应为 -1");
        assertEquals(200, netherCandidates.get(0).structureRawId(), "结构 ID 应为 200");
    }

    @Test
    void removeStructureShouldBeScopedToDimension() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        long start = ChunkPos.asLong(0, 0);
        idx.register(0, 300, start, 0, 0, 1, 1);
        idx.register(-1, 300, start, 0, 0, 1, 1);

        // 移除主世界中的结构 300，下界不受影响
        idx.removeStructure(0, 300);

        assertTrue(idx.queryCandidates(0, 0, 0).isEmpty(), "主世界结构应已移除");
        assertEquals(1, idx.queryCandidates(-1, 0, 0).size(), "下界结构应保留");
    }

    @Test
    void clearDimensionShouldOnlyClearTargetDimension() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        long start = ChunkPos.asLong(5, 5);
        idx.register(0, 400, start, 5, 5, 5, 5);
        idx.register(-1, 401, start, 5, 5, 5, 5);
        idx.register(1, 402, start, 5, 5, 5, 5);

        idx.clearDimension(0);

        assertTrue(idx.queryCandidates(0, 5, 5).isEmpty(), "主世界应已清空");
        assertFalse(idx.queryCandidates(-1, 5, 5).isEmpty(), "下界应保留");
        assertFalse(idx.queryCandidates(1, 5, 5).isEmpty(), "末地应保留");
    }

    @Test
    void multiRegionStructureShouldRegisterInAllCoveredRegions() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        // 跨越 Region 边界：从 chunkX=30 到 chunkX=34（Region 0 和 1）
        long start = ChunkPos.asLong(30, 0);
        idx.register(0, 500, start, 30, 0, 34, 0);

        // 两个 Region 都应能查询到
        assertFalse(idx.queryCandidates(0, 31, 0).isEmpty(), "Region 0 内应查询到");
        assertFalse(idx.queryCandidates(0, 33, 0).isEmpty(), "Region 1 内应查询到");
    }

    @Test
    void queryCandidatesOutsideBoundsShouldReturnEmpty() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        long start = ChunkPos.asLong(10, 10);
        idx.register(0, 600, start, 10, 10, 12, 12);

        // 查询范围外的 ChunkPos 不应返回候选
        List<IndexedStart> result = idx.queryCandidates(0, 13, 13);
        assertTrue(result.isEmpty(), "包围盒外不应返回候选");
    }

    @Test
    void totalStartsShouldCountAllDimensions() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        long start = ChunkPos.asLong(0, 0);
        idx.register(0, 700, start, 0, 0, 1, 1);
        idx.register(-1, 701, start, 0, 0, 1, 1);

        assertEquals(2, idx.totalStarts(), "应统计所有维度的起点数");
    }

    @Test
    void duplicateRegistrationShouldAccumulate() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        long start = ChunkPos.asLong(0, 0);
        idx.register(0, 800, start, 0, 0, 1, 1);
        idx.register(0, 800, start, 0, 0, 1, 1);

        // 同一结构重复注册应累积（不自动去重，由调用方保证唯一性）
        assertEquals(2, idx.totalStarts(), "重复注册应累积计数");
    }

    /**
     * 审查新发现 #2 修复验证：同 regionZ 不同 regionX 的 Region 不应碰撞。
     * <p>
     * 旧实现 composeKey 用 {@code subKey & 0xFFFFFFFFL} 掩码丢弃 regionX（高 32 位），
     * 导致同维度内相同 regionZ 的所有 Region 共享同一个桶，索引退化约 32 倍候选量。
     */
    @Test
    void differentRegionXWithSameRegionZShouldNotCollide() {
        StructureStartIndex idx = StructureStartIndex.getInstance();
        // Region (0,0)：chunk (1,1)，结构 900
        long start1 = ChunkPos.asLong(1, 1);
        idx.register(0, 900, start1, 1, 1, 1, 1);
        // Region (1,0)：chunk (33,1)，结构 901（同 regionZ=0，不同 regionX）
        long start2 = ChunkPos.asLong(33, 1);
        idx.register(0, 901, start2, 33, 1, 33, 1);

        // 查询 chunk (1,1) 应只返回结构 900，不应返回 901
        List<IndexedStart> candidates1 = idx.queryCandidates(0, 1, 1);
        assertEquals(1, candidates1.size(), "Region (0,0) 查询应只返回 1 个候选");
        assertEquals(900, candidates1.get(0).structureRawId(), "应返回结构 900");

        // 查询 chunk (33,1) 应只返回结构 901
        List<IndexedStart> candidates2 = idx.queryCandidates(0, 33, 1);
        assertEquals(1, candidates2.size(), "Region (1,0) 查询应只返回 1 个候选");
        assertEquals(901, candidates2.get(0).structureRawId(), "应返回结构 901");
    }
}
