package com.mochi_753.steadychunks.consistency;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 一致性检查报告，对应开发计划 Phase 11.1。
 * <p>
 * 记录单次检查的哈希值和发现的问题。
 * 严格模式报告包含各维度哈希；语义模式报告包含问题列表。
 */
public final class ConsistencyReport {
    private final ChunkPos pos;
    private final ResourceKey<Level> dimension;
    private final List<ConsistencyIssue> issues = new ArrayList<>();
    private final long timestamp = System.currentTimeMillis();

    private int blockHash;
    private int fluidHash;
    private int heightmapHash;
    private int blockEntityHash;
    private int structureRefHash;
    private int structureStartHash;
    private int overallHash;

    public ConsistencyReport(ChunkPos pos, ResourceKey<Level> dimension) {
        this.pos = pos;
        this.dimension = dimension;
    }

    public void addIssue(ConsistencyIssue issue) {
        issues.add(issue);
    }

    // 哈希设置器（由 WorldGenConsistencyChecker 调用）
    void setBlockHash(int h) { this.blockHash = h; }
    void setFluidHash(int h) { this.fluidHash = h; }
    void setHeightmapHash(int h) { this.heightmapHash = h; }
    void setBlockEntityHash(int h) { this.blockEntityHash = h; }
    void setStructureRefHash(int h) { this.structureRefHash = h; }
    void setStructureStartHash(int h) { this.structureStartHash = h; }
    void setOverallHash(int h) { this.overallHash = h; }

    // 访问器
    public ChunkPos pos() { return pos; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long timestamp() { return timestamp; }
    public List<ConsistencyIssue> issues() { return issues; }
    public int blockHash() { return blockHash; }
    public int fluidHash() { return fluidHash; }
    public int heightmapHash() { return heightmapHash; }
    public int blockEntityHash() { return blockEntityHash; }
    public int structureRefHash() { return structureRefHash; }
    public int structureStartHash() { return structureStartHash; }
    public int overallHash() { return overallHash; }

    /**
     * 是否通过检查（无 error 级别问题）。
     */
    public boolean passed() {
        return issues.stream().noneMatch(i -> i.level() == ConsistencyIssue.Level.ERROR);
    }

    /**
     * 生成报告摘要。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConsistencyReport[dim=").append(dimension.location())
                .append(" pos=").append(pos)
                .append(" passed=").append(passed())
                .append(" issues=").append(issues.size());
        if (overallHash != 0) {
            sb.append(" overallHash=").append(overallHash);
        }
        sb.append("]");
        return sb.toString();
    }
}
